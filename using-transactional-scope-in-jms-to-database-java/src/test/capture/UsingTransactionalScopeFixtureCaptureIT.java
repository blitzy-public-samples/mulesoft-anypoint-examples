/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package org.mule.examples;

import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mule.MessageExchangePattern;
import org.mule.api.MuleEvent;
import org.mule.api.MuleException;
import org.mule.api.MuleMessage;
import org.mule.module.client.MuleClient;
import org.mule.processor.chain.SubflowInterceptingChainLifecycleWrapper;
import org.mule.tck.junit4.FunctionalTestCase;

/**
 * Tier 2A in-JVM fixture capture for the original example
 * {@code using-transactional-scope-in-jms-to-database} (D-081).
 * <p>
 * The class runs only inside an isolated copy of the original project, compiled
 * there by the Java 8 toolchain against the original's Mule 3.8.0 test
 * classpath and selected by Failsafe through {@code -Dit.test}. The Java 17
 * project never compiles it: {@code src/test/capture/} is outside its source
 * roots.
 * <p>
 * It starts {@code transactions.xml} and {@code testflows/test-flows.xml} on
 * {@link FunctionalTestCase}, whose {@code jms:activemq-connector} runs the
 * in-VM ActiveMQ broker {@code vm://localhost?broker.persistent=false&broker.useJmx=false}
 * (D-080). Each test sends the copy's {@code src/test/resources/message.xml} to
 * {@code jms://in}, waits for the connector's redelivery-limit ERROR entry
 * (D-024, D-025), requests {@code jms://out} for 10 s, runs the
 * {@code selectOrders} sub-flow and writes one fixture
 * {@code <capture.out>/using-transactional-scope-in-jms-to-database_<scenario>.json}
 * holding what the original produced (D-023). Mule's verbose exception
 * messages are switched on for the class (D-185). Business outcomes are written as
 * observed and never asserted. A test fails only when the capture cannot be
 * completed: {@code capture.out} is unset, the log appender cannot be
 * registered or received nothing, a wait times out, or a Mule, database or
 * file step fails. Its failure message starts with the fixture identity
 * {@code using-transactional-scope-in-jms-to-database_<scenario>}, and the
 * message and the copied cause of a failed step carry no password (D-334).
 * <p>
 * The Mule context, and with it the in-VM broker, is created for each test
 * method; one MySQL schema serves the whole class. Its name is {@code company}
 * followed by a random UUID without hyphens, 39 lowercase alphanumeric
 * characters (D-333).
 * <p>
 * Before any Mule context starts, {@link #prepareCapture()} reads
 * {@code database.user}, {@code database.password} and {@code database.url}
 * from the copy's {@code src/test/resources/mule.test.properties}, checks that
 * the schema does not exist yet, creates it with the original
 * {@link MySQLDbCreator}, checks that it exists and that its {@code orders}
 * table is reachable through the {@code jdbc.url} value and empty, and only
 * then sets {@code jdbc.url} and {@value #VERBOSE_EXCEPTIONS_PROPERTY}.
 * {@link #tearDownCapture()} drops a schema this class created with the same
 * helper, checks that it is gone, and restores both system properties to
 * their values before the class ran, clearing those that were unset. A failed
 * step fails the class with a message that starts with
 * {@code using-transactional-scope-in-jms-to-database capture setup:} or
 * {@code using-transactional-scope-in-jms-to-database capture teardown:} and
 * names the step, the properties file, the required keys and the schema; the
 * message and the copied causes carry no password (D-334).
 * <p>
 * Usage, from the isolated copy:
 * <pre>
 * env -u MULE_HOME mvn -B -s "$EE_SETTINGS" -DMULE_HOME="$MULE_HOME" \
 *     -Dit.test=UsingTransactionalScopeFixtureCaptureIT -Dcapture.out="$FX" verify
 * </pre>
 */
public class UsingTransactionalScopeFixtureCaptureIT extends FunctionalTestCase
{
    /** Folder name of the original example; prefix of every scenario identity. */
    private static final String EXAMPLE = "using-transactional-scope-in-jms-to-database";

    /** Git-ignored database settings of the copy: database.user, database.password, database.url. */
    private static final String PATH_TO_TEST_PROPERTIES = "./src/test/resources/mule.test.properties";

    /** Key of the database user in {@link #PATH_TO_TEST_PROPERTIES}; its value must not be blank. */
    private static final String DATABASE_USER_KEY = "database.user";

    /** Key of the database password in {@link #PATH_TO_TEST_PROPERTIES}; its value may be empty. */
    private static final String DATABASE_PASSWORD_KEY = "database.password";

    /** Key of the server URL in {@link #PATH_TO_TEST_PROPERTIES}, to which the schema name is appended; its value must not be blank. */
    private static final String DATABASE_URL_KEY = "database.url";

    /** Keys {@link #PATH_TO_TEST_PROPERTIES} must define, in the order failure messages list them. */
    private static final String[] REQUIRED_KEYS = {DATABASE_USER_KEY, DATABASE_PASSWORD_KEY, DATABASE_URL_KEY};

    /** Schema script the database is created from. */
    private static final String PATH_TO_SQL_SCRIPT = "src/main/resources/order.sql";

    /** Order sent to queue {@code in}. */
    private static final String PATH_TO_MESSAGE = "./src/test/resources/message.xml";

    /** Schema created for this class and dropped after it: {@code company} and a random UUID without hyphens (D-333). */
    private static final String DATABASE_NAME = "company" + UUID.randomUUID().toString().replace("-", "");

    /** Query counting the schemas named by its single parameter. */
    private static final String SCHEMA_COUNT_QUERY = "select count(*) from information_schema.schemata where schema_name = ?";

    /** System property the original configuration binds as the database URL. */
    private static final String JDBC_URL_PROPERTY = "jdbc.url";

    /** Prefix of every failure of {@link #prepareCapture()} (D-334). */
    private static final String SETUP_CONTEXT = EXAMPLE + " capture setup: ";

