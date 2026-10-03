package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the MySQL connection {@code MySQL_Configuration}, bound by constructor binding from the
 * {@code mysql.*} keys of {@code application.yml} (D-012).
 *
 * <p>Source: {@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:7}, the
 * global element {@code db:mysql-config} named {@code MySQL_Configuration} with {@code host="${mysql.host}"},
 * {@code port="3306"}, {@code database="company"}, {@code user="${mysql.user}"} and
 * {@code password="${mysql.password}"}.
 *
 * <p>Keys and defaults:
 * <ul>
 *   <li>{@code mysql.host}, {@code mysql.user} and {@code mysql.password} have no default in code. The committed
 *       {@code application.yml} holds marked placeholders for them, the values to supply (D-012), and an absent
 *       key binds {@code null}.</li>
 *   <li>{@code mysql.port} defaults to {@code 3306} and {@code mysql.database} to {@code company}, the literals of
 *       the original element.</li>
 *   <li>Under relaxed binding the environment variables {@code MYSQL_HOST}, {@code MYSQL_PORT},
 *       {@code MYSQL_DATABASE}, {@code MYSQL_USER} and {@code MYSQL_PASSWORD} set the same keys.</li>
 * </ul>
 *
 * <p>{@code SalesforceToMySqlIT} reads none of these keys; it replaces the data source with an embedded H2
 * database (D-074).
 *
 * <p>Example: with {@code mysql.host=db} and no {@code mysql.port} or {@code mysql.database} key,
 * {@link #jdbcUrl()} returns {@code jdbc:mysql://db:3306/company}.
 *
 * @param host     {@code mysql.host}: the host name or address of the MySQL server
 * @param port     {@code mysql.port}: the TCP port of the MySQL server; {@code 3306} when the key is absent
 * @param database {@code mysql.database}: the database name; {@code company} when the key is absent
 * @param user     {@code mysql.user}: the user name of the connection
 * @param password {@code mysql.password}: the password of the connection; {@link #toString()} masks it
 */
@ConfigurationProperties(prefix = "mysql")
public record MySqlProperties(
        String host,
        @DefaultValue("3306") int port,
        @DefaultValue("company") String database,
        String user,
        String password) {

    /**
     * Returns the JDBC URL {@code jdbc:mysql://host:port/database} of this connection, with no query parameters.
     *
     * <p>Each component is inserted as its string value: a {@code null} host or database appears as the text
     * {@code null}.
     *
     * @return {@code "jdbc:mysql://" + host + ":" + port + "/" + database}
     */
    public String jdbcUrl() {
        return "jdbc:mysql://" + host + ":" + port + "/" + database;
    }

    /**
     * Returns {@code MySqlProperties[host=<host>, port=<port>, database=<database>, user=<user>, password=****]}.
     * The password is always printed as {@code ****}, whatever its value, {@code null} included (D-309).
     *
     * @return the components of this record with the password masked
     */
    @Override
    public String toString() {
        return "MySqlProperties[host=" + host
                + ", port=" + port
                + ", database=" + database
                + ", user=" + user
                + ", password=****]";
    }
}
