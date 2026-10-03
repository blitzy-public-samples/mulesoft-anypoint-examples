package com.mulesoft.examples.service_orchestration_and_choice_routing.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.apache.derby.jdbc.EmbeddedXADataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.XADataSourceWrapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Builds the embedded Derby XA data source of the application and the JDBC templates that run
 * statements on it (D-026).
 *
 * <p>Source: the {@code spring:bean} {@code Derby_Data_Source}
 * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:7-10] and the
 * {@code db:generic-config} {@code Generic_Database_Configuration} over it [same file:23], which
 * every {@code db:*} operation of the application uses: the {@code orders} insert of
 * {@code inhouseOrder} [same file:110-112], the {@code order_audits} insert of
 * {@code auditService} [same file:134-136] and the two {@code CREATE TABLE} statements of
 * {@code databaseInitialisation} [same file:161-167].
 *
 * <ul>
 *   <li>{@link #dataSource(XADataSourceWrapper, String)} builds a Derby
 *       {@link EmbeddedXADataSource} from the key {@value #URL_KEY} and returns it wrapped by the
 *       {@link XADataSourceWrapper} of the Narayana JTA starter. A connection taken from it while
 *       a JTA transaction is active is enlisted in that transaction, and the statements run on it
 *       commit or roll back with the transaction (D-026).</li>
 *   <li>{@link #jdbcTemplate(DataSource)} and {@link #namedParameterJdbcTemplate(DataSource)} run
 *       statements on that data source.</li>
 * </ul>
 *
 * <p>Boot's {@code DataSourceAutoConfiguration}, {@code XADataSourceAutoConfiguration} and
 * {@code JdbcTemplateAutoConfiguration} back off on these beans, and no {@code spring.datasource.*}
 * key is read. The class declares no transaction manager and no {@link XADataSourceWrapper}; the
 * Narayana starter supplies both. Closing the application context issues no Derby engine
 * shutdown.
 *
 * <p>A database name without a sub-protocol, such as {@code muleEmbeddedDB}, is a directory under
 * the Derby system home, which is the working directory of the JVM unless the system property
 * {@code derby.system.home} names another one. A {@code memory:<name>} database name selects
 * Derby's in-memory sub-protocol.
 *
 * <pre>{@code
 * EmbeddedXADataSource xa = DerbyConfig.derbyXaDataSource("jdbc:derby:muleEmbeddedDB;create=true");
 * xa.getDatabaseName();          // "muleEmbeddedDB"
 * xa.getCreateDatabase();        // "create"
 * xa.getConnectionAttributes();  // null
 *
 * DerbyConfig.derbyXaDataSource("jdbc:derby:memory:testDb;create=true;territory=en_US")
 *         .getConnectionAttributes();  // "territory=en_US"
 *
 * DerbyConfig.derbyXaDataSource("jdbc:h2:mem:testDb");  // IllegalArgumentException
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class DerbyConfig {

    /** Configuration key of the Derby connection URL. */
    static final String URL_KEY = "derby-data-source.url";

    /** Protocol prefix of every Derby connection URL. */
    static final String URL_PREFIX = "jdbc:derby:";

    /** Prefix of the Derby network client URL, which the embedded driver does not accept. */
    static final String NETWORK_CLIENT_PREFIX = "jdbc:derby://";

    /** Prefix of the DB2 JCC client URL, which the embedded driver does not accept. */
    static final String JCC_CLIENT_PREFIX = "jdbc:derby:net:";

    /** URL attribute that creates the database when it does not exist. */
    static final String CREATE_ATTRIBUTE = "create=true";

    /** {@code createDatabase} value of a Derby data source that creates its database. */
    static final String CREATE_DATABASE = "create";

    /** Separator of the database name and the attributes in a Derby URL. */
    static final String ATTRIBUTE_SEPARATOR = ";";

    /** Logger of the data source creation. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DerbyConfig.class);

    /**
     * Builds the Derby XA data source from {@value #URL_KEY} and enlists it through the Narayana
     * wrapper (D-026).
     *
     * <p>This is the only {@link DataSource} bean of the context, and it is marked primary. Every
     * connection it returns comes from Narayana's wrapper: inside a JTA transaction the
     * connection's XA resource joins that transaction, as the original {@code ALWAYS_JOIN}
     * insert joined the transaction of its inbound endpoint.
     *
     * @param wrapper the {@link XADataSourceWrapper} supplied by the Narayana JTA starter
     * @param url     the value of {@value #URL_KEY}, for example
     *                {@code jdbc:derby:muleEmbeddedDB;create=true}
     * @return the wrapped Derby data source
     * @throws IllegalArgumentException when {@code url} is not an embedded Derby URL or names no
     *                                  database; the message names {@value #URL_KEY}
     * @throws Exception                when the wrapper fails to wrap the XA data source
     */
    @Bean
    @Primary
    public DataSource dataSource(XADataSourceWrapper wrapper,
                                 @Value("${derby-data-source.url}") String url) throws Exception {
        Objects.requireNonNull(wrapper, "wrapper");
        EmbeddedXADataSource xaDataSource = derbyXaDataSource(url);
        DataSource dataSource = wrapper.wrapDataSource(xaDataSource);
        LOGGER.info("Derby XA data source for database '{}' (createDatabase={}) enlisted through {}",
                xaDataSource.getDatabaseName(), xaDataSource.getCreateDatabase(),
                wrapper.getClass().getName());
        return dataSource;
    }

    /**
     * Returns a {@link JdbcTemplate} over the Derby data source.
     *
     * @param dataSource the data source built by {@link #dataSource(XADataSourceWrapper, String)}
     * @return a template that runs plain and DDL statements on {@code dataSource}
     */
    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /**
     * Returns a {@link NamedParameterJdbcTemplate} over the Derby data source, the replacement of
     * the {@code db:parameterized-query} operations of {@code Generic_Database_Configuration}.
     *
     * @param dataSource the data source built by {@link #dataSource(XADataSourceWrapper, String)}
     * @return a template that runs parameterized statements on {@code dataSource}
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Builds an unwrapped Derby {@link EmbeddedXADataSource} from a Derby connection URL.
     *
     * <p>The URL is read as {@code jdbc:derby:<databaseName>[;<attribute>]...}:
     * <ol>
     *   <li>The prefix {@value #URL_PREFIX} is removed and the remainder is split on
     *       {@value #ATTRIBUTE_SEPARATOR}.</li>
     *   <li>The first part is the database name, passed unchanged to
     *       {@link EmbeddedXADataSource#setDatabaseName(String)}, sub-protocol included
     *       ({@code memory:testDb}).</li>
     *   <li>Each attribute equal to {@value #CREATE_ATTRIBUTE} sets
     *       {@link EmbeddedXADataSource#setCreateDatabase(String)} to {@value #CREATE_DATABASE}.</li>
     *   <li>Every other non-empty attribute is kept in URL order; the kept attributes, joined with
     *       {@value #ATTRIBUTE_SEPARATOR}, are passed to
     *       {@link EmbeddedXADataSource#setConnectionAttributes(String)}. Empty attributes
     *       ({@code ;;} or a trailing {@code ;}) are skipped. Without other attributes the
     *       connection attributes stay {@code null}.</li>
     * </ol>
     *
     * <p>The method opens no connection and boots no database.
     *
     * @param url a Derby connection URL, for example {@code jdbc:derby:muleEmbeddedDB;create=true}
     * @return a new, configured {@link EmbeddedXADataSource}
     * @throws IllegalArgumentException when {@code url} is {@code null}, does not start with
     *                                  {@value #URL_PREFIX}, is a Derby client URL
     *                                  ({@value #NETWORK_CLIENT_PREFIX} or
     *                                  {@value #JCC_CLIENT_PREFIX}), or names no database; the
     *                                  message names {@value #URL_KEY} and leaves out the URL value
     */
    static EmbeddedXADataSource derbyXaDataSource(String url) {
        if (url == null || !url.startsWith(URL_PREFIX)
                || url.startsWith(NETWORK_CLIENT_PREFIX) || url.startsWith(JCC_CLIENT_PREFIX)) {
            throw new IllegalArgumentException(URL_KEY
                    + " must be an embedded Derby URL of the form "
                    + URL_PREFIX + "<databaseName>[;<attribute>=<value>]...");
        }

        String[] parts = url.substring(URL_PREFIX.length()).split(ATTRIBUTE_SEPARATOR, -1);
        String databaseName = parts[0];
        if (databaseName.isBlank()) {
            throw new IllegalArgumentException(URL_KEY
                    + " names no database: the part after " + URL_PREFIX
                    + " and before the first " + ATTRIBUTE_SEPARATOR + " is empty");
        }

        boolean createDatabase = false;
        List<String> connectionAttributes = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String attribute = parts[i];
            if (attribute.isEmpty()) {
                continue;
            }
            if (CREATE_ATTRIBUTE.equals(attribute)) {
                createDatabase = true;
            } else {
                connectionAttributes.add(attribute);
            }
        }

        EmbeddedXADataSource xaDataSource = new EmbeddedXADataSource();
        xaDataSource.setDatabaseName(databaseName);
        if (createDatabase) {
            xaDataSource.setCreateDatabase(CREATE_DATABASE);
        }
        if (!connectionAttributes.isEmpty()) {
            xaDataSource.setConnectionAttributes(String.join(ATTRIBUTE_SEPARATOR, connectionAttributes));
        }
        return xaDataSource;
    }
}
