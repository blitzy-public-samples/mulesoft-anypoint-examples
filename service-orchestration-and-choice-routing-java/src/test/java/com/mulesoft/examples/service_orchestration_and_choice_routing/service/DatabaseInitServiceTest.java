package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.sql.SQLException;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests of {@link DatabaseInitService}: flow {@code databaseInitialisation}
 * ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:159-172}), its two
 * {@code db:execute-ddl} statements [fulfillment.xml:162, :166], the {@code set-payload}
 * {@code db populated} [fulfillment.xml:168] and the {@code catch-exception-strategy} payload
 * {@code table already populated} [fulfillment.xml:169-171].
 *
 * <p>Each test constructs the service with {@code new DatabaseInitService(jdbcTemplate)} over a
 * Mockito mock of {@link JdbcTemplate}, with no Spring application context and no database. A
 * started {@link ListAppender} is attached to the {@code DatabaseInitService} logger, whose level is
 * {@link Level#ALL} during each test and restored afterwards. The tests assert
 * <ul>
 *   <li>the two DDL constants equal the original statements verbatim and the two results equal the
 *       original payloads;</li>
 *   <li>both statements run through {@link JdbcTemplate#execute(String)}, {@code orders} first, and
 *       the result is {@code db populated} with no ERROR event;</li>
 *   <li>a failure of the first statement skips the second; a failure of either statement gives
 *       {@code table already populated} and exactly one ERROR event carrying the failure's message
 *       and stack trace.</li>
 * </ul>
 * The tests cover every line of {@link DatabaseInitService} (D-049). The class and its four
 * {@code @Test} methods are public; the lifecycle methods are package-private (D-617).
 */
@ExtendWith(MockitoExtension.class)
public class DatabaseInitServiceTest {

    /** DDL of the {@code db:execute-ddl} "Create orders Table", fulfillment.xml:162. */
    private static final String ORIGINAL_CREATE_ORDERS_TABLE = "CREATE TABLE orders (i int generated always"
            + " as identity, product_id varchar(256), name varchar(256), manufacturer varchar(256),"
            + " quantity integer, price integer)";

    /** DDL of the {@code db:execute-ddl} "Create order_audits Table", fulfillment.xml:166. */
    private static final String ORIGINAL_CREATE_ORDER_AUDITS_TABLE = "CREATE TABLE order_audits (i int"
            + " generated always as identity, order_id varchar(256), total_value integer)";

    /** Derby SQLState of a {@code CREATE TABLE} on an existing table. */
    private static final String TABLE_EXISTS_SQL_STATE = "X0Y32";

    /** Template mock that receives the DDL statements. */
    @Mock
    private JdbcTemplate jdbcTemplate;

    /** The unit under test, constructed over {@link #jdbcTemplate}. */
    private DatabaseInitService service;

    /** Appender that records the events of the {@code DatabaseInitService} logger. */
    private ListAppender<ILoggingEvent> appender;

    /** The {@code DatabaseInitService} logger. */
    private Logger serviceLogger;

    /** Level of {@link #serviceLogger} before the test. */
    private Level previousLevel;

    /**
     * Creates the service and attaches a started list appender to its logger at {@link Level#ALL}.
     * Package-private, like {@link #tearDown()}: the public methods of this class are its four
     * {@code @Test} methods (D-617).
     */
    @BeforeEach
    void setUp() {
        service = new DatabaseInitService(jdbcTemplate);

        serviceLogger = (Logger) LoggerFactory.getLogger(DatabaseInitService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.ALL);

        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    /** Detaches and stops the list appender and restores the logger's previous level. */
    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    /**
     * {@link DatabaseInitService#CREATE_ORDERS_TABLE} and
     * {@link DatabaseInitService#CREATE_ORDER_AUDITS_TABLE} equal the DDL of fulfillment.xml:162 and
     * :166; the results equal the payloads {@code db populated} [:168] and
     * {@code table already populated} [:169-171].
     */
    @Test
    @DisplayName("Database initialisation DDL and results equal the original statements and payloads")
    public void ddlConstantsEqualOriginalStatements() {
        assertThat(DatabaseInitService.CREATE_ORDERS_TABLE).isEqualTo(ORIGINAL_CREATE_ORDERS_TABLE);
        assertThat(DatabaseInitService.CREATE_ORDER_AUDITS_TABLE).isEqualTo(ORIGINAL_CREATE_ORDER_AUDITS_TABLE);
        assertThat(DatabaseInitService.DB_POPULATED).isEqualTo("db populated");
        assertThat(DatabaseInitService.TABLE_ALREADY_POPULATED).isEqualTo("table already populated");
    }

    /**
     * On an empty database both statements run, {@code orders} then {@code order_audits}, through
     * {@code execute(String)} and nothing else; the result is {@code db populated} and no ERROR event
     * is logged.
     */
    @Test
    @DisplayName("Database initialisation creates orders, then order_audits, and answers db populated")
    public void createsBothTablesInOrderAndReportsPopulated() {
        String result = service.databaseInitialisation();

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDERS_TABLE);
        order.verify(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDER_AUDITS_TABLE);
        verifyNoMoreInteractions(jdbcTemplate);
        assertThat(result).isEqualTo(DatabaseInitService.DB_POPULATED);
        assertThat(appender.list).extracting(ILoggingEvent::getLevel).doesNotContain(Level.ERROR);
    }

    /**
     * When {@code CREATE TABLE orders} fails with Derby's {@code X0Y32} the result is
     * {@code table already populated}, {@code CREATE TABLE order_audits} is never run, and exactly one
     * ERROR event carries the failure.
     */
    @Test
    @DisplayName("Database initialisation answers table already populated and skips order_audits when orders exists")
    public void firstTableFailureReportsAlreadyPopulated() {
        BadSqlGrammarException failure = new BadSqlGrammarException("ddl", DatabaseInitService.CREATE_ORDERS_TABLE,
                new SQLException("Table/View 'ORDERS' already exists", TABLE_EXISTS_SQL_STATE));
        doThrow(failure).when(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDERS_TABLE);

        String result = service.databaseInitialisation();

        verify(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDERS_TABLE);
        verify(jdbcTemplate, never()).execute(DatabaseInitService.CREATE_ORDER_AUDITS_TABLE);
        verifyNoMoreInteractions(jdbcTemplate);
        assertThat(result).isEqualTo(DatabaseInitService.TABLE_ALREADY_POPULATED);
        assertSingleErrorEventFor(failure);
    }

    /**
     * When {@code CREATE TABLE orders} succeeds and {@code CREATE TABLE order_audits} fails with
     * Derby's {@code X0Y32}, both statements have run in order, the result is
     * {@code table already populated}, and exactly one ERROR event carries the failure.
     */
    @Test
    @DisplayName("Database initialisation answers table already populated when order_audits exists")
    public void secondTableFailureReportsAlreadyPopulated() {
        BadSqlGrammarException failure = new BadSqlGrammarException("ddl",
                DatabaseInitService.CREATE_ORDER_AUDITS_TABLE,
                new SQLException("Table/View 'ORDER_AUDITS' already exists", TABLE_EXISTS_SQL_STATE));
        doNothing().when(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDERS_TABLE);
        doThrow(failure).when(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDER_AUDITS_TABLE);

        String result = service.databaseInitialisation();

        InOrder order = inOrder(jdbcTemplate);
        order.verify(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDERS_TABLE);
        order.verify(jdbcTemplate).execute(DatabaseInitService.CREATE_ORDER_AUDITS_TABLE);
        verifyNoMoreInteractions(jdbcTemplate);
        assertThat(result).isEqualTo(DatabaseInitService.TABLE_ALREADY_POPULATED);
        assertSingleErrorEventFor(failure);
    }

    /**
     * Asserts that the logger recorded exactly one ERROR event, whose formatted message contains the
     * message of {@code failure} and whose throwable is of the class of {@code failure}.
     *
     * @param failure the exception thrown by the failing statement
     */
    private void assertSingleErrorEventFor(Exception failure) {
        List<ILoggingEvent> errors = appender.list.stream()
                .filter(event -> Level.ERROR.equals(event.getLevel()))
                .toList();
        assertThat(errors).hasSize(1);
        ILoggingEvent error = errors.get(0);
        assertThat(error.getFormattedMessage()).contains(failure.getMessage());
        assertThat(error.getThrowableProxy()).isNotNull();
        assertThat(error.getThrowableProxy().getClassName()).isEqualTo(failure.getClass().getName());
    }
}