    /** Prefix of every failure of {@link #tearDownCapture()} (D-334). */
    private static final String TEARDOWN_CONTEXT = EXAMPLE + " capture teardown: ";

    /** Text that replaces a credential value in failure messages (D-334). */
    private static final String REDACTED = "****";

    /** A {@code password=} parameter of any letter case and its value, up to the next {@code &}, {@code ;} or whitespace. */
    private static final Pattern PASSWORD_PARAMETER = Pattern.compile("(?i)(password=)[^&;\\s]*");

    /** System property naming the directory the fixtures are written to. */
    private static final String CAPTURE_OUT_PROPERTY = "capture.out";

    /** Mule system property selecting verbose exception messages, read when a Mule context is created (D-185). */
    private static final String VERBOSE_EXCEPTIONS_PROPERTY = "mule.verbose.exceptions";

    /** Name of the log appender registered on the root logger configurations. */
    private static final String APPENDER_NAME = "fixture-capture";

    /** Category of the flow's {@code logger} element. */
    private static final String LOGGER_CATEGORY = "org.mule.api.processor.LoggerMessageProcessor";

    /** Category under which the root-cause messages of the ERROR entries are written. */
    private static final String EXCEPTION_CATEGORY = "org.mule.exception.DefaultMessagingExceptionStrategy";

    /** Category under which the redelivery-limit sentence is written. */
    private static final String REDELIVERY_CATEGORY = "redelivery";

    /** Text identifying the connector's redelivery-limit ERROR entry. */
    private static final String REDELIVERY_MARKER = "exceeds the maxRedelivery setting";

    /** The redelivery-limit sentence, ending at the connector name and its full stop. */
    private static final Pattern REDELIVERY_SENTENCE = Pattern.compile(
            "Message with id \"[^\"]*\" has been redelivered \\d+ times on endpoint \"[^\"]*\", "
            + "which exceeds the maxRedelivery setting of \\d+ on the connector \"[^\"]*\"\\.");

    /** Line of a Mule detailed exception message that precedes the root exception. */
    private static final String ROOT_EXCEPTION_MARKER = "Root Exception stack trace:";

    /** Separator between an exception class name and its message. */
    private static final String CLASS_MESSAGE_SEPARATOR = ": ";

    /** Endpoint the order is sent to. */
    private static final String INBOUND_ENDPOINT = "jms://in";

    /** Endpoint the flow publishes to after a committed transaction. */
    private static final String OUTBOUND_ENDPOINT = "jms://out";

    /** Query of the {@code selectOrders} sub-flow. */
    private static final String SELECT_ORDERS_QUERY = "select count(*) from orders";

    /** Upper bound of the wait for the redelivery-limit entry. */
    private static final long REDELIVERY_WAIT_MILLIS = 60000L;

    /** Interval between two looks at the collected log entries. */
    private static final long POLL_INTERVAL_MILLIS = 100L;

    /** Timeout of the request on {@code jms://out}. */
    private static final long OUT_REQUEST_TIMEOUT_MILLIS = 10000L;

    /** Seconds added to the redelivery wait and the {@code jms://out} request in {@link #MIN_TEST_TIMEOUT_SECS}. */
    private static final int TEST_TIMEOUT_MARGIN_SECS = 60;

    /**
     * Lower bound of the per-test timeout: 60 000 ms, 10 000 ms and
     * {@value #TEST_TIMEOUT_MARGIN_SECS} s, 130 s (D-337).
     */
    private static final int MIN_TEST_TIMEOUT_SECS =
            (int) ((REDELIVERY_WAIT_MILLIS + OUT_REQUEST_TIMEOUT_MILLIS) / 1000L) + TEST_TIMEOUT_MARGIN_SECS;

    /** Number of trailing entries listed when the wait times out. */
    private static final int TRAILING_ENTRIES_REPORTED = 5;

    /** The single, started collector of log events. */
    private static final CaptureAppender APPENDER = new CaptureAppender();

    static
    {
        APPENDER.start();
    }

    /** Raw bytes of {@code message.xml}. */
    private static byte[] MESSAGE_BYTES;

    /** {@code message.xml} decoded as UTF-8, the payload sent to {@code jms://in}. */
    private static String MESSAGE;

    /** Value of {@value #VERBOSE_EXCEPTIONS_PROPERTY} before this class ran, or {@code null} when unset. */
    private static String previousVerboseExceptions;

    /** Value of {@value #JDBC_URL_PROPERTY} before this class ran, or {@code null} when unset. */
    private static String previousJdbcUrl;

    /** True once {@link #prepareCapture()} has saved {@link #previousVerboseExceptions} and {@link #previousJdbcUrl}. */
    private static boolean propertiesSaved;

    /** Value of {@value #DATABASE_URL_KEY}, or {@code null} before setup and after teardown. */
    private static String databaseUrl;

    /** Value of {@value #DATABASE_USER_KEY}, or {@code null} before setup and after teardown. */
    private static String databaseUser;

    /** Value of {@value #DATABASE_PASSWORD_KEY}, or {@code null} before setup and after teardown. */
    private static String databasePassword;

    /** Original helper creating and dropping {@link #DATABASE_NAME}; built once the settings are checked, {@code null} before and after. */
    private static MySQLDbCreator dbCreator;

    /** True from the moment {@link #DATABASE_NAME} is seen to exist after creation until it is seen to be dropped. */
    private static boolean schemaCreated;

    /** Logger contexts whose root configuration holds {@link #APPENDER} during the current test. */
    private final List<LoggerContext> registeredContexts = new ArrayList<LoggerContext>();

    /** Mule configuration files of the original IT, loaded through Mule 3.8's deprecated configuration hook. */
    @Override
    @SuppressWarnings("deprecation")
    protected String getConfigResources()
    {
        return "transactions.xml,testflows/test-flows.xml";
    }

