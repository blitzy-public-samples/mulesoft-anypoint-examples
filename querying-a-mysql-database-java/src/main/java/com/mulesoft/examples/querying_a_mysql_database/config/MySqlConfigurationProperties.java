package com.mulesoft.examples.querying_a_mysql_database.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings of the MySQL data source, bound from {@code db.mysql-configuration.*}: the
 * {@code url} and {@code driverClassName} attributes of the global element {@code db:generic-config}
 * named {@code MySQL_Configuration} [querying-a-mysql-database/src/main/app/database-to-json.xml:3].
 *
 * <ul>
 *   <li>{@code url} binds {@code db.mysql-configuration.url}, which resolves {@code jdbc.url}; the
 *       committed {@code application.yml} holds a placeholder for it (D-012).</li>
 *   <li>{@code driverClassName} binds {@code db.mysql-configuration.driver-class-name}.</li>
 *   <li>Both bind through the canonical constructor, with no default and no validation; an absent key
 *       binds {@code null}. {@code @ConfigurationPropertiesScan} on the main class registers the
 *       record.</li>
 * </ul>
 *
 * @param url JDBC URL of the MySQL database
 * @param driverClassName fully qualified class name of the JDBC driver
 */
@ConfigurationProperties(prefix = "db.mysql-configuration")
public record MySqlConfigurationProperties(String url, String driverClassName) { }
