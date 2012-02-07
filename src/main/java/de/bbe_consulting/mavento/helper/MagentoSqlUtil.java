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

package de.bbe_consulting.mavento.helper;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;

import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.cli.CommandLineException;
import org.codehaus.plexus.util.cli.CommandLineUtils;
import org.codehaus.plexus.util.cli.Commandline;
import org.codehaus.plexus.util.cli.CommandLineUtils.StringStreamConsumer;
import org.codehaus.plexus.util.cli.WriterStreamConsumer;
import org.apache.maven.plugin.logging.Log;

import de.bbe_consulting.mavento.type.MagentoCoreConfig;

/**
 * Magento SQL Util Class
 * 
 * @author Erik Dannenberg
 */
public final class MagentoSqlUtil {

	/**
	 * Private constructor, only static methods in this util class 
	 */
	private MagentoSqlUtil() {
	}
	
	/**
     * Reindex magento database
     *
     * @param Magento directory
     * @param Maven mojo logger instance
     * @throws MojoExecutionException
     */
	public static void indexDb (String magentoDir, Log logger) throws MojoExecutionException {
		Commandline cl = new Commandline();
   		cl.addArguments( new String[] {"indexer.php","--reindexall"} );
   		cl.setWorkingDirectory(magentoDir+"/shell");
		cl.setExecutable("php");
		
        StringStreamConsumer output = new CommandLineUtils.StringStreamConsumer();
        StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
        
        try {
        	logger.info("Rebuilding all magento indices..");
 			int returnValue = CommandLineUtils.executeCommandLine(cl, output, error);
 			if (returnValue != 0) {
 				logger.info("retval: "+returnValue);
 				logger.info(output.getOutput().toString() );
 				logger.info(error.getOutput().toString());
 				throw new MojoExecutionException( "Error while reindexing magento database!");
 			}
 			logger.info("..done.");
 		} catch (CommandLineException e) {
 			throw new MojoExecutionException( "Error while reindexing magento database!", e );
 		}	
	}
	
