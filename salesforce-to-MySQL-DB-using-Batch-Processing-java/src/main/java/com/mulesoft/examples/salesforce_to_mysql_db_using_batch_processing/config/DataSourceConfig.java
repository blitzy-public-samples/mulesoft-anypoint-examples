package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Builds the MySQL {@link DataSource} of database configuration {@code MySQL_Configuration} from
 * {@link MySqlProperties} and the {@link NamedParameterJdbcTemplate} over the injected {@link DataSource} (D-063).
 *
 * <p>Source: {@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:7}, the
 * global element {@code db:mysql-config} named {@code MySQL_Configuration}, which the {@code db:select},
 * {@code db:insert} and {@code db:update} operations of batch job {@code salesforce-to-database-Batch} reference
 * (lines 27-31, 39-42 and 45-48 of the same file).
 *
 * <p>Beans:
 * <ul>
 *   <li>{@code dataSource}: a HikariCP pool for {@link MySqlProperties#jdbcUrl()} that opens no connection while the
 *       application context starts (D-515).</li>
 *   <li>{@code namedParameterJdbcTemplate}: the template over the {@link DataSource} the container injects. With a
 *       single {@link DataSource} bean that is {@code dataSource}. A test that adds a differently named
 *       {@code @Primary} {@link DataSource}, such as an embedded H2 database in {@code MODE=MySQL}, gets a template
 *       over that database (D-039, D-074).</li>
 * </ul>
 *
 * <p>Boot's auto-configuration backs off from its own {@link DataSource} and {@link NamedParameterJdbcTemplate} and
 * supplies the {@code JdbcTemplate} and the transaction manager over the single or {@code @Primary}
 * {@link DataSource}. The {@code dataSource} bean reads no {@code spring.datasource.*} key.
 *
 * <p>Example, a test that replaces the MySQL database:
 *
 * <pre>
 * &#64;TestConfiguration
 * static class H2Database {
 *     &#64;Bean
 *     &#64;Primary
 *     DataSource h2DataSource() {
 *         return DataSourceBuilder.create().url("jdbc:h2:mem:company;MODE=MySQL").build();
 *     }
 * }
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    /**
     * Returns the HikariCP pool of {@code MySQL_Configuration}: driver class {@code com.mysql.cj.jdbc.Driver} of
     * {@code mysql-connector-j}, URL {@link MySqlProperties#jdbcUrl()}, user name {@link MySqlProperties#user()} and
     * password {@link MySqlProperties#password()}. A {@code null} user name or password is left unset.
     *
     * <p>Hikari pool with no idle connections that opens its first connection on the first {@code getConnection()}
     * call (D-515):
     * <ul>
     *   <li>building the bean starts no pool and opens no connection, whatever the configured values, the marked
     *       placeholders of the committed {@code application.yml} included (D-012);</li>
     *   <li>the first {@code getConnection()} starts the pool without a test connection
     *       ({@code initializationFailTimeout} -1), and the pool opens a connection only when one is requested and none
     *       is idle ({@code minimumIdle} 0);</li>
     *   <li>while no connection can be opened, for example for an unreachable host or a wrong port, database, user
     *       name or password, a {@code getConnection()} call fails with a {@link java.sql.SQLException} once Hikari's
     *       {@code connectionTimeout} (30 seconds by default) has elapsed.</li>
     * </ul>
     *
     * <p>Example: with {@code mysql.host=db}, {@code mysql.user=app} and no {@code mysql.port} or
     * {@code mysql.database} key, the pool's JDBC URL is {@code jdbc:mysql://db:3306/company} and its user name is
     * {@code app}.
     *
     * @param properties the bound {@code mysql.*} keys
     * @return a {@link HikariDataSource} whose pool has not started
     */
    @Bean
    public DataSource dataSource(MySqlProperties properties) {
        HikariDataSource dataSource = DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .url(properties.jdbcUrl())
                .username(properties.user())
                .password(properties.password())
                .build();
        // No idle minimum: connections are opened on request only (D-515).
        dataSource.setMinimumIdle(0);
        // Pool start makes no test connection: the first getConnection() starts the pool (D-515).
        dataSource.setInitializationFailTimeout(-1);
        return dataSource;
    }

    /**
     * Returns the named-parameter JDBC template over the injected {@link DataSource}: {@code dataSource}, or the
     * {@code @Primary} {@link DataSource} when the context holds more than one (D-039, D-074).
     *
     * <p>The template runs the parameterized {@code select}, {@code insert} and {@code update} statements of
     * {@code ContactJdbcClient}. It obtains a connection from the data source for each statement and releases it
     * afterwards, and a connection failure surfaces as
     * {@link org.springframework.jdbc.CannotGetJdbcConnectionException}.
     *
     * @param dataSource the data source the statements run against
     * @return a {@link NamedParameterJdbcTemplate} over {@code dataSource}
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
