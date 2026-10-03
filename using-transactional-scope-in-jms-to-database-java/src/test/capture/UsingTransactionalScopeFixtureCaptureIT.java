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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * completed: {@code capture.out} is unset, the log appender received nothing,
 * or a wait times out.
 * <p>
 * The Mule context, and with it the in-VM broker, is created for each test
 * method; the MySQL database created by {@link MySQLDbCreator} from the copy's
 * {@code src/test/resources/mule.test.properties} serves the whole class.
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

    /** Schema script the database is created from. */
    private static final String PATH_TO_SQL_SCRIPT = "src/main/resources/order.sql";

    /** Order sent to queue {@code in}. */
    private static final String PATH_TO_MESSAGE = "./src/test/resources/message.xml";

    /** Database created for this class and dropped after it. */
    private static final MySQLDbCreator DBCREATOR = new MySQLDbCreator("company" + System.currentTimeMillis(), PATH_TO_SQL_SCRIPT, PATH_TO_TEST_PROPERTIES);

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
     * Reads the order, creates the database from {@code order.sql} and exposes
     * its URL as the {@code jdbc.url} property the original configuration binds.
     * Sets {@value #VERBOSE_EXCEPTIONS_PROPERTY} to {@code true} for the Mule
     * contexts of this class, whose exception log entries then carry the
     * {@value #ROOT_EXCEPTION_MARKER} line (D-185).
     */
    @BeforeClass
    public static void prepareCapture() throws IOException
    {
        Path messagePath = Paths.get(PATH_TO_MESSAGE);
        MESSAGE_BYTES = Files.readAllBytes(messagePath);
        MESSAGE = new String(MESSAGE_BYTES, StandardCharsets.UTF_8);
        previousVerboseExceptions = System.getProperty(VERBOSE_EXCEPTIONS_PROPERTY);
        System.setProperty(VERBOSE_EXCEPTIONS_PROPERTY, "true");
        DBCREATOR.setUpDatabase();
        System.setProperty("jdbc.url", DBCREATOR.getDatabaseUrlWithName());
    }

    /** Drops the database created for this class and restores {@value #VERBOSE_EXCEPTIONS_PROPERTY}. */
    @AfterClass
    public static void tearDownCapture()
    {
        try
        {
            DBCREATOR.tearDownDataBase();
        }
        finally
        {
            if (previousVerboseExceptions == null)
            {
                System.clearProperty(VERBOSE_EXCEPTIONS_PROPERTY);
            }
            else
            {
                System.setProperty(VERBOSE_EXCEPTIONS_PROPERTY, previousVerboseExceptions);
            }
        }
    }

    /**
     * Registers {@link #APPENDER} on the root logger configuration of every
     * distinct log4j-core context: the caller's context and the context of the
     * Mule execution class loader. Registration replaces any earlier
     * registration of the same name.
     */
    @Override
    protected void doSetUp() throws Exception
    {
        super.doSetUp();
        registeredContexts.clear();
        Object primary = LogManager.getContext(false);
        if (!(primary instanceof LoggerContext))
        {
            fail("appender " + APPENDER_NAME + " cannot be registered: the log4j context "
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
     * @throws Exception when Mule, the database or the file system fails
     */
    private void capture(String scenario) throws Exception
    {
        String captureOut = System.getProperty(CAPTURE_OUT_PROPERTY);
        if (captureOut == null || captureOut.trim().isEmpty())
        {
            fail("system property capture.out is not set");
        }
        File outputDirectory = new File(captureOut);
        if (!outputDirectory.mkdirs() && !outputDirectory.isDirectory())
        {
            fail("capture.out directory cannot be created: " + outputDirectory.getAbsolutePath());
        }
        String identity = EXAMPLE + "_" + scenario;

        APPENDER.clear();
        MuleClient client = new MuleClient(muleContext);
        client.send(INBOUND_ENDPOINT, MESSAGE, null);

        awaitRedeliveryLimit();

        MuleMessage out = client.request(OUTBOUND_ENDPOINT, OUT_REQUEST_TIMEOUT_MILLIS);

        SubflowInterceptingChainLifecycleWrapper subflow = getSubFlow("selectOrders");
        subflow.initialise();
        MuleEvent response = subflow.process(getTestEvent(null, MessageExchangePattern.REQUEST_RESPONSE));
        if (response == null || response.getMessage() == null)
        {
            fail("sub-flow selectOrders returned no message");
        }
        String records = response.getMessage().getPayloadAsString();

        List<Entry> entries = APPENDER.snapshot();
        if (!containsLoggerInfo(entries))
        {
            fail("appender " + APPENDER_NAME + " received no " + LOGGER_CATEGORY + " INFO entry; "
                 + entries.size() + " entries collected");
        }

        byte[] outBytes = out == null ? null : out.getPayloadAsBytes();
        Map<String, Object> fixture = buildFixture(identity, records, entries, outBytes);
        Path target = new File(captureOut, identity + ".json").toPath();
        Files.write(target, JsonWriter.write(fixture).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Polls the collected entries every 100 ms for up to 60 000 ms until an
     * ERROR entry contains {@value #REDELIVERY_MARKER}, and fails with a
     * summary of the collected entries on timeout.
     */
    private static void awaitRedeliveryLimit() throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + REDELIVERY_WAIT_MILLIS;
        while (!containsRedeliveryLimit(APPENDER.snapshot()))
        {
            if (System.currentTimeMillis() >= deadline)
            {
                fail(timeoutMessage(APPENDER.snapshot()));
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
     * Failure text of a timed-out wait: the entry count, a statement when the
     * appender collected nothing, and the logger name and first line of each of
     * the last five entries.
     */
    private static String timeoutMessage(List<Entry> entries)
    {
        StringBuilder message = new StringBuilder();
        message.append("no ERROR entry containing \"").append(REDELIVERY_MARKER).append("\" within ")
               .append(REDELIVERY_WAIT_MILLIS).append(" ms; ").append(entries.size()).append(" entries collected");
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
