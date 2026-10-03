package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Database configuration {@code MySQL_Configuration}: the data source built from {@code jdbc.url}, the
 * named-parameter JDBC template that runs the {@code orders} insert, and the local JDBC transaction manager used by
 * {@code @Transactional} (D-025, D-063).
 *
 * <p>Source: {@code using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:4}, the global element
 * {@code db:generic-config} named {@code MySQL_Configuration} with {@code url="${jdbc.url}"}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code jdbc.url} is the full JDBC connection string, user and password included where the server requires
 *       them, for example {@code jdbc:mysql://<host>:3306/<database>?user=<user>&password=<password>}. The committed
 *       {@code application.yml} holds a marked placeholder that is not a JDBC URL (D-012); with that value the
 *       {@code dataSource} bean fails with {@link IllegalArgumentException} and the application context does not
 *       start.</li>
 *   <li>The JDBC driver class is derived from the URL prefix: {@code jdbc:mysql:} selects the MySQL Connector/J
 *       driver of {@code mysql-connector-j} (D-063) and {@code jdbc:h2:} selects the H2 driver.</li>
 * </ul>
 *
 * <p>The three beans replace Boot's auto-configured data source, transaction manager and
 * {@link NamedParameterJdbcTemplate}; no {@code spring.datasource.*} key is read.
 */
@Configuration
@EnableConfigurationProperties(DataSourceConfig.JdbcProperties.class)
public class DataSourceConfig {

    /**
     * Builds the pooled data source of {@code MySQL_Configuration} from {@code jdbc.url} (D-063).
     *
     * <p>Only the URL is set; the driver class is derived from the URL prefix, and user and password are read from
     * the URL by the driver. The pool opens its first connection on first use, not at startup.
     *
     * @param properties the bound {@code jdbc.*} keys
     * @return a HikariCP data source for {@link JdbcProperties#url()}
     * @throws IllegalArgumentException when {@code jdbc.url} does not start with {@code jdbc}, as with the committed
     *         placeholder
     */
    @Bean
    public DataSource dataSource(JdbcProperties properties) {
        return DataSourceBuilder.create().url(properties.url()).build();
    }

    /**
     * Named-parameter JDBC template over {@code MySQL_Configuration}; it runs the {@code orders} insert of
     * {@code transactionsFlow1} [transactions.xml:13-16] (D-063).
     *
     * @param dataSource the {@code MySQL_Configuration} data source
     * @return a template bound to {@code dataSource}
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Local JDBC transaction manager used by {@code @Transactional} (D-025).
     *
     * <p>It is the only transaction manager in the context. The JMS listener container does not use it: the
     * listener session is locally transacted, and the JDBC transaction begins and ends inside it, as
     * {@code ee:multi-transactional action="ALWAYS_BEGIN"} does [transactions.xml:12-21].
     *
     * @param dataSource the {@code MySQL_Configuration} data source
     * @return a transaction manager bound to {@code dataSource}
     */
    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Binds the key {@code jdbc.url}, the {@code ${jdbc.url}} placeholder of {@code MySQL_Configuration}
     * [transactions.xml:4]; its value comes from {@code application.yml} or an override of the same key.
     *
     * @param url the JDBC connection string
     */
    @ConfigurationProperties("jdbc")
    public record JdbcProperties(String url) {
    }
}
