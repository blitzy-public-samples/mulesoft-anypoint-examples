/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.it.support;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates schema {@code <databaseName>} and table {@code contact} from the classpath script on the
 * injected {@link DataSource}, and drops the schema again (D-074).
 * <p>
 * Port of {@code org.mule.examples.db.MySQLDbCreator}. The statements are H2 SQL for a database opened
 * with {@code MODE=MySQL;DATABASE_TO_LOWER=TRUE}. Neither method throws: a failure is logged at ERROR
 * and the method returns. Each call opens one connection and one statement and closes both.
 * <p>
 * Usage:
 * <pre>{@code
 * MySqlDbCreator dbCreator = new MySqlDbCreator(dataSource, "company", "contact.sql");
 * dbCreator.setUpDatabase();     // schema company with an empty table contact
 * // ... test body ...
 * dbCreator.tearDownDataBase();  // schema company and its tables removed
 * }</pre>
 */
public class MySqlDbCreator {
	private static final Logger log = LoggerFactory.getLogger(MySqlDbCreator.class);
	private final DataSource dataSource;
	private final String databaseName;
	private final String sqlScriptResource;

	/**
	 * Stores the three values. Opens no connection and reads no resource.
	 *
	 * @param dataSource        the database the schema is created in and dropped from
	 * @param databaseName      the schema name, for example {@code company}
	 * @param sqlScriptResource the classpath location of the table script, for example {@code contact.sql}
	 */
	public MySqlDbCreator(DataSource dataSource, String databaseName, String sqlScriptResource) {
		this.dataSource = dataSource;
		this.databaseName = databaseName;
		this.sqlScriptResource = sqlScriptResource;
	}

	/**
	 * Logs the populate banner at INFO, then runs one batch on one connection:
	 * <ol>
	 * <li>{@code CREATE SCHEMA IF NOT EXISTS <databaseName>}</li>
	 * <li>{@code SET SCHEMA <databaseName>}</li>
	 * <li>{@code DROP TABLE IF EXISTS contact }</li>
	 * <li>the script's non-empty lines, joined without a separator</li>
	 * </ol>
	 * and logs {@code Success} at INFO once the batch has run. A repeated call recreates an empty
	 * {@code contact} table.
	 * <p>
	 * A {@link SQLException}, a {@link java.sql.BatchUpdateException} included, is logged as three ERROR
	 * lines: its message, its SQL state and its vendor error code. Any other exception, including a
	 * {@link FileNotFoundException} for a script resource that is not on the classpath, is logged as one
	 * ERROR entry with its stack trace; the batch is then not executed. Nothing is rethrown.
	 */
	public void setUpDatabase() {
		log.info("******************************** Populate MySQL DB **************************");
		try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.addBatch("CREATE SCHEMA IF NOT EXISTS " + databaseName);
			stmt.addBatch("SET SCHEMA " + databaseName);
			stmt.addBatch("DROP TABLE IF EXISTS contact ");

			final InputStream in = MySqlDbCreator.class.getClassLoader().getResourceAsStream(sqlScriptResource);
			if (in == null) {
				throw new FileNotFoundException(sqlScriptResource);
			}
			final StringBuilder script = new StringBuilder();
			try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
				String strLine;
				while ((strLine = br.readLine()) != null) {
					if (strLine.length() > 0) {
						script.append(strLine);
					}
				}
			}
			stmt.addBatch(script.toString());

			stmt.executeBatch();
			log.info("Success");
		} catch (SQLException ex) {
			log.error("SQLException: " + ex.getMessage());
			log.error("SQLState: " + ex.getSQLState());
			log.error("VendorError: " + ex.getErrorCode());
		} catch (Exception except) {
			log.error("Populating database {} from {} failed", databaseName, sqlScriptResource, except);
		}
	}

	/**
	 * Logs the delete banner at INFO, makes {@code PUBLIC} the connection's current schema and runs
	 * {@code DROP SCHEMA <databaseName> CASCADE}, which removes the schema with every table in it.
	 * <p>
	 * Any exception, for example for a schema that does not exist, is logged as one ERROR entry with its
	 * stack trace and is not rethrown.
	 */
	public void tearDownDataBase() {
		log.info("******************************** Delete Tables from MySQL DB **************************");
		try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
			stmt.execute("SET SCHEMA PUBLIC");
			stmt.executeUpdate("DROP SCHEMA " + databaseName + " CASCADE");
		} catch (Exception except) {
			log.error("Dropping database {} failed", databaseName, except);
		}
	}
}
