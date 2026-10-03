package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs the employee select statements against the {@code MySQL_Configuration} data source.
 *
 * <p>Source: {@code querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:11-15}, the
 * {@code db:select} of flow {@code attachmentsFlow1} with its {@code db:dynamic-query} (line 12), which references
 * the {@code db:generic-config} named {@code MySQL_Configuration} (line 5).
 *
 * <p>Execution:
 * <ul>
 *   <li>{@link #select(String)} receives the complete statement text that {@code service.EmployeeReportService}
 *       composes for one employee name and runs it unchanged as a plain statement (D-044). The class composes,
 *       inspects and alters no SQL text and holds no SQL text of its own.</li>
 *   <li>Every statement runs through the {@link JdbcTemplate} bean that Spring Boot's JDBC template
 *       auto-configuration builds over the {@code dataSource} bean of {@code config.DataSourceConfig}. The template
 *       takes a connection from the data source for the statement and releases it afterwards.</li>
 *   <li>The class is a concrete, non-final {@code @Component} with no Java interface and no {@code execute}
 *       wrapper (D-672). It starts or joins no transaction, and it retries, caches and logs nothing; the template
 *       logs each statement under the {@code org.springframework.jdbc.core.JdbcTemplate} category at
 *       {@code DEBUG}.</li>
 *   <li>A failure reaches the caller unchanged as the exception the template raises, a
 *       {@link org.springframework.dao.DataAccessException} for every connection or statement failure.</li>
 * </ul>
 *
 * <p>The instance holds only the injected template, which is thread-safe; concurrent calls run on separate
 * connections of the pool.
 *
 * <p>Example, for the statement composed for the employee name {@code Chava Puckett}:
 * <pre>{@code
 * List<Map<String, Object>> rows = employeeJdbcClient.select(statement);
 * // rows: [{first_name=Chava, last_name=Puckett, gender=F, dob=1985-09-02, hire_date=2008-10-12}]
 * }</pre>
 */
@Component
public class EmployeeJdbcClient {

    /** Template that runs each statement over the {@code MySQL_Configuration} data source. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Creates the client over the given template.
     *
     * @param jdbcTemplate the template that runs each statement, stored as given; in the application it is Spring
     *     Boot's auto-configured {@link JdbcTemplate} over the {@code dataSource} bean of
     *     {@code config.DataSourceConfig}
     */
    public EmployeeJdbcClient(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs the composed select statement as a plain statement and returns its rows (D-044).
     *
     * <p>{@link JdbcTemplate#queryForList(String)} creates a {@link java.sql.Statement} and calls
     * {@code executeQuery} with {@code sql} exactly as given. No parameter is bound, and no character of the text
     * is escaped, quoted, trimmed or checked: MySQL alone decides the outcome of every text, including text that
     * holds {@code "} or {@code \}.
     *
     * <p>Rows:
     * <ul>
     *   <li>The list holds one map per result row, in the order MySQL returns the rows, and is returned as the
     *       template builds it: not copied, sorted or converted. A statement that matches no row returns an empty
     *       list.</li>
     *   <li>Each map is the template's case-insensitive map keyed by column label, in column order:
     *       {@code first_name}, {@code last_name}, {@code gender}, {@code dob} and {@code hire_date} for the
     *       employee statement.</li>
     *   <li>Each value is read with {@code ResultSet.getObject}: with MySQL Connector/J a {@code String} for the
     *       {@code VARCHAR} and {@code ENUM} columns and a {@code java.sql.Date} for the {@code DATE} columns of the
     *       employee statement, and {@code null} for SQL {@code NULL}.</li>
     * </ul>
     *
     * <p>Failures propagate unchanged: a {@code null} {@code sql} raises the template's
     * {@link IllegalArgumentException}, a connection that cannot be opened raises
     * {@link org.springframework.jdbc.CannotGetJdbcConnectionException}, and a statement MySQL rejects raises the
     * translated {@link org.springframework.dao.DataAccessException} subclass, for example
     * {@link org.springframework.jdbc.BadSqlGrammarException} for a syntax error.
     *
     * @param sql the complete statement text
     * @return one map per row, keyed by column label; an empty list when no row matches
     * @throws org.springframework.dao.DataAccessException if no connection can be opened or the statement fails
     */
    public List<Map<String, Object>> select(String sql) {
        return jdbcTemplate.queryForList(sql);
    }
}
