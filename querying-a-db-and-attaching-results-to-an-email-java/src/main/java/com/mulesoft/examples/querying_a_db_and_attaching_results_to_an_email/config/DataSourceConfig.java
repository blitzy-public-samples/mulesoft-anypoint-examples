package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the JDBC data source of the {@code MySQL_Configuration} database configuration from {@code jdbc.url}
 * with the MySQL Connector/J driver (D-063).
 *
 * <p>Source: {@code querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:5}, the global
 * element {@code db:generic-config} named {@code MySQL_Configuration} with
 * {@code driverClassName="com.mysql.jdbc.Driver"} and {@code url="${jdbc.url}"}, referenced by the
 * {@code db:select} of flow {@code attachmentsFlow1}.
 *
 * <p>Beans and their consumers:
 * <ul>
 *   <li>{@code dataSource} is the application's only {@link DataSource}. Boot's data source auto-configuration
 *       backs off from it and reads no {@code spring.datasource.*} key.</li>
 *   <li>Boot's JDBC template auto-configuration builds the {@code JdbcTemplate} and the
 *       {@code NamedParameterJdbcTemplate} over this data source.</li>
 *   <li>No transaction manager, pool size or timeout is configured here; the pool runs with HikariCP's
 *       defaults (D-554).</li>
 * </ul>
 *
 * <p>The URL is read from {@link MySqlConfigurationProperties#url()} and is never written in code (D-012). The
 * driver class {@code com.mysql.cj.jdbc.Driver} is the {@code mysql-connector-j} 8.3.0 class that corresponds to
 * the element's {@code com.mysql.jdbc.Driver} (D-063).
 */
@Configuration
public class DataSourceConfig {

    /**
     * JDBC data source for {@code jdbc.url}; no connection is opened until first use.
     *
     * <p>The returned {@link HikariDataSource} is created with its no-argument constructor; its pool starts on the
     * first {@code getConnection()} call (D-554). Building the bean opens no connection, also while {@code jdbc.url}
     * holds the placeholder of the committed {@code application.yml} (D-012). Setting the driver class loads
     * {@code com.mysql.cj.jdbc.Driver} from the classpath.
     *
     * <p>Example: with {@code jdbc.url=jdbc:mysql://localhost:3306/company?user=<user>&password=<password>} the
     * bean's {@code getJdbcUrl()} returns that text unchanged and {@code getDriverClassName()} returns
     * {@code com.mysql.cj.jdbc.Driver}.
     *
     * @param properties the bound {@code jdbc.*} keys of {@code MySQL_Configuration}
     * @return a {@link HikariDataSource} for {@code jdbc.url} whose pool has not started
     */
    @Bean
    public DataSource dataSource(MySqlConfigurationProperties properties) {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(properties.url());
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return dataSource;
    }
}
