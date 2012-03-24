/**
 * Copyright 2011-2012 BBe Consulting GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package de.bbe_consulting.mavento;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumSet;
import java.util.Map;

import javax.xml.transform.TransformerException;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;

import de.bbe_consulting.mavento.helper.FileUtil;
import de.bbe_consulting.mavento.helper.MagentoSqlUtil;
import de.bbe_consulting.mavento.helper.MagentoUtil;
import de.bbe_consulting.mavento.helper.MagentoXmlUtil;
import de.bbe_consulting.mavento.helper.visitor.CopyFilesVisitor;
import de.bbe_consulting.mavento.helper.visitor.MoveFilesVisitor;
import de.bbe_consulting.mavento.type.MagentoVersion;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

/**
 * To create a dependency artifact from a running Magento instance:
 * 
 * <pre>
 * mvn magento:artifact -DmagentoPath=/path/to/magento/folder
 * </pre>
 * 
 * Use -DskipTempDb to do all cleaning operations directly on the source db.
 * NOT recommended for actual live instances.
 * 
 * Use -DtempDb to specify temp db name, default is source db name + _temp 
 * Use -DtruncateLogs to prune all log tables. Enabled per default.
 * Use -DtruncateCustomers to also prune all customer/order data.
 * 
 * To create a dependency artifact from a vanilla Magento zip:
 * 
 * <pre>
 * mvn magento:artifact -DmagentoZip=/path/to/.zip -DdbUser= -DdbPassword= -DdbName=
 * </pre>
 * 
 * This goal does not need an active Maven project.<br/>
 * 
 * @goal artifact
 * @aggregator false
 * @requiresProject false
 * @author Erik Dannenberg
 */
public class MagentoArtifactMojo extends AbstractMojo {

    /**
     * The Maven Project Object
     *
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * The Maven Session Object
     *
     * @parameter expression="${session}"
     * @required
     * @readonly
     */
    protected MavenSession session;

    /**
     * The Maven PluginManager Object
     *
     * @component
     * @required
     */
    protected BuildPluginManager pluginManager;

    /**
     * Root path of a magento instance you want to create an artifact of.<br/>
     * 
     * @parameter expression="${magentoPath}"
     */
    protected String magentoPath;

    /**
     * Path to a magento vanilla install archive in zip format.<br/>
     * 
     * @parameter expression="${magentoZip}"
     */
    protected String magentoZip;

    /**
     * Database name to use for magento install.<br/>
     * Only used when creating an artifact from a vanilla magento zip.
     * 
     * @parameter expression="${dbName}"
     */
    protected String dbName;

    /**
     * Database user for install db.<br/>
     * Only used when creating an artifact from a vanilla magento zip.
     * 
     * @parameter expression="${dbUser}"
     */
    protected String dbUser;

    /**
     * Database password for install db.<br/>
     * Only used when creating an artifact from a vanilla magento zip.
     * 
     * @parameter expression="${dbPassword}"
     */
    protected String dbPassword;

    /**
     * Database host for install db.<br/>
     * Only used when creating an artifact from a vanilla magento zip.
     * 
     * @parameter expression="${dbHost}" default-value="localhost";
     */
    protected String dbHost;

    /**
     * Database port for install db..<br/>
     * Only used when creating an artifact from a vanilla magento zip.
     * 
     * @parameter expression="${dbPort}" default-value="3306";
     */
    protected String dbPort;

    /**
     * Temp directory to use while creating the artifact. <br/>
     * Defaults to your system's temp directory. (i.e. /tmp with Linux, etc)
     * 
     * @parameter expression="${tempDir}"
     */
    protected String tempDir;

    /**
     * Database to use for cleaning operations before final dump.<br/>
     * If left empty the plugin will append _temp to the original database name.<br/>
     * Only used when creating an artifact from a running instance.
     * 
     * @parameter expression="${tempDb}"
     */
    protected String tempDb;
    
    /**
     * Where to write the final artifact file. <br/>
     * Default is Magento root foldername+timestamp in the current directory.
     * 
     * @parameter expression="${artifactFile}" default-value=""
     */
    protected String artifactFile;

    /**
     * Db settings in local.xml will not be scrambled if set to true.<br/>
     * 
     * @parameter expression="${keepDbSettings}" default-value="false"
     */
    protected Boolean keepDbSettings;
    
    /**
     * Use a temporary database for cleaning operations. (log tables, customer data, etc)<br/>
     * 
     * @parameter expression="${useTempDb}" default-value="false"
     */
    protected Boolean skipTempDb;

    /**
     * Truncate magento log/report tables before final dump.<br/>
     * 
     * @parameter expression="${truncateLogs}" default-value="true"
     */
    protected Boolean truncateLogs;
    