    /**
     * Timeout of each test in the inherited timeout rule: the configured
     * {@code mule.test.timeoutSecs} value or {@link #MIN_TEST_TIMEOUT_SECS},
     * whichever is larger. A redelivery wait that times out fails with its
     * {@link #timeoutMessage(String, List)} before this timeout (D-337).
     */
    @Override
    public int getTestTimeoutSecs()
    {
        return Math.max(super.getTestTimeoutSecs(), MIN_TEST_TIMEOUT_SECS);
    }

    /**
     * Prepares the class, in this order: saves the values of
     * {@value #VERBOSE_EXCEPTIONS_PROPERTY} and {@value #JDBC_URL_PROPERTY};
     * reads the order; reads {@link #PATH_TO_TEST_PROPERTIES} and checks that it
     * defines {@value #DATABASE_USER_KEY}, {@value #DATABASE_PASSWORD_KEY} and
     * {@value #DATABASE_URL_KEY}, with a non-blank user and URL; checks over a
     * connection to the server URL that {@link #DATABASE_NAME} does not exist;
     * creates it from {@code order.sql} with {@link MySQLDbCreator#setUpDatabase()};
     * checks that it exists; checks over
     * {@link MySQLDbCreator#getDatabaseUrlWithName()} that its {@code orders}
     * table holds no row. Only then sets {@value #VERBOSE_EXCEPTIONS_PROPERTY}
     * to {@code true} for the Mule contexts of this class, whose exception log
     * entries then carry the {@value #ROOT_EXCEPTION_MARKER} line (D-185), and
     * {@value #JDBC_URL_PROPERTY} to the URL of the new schema, the property the
     * original configuration binds (D-334).
     *
     * @throws AssertionError when a step fails; the message starts with
     *         {@value #SETUP_CONTEXT} and carries no password, and the cause, when
     *         present, is the {@link #redactedCopy(Throwable)} of the failure
     */
    @BeforeClass
    public static void prepareCapture()
    {
        previousVerboseExceptions = System.getProperty(VERBOSE_EXCEPTIONS_PROPERTY);
        previousJdbcUrl = System.getProperty(JDBC_URL_PROPERTY);
        propertiesSaved = true;

        try
        {
            MESSAGE_BYTES = Files.readAllBytes(Paths.get(PATH_TO_MESSAGE));
        }
        catch (IOException e)
        {
            throw setupFailure("read message", "the order " + PATH_TO_MESSAGE + " cannot be read", e);
        }
        MESSAGE = new String(MESSAGE_BYTES, StandardCharsets.UTF_8);

        loadDatabaseSettings();

        long existing;
        try
        {
            existing = countSchemas();
        }
        catch (SQLException e)
        {
            throw setupFailure("check schema absent", "information_schema.schemata cannot be queried over the "
                               + DATABASE_URL_KEY + " server as " + DATABASE_USER_KEY, e);
        }
        if (existing != 0)
        {
            throw setupFailure("check schema absent", "the schema already exists on the " + DATABASE_URL_KEY
                               + " server; it is left in place and not dropped", null);
        }

        dbCreator = new MySQLDbCreator(DATABASE_NAME, PATH_TO_SQL_SCRIPT, PATH_TO_TEST_PROPERTIES);
        dbCreator.setUpDatabase();

        long created;
        try
        {
            created = countSchemas();
        }
        catch (SQLException e)
        {
            throw setupFailure("create schema", "information_schema.schemata cannot be queried after "
                               + "MySQLDbCreator.setUpDatabase() ran " + PATH_TO_SQL_SCRIPT
                               + "; drop the schema by hand if it exists", e);
        }
        if (created > 0)
        {
            schemaCreated = true;
        }
        if (created == 0)
        {
            throw setupFailure("create schema", "MySQLDbCreator.setUpDatabase() did not create the schema from "
                               + PATH_TO_SQL_SCRIPT + "; MySQLDbCreator logs its own error above", null);
        }
        if (created != 1)
        {
            throw setupFailure("create schema", "information_schema.schemata lists the schema " + created
                               + " times, expected once", null);
        }

        long orders;
        try
        {
            orders = countOrders();
        }
        catch (SQLException e)
        {
            throw setupFailure("check orders table", "\"" + SELECT_ORDERS_QUERY + "\" cannot run over "
                               + "MySQLDbCreator.getDatabaseUrlWithName(), the " + JDBC_URL_PROPERTY
                               + " value; MySQLDbCreator logs its own error above when " + PATH_TO_SQL_SCRIPT
                               + " failed", e);
        }
        if (orders != 0)
        {
            throw setupFailure("check orders table", "table orders holds " + orders + " rows, expected 0", null);
        }

        System.setProperty(VERBOSE_EXCEPTIONS_PROPERTY, "true");
        System.setProperty(JDBC_URL_PROPERTY, dbCreator.getDatabaseUrlWithName());
    }

    /**
     * Ends the class, also after a failed or partial {@link #prepareCapture()}.
     * When this class created {@link #DATABASE_NAME}, drops it with
     * {@link MySQLDbCreator#tearDownDataBase()} and checks over a new connection
     * to the server URL that it no longer exists. Then, when
     * {@link #prepareCapture()} saved them, restores
     * {@value #VERBOSE_EXCEPTIONS_PROPERTY} and {@value #JDBC_URL_PROPERTY} to
     * their values before the class ran, clearing a property that was unset,
     * and discards the settings, the helper and the saved values (D-334).
     *
     * @throws AssertionError after the restore, when the schema still exists or
     *         its removal cannot be checked; the message starts with
     *         {@value #TEARDOWN_CONTEXT}, names the schema to drop by hand and
     *         carries no password
     */
    @AfterClass
    public static void tearDownCapture()
    {
        AssertionError failure = null;
        try
        {
            if (schemaCreated)
            {
                failure = dropSchema();
            }
        }
        finally
        {
            if (propertiesSaved)
            {
                restoreProperty(VERBOSE_EXCEPTIONS_PROPERTY, previousVerboseExceptions);
                restoreProperty(JDBC_URL_PROPERTY, previousJdbcUrl);
            }
            propertiesSaved = false;
            previousVerboseExceptions = null;
            previousJdbcUrl = null;
            schemaCreated = false;
            dbCreator = null;
            databaseUrl = null;
            databaseUser = null;
            databasePassword = null;
        }
        if (failure != null)
        {
            throw failure;
        }
    }

