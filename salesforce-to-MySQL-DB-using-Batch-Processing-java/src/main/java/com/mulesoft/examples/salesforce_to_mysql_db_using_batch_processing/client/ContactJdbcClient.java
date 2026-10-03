package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Runs the three parameterized statements of batch job {@code salesforce-to-database-Batch} against the
 * {@code contact} table of the database configuration {@code MySQL_Configuration}.
 *
 * <p>Source: {@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml}, the
 * global element {@code db:mysql-config} named {@code MySQL_Configuration} (line 7) and the three operations that
 * reference it:
 * <ul>
 *   <li>{@link #selectByEmail(Object)}: the {@code db:select} of step {@code queryExistingContactInDbStep}
 *       (line 28);</li>
 *   <li>{@link #insert(Object, Object, Object)}: the {@code db:insert} of step {@code upsertContactInDbStep}
 *       (line 40);</li>
 *   <li>{@link #update(Object, Object, Object)}: the {@code db:update} of step {@code upsertContactInDbStep}
 *       (line 46).</li>
 * </ul>
 * The table is the one {@code contact.sql} creates: {@code first_name varchar(45)} and {@code last_name varchar(45)},
 * both nullable, and the primary key {@code email varchar(45) NOT NULL}.
 *
 * <p>Statements: each SQL text is the original text with every {@code #[payload.<name>]} expression replaced by the
 * named parameter {@code :<name>}. {@link NamedParameterJdbcTemplate} binds the parameters in their order of
 * appearance in the text, the order of the original expressions. Each value is bound as given, with no type
 * conversion; a {@code null} value binds as SQL {@code NULL}. No SQL text is composed from a value.
 *
 * <p>Execution: the class is a concrete {@code @Component} with no Java interface and no {@code execute} wrapper
 * (D-652). Each method runs one statement through the {@code namedParameterJdbcTemplate} bean of
 * {@code config.DataSourceConfig}, which takes a connection from the data source for the statement and releases it
 * afterwards (D-515). No method starts or joins a transaction: each statement commits on its own. A failure of a
 * statement reaches the caller as the {@link org.springframework.dao.DataAccessException} the template raises,
 * unchanged; batch job {@code SalesforceToDatabaseBatchJob} records it against the contact record (D-035). The
 * template logs each statement under the {@code org.springframework.jdbc.core.JdbcTemplate} category at
 * {@code DEBUG}.
 *
 * <p>Tests replace the MySQL data source with an embedded H2 database in {@code MODE=MySQL} loaded with
 * {@code contact.sql}, and the statements run against it unchanged (D-039, D-074).
 *
 * <p>Example:
 * <pre>
 * List&lt;Map&lt;String, Object&gt;&gt; rows = client.selectByEmail("ana.lopez@example.com");
 * if (rows.isEmpty()) {
 *     client.insert("Ana", "Lopez", "ana.lopez@example.com");    // returns 1
 * } else {
 *     client.update("Ana", "Lopez", "ana.lopez@example.com");    // returns 1
 * }
 * </pre>
 */
@Component
public class ContactJdbcClient {

    /**
     * Select of step {@code queryExistingContactInDbStep}
     * [salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:28].
     */
    static final String SELECT_BY_EMAIL = "SELECT first_name,last_name,email FROM contact WHERE email=:email";

    /**
     * Insert of step {@code upsertContactInDbStep}
     * [salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:40].
     */
    static final String INSERT =
            "INSERT INTO contact (first_name, last_name, email) VALUES (:first_name,:last_name,:email)";

    /**
     * Update of step {@code upsertContactInDbStep}
     * [salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:46].
     */
    static final String UPDATE =
            "UPDATE contact SET first_name=:first_name,last_name=:last_name WHERE email = :email";

    /** Parameter name of the {@code #[payload.email]} expression. */
    private static final String EMAIL = "email";

    /** Parameter name of the {@code #[payload.first_name]} expression. */
    private static final String FIRST_NAME = "first_name";

    /** Parameter name of the {@code #[payload.last_name]} expression. */
    private static final String LAST_NAME = "last_name";

    /** Template that runs every statement of this class. */
    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Creates the client over the given template.
     *
     * @param jdbcTemplate the {@code namedParameterJdbcTemplate} bean of {@code config.DataSourceConfig}
     * @throws NullPointerException if {@code jdbcTemplate} is {@code null}
     */
    public ContactJdbcClient(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
    }

    /**
     * Runs {@code SELECT first_name,last_name,email FROM contact WHERE email=:email}, the {@code db:select} of step
     * {@code queryExistingContactInDbStep} [salesforce-to-database.xml:28], with {@code email} bound to the given
     * value.
     *
     * <p>Each returned row is a map with the keys {@code first_name}, {@code last_name} and {@code email} in that
     * order, as the driver labels the columns, and its key lookup ignores case. A {@code null} email binds as SQL
     * {@code NULL}, which matches no row.
     *
     * @param email the value of {@code payload.email}, bound as given
     * @return the matching rows, empty when no row has this email; never {@code null}
     * @throws org.springframework.dao.DataAccessException if the statement fails, for example when no connection
     *                                                     can be obtained
     */
    public List<Map<String, Object>> selectByEmail(Object email) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue(EMAIL, email);
        return jdbcTemplate.queryForList(SELECT_BY_EMAIL, params);
    }

    /**
     * Runs {@code INSERT INTO contact (first_name, last_name, email) VALUES (:first_name,:last_name,:email)}, the
     * {@code db:insert} of step {@code upsertContactInDbStep} [salesforce-to-database.xml:40], with the three values
     * bound in that order.
     *
     * @param firstName the value of {@code payload.first_name}, bound as given; {@code null} binds as SQL
     *                  {@code NULL}
     * @param lastName  the value of {@code payload.last_name}, bound as given; {@code null} binds as SQL
     *                  {@code NULL}
     * @param email     the value of {@code payload.email}, bound as given; {@code null} binds as SQL {@code NULL}
     * @return the number of inserted rows, {@code 1} for a successful insert
     * @throws org.springframework.dao.DataAccessException if the statement fails, for example for a {@code null}
     *                                                     email ({@code email} is {@code NOT NULL}) or an email
     *                                                     that is already present (primary key)
     */
    public int insert(Object firstName, Object lastName, Object email) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue(FIRST_NAME, firstName)
                .addValue(LAST_NAME, lastName)
                .addValue(EMAIL, email);
        return jdbcTemplate.update(INSERT, params);
    }

    /**
     * Runs {@code UPDATE contact SET first_name=:first_name,last_name=:last_name WHERE email = :email}, the
     * {@code db:update} of step {@code upsertContactInDbStep} [salesforce-to-database.xml:46], with the three values
     * bound in that order.
     *
     * @param firstName the value of {@code payload.first_name}, bound as given; {@code null} binds as SQL
     *                  {@code NULL}
     * @param lastName  the value of {@code payload.last_name}, bound as given; {@code null} binds as SQL
     *                  {@code NULL}
     * @param email     the value of {@code payload.email} that selects the row, bound as given; {@code null} binds
     *                  as SQL {@code NULL}, which matches no row
     * @return the number of rows the database reports as affected by the update: {@code 1} when a row has this
     *         email, {@code 0} when none has
     * @throws org.springframework.dao.DataAccessException if the statement fails, for example when no connection
     *                                                     can be obtained
     */
    public int update(Object firstName, Object lastName, Object email) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue(FIRST_NAME, firstName)
                .addValue(LAST_NAME, lastName)
                .addValue(EMAIL, email);
        return jdbcTemplate.update(UPDATE, params);
    }
}