    /**
     * When truncating magento log tables also truncate the report_viewed_product_index table.<br/>
     * Activating truncateCustomers will truncate the table in any case.
     * 
     * @parameter expression="${includeViewedProduct}" default-value="false"
     */
    protected Boolean includeViewedProduct;
    
    /**
     * Truncate magento sales/customer tables before final dump.<br/>
     * 
     * @parameter expression="${truncateCustomers}" default-value="false"
     */
    protected Boolean truncateCustomers;
    
    /**
     * Working dir.
     */
    private Path tempDirPath;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        if (tempDir != null && !tempDir.isEmpty()) {
            try {
                tempDirPath = Paths.get(tempDir);
                Files.createDirectories(tempDirPath);
            } catch (IOException e) {
                throw new MojoExecutionException("Error creating temp directory: " + e.getMessage(), e);
            }
        } else {
            try {
                tempDirPath = Files.createTempDirectory("mavento_artifact_");
            } catch (IOException e) {
                throw new MojoExecutionException("Could not create tmp dir. " + e.getMessage(), e);
            }
        }
        if (magentoPath != null && !magentoPath.isEmpty()) {
            createCustomArtifact();
        } else if (magentoZip != null && !magentoZip.isEmpty()) {
            createVanillaArtifact();
        } else {
            getLog().info("use -DmagentoPath to create a custom artifact or -DmagentoTar to create a vanilla artifact");
        }
    }

    /**
     * Create a maven artifact from a vanilla magento zip.
     * 
     * @throws MojoExecutionException
     */
    private void createVanillaArtifact() throws MojoExecutionException {
        getLog().info("Working directory is: " + tempDirPath);
        try {
            FileUtil.unzipFile(magentoZip, tempDirPath.toString());
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        final Path maventoDir = Paths.get(tempDirPath + "/mavento_setup/sql");
        try {
            Files.createDirectories(maventoDir);
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        
        final MagentoVersion mageVersion;
        try {
            mageVersion = MagentoUtil.getMagentoVersion(Paths.get(tempDirPath+"/magento/app/Mage.php"));
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }

        String sampleDataVersion = "1.6.1.0";
        if (mageVersion.getMajorVersion() <= 1 && mageVersion.getMinorVersion() <=6 && mageVersion.getRevisionVersion() < 1) {
            sampleDataVersion = "1.2.0.0";
        }
        
        // call dependency plugin to resolve and extract sample data
        executeMojo(
                plugin(
                    groupId("org.apache.maven.plugins"),
                    artifactId("maven-dependency-plugin"),
                    version("2.0")
                ),
                goal("unpack"),
                configuration(
                    element(name("outputDirectory"), maventoDir.getParent() + "/sample_data"),
                    element(name("markersDirectory"), maventoDir.getParent() +"/dep-marker"),
                    element("silent", "true"),
                    element("artifactItems",
                        element("artifactItem", 
                            element("groupId", "com.varien"),
                            element("artifactId", "magento-sample-data"),
                            element("version", sampleDataVersion),
                            element("type", "jar")
                            ))
                ),
                executionEnvironment(
                    project,
                    session,
                    pluginManager
                )
            );
        
        getLog().info("Magento version: " + mageVersion);
        
        final String sqlDumpEmpty = maventoDir.toString()+"/magento.sql";
        final String sqlDumpSample = maventoDir.toString()+"/magento_with_sample_data.sql";
        final Path sampleDataPre = Paths.get(maventoDir.getParent()
                + "/sample_data/magento_sample_data_for_" + sampleDataVersion + ".sql");
        if (!Files.exists(sampleDataPre)) {
            throw new MojoExecutionException ("Could not find magento sample data dump. " + sampleDataPre);
        }
        // import sample data dump
        MagentoSqlUtil.recreateMagentoDb(dbUser, dbPassword, dbHost, dbPort, dbName, getLog());
        MagentoSqlUtil.importSqlDump(sampleDataPre.toString(), dbUser, dbPassword, dbHost, dbPort, dbName, getLog());
        // run magento setup and indexer
        MagentoUtil.execMagentoInstall(tempDirPath, dbUser, dbPassword, dbHost+":"+dbPort, dbName, getLog());
        MagentoSqlUtil.indexDb(tempDirPath.toString()+"/magento", getLog());
        // dump final sample data db
        MagentoSqlUtil.dumpSqlDb(sqlDumpSample, dbUser, dbPassword, dbHost, dbPort, dbName, getLog());
        // one more time without sample data
        MagentoSqlUtil.recreateMagentoDb(dbUser, dbPassword, dbHost, dbPort, dbName, getLog());
        try {
            FileUtil.deleteFile(tempDirPath.toString()+"/magento", getLog());
            FileUtil.unzipFile(magentoZip, tempDirPath.toString());
        } catch (IOException e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        MagentoUtil.execMagentoInstall(tempDirPath, dbUser, dbPassword, dbHost+":"+dbPort, dbName, getLog());
        // dump final db
        MagentoSqlUtil.dumpSqlDb(sqlDumpEmpty, dbUser, dbPassword, dbHost, dbPort, dbName, getLog());
        // move magento files
        try {
            final MoveFilesVisitor mv = new MoveFilesVisitor(Paths.get(tempDirPath+"/magento"), tempDirPath);
            try {
                Files.walkFileTree(Paths.get(tempDirPath+"/magento"),
                        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                        Integer.MAX_VALUE, mv);
            } catch (IOException e) {
                throw new MojoExecutionException("Error moving to tmp dir. " + e.getMessage(), e);
            }
            FileUtil.deleteFile(maventoDir.getParent() + "/sample_data/META-INF", getLog());
            FileUtil.deleteFile(maventoDir.getParent() + "/dep-marker", getLog());
            FileUtil.deleteFile(sampleDataPre.toString(), getLog());
            FileUtil.deleteFile(tempDirPath + "/var/cache", getLog());
            FileUtil.deleteFile(tempDirPath + "/var/session", getLog());
        } catch (IOException e) {
            throw new MojoExecutionException("Error deleting cache or session directories. " + e.getMessage(), e);
        }

        if (artifactFile == null || artifactFile.isEmpty()) {
            artifactFile = "magento-"+mageVersion+".jar";
        }
        getLog().info("Creating jar file: " + artifactFile + "..");
        FileUtil.createJar(artifactFile, tempDirPath.toAbsolutePath().toString());
        getLog().info("..done.");
        
        // clean up
        try {
            getLog().info("Cleaning up..");
            FileUtil.deleteFile(tempDirPath.toString(), getLog());
            getLog().info("..done.");
        } catch (IOException e) {
            throw new MojoExecutionException("Error deleting tmp dir. " + e.getMessage(), e);
        }

        // finally install the new artifact into the local repository
        executeMojo(
                plugin(
                    groupId("org.apache.maven.plugins"),
                    artifactId("maven-install-plugin"),
                    version("2.3.1")
                ),
                goal("install-file"),
                configuration(
                    element(name("file"), artifactFile),
                    element("groupId", "com.varien"),
                    element("artifactId", "magento"),
                    element("version", mageVersion.toString()),
                    element("packaging", "jar")
                ),
                executionEnvironment(
                    project,
                    session,
                    pluginManager
                )
            );
        getLog().info("");
        getLog().info("Great success! " + artifactFile + " was successfully installed and is ready for use.");
    }

    /**
     * Create a maven artifact from a running magento instance.
     * 
     * @throws MojoExecutionException
     */
    private void createCustomArtifact() throws MojoExecutionException {

        if (magentoPath.endsWith("/")) {
            magentoPath = magentoPath.substring(0, magentoPath.length() - 1);
        }
        
        getLog().info("Scanning: " + Paths.get(magentoPath));

        // try to find magento version
        final Path appMage = Paths.get(magentoPath + "/app/Mage.php");
        final MagentoVersion mageVersion;
        try {
            mageVersion = MagentoUtil.getMagentoVersion(appMage);
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
        getLog().info("..found Magento " + mageVersion.toString());

        // get sql settings from local.xml
        final Path localXmlPath = Paths.get(magentoPath + "/app/etc/local.xml");
        Document localXml;
        if (Files.exists(localXmlPath)) {
            localXml = MagentoXmlUtil.readXmlFile(localXmlPath.toAbsolutePath().toString());
        } else {
            throw new MojoExecutionException("Could not read or parse /app/etc/local.xml");
        }
        Map<String, String> dbSettings = MagentoXmlUtil.getDbValues(localXml);
        getLog().info("..done.");
        
        getLog().info("Working directory is: " + tempDirPath);
        
        // copy magento source to tmp dir
        getLog().info("Creating snapshot..");
        final CopyFilesVisitor cv = new CopyFilesVisitor(Paths.get(magentoPath), tempDirPath, true);
        try {
            Files.walkFileTree(Paths.get(magentoPath),
                    EnumSet.of(FileVisitOption.FOLLOW_LINKS),
                    Integer.MAX_VALUE, cv);
        } catch (IOException e) {
            throw new MojoExecutionException("Error copying to tmp dir. " + e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException("Error copying to tmp dir. " + e.getMessage(), e);
        }
        getLog().info("..done.");

        // dump db
        try {
            Files.createDirectories(Paths.get(tempDirPath + "/mavento_setup/sql"));
        } catch (IOException e) {
            throw new MojoExecutionException("Error creating directory. " + e.getMessage(), e);
        }
        final String dumpFile = Paths.get(tempDirPath + "/mavento_setup/sql/magento.sql").toString();
        MagentoSqlUtil.dumpSqlDb(dumpFile, dbSettings.get("user"),
                dbSettings.get("password"), dbSettings.get("host"),
                dbSettings.get("port"), dbSettings.get("dbname"), getLog());
        
        // import into temp db
        if (tempDb == null || tempDb.isEmpty()) {
            tempDb = dbSettings.get("dbname") + "_temp";
        }
        if (!skipTempDb && tempDb.equals(dbSettings.get("dbname"))) {
            throw new MojoExecutionException("Error: source and temp database names are the same, aborting..");
        }
        // do cleaning work on source db?
        if (skipTempDb) {
            tempDb = dbName;
        }

        if (!skipTempDb) {
            // import dump into temp db
            MagentoSqlUtil.recreateMagentoDb(dbSettings.get("user"), dbSettings.get("password"),
                    dbSettings.get("host"), dbSettings.get("port"), tempDb, getLog());
            MagentoSqlUtil.importSqlDump(dumpFile, dbSettings.get("user"), dbSettings.get("password"),
                    dbSettings.get("host"), dbSettings.get("port"), tempDb, getLog());
        }
        final String jdbcUrlTempDb = MagentoSqlUtil.getJdbcUrl(dbSettings.get("host"),
                dbSettings.get("port"), tempDb);
        if (truncateLogs) {
            getLog().info("Cleaning log tables..");
            MagentoSqlUtil.truncateLogTables(dbSettings.get("user"), dbSettings.get("password"),
                    jdbcUrlTempDb, includeViewedProduct, getLog());
            getLog().info("..done.");
        }
        if (truncateCustomers) {
            getLog().info("Deleting customer/sales data..");
            MagentoSqlUtil.truncateSalesTables(dbSettings.get("user"), dbSettings.get("password"),
                    jdbcUrlTempDb, getLog());
            MagentoSqlUtil.truncateCustomerTables(dbSettings.get("user"), dbSettings.get("password"),
                    jdbcUrlTempDb, getLog());
            getLog().info("..done.");
        }
        // dump purged db
        MagentoSqlUtil.dumpSqlDb(dumpFile, dbSettings.get("user"),
                dbSettings.get("password"), dbSettings.get("host"),
                dbSettings.get("port"), tempDb, getLog());

        // scramble db settings in local.xml
        if (!keepDbSettings) {
            getLog().info("Scrambling original database settings in local.xml..");
            final Path tmpLocalXml = Paths.get(tempDirPath + "/app/etc/local.xml");
            localXml = MagentoXmlUtil.readXmlFile(tmpLocalXml.toString());
            MagentoXmlUtil.updateDbValues("localhost", "heinzi", "floppel", "db_magento", localXml);

            try {
                MagentoXmlUtil.writeXmlFile(MagentoXmlUtil.transformXmlToString(localXml), tmpLocalXml.toString());
            } catch (TransformerException e) {
                throw new MojoExecutionException(e.getMessage(), e);
            }
            getLog().info("..done.");
        }

        // clean /var before we build the artifact
        try {
            FileUtil.deleteFile(tempDirPath + "/var/cache", getLog());
            FileUtil.deleteFile(tempDirPath + "/var/session", getLog());
        } catch (IOException e) {
            throw new MojoExecutionException("Error deleting cache or session directories. " + e.getMessage(), e);
        }

        // create the jar
        final SimpleDateFormat sdf = new SimpleDateFormat();
        sdf.applyPattern("yyyyMMddHHmmss");
        final String defaultVersion = sdf.format(new Date()) + "-SNAPSHOT";
        if (artifactFile == null || artifactFile.equals("")) {
            artifactFile = Paths.get(magentoPath).getFileName().toString() + "-" + defaultVersion + ".jar";
        } else if (!artifactFile.endsWith(".jar")) {
            artifactFile += "-" + defaultVersion + ".jar";
        }
        getLog().info("Creating jar file: " + artifactFile + "..");
        FileUtil.createJar(artifactFile, tempDirPath.toString());
        getLog().info("..done.");

        // clean up
        try {
            getLog().info("Cleaning up..");
            FileUtil.deleteFile(tempDirPath.toString(), getLog());
            getLog().info("..done.");
        } catch (IOException e) {
            throw new MojoExecutionException("Error deleting tmp dir. " + e.getMessage(), e);
        }

        getLog().info("");
        getLog().info("Great success! " + artifactFile + " is now ready for install.");
        getLog().info("Use:\n");
        getLog().info("mvn install:install-file -Dpackaging=jar -Dfile="
                        + artifactFile + " -Dversion=" + defaultVersion
                        + " -DgroupId= -DartifactId= \n");
        getLog().info("..to install the jar into your local maven repository.");
    }

}