    /**
     * Reads {@link #PATH_TO_TEST_PROPERTIES} with {@link Properties#load(InputStream)},
     * as {@link MySQLDbCreator} does, keeps the three settings and checks them:
     * every key of {@link #REQUIRED_KEYS} is defined, and the values of
     * {@value #DATABASE_USER_KEY} and {@value #DATABASE_URL_KEY} are not blank.
     *
     * @throws AssertionError when the file cannot be read, or listing every
     *         missing key and every blank value by key name
     */
    private static void loadDatabaseSettings()
    {
        Properties settings = new Properties();
        try (InputStream in = new FileInputStream(PATH_TO_TEST_PROPERTIES))
        {
            settings.load(in);
        }
        catch (IOException | IllegalArgumentException e)
        {
            throw setupFailure("read properties", "the properties file cannot be read", e);
        }
        databasePassword = settings.getProperty(DATABASE_PASSWORD_KEY);
        databaseUser = settings.getProperty(DATABASE_USER_KEY);
        databaseUrl = settings.getProperty(DATABASE_URL_KEY);

        List<String> missing = new ArrayList<String>();
        for (String key : REQUIRED_KEYS)
        {
            if (settings.getProperty(key) == null)
            {
                missing.add(key);
            }
        }
        List<String> blank = new ArrayList<String>();
        if (databaseUser != null && databaseUser.trim().isEmpty())
        {
            blank.add(DATABASE_USER_KEY);
        }
        if (databaseUrl != null && databaseUrl.trim().isEmpty())
        {
            blank.add(DATABASE_URL_KEY);
        }
        if (missing.isEmpty() && blank.isEmpty())
        {
            return;
        }
        StringBuilder detail = new StringBuilder();
        if (!missing.isEmpty())
        {
            detail.append("missing keys: ").append(join(missing));
        }
        if (!blank.isEmpty())
        {
            detail.append(missing.isEmpty() ? "" : "; ").append("blank values: ").append(join(blank));
        }
        detail.append(" (").append(DATABASE_USER_KEY).append(" and ").append(DATABASE_URL_KEY)
              .append(" must not be blank, ").append(DATABASE_PASSWORD_KEY).append(" may be empty)");
        throw setupFailure("check properties", detail.toString(), null);
    }

    /**
     * Drops {@link #DATABASE_NAME} with {@link MySQLDbCreator#tearDownDataBase()}
     * and checks that {@code information_schema.schemata} no longer lists it.
     *
     * @return {@code null} when the schema is gone, otherwise the teardown failure to throw
     */
    private static AssertionError dropSchema()
    {
        dbCreator.tearDownDataBase();
        long remaining;
        try
        {
            remaining = countSchemas();
        }
        catch (SQLException e)
        {
            return teardownFailure("verify schema dropped", "information_schema.schemata cannot be queried after "
                                   + "MySQLDbCreator.tearDownDataBase(); if the schema still exists, drop it by hand: DROP SCHEMA "
                                   + DATABASE_NAME, e);
        }
        if (remaining != 0)
        {
            return teardownFailure("verify schema dropped", "the schema still exists after "
                                   + "MySQLDbCreator.tearDownDataBase(), which logs its own error above; drop it by hand: DROP SCHEMA "
                                   + DATABASE_NAME, null);
        }
        schemaCreated = false;
        return null;
    }

    /** Sets {@code key} to {@code value}, or clears it when {@code value} is {@code null}. */
    private static void restoreProperty(String key, String value)
    {
        if (value == null)
        {
            System.clearProperty(key);
        }
        else
        {
            System.setProperty(key, value);
        }
    }

