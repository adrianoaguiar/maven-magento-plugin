/**
 * Copyright 2011-2013 BBe Consulting GmbH
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

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;

import de.bbe_consulting.mavento.helper.MagentoSqlUtil;

/**
 * Dump current Magento database timestamped to sqldumps/ of project base dir.<br/>
 * To override filename use:<br/>
 * 
 * <pre>
 * mvn magento:dump-db -Dout=mydump.sql
 * </pre>
 * 
 * To only dump certain tables use -Dtables, -Dwhere is optional and takes mysql syntax.<br/>
 * The plugin will check for eav type tables and include them per default unless -DskipTableCompletion is set.<br/>
 * 
 * <pre>
 * mvn magento:dump-db -Dtables=catalog_category_entity -Dwhere='entity_id > 2931'
 * </pre>
 * 
 * This would dump all category data, including the eav tables (_int,_text, etc), where entity_id is greater than 2931.
 * 
 * @goal dump-db
 * @aggregator false
 * @requiresDependencyResolution compile
 * @author Erik Dannenberg
 */
public class MagentoDumpDbMojo extends AbstractMagentoSqlMojo {

    /**
     * Output file. Default: sqldumps/$dbname+timestamp
     * 
     * @parameter expression="${out}"
     */
    private String magentoDumpFile;

    /**
     * Comma seperated list of table names.
     * 
     * @parameter expression="${tables}"
     */
    private String magentoTables;

    /**
     * Limit the dumped data via sql where syntax.
     *  
     * @parameter expression="${where}"
     */
    private String magentoDumpCondition;

    /**
     * If true the plugin will not look for eav type tables to include. Default: false
     * 
     * @parameter expression="${skipTableCompletion}" default="false"
     */
    private boolean skipEntityTableCompletion;

    public void execute() throws MojoExecutionException, MojoFailureException {

        final File f = new File(project.getBasedir() + "/sqldumps");
        if (!f.exists()) {
            f.mkdirs();
        }
        if (magentoDumpFile == null || magentoDumpFile.isEmpty()) {
            final SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmm", new Locale("en", "EN"));
            final String dumpDate = format.format(new Date());
            magentoDumpFile = project.getBasedir() + "/sqldumps/"
                    + magentoDbName + "-" + dumpDate + ".sql";
        } else {
            magentoDumpFile = project.getBasedir() + "/sqldumps/" + magentoDumpFile;
        }

        // dump some tables or whole db?
        if (magentoTables != null && !magentoTables.isEmpty()) {
            ArrayList<String> tableNames = new ArrayList<String>(Arrays.asList(magentoTables.split(",")));
            if (!skipEntityTableCompletion) {
                tableNames = MagentoSqlUtil.getEntityDataTables(tableNames,
                        magentoDbUser, magentoDbPasswd, magentoDbHost, magentoDbPort, magentoDbName);
            }
            MagentoSqlUtil.dumpSqlTables(tableNames, magentoDumpCondition, magentoDumpFile, magentoDbUser, magentoDbPasswd,
                    magentoDbHost, magentoDbPort, magentoDbName, getLog());
        } else {
            MagentoSqlUtil.dumpSqlDb(magentoDumpFile, magentoDbUser, magentoDbPasswd,
                    magentoDbHost, magentoDbPort, magentoDbName, getLog());
        }
    }
}
