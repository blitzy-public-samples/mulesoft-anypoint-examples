package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Implements flow {@code databaseInitialisation}
 * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:159-172]: creates the
 * tables {@code orders} and {@code order_audits}; answers {@value #DB_POPULATED}, or
 * {@value #TABLE_ALREADY_POPULATED} when a statement fails.
 *
 * <p>The statements run through the {@link JdbcTemplate} over the Derby XA data source of
 * {@code config.DerbyConfig} (D-026), one after the other, in auto-commit mode and outside any
 * JTA transaction:
 * <ol>
 *   <li>{@link #CREATE_ORDERS_TABLE}, the {@code db:execute-ddl} "Create orders Table"
 *       [fulfillment.xml:161-164];</li>
 *   <li>{@link #CREATE_ORDER_AUDITS_TABLE}, the {@code db:execute-ddl} "Create order_audits Table"
 *       [fulfillment.xml:165-167].</li>
 * </ol>
 * When both succeed the result is {@value #DB_POPULATED} [fulfillment.xml:168]. When the first
 * statement fails the second is not run. Any exception from either statement is logged at ERROR
 * and the result is {@value #TABLE_ALREADY_POPULATED}, the payload of the
 * {@code catch-exception-strategy} [fulfillment.xml:169-171]. A table created by the first
 * statement stays created when the second fails. The method throws no exception.
 *
 * <p>The class holds no mutable state and is safe for concurrent use.
 *
 * <pre>{@code
 * DatabaseInitService service = new DatabaseInitService(jdbcTemplate);
 * service.databaseInitialisation(); // "db populated" on an empty database
 * service.databaseInitialisation(); // "table already populated": Derby reports X0Y32 for ORDERS
 * }</pre>
 */
@Service
public class DatabaseInitService {

    /**
     * DDL of the {@code db:execute-ddl} "Create orders Table" [fulfillment.xml:162], verbatim.
     */
    public static final String CREATE_ORDERS_TABLE = "CREATE TABLE orders (i int generated always as identity,"
            + " product_id varchar(256), name varchar(256), manufacturer varchar(256), quantity integer,"
            + " price integer)";

    /**
     * DDL of the {@code db:execute-ddl} "Create order_audits Table" [fulfillment.xml:166], verbatim.
     */
    public static final String CREATE_ORDER_AUDITS_TABLE = "CREATE TABLE order_audits (i int generated always"
            + " as identity, order_id varchar(256), total_value integer)";

    /** Result when both tables are created, the {@code set-payload} of fulfillment.xml:168. */
    public static final String DB_POPULATED = "db populated";

    /**
     * Result when a statement fails, the {@code set-payload} of the
     * {@code catch-exception-strategy} at fulfillment.xml:169-171.
     */
    public static final String TABLE_ALREADY_POPULATED = "table already populated";

    /** Logger of the failed statements. */
    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseInitService.class);

    /** Template that runs the two DDL statements on the Derby data source. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the service over the {@code jdbcTemplate} bean of {@code config.DerbyConfig}.
     *
     * @param jdbcTemplate the template that runs the DDL statements
     * @throws NullPointerException when {@code jdbcTemplate} is {@code null}
     */
    public DatabaseInitService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Implements flow {@code databaseInitialisation}: runs {@link #CREATE_ORDERS_TABLE}, then
     * {@link #CREATE_ORDER_AUDITS_TABLE}, through {@link JdbcTemplate#execute(String)}.
     *
     * <p>When the first statement throws, the second is not run. Every exception thrown by either
     * statement is logged at ERROR with its message and stack trace and is not rethrown.
     *
     * @return {@value #DB_POPULATED} when both statements complete, otherwise
     *         {@value #TABLE_ALREADY_POPULATED}
     */
    public String databaseInitialisation() {
        try {
            jdbcTemplate.execute(CREATE_ORDERS_TABLE);
            jdbcTemplate.execute(CREATE_ORDER_AUDITS_TABLE);
            return DB_POPULATED;
        } catch (Exception e) {
            LOGGER.error("Flow databaseInitialisation: table creation failed, answering '{}': {}",
                    TABLE_ALREADY_POPULATED, e.getMessage(), e);
            return TABLE_ALREADY_POPULATED;
        }
    }
}