    /**
     * Number of rows {@link #SCHEMA_COUNT_QUERY} counts for {@link #DATABASE_NAME},
     * over a new connection to {@value #DATABASE_URL_KEY} as
     * {@value #DATABASE_USER_KEY} with {@value #DATABASE_PASSWORD_KEY}.
     */
    private static long countSchemas() throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(databaseUrl, databaseUser, databasePassword);
             PreparedStatement statement = connection.prepareStatement(SCHEMA_COUNT_QUERY))
        {
            statement.setString(1, DATABASE_NAME);
            try (ResultSet result = statement.executeQuery())
            {
                if (!result.next())
                {
                    throw new SQLException("\"" + SCHEMA_COUNT_QUERY + "\" returned no row");
                }
                return result.getLong(1);
            }
        }
    }

    /**
     * Result of {@value #SELECT_ORDERS_QUERY} over a new connection to
     * {@link MySQLDbCreator#getDatabaseUrlWithName()}, the URL the Mule
     * contexts bind as {@value #JDBC_URL_PROPERTY}.
     */
    private static long countOrders() throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(dbCreator.getDatabaseUrlWithName());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(SELECT_ORDERS_QUERY))
        {
            if (!result.next())
            {
                throw new SQLException("\"" + SELECT_ORDERS_QUERY + "\" returned no row");
            }
            return result.getLong(1);
        }
    }

    /** Setup failure of {@code step}; see {@link #failure(String, String, String, Throwable)}. */
    private static AssertionError setupFailure(String step, String detail, Throwable cause)
    {
        return failure(SETUP_CONTEXT, step, detail, cause);
    }

    /** Teardown failure of {@code step}; see {@link #failure(String, String, String, Throwable)}. */
    private static AssertionError teardownFailure(String step, String detail, Throwable cause)
    {
        return failure(TEARDOWN_CONTEXT, step, detail, cause);
    }

    /**
     * Failure whose message is {@code context}, the step, {@code detail}, the
     * properties file with its absolute path, the required keys and the schema,
     * passed through {@link #redact(String)}, and whose cause is the
     * {@link #redactedCopy(Throwable)} of {@code cause}, or none when
     * {@code cause} is {@code null}.
     */
    private static AssertionError failure(String context, String step, String detail, Throwable cause)
    {
        String message = redact(context + "step \"" + step + "\": " + detail
                                + " [properties file " + PATH_TO_TEST_PROPERTIES
                                + " (" + new File(PATH_TO_TEST_PROPERTIES).getAbsolutePath() + ")"
                                + ", required keys " + join(Arrays.asList(REQUIRED_KEYS))
                                + ", schema " + DATABASE_NAME + "]");
        return cause == null ? new AssertionError(message) : new AssertionError(message, redactedCopy(cause));
    }

    /** {@code parts} separated by {@code ", "}. */
    private static String join(List<String> parts)
    {
        StringBuilder joined = new StringBuilder();
        for (String part : parts)
        {
            joined.append(joined.length() == 0 ? "" : ", ").append(part);
        }
        return joined.toString();
    }

    /**
     * {@code text} with every occurrence of the {@value #DATABASE_PASSWORD_KEY}
     * value, when it is set and not empty, and the value of every
     * {@code password=} parameter, in any letter case, replaced by
     * {@value #REDACTED}; {@code null} for {@code null} (D-334).
     */
    private static String redact(String text)
    {
        if (text == null)
        {
            return null;
        }
        String redacted = text;
        String password = databasePassword;
        if (password != null && !password.isEmpty())
        {
            redacted = redacted.replace(password, REDACTED);
        }
        return PASSWORD_PARAMETER.matcher(redacted).replaceAll("$1" + REDACTED);
    }

    /**
     * Copy of {@code cause}, its cause chain and its suppressed throwables as
     * {@link RedactedThrowable}s: each copy has the {@link #redact(String)}ed
     * message, the class name in its {@code toString()} and the stack trace of
     * the throwable it copies. A throwable met a second time is not copied
     * again, which ends that branch of the copy (D-334).
     *
     * @return the copy, or {@code null} for {@code null}
     */
    private static Throwable redactedCopy(Throwable cause)
    {
        return copyRedacted(cause, Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>()));
    }

    /** {@link #redactedCopy(Throwable)} of {@code original}, skipping the throwables in {@code visited}, to which it adds those it copies. */
    private static Throwable copyRedacted(Throwable original, Set<Throwable> visited)
    {
        if (original == null || !visited.add(original))
        {
            return null;
        }
        RedactedThrowable copy = new RedactedThrowable(original.getClass().getName(),
                                                       redact(original.getLocalizedMessage()));
        copy.setStackTrace(original.getStackTrace());
        Throwable cause = copyRedacted(original.getCause(), visited);
        if (cause != null)
        {
            copy.initCause(cause);
        }
        for (Throwable suppressed : original.getSuppressed())
        {
            Throwable suppressedCopy = copyRedacted(suppressed, visited);
            if (suppressedCopy != null)
            {
                copy.addSuppressed(suppressedCopy);
            }
        }
        return copy;
    }

    /**
     * Registers {@link #APPENDER} on the root logger configuration of every
     * distinct log4j-core context: the caller's context and the context of the
     * Mule execution class loader. Registration replaces any earlier
     * registration of the same name. Runs in the started Mule context of the
     * test, before the order is sent.
     *
     * @param identity scenario identity that starts the failure message
     */
    private void registerAppender(String identity)
    {
        registeredContexts.clear();
        Object primary = LogManager.getContext(false);
        if (!(primary instanceof LoggerContext))
        {
            fail(identity + ": appender " + APPENDER_NAME + " cannot be registered: the log4j context "
                 + describe(primary) + " is not a log4j-core LoggerContext");
        }
        registeredContexts.add((LoggerContext) primary);
        Object execution = LogManager.getContext(muleContext.getExecutionClassLoader(), false);
        if (execution instanceof LoggerContext && execution != primary)
        {
            registeredContexts.add((LoggerContext) execution);
        }
        for (LoggerContext context : registeredContexts)
        {
            LoggerConfig root = rootLoggerConfig(context);
            root.removeAppender(APPENDER_NAME);
            root.addAppender(APPENDER, null, null);
            context.updateLoggers();
        }
    }

    /** Removes {@link #APPENDER} from the root logger configurations it was registered on. */
    @Override
    protected void doTearDown() throws Exception
    {
        try
        {
            for (LoggerContext context : registeredContexts)
            {
                rootLoggerConfig(context).removeAppender(APPENDER_NAME);
                context.updateLoggers();
            }
            registeredContexts.clear();
        }
        finally
        {
            super.doTearDown();
        }
    }

    /** Scenario {@code order-rolled-back}: the insert is rolled back and nothing reaches {@code out}. */
    @Test
    public void captureOrderRolledBack() throws Exception
    {
        capture("order-rolled-back");
    }

    /** Scenario {@code redelivery-limit}: the connector stops after {@code maxRedelivery="2"} (D-024, D-025). */
    @Test
    public void captureRedeliveryLimit() throws Exception
    {
        capture("redelivery-limit");
    }

    /**
     * Runs the original flow once and writes the fixture of {@code scenario}.
     *
     * @param scenario scenario name; the fixture identity is {@code EXAMPLE + "_" + scenario}
     * @throws Exception an {@link AssertionError} whose message starts with the
     *         fixture identity and carries no password when a step fails; the
     *         cause of a failed Mule, database or file step is the
     *         {@link #redactedCopy(Throwable)} of the exception it raised (D-334)
     */
    private void capture(String scenario) throws Exception
    {
        String identity = EXAMPLE + "_" + scenario;
        String captureOut = System.getProperty(CAPTURE_OUT_PROPERTY);
        if (captureOut == null || captureOut.trim().isEmpty())
        {
            fail(identity + ": system property capture.out is not set");
        }
        File outputDirectory = new File(captureOut);
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory())
        {
            fail(identity + ": capture.out directory cannot be created: " + outputDirectory.getAbsolutePath());
        }

        registerAppender(identity);
        APPENDER.clear();
        MuleClient client;
        try
        {
            client = new MuleClient(muleContext);
            client.send(INBOUND_ENDPOINT, MESSAGE, null);
        }
        catch (MuleException e)
        {
            throw captureFailure(identity, "send to " + INBOUND_ENDPOINT, e);
        }

        awaitRedeliveryLimit(identity);

        MuleMessage out;
        try
        {
            out = client.request(OUTBOUND_ENDPOINT, OUT_REQUEST_TIMEOUT_MILLIS);
        }
        catch (MuleException e)
        {
            throw captureFailure(identity, "request on " + OUTBOUND_ENDPOINT, e);
        }

        String records;
        try
        {
            SubflowInterceptingChainLifecycleWrapper subflow = getSubFlow("selectOrders");
            subflow.initialise();
            MuleEvent response = subflow.process(getTestEvent(null, MessageExchangePattern.REQUEST_RESPONSE));
            if (response == null || response.getMessage() == null)
            {
                fail(identity + ": sub-flow selectOrders returned no message");
            }
            records = response.getMessage().getPayloadAsString();
        }
        catch (Exception e)
        {
            throw captureFailure(identity, "sub-flow selectOrders", e);
        }

        List<Entry> entries = APPENDER.snapshot();
        if (!containsLoggerInfo(entries))
        {
            fail(identity + ": appender " + APPENDER_NAME + " received no " + LOGGER_CATEGORY + " INFO entry; "
                 + entries.size() + " entries collected");
        }

        byte[] outBytes;
        try
        {
            outBytes = out == null ? null : out.getPayloadAsBytes();
        }
        catch (Exception e)
        {
            throw captureFailure(identity, "payload of the message from " + OUTBOUND_ENDPOINT, e);
        }
        Map<String, Object> fixture = buildFixture(identity, records, entries, outBytes);
        Path target = new File(captureOut, identity + ".json").toPath();
        try
        {
            Files.write(target, JsonWriter.write(fixture).getBytes(StandardCharsets.UTF_8));
        }
        catch (IOException e)
        {
            throw captureFailure(identity, "write of the fixture " + target.toAbsolutePath(), e);
        }
    }

    /**
     * Failure of capture step {@code step} of scenario {@code identity}: the
     * {@link #failure(String, String, String, Throwable)} with the context
     * {@code <identity>: }, the detail {@code cause.toString()} and the
     * {@link #redactedCopy(Throwable)} of {@code cause} as its cause (D-334).
     */
    private static AssertionError captureFailure(String identity, String step, Throwable cause)
    {
        return failure(identity + ": ", step, String.valueOf(cause), cause);
    }

    /**
     * Polls the collected entries every 100 ms for up to 60 000 ms until an
     * ERROR entry contains {@value #REDELIVERY_MARKER}, and on timeout fails
     * with the {@link #redact(String)}ed {@link #timeoutMessage(String, List)}
     * of {@code identity}.
     */
    private static void awaitRedeliveryLimit(String identity) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + REDELIVERY_WAIT_MILLIS;
        while (!containsRedeliveryLimit(APPENDER.snapshot()))
        {
            if (System.currentTimeMillis() >= deadline)
            {
                fail(redact(timeoutMessage(identity, APPENDER.snapshot())));
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
    }

    /** True when an ERROR entry contains {@value #REDELIVERY_MARKER}. */
    private static boolean containsRedeliveryLimit(List<Entry> entries)
    {
        for (Entry entry : entries)
        {
            if (entry.level == Level.ERROR && entry.text.contains(REDELIVERY_MARKER))
            {
                return true;
            }
        }
        return false;
    }

    /** True when an INFO entry of {@value #LOGGER_CATEGORY} is present. */
    private static boolean containsLoggerInfo(List<Entry> entries)
    {
        for (Entry entry : entries)
        {
            if (entry.level == Level.INFO && LOGGER_CATEGORY.equals(entry.loggerName))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * Failure text of a timed-out wait of scenario {@code identity}: the
     * identity, the entry count, a statement when the appender collected
     * nothing, and the logger name and first line of each of the last five
     * entries.
     */
    private static String timeoutMessage(String identity, List<Entry> entries)
    {
        StringBuilder message = new StringBuilder();
        message.append(identity).append(": no ERROR entry containing \"").append(REDELIVERY_MARKER)
               .append("\" within ").append(REDELIVERY_WAIT_MILLIS).append(" ms; ").append(entries.size())
               .append(" entries collected");
        if (entries.isEmpty())
        {
            message.append("; appender ").append(APPENDER_NAME).append(" collected nothing");
            return message.toString();
        }
        message.append("; last entries:");
        int from = Math.max(0, entries.size() - TRAILING_ENTRIES_REPORTED);
        for (Entry entry : entries.subList(from, entries.size()))
        {
            message.append("\n  ").append(entry.loggerName).append(": ").append(firstLine(entry.text));
        }
        return message.toString();
    }


    /**
     * Builds the fixture object with the keys {@code scenario}, {@code setup},
     * {@code replay}, {@code trigger}, {@code outputs} and {@code volatile}, in
     * this order.
     *
     * @param identity fixture identity
     * @param records  payload of the {@code selectOrders} sub-flow as a string
     * @param entries  log entries collected during the scenario, in collection order
     * @param outBytes payload of the message received on {@code jms://out}, or {@code null} when none arrived
     */
    private static Map<String, Object> buildFixture(String identity, String records, List<Entry> entries, byte[] outBytes)
    {
        List<Object> setup = new ArrayList<Object>();
        setup.add("seed: " + EXAMPLE + "/" + PATH_TO_SQL_SCRIPT);
        setup.add("properties: database.user, database.password, database.url (src/test/resources/mule.test.properties)");

        Map<String, Object> trigger = object(
                "jms", object("destination", "in", "bodyBase64", base64(MESSAGE_BYTES)));

        Entry redeliveryEntry = firstRedeliverySentenceEntry(entries);
        List<Object> redeliveryMessages = new ArrayList<Object>();
        if (redeliveryEntry != null)
        {
            redeliveryMessages.add(redeliverySentence(redeliveryEntry.text));
        }

        List<Object> outputs = new ArrayList<Object>();
        outputs.add(object("records", object(
                "backend", "mysql",
                "query", SELECT_ORDERS_QUERY,
                "resultBase64", base64(records.getBytes(StandardCharsets.UTF_8)))));
        outputs.add(object("log", object(
                "category", LOGGER_CATEGORY,
                "messages", loggerMessages(entries))));
        outputs.add(object("log", object(
                "category", EXCEPTION_CATEGORY,
                "messages", rootCauseMessages(entries, redeliveryEntry))));
        outputs.add(object("log", object(
                "category", REDELIVERY_CATEGORY,
                "messages", redeliveryMessages)));
        if (outBytes != null)
        {
            outputs.add(object("message", object(
                    "destination", "out",
                    "bodyBase64", base64(outBytes))));
        }

        // Masks the JMS message id of the redelivery sentence in every log comparison (D-184).
        List<Object> volatileEntries = new ArrayList<Object>();
        volatileEntries.add(object("where", "log", "regex", "ID:[^\"]+"));

        return object(
                "scenario", identity,
                "setup", setup,
                "replay", "embedded",
                "trigger", trigger,
                "outputs", outputs,
                "volatile", volatileEntries);
    }

    /** Text of every {@value #LOGGER_CATEGORY} entry, in collection order. */
    private static List<Object> loggerMessages(List<Entry> entries)
    {
        List<Object> messages = new ArrayList<Object>();
        for (Entry entry : entries)
        {
            if (LOGGER_CATEGORY.equals(entry.loggerName))
            {
                messages.add(entry.text);
            }
        }
        return messages;
    }

    /**
     * Root-cause message of every ERROR entry other than {@code excluded}, in
     * collection order. Entries yielding no message, or an empty one, are left out.
     */
    private static List<Object> rootCauseMessages(List<Entry> entries, Entry excluded)
    {
        List<Object> messages = new ArrayList<Object>();
        for (Entry entry : entries)
        {
            if (entry.level != Level.ERROR || entry == excluded)
            {
                continue;
            }
            String message = rootCauseMessage(entry);
            if (message != null && !message.isEmpty())
            {
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * Root-cause message of one entry. With a throwable attached, the message of
     * the last throwable of its cause chain; otherwise the text after the first
     * {@code ": "} of the line following {@value #ROOT_EXCEPTION_MARKER}, trimmed.
     *
     * @return the message, or {@code null} when the entry yields none
     */
    private static String rootCauseMessage(Entry entry)
    {
        if (entry.thrown != null)
        {
            return rootCause(entry.thrown).getMessage();
        }
        return rootCauseFromText(entry.text);
    }

    /** Last throwable of the cause chain of {@code thrown}; a cause already visited ends the chain. */
    private static Throwable rootCause(Throwable thrown)
    {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        Throwable current = thrown;
        visited.add(current);
        Throwable cause = current.getCause();
        while (cause != null && cause != current && visited.add(cause))
        {
            current = cause;
            cause = current.getCause();
        }
        return current;
    }

    /**
     * Message part of the line after the {@value #ROOT_EXCEPTION_MARKER} line of a
     * Mule detailed exception message, for example {@code exception customized}
     * from {@code org.exceptions.MyException: exception customized}.
     *
     * @return the message, or {@code null} when the marker, the following line or its separator is absent
     */
    private static String rootCauseFromText(String text)
    {
        String[] lines = text.split("\r\n|\r|\n", -1);
        for (int i = 0; i < lines.length - 1; i++)
        {
            if (lines[i].contains(ROOT_EXCEPTION_MARKER))
            {
                String line = lines[i + 1].trim();
                int separator = line.indexOf(CLASS_MESSAGE_SEPARATOR);
                return separator < 0 ? null : line.substring(separator + CLASS_MESSAGE_SEPARATOR.length());
            }
        }
        return null;
    }

    /** First ERROR entry whose text holds the redelivery-limit sentence, or {@code null}. */
    private static Entry firstRedeliverySentenceEntry(List<Entry> entries)
    {
        for (Entry entry : entries)
        {
            if (entry.level == Level.ERROR && REDELIVERY_SENTENCE.matcher(entry.text).find())
            {
                return entry;
            }
        }
        return null;
    }

    /** The first redelivery-limit sentence within {@code text}. */
    private static String redeliverySentence(String text)
    {
        Matcher matcher = REDELIVERY_SENTENCE.matcher(text);
        if (!matcher.find())
        {
            throw new IllegalArgumentException("no redelivery-limit sentence in: " + firstLine(text));
        }
        return matcher.group();
    }

    /** Text up to the first line break. */
    private static String firstLine(String text)
    {
        int end = text.length();
        int newline = text.indexOf('\n');
        if (newline >= 0)
        {
            end = newline;
        }
        int carriageReturn = text.indexOf('\r');
        if (carriageReturn >= 0 && carriageReturn < end)
        {
            end = carriageReturn;
        }
        return text.substring(0, end);
    }

    /** Standard base64 encoding of {@code bytes}. */
    private static String base64(byte[] bytes)
    {
        return Base64.getEncoder().encodeToString(bytes);
    }

    /**
     * Ordered JSON object of alternating keys and values.
     *
     * @param keysAndValues key, value, key, value, ...; keys are strings
     */
    private static Map<String, Object> object(Object... keysAndValues)
    {
        if (keysAndValues.length % 2 != 0)
        {
            throw new IllegalArgumentException("keys and values must come in pairs");
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2)
        {
            if (!(keysAndValues[i] instanceof String))
            {
                throw new IllegalArgumentException("JSON object key at position " + i + " is not a string");
            }
            result.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return result;
    }

    /** Root logger configuration of the current configuration of {@code context}. */
    private static LoggerConfig rootLoggerConfig(LoggerContext context)
    {
        Configuration configuration = context.getConfiguration();
        return configuration.getRootLogger();
    }

    /** Class name of a log4j context, or {@code null}. */
    private static String describe(Object context)
    {
        return context == null ? "null" : context.getClass().getName();
    }


    /**
     * Copy of another throwable made by {@link #redactedCopy(Throwable)}:
     * {@link #toString()} is {@code <class name of the original>: <message>}, or
     * the class name alone when the message is {@code null}.
     */
    private static final class RedactedThrowable extends Exception
    {
        private static final long serialVersionUID = 1L;

        private final String originalClassName;

        private RedactedThrowable(String originalClassName, String message)
        {
            super(message);
            this.originalClassName = originalClassName;
        }

        @Override
        public String toString()
        {
            String message = getLocalizedMessage();
            return message == null ? originalClassName : originalClassName + ": " + message;
        }
    }

    /** One collected log event: level, logger name, formatted message ({@code %m}) and attached throwable. */
    private static final class Entry
    {
        private final Level level;
        private final String loggerName;
        private final String text;
        private final Throwable thrown;

        private Entry(Level level, String loggerName, String text, Throwable thrown)
        {
            this.level = level;
            this.loggerName = loggerName == null ? "" : loggerName;
            this.text = text == null ? "" : text;
            this.thrown = thrown;
        }
    }

    /**
     * Collects every log event of the root logger configurations it is
     * registered on, at every level, in arrival order.
     */
    private static final class CaptureAppender extends AbstractAppender
    {
        private static final long serialVersionUID = 1L;

        private final transient List<Entry> entries = new CopyOnWriteArrayList<Entry>();

        private CaptureAppender()
        {
            super(APPENDER_NAME, null, null, true);
        }

        @Override
        public void append(LogEvent event)
        {
            String text = event.getMessage() == null ? null : event.getMessage().getFormattedMessage();
            entries.add(new Entry(event.getLevel(), event.getLoggerName(), text, event.getThrown()));
        }

        /** Discards the collected entries. */
        private void clear()
        {
            entries.clear();
        }

        /** Copy of the collected entries, in arrival order. */
        private List<Entry> snapshot()
        {
            return new ArrayList<Entry>(entries);
        }
    }

    /**
     * Serialises {@link Map} (string keys, iteration order kept), {@link List},
     * {@link String} and {@code null} values as JSON, indented by two spaces per
     * level and terminated by a line feed.
     * <p>
     * String escaping: {@code "} and {@code \} are prefixed with a backslash;
     * line feed, carriage return and tab become {@code \n}, {@code \r} and
     * {@code \t}; every other character below U+0020 becomes {@code \}{@code uXXXX};
     * every other character is written unchanged.
     */
    private static final class JsonWriter
    {
        private static final String INDENT = "  ";

        private JsonWriter()
        {
        }

        /** JSON text of {@code value}, ending with a line feed. */
        private static String write(Object value)
        {
            StringBuilder json = new StringBuilder();
            writeValue(json, value, 0);
            json.append('\n');
            return json.toString();
        }

        private static void writeValue(StringBuilder json, Object value, int depth)
        {
            if (value == null)
            {
                json.append("null");
            }
            else if (value instanceof String)
            {
                writeQuoted(json, (String) value);
            }
            else if (value instanceof Map)
            {
                writeObject(json, (Map<?, ?>) value, depth);
            }
            else if (value instanceof List)
            {
                writeArray(json, (List<?>) value, depth);
            }
            else
            {
                throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
            }
        }

        private static void writeObject(StringBuilder json, Map<?, ?> object, int depth)
        {
            if (object.isEmpty())
            {
                json.append("{}");
                return;
            }
            json.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> member : object.entrySet())
            {
                if (!(member.getKey() instanceof String))
                {
                    throw new IllegalArgumentException("JSON object key is not a string: " + member.getKey());
                }
                json.append(first ? "\n" : ",\n");
                first = false;
                indent(json, depth + 1);
                writeQuoted(json, (String) member.getKey());
                json.append(": ");
                writeValue(json, member.getValue(), depth + 1);
            }
            json.append('\n');
            indent(json, depth);
            json.append('}');
        }

        private static void writeArray(StringBuilder json, List<?> array, int depth)
        {
            if (array.isEmpty())
            {
                json.append("[]");
                return;
            }
            json.append('[');
            boolean first = true;
            for (Object element : array)
            {
                json.append(first ? "\n" : ",\n");
                first = false;
                indent(json, depth + 1);
                writeValue(json, element, depth + 1);
            }
            json.append('\n');
            indent(json, depth);
            json.append(']');
        }

        private static void writeQuoted(StringBuilder json, String value)
        {
            json.append('"');
            for (int i = 0; i < value.length(); i++)
            {
                char c = value.charAt(i);
                switch (c)
                {
                    case '"':
                        json.append("\\\"");
                        break;
                    case '\\':
                        json.append("\\\\");
                        break;
                    case '\n':
                        json.append("\\n");
                        break;
                    case '\r':
                        json.append("\\r");
                        break;
                    case '\t':
                        json.append("\\t");
                        break;
                    default:
                        if (c < 0x20)
                        {
                            json.append(String.format("\\u%04x", (int) c));
                        }
                        else
                        {
                            json.append(c);
                        }
                        break;
                }
            }
            json.append('"');
        }

        private static void indent(StringBuilder json, int depth)
        {
            for (int i = 0; i < depth; i++)
            {
                json.append(INDENT);
            }
        }
    }
}
