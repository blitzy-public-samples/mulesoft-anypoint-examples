package com.mulesoft.examples.querying_a_mysql_database.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL data source and JDBC template of the global element {@code db:generic-config} named
 * {@code MySQL_Configuration} [querying-a-mysql-database/src/main/app/database-to-json.xml:3].
 *
 * <p>Beans:
 * <ul>
 *   <li>{@link #dataSource} is a HikariCP {@link DataSource} built from the two bound attributes of
 *       {@link MySqlConfigurationProperties}: {@code url}, which resolves the {@code jdbc.url} placeholder
 *       (D-012), and {@code driverClassName}, the element's Connector/J 8.3.0 legacy driver name
 *       {@code com.mysql.jdbc.Driver} (D-063). The pool opens on the first connection request.</li>
 *   <li>{@link #jdbcTemplate} is the {@link JdbcTemplate} over that data source. Its consumer,
 *       {@code client.EmployeeJdbcClient}, runs the SQL text of the flow's {@code db:dynamic-query} through it as
 *       a plain statement (D-044).</li>
 * </ul>
 *
 * <p>Spring Boot's data source auto-configuration backs off from the declared {@link DataSource}, and its JDBC
 * template auto-configuration backs off from the declared {@link JdbcTemplate}; Boot's
 * {@code NamedParameterJdbcTemplate} is built around this {@link JdbcTemplate} (D-063). No
 * {@code spring.datasource.*} key is read. The class sets no user name, password, pool size, timeout or
 * connection test query: credentials, when present, are parameters of the JDBC URL.
 *
 * <p>Building either bean opens no database connection, so the application context starts while
 * {@code jdbc.url} holds the committed placeholder and no MySQL server runs. A connection failure, such as a
 * URL the driver does not accept or an unreachable server, is raised by the first {@code getConnection()} call
 * and propagates to the caller unchanged.
 */
@Configuration
public class DataSourceConfig {

    /**
     * MySQL data source of {@code MySQL_Configuration}; the pool opens on the first connection request.
     *
     * <p>The {@link HikariDataSource} is created with its no-argument constructor, so creating the bean starts no
     * pool. Only two settings are applied:
     * <ul>
     *   <li>the JDBC URL from {@link MySqlConfigurationProperties#url()}, passed unchanged (D-012);</li>
     *   <li>the driver class from {@link MySqlConfigurationProperties#driverClassName()}, passed unchanged
     *       (D-063). Setting it loads and instantiates the named class at bean creation; Connector/J 8.3.0
     *       prints its deprecation notice for {@code com.mysql.jdbc.Driver} to standard error the first time
     *       the JVM loads that class.</li>
     * </ul>
     * Every other pool setting keeps its HikariCP default.
     *
     * @param props the bound {@code db.mysql-configuration.*} attributes of {@code MySQL_Configuration}
     * @return a {@link HikariDataSource} for the bound URL and driver class whose pool has not started
     */
    @Bean
    public DataSource dataSource(MySqlConfigurationProperties props) {
        // No-argument constructor: the pool starts on the first getConnection(), never at bean creation.
        HikariDataSource ds = new HikariDataSource();
        ds.setJdbcUrl(props.url());
        ds.setDriverClassName(props.driverClassName());
        return ds;
    }

    /**
     * JDBC template over the {@code MySQL_Configuration} data source, with Spring's default template settings.
     *
     * @param dataSource the data source returned by {@link #dataSource(MySqlConfigurationProperties)}
     * @return a {@link JdbcTemplate} that obtains its connections from {@code dataSource}
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