	/**
	 * Create a new Database
	 * 
	 * @param magentoDbUser The db user
	 * @param magentoDbPasswd The db password
	 * @param magentoDbHost The db host url
	 * @param magentoDbPort the db host port
	 * @throws MojoExecutionException on error
	 */
	public static void createMagentoDb(String magentoDbUser, String magentoDbPasswd, String magentoDbHost, String magentoDbPort, String magentoDbName, Log logger) throws MojoExecutionException {
		Commandline cl = getMysqlCommandLine(magentoDbUser, magentoDbPasswd, magentoDbHost, magentoDbPort);
		InputStream input = null;
		
		try {
			input = new ByteArrayInputStream(("CREATE DATABASE "+magentoDbName).getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new MojoExecutionException("Error while creating database!", e );
		}

       StringStreamConsumer output = new CommandLineUtils.StringStreamConsumer();
       StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
       
       try {
       	logger.info("Creating database "+magentoDbName+"..");
			int returnValue = CommandLineUtils.executeCommandLine(cl, input, output, error);
			if (returnValue != 0) {
				logger.info("retval: "+returnValue);
				logger.info(output.getOutput().toString() );
				logger.info(error.getOutput().toString());
				throw new MojoExecutionException( "Error while creating database!");
			} else {
				logger.info("..done.");
			}
		} catch (CommandLineException e) {
			throw new MojoExecutionException( "Error while creating database!", e );
		}
	}
	
	/**
	 * Drop magento database.
	 * 
	 * @param magentoDbUser The db user
	 * @param magentoDbPasswd The db password
	 * @param magentoDbHost The db host url
	 * @param magentoDbPort The db host port
	 * @param magentoDbName The target db
	 * @param logger Maven plugin logger reference
	 * @throws MojoExecutionException on error
	 */
	public static void dropMagentoDb(String magentoDbUser, String magentoDbPasswd, String magentoDbHost, String magentoDbPort, String magentoDbName, Log logger) throws MojoExecutionException {
		Commandline cl = getMysqlCommandLine(magentoDbUser, magentoDbPasswd, magentoDbHost, magentoDbPort);
    	InputStream input = null;
        
        try {
			 input = new ByteArrayInputStream(("DROP DATABASE "+magentoDbName).getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			throw new MojoExecutionException( "Error while dropping database!", e);
		}
 
        StringStreamConsumer output = new CommandLineUtils.StringStreamConsumer();
        StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
        
        try {
        	logger.info("Dropping database "+magentoDbName+"..");
			int returnValue = CommandLineUtils.executeCommandLine(cl, input, output, error);
			if (returnValue != 0) {
				String e = error.getOutput().toString();
				if (e.startsWith("ERROR 1008")) {
					logger.warn("..Database does not exist!");				
				} else {
					logger.info(output.getOutput().toString() );
					logger.info(error.getOutput().toString());
					logger.info("retval: "+returnValue);
				}
			} 
			logger.info("..done.");
		} catch (CommandLineException e) {
			throw new MojoExecutionException( "Error while dropping database", e);
		}
	}
	
	/**
	 * Import a sql dump
	 * 
	 * @param sqlDump Source file
	 * @param magentoDbUser Target db user
	 * @param magentoDbPasswd Target db password
	 * @param magentoDbHost Target db host url
	 * @param magentoDbPort Target db host port
	 * @param magentoDbName Target db
	 * @param logger Maven plugin logger reference
	 * @throws MojoExecutionException on error
	 */
	public static void importSqlDump(String sqlDump, String magentoDbUser, String magentoDbPasswd, String magentoDbHost, String magentoDbPort, String magentoDbName, Log logger) throws MojoExecutionException {
		Commandline cl = MagentoSqlUtil.getMysqlCommandLine(magentoDbUser, magentoDbPasswd, magentoDbHost, magentoDbPort, magentoDbName);
        InputStream input = null;
        
        try {
			 input = new FileInputStream(sqlDump);
		} catch (FileNotFoundException e) {
			throw new MojoExecutionException("Error while reading sql dump.", e );
		}
 
        StringStreamConsumer output = new CommandLineUtils.StringStreamConsumer();
        StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
        
        try {
        	logger.info("Importing sql dump into database "+magentoDbName+"..");
			int returnValue = CommandLineUtils.executeCommandLine(cl, input, output, error);
			if (returnValue != 0) {
				logger.info(output.getOutput().toString() );
				logger.info(error.getOutput().toString());
				logger.info("retval: "+returnValue);
				throw new MojoExecutionException("Error while importing sql dump.");
			}
			logger.info("..done.");
		} catch (CommandLineException e) {
			throw new MojoExecutionException( "Error while importing sql dump.", e );
		}
	}
	
	/**
	 * Dump a sql database to text file.
	 * 
	 * @param sqlDump Target file 
	 * @param magentoDbUser Source db user
	 * @param magentoDbPasswd Source db password
	 * @param magentoDbHost Source db host url
	 * @param magentoDbPort Source db host port
	 * @param magentoDbName Source db name
	 * @param logger Maven plugin logger reference
	 * @throws MojoExecutionException on error
	 */
	public static void dumpSqlDb(String sqlDump, String magentoDbUser, String magentoDbPasswd, String magentoDbHost, String magentoDbPort, String magentoDbName, Log logger) throws MojoExecutionException {
		Commandline cl = new Commandline("mysqldump");
		cl.addArguments( new String[] { "--user="+magentoDbUser, "--password="+magentoDbPasswd, "--host="+magentoDbHost, "--port="+magentoDbPort, "-C" , magentoDbName} );
   		
        WriterStreamConsumer output = null;
        StringStreamConsumer error = new CommandLineUtils.StringStreamConsumer();
        
        PrintWriter p = null;
        try {
			p = new PrintWriter(sqlDump);
			output = new WriterStreamConsumer(p);
		} catch (FileNotFoundException e) {
			throw new MojoExecutionException("File " + sqlDump + " not found!", e);
		}
        
        try {
        	logger.info("Dumping database " + magentoDbName + " to " + sqlDump + "..");
			int returnValue = CommandLineUtils.executeCommandLine(cl, output, error);
			if (returnValue != 0) {
				logger.info(error.getOutput().toString());
				logger.info("retval: "+returnValue);
				throw new MojoExecutionException("Error while exporting sql dump.");
			} 
			logger.info("..done.");
		} catch (CommandLineException e) {
			throw new MojoExecutionException( "Error while dumping from database "+magentoDbName+".", e );
		}
	}
	
	public static Commandline getMysqlCommandLine(String magentoDbUser, String magentoDbPasswd, String magentoDbHost, String magentoDbPort) {
		Commandline cl = new Commandline("mysql");
   		cl.addArguments( new String[] { "--user="+magentoDbUser, "--password="+magentoDbPasswd, "--host="+magentoDbHost, "--port="+magentoDbPort} );
   		return cl;
	}
	
    public static Commandline getMysqlCommandLine(String magentoDbUser, String magentoDbPasswd, String magentoDbHost, String magentoDbPort, String magentoDbName) {
    	Commandline cl = new Commandline("mysql");
   		cl.addArguments( new String[] { "--user="+magentoDbUser, "--password="+magentoDbPasswd, "--host="+magentoDbHost, "--port="+magentoDbPort, magentoDbName} );
        return cl;
    }
    
    public static String getJdbcUrl(String magentoDbHost, String magentoDbPort, String magentoDbName) {
    	return "jdbc:mysql://"+magentoDbHost+":"+magentoDbPort+"/"+magentoDbName;
    }
    
    private static Connection getJdbcConnection (String magentoDbUser, String magentoDbPasswd, String jdbcUrl) throws MojoExecutionException {
    	Connection c = null;
    	try {
	    	String mysqlDriver = "com.mysql.jdbc.Driver";
	        Class.forName(mysqlDriver);
	        c = DriverManager.getConnection(jdbcUrl, magentoDbUser, magentoDbPasswd);
    	}  catch (SQLException e) {
    		throw new MojoExecutionException("SQL error. "+e.getMessage(), e);
		} catch (ClassNotFoundException e) {
			throw new MojoExecutionException("Could not find MySQL driver. "+e.getMessage(), e);
		} 
    	return c;
    }
    
    // read core_config_data values
    public static MagentoCoreConfig getCoreConfigData(MagentoCoreConfig configData, String magentoDbUser, String magentoDbPasswd, String jdbcUrl, Log logger) throws MojoExecutionException {
    	Connection c = getJdbcConnection(magentoDbUser, magentoDbPasswd, jdbcUrl);
    	
    	try {
    		String query = "SELECT value FROM core_config_data WHERE path=? AND scope=? AND scope_id=?";
    		PreparedStatement st = c.prepareStatement(query);
			st.setString(1, configData.getPath());
	        st.setString(2, configData.getScope());
	        st.setInt(3, configData.getScopeId());
	        ResultSet r = st.executeQuery();
	        if (r.next()) {
	        	configData.setValue(r.getString(1));
	        } else {
	        	throw new MojoExecutionException("Could not find config entry for: "+configData.getPath()+" with scope/id: "+configData.getScope()+"/"+configData.getScopeId());
	        }
    	} catch (SQLException e) {
    		throw new MojoExecutionException("SQL error. "+e.getMessage(), e);
    	} catch (Exception e) {
    		throw new MojoExecutionException("Error. "+e.getMessage(), e);
		} finally {
			try {
				c.close();
			} catch (SQLException e) {
				throw new MojoExecutionException("Error closing database connection. "+e.getMessage(), e);
			}
		}
    	return configData;
    }
    
    // update/insert entries in magento core_config_data table
    public static void setCoreConfigData(Map<String, String> configData, String magentoDbUser, String magentoDbPasswd, String jdbcUrl, Log logger) throws MojoExecutionException {
    	Connection c = getJdbcConnection(magentoDbUser, magentoDbPasswd, jdbcUrl);
    	PreparedStatement st = null;
    	ArrayList<MagentoCoreConfig> newEntries = new ArrayList<MagentoCoreConfig>();
    	ArrayList<MagentoCoreConfig> existingEntries = new ArrayList<MagentoCoreConfig>();
    	
    	// filter non/existing entries for further processing
    	try {
    		String query = "SELECT value FROM core_config_data WHERE path=? AND scope=? AND scope_id=?";
    		st = c.prepareStatement(query);
    		MagentoCoreConfig configEntry = null;
    		for ( Map.Entry<String,String> rawConfigEntry : configData.entrySet()) {
    			configEntry = new MagentoCoreConfig(rawConfigEntry.getKey(), rawConfigEntry.getValue());
    	        st.setString(1, configEntry.getPath());
    	        st.setString(2, configEntry.getScope());
    	        st.setInt(3, configEntry.getScopeId());
    	        ResultSet r = st.executeQuery();
    	        if (r.next()) {
    	        	existingEntries.add(configEntry);
    	        } else {
    	        	newEntries.add(configEntry);
    	        }
    		}
    	
	    	// insert new config entries
	    	if (!newEntries.isEmpty()) {
	    		c.setAutoCommit(false);
	    		String insertQuery = "INSERT INTO core_config_data (scope, scope_id, path, value) VALUES(?,?,?,?)";
		        st = c.prepareStatement(insertQuery);
		        for ( MagentoCoreConfig newConfigEntry : newEntries) {
	    			st.setString(1, newConfigEntry.getScope());
	    			st.setInt(2, newConfigEntry.getScopeId());
	    			st.setString(3, newConfigEntry.getPath());
	    	        st.setString(4, newConfigEntry.getValue());
	    	        st.addBatch();
	    		}
		        int[] insertCounts = st.executeBatch();
		        for (int i=0; i < insertCounts.length; i++) {           
		        	switch (insertCounts[i]) {
		        		case Statement.SUCCESS_NO_INFO:
		        			break;
		        		case Statement.EXECUTE_FAILED:
		        			throw new MojoExecutionException("Error inserting entries in core_config_data");
		        		default:
		        			break;
		        	}
		          }
		          c.commit();
	    	}
    	
	    	// update existing config entries
	    	if (!existingEntries.isEmpty()) {
		    		c.setAutoCommit(false);
		    		String updateQuery = "UPDATE core_config_data SET value=? WHERE scope=? AND scope_id=? AND path=?";
			        st = c.prepareStatement(updateQuery);
			        for ( MagentoCoreConfig oldConfigEntry : existingEntries) {
			        	st.setString(1, oldConfigEntry.getValue());
			        	st.setString(2, oldConfigEntry.getScope());
		    			st.setInt(3, oldConfigEntry.getScopeId());
		    			st.setString(4, oldConfigEntry.getPath());
		    			st.addBatch();
		    		}
			        int[] updateCounts = st.executeBatch();
			        for (int i=0; i < updateCounts.length; i++) {           
			        	switch (updateCounts[i]) {
			        		case Statement.SUCCESS_NO_INFO:
			        			break;
			        		case Statement.EXECUTE_FAILED:
			        			throw new MojoExecutionException("Error updating entries in core_config_data");
			        		default:
			        			break;
			        	}
			          }
			          c.commit();
		    	
	    	}
	    	
    	} catch (SQLException e) {
    		throw new MojoExecutionException("SQL error. "+e.getMessage(), e);
    	} catch (Exception e) {
    		throw new MojoExecutionException(e.getMessage(), e);
		} finally {
    		try {
				c.close();
			} catch (SQLException e) {
				throw new MojoExecutionException(e.getMessage(), e);
			}
    	}
    
    }
    
    // update admin user/role with project settings
    public static void updateAdminUser(Map<String, String> configData, String magentoDbUser, String magentoDbPasswd, String jdbcUrl, Log logger) throws MojoExecutionException {
    	Connection c = getJdbcConnection(magentoDbUser, magentoDbPasswd, jdbcUrl);

    	try {
    		int userId = 0;
    		
    		String query = "SELECT * FROM admin_role WHERE role_type='U' ORDER BY user_id ASC";
    		PreparedStatement st = c.prepareStatement(query);
    		ResultSet r = st.executeQuery();
    		
    		if (r.next()) {
    			userId = r.getInt("user_id");
    			query = "UPDATE admin_role SET role_name=? WHERE role_id=?";
    			st = c.prepareStatement(query);
    			st.setString(1, configData.get("ADMIN_NAME_FIRST"));
    			st.setInt(2, r.getInt("role_id"));
    			st.executeUpdate();
    		}
    		
    		query = "SELECT * FROM admin_user WHERE user_id=?";
    		st = c.prepareStatement(query);
    		st.setInt(1, userId);
    		r = st.executeQuery();

    		SimpleDateFormat sdf = new SimpleDateFormat( "yyyy-MM-dd hh:mm:ss" );
    		Date d = sdf.parse(configData.get("INSTALL_DATESQL"));
    		Timestamp ts = new Timestamp( d.getTime() );
    		
    		if (r.next()) {
    			query = "UPDATE admin_user SET firstname=?, lastname=?, email=?, username=?, password=?, created=?, modified=?, logdate=? WHERE user_id=?";
    			st = c.prepareStatement(query);
    			st.setString(1, configData.get("ADMIN_NAME_FIRST"));
    			st.setString(2, configData.get("ADMIN_NAME_LAST"));
    			st.setString(3, configData.get("ADMIN_EMAIL"));
    			st.setString(4, configData.get("ADMIN_USERNAME"));
    			st.setString(5, configData.get("ADMIN_PASSWD"));
    			st.setTimestamp(6, ts);
    			st.setTimestamp(7, ts);
    			st.setTimestamp(8, ts );
    			st.setInt(9, userId);
    			st.executeUpdate();
    		}

    	} catch (SQLException e) {
    		throw new MojoExecutionException("SQL error. "+e.getMessage(), e);
    	} catch (ParseException e) {
			throw new MojoExecutionException("Error parsing install date. "+e.getMessage(), e);
		} finally {
			try {
				c.close();
			} catch (SQLException e) {
				throw new MojoExecutionException(e.getMessage(), e);
			}
		}
    }
    
    // update cache options for magento>1.4.0.0
    public static void updateCacheConfig(Map<String, String> configData, String magentoDbUser, String magentoDbPasswd, String jdbcUrl, Log logger) throws MojoExecutionException {
    	Connection c = getJdbcConnection(magentoDbUser, magentoDbPasswd, jdbcUrl);

    	try {
    		c.setAutoCommit(false);
    		String query = "UPDATE core_cache_option SET value=? WHERE code=?";
    		PreparedStatement st = c.prepareStatement(query);
    		
    		for ( Map.Entry<String,String> configEntry : configData.entrySet()) {
    			st.setString(1, configEntry.getValue());
    	        st.setString(2, configEntry.getKey());
    			st.addBatch();
    		}
	        int[] updateCounts = st.executeBatch();
	        for (int i=0; i < updateCounts.length; i++) {           
	        	switch (updateCounts[i]) {
	        		case Statement.SUCCESS_NO_INFO:
	        			break;
	        		case Statement.EXECUTE_FAILED:
	        			break;
	        		default:
	        			break;
	        	}
	          }
	          c.commit();
    	} catch (SQLException e) {
    		throw new MojoExecutionException("SQL error. "+e.getMessage(), e);
    	} finally {
    		try {
				c.close();
			} catch (SQLException e) {
				throw new MojoExecutionException(e.getMessage(), e);
			}
    	}
    	
    }
    
    public static void truncateLogTables(String magentoDbUser, String magentoDbPasswd, String jdbcUrl, Log logger) throws MojoExecutionException {
    	Connection c = getJdbcConnection(magentoDbUser, magentoDbPasswd, jdbcUrl);
    	
    	String[] tableData = {"dataflow_batch_export", 
    							"dataflow_batch_import",
    							"log_url",
    							"log_url_info",
    							"log_visitor",
    							"log_visitor_info",
    							"report_event"};

    	try {
    		c.setAutoCommit(false);
    		Statement st = c.createStatement();
    		for (int i = 0; i < tableData.length; i++) {
    			st.addBatch("TRUNCATE TABLE "+tableData[i]);
    		}
	        int[] updateCounts = st.executeBatch();
	        for (int i=0; i < updateCounts.length; i++) {           
	        	switch (updateCounts[i]) {
	        		case Statement.SUCCESS_NO_INFO:
	        			break;
	        		case Statement.EXECUTE_FAILED:
	        			break;
	        		default:
	        			break;
	        	}
	          }
	          c.commit();   		
    	} catch (SQLException e) {
    		throw new MojoExecutionException("SQL error. "+e.getMessage(), e);
    	} finally {
    		try {
				c.close();
			} catch (SQLException e) {
				throw new MojoExecutionException(e.getMessage(), e);
			}
    	}
    }

}
