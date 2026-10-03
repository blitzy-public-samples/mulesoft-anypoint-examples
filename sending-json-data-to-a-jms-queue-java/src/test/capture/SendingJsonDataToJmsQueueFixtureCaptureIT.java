package org.mule.examples;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.mule.api.MuleMessage;
import org.mule.tck.junit4.FunctionalTestCase;
import org.mule.tck.junit4.rule.DynamicPort;

/**
 * Tier 2A capture of scenario sending-json-data-to-a-jms-queue_post-sales (D-081): posts message.json to
 * POST /sales, reads the in-VM queue sales (D-080), collects the flow logger's messages and writes the fixture
 * to the directory named by capture.out. Compiled and run only inside an isolated copy of the original on the
 * Java 8 toolchain (D-070).
 * <p>
 * The fixture is one UTF-8 JSON object, two-space indented, with the keys {@code scenario}, {@code setup},
 * {@code replay}, {@code request}, {@code response}, {@code outputs} and {@code volatile}, in that order. Request,
 * response and message bytes are stored as base64 exactly as sent or received. The {@code volatile} list is
 * always written empty; masks are added to a captured fixture by hand, each with its own DECISIONS.md row.
 * <p>
 * Run from the root of the isolated copy, with this file under {@code src/test/java/org/mule/examples/}:
 * <pre>
 * env -u MULE_HOME mvn -B -s "$EE_SETTINGS" -DMULE_HOME="$MULE_HOME" \
 *     -Dit.test=SendingJsonDataToJmsQueueFixtureCaptureIT -Dcapture.out="$FX" verify
 * </pre>
 * The run leaves exactly one file, {@code $FX/sending-json-data-to-a-jms-queue_post-sales.json}.
 */
public class SendingJsonDataToJmsQueueFixtureCaptureIT extends FunctionalTestCase
{
    /** Scenario identity: the fixture file name without {@code .json} and its {@code scenario} value. */
    private static final String SCENARIO = "sending-json-data-to-a-jms-queue_post-sales";

    /** Default category of the flow's {@code <logger level="INFO"/>} (json-to-jms.xml:10). */
    private static final String CATEGORY = "org.mule.api.processor.LoggerMessageProcessor";

    /** Address of the in-VM queue the flow's one-way outbound endpoint sends to (json-to-jms.xml:9). */
    private static final String JMS_ADDRESS = "jms://sales";

    /** Milliseconds {@code MuleClient.request} waits for the queued message. */
    private static final long JMS_TIMEOUT = 5000L;

    /** Request path of the flow's HTTP listener (json-to-jms.xml:6). */
    private static final String PATH = "/sales";

    /** Queue name recorded as the {@code destination} of the {@code message} output. */
    private static final String DESTINATION = "sales";

    /** System property naming the directory the fixture is written to. */
    private static final String CAPTURE_OUT_PROPERTY = "capture.out";

    /** Request body file, relative to the basedir of the isolated copy. */
    private static final String BODY_FILE = "src/test/resources/message.json";

    /** Name under which the log collector is registered in the Log4j2 configuration. */
    private static final String COLLECTOR_NAME = "SendingJsonDataToJmsQueueFixtureCaptureLogCollector";

    /** Connect and read timeout of the HTTP request, in milliseconds. */
    private static final int HTTP_TIMEOUT_MILLIS = 30000;

    /** Request header values, set in this order: User-Agent, Accept, Content-Type. */
    private static final String USER_AGENT = "fixture-capture";
    private static final String ACCEPT = "*/*";
    private static final String CONTENT_TYPE = "application/json";

    /** Connection header value the JDK HTTP client adds to a request by default. */
    private static final String CONNECTION = "keep-alive";

    /** Line terminator of the recorded {@code rawHeaders}. */
    private static final String CRLF = "\r\n";

    /** One level of indentation in the fixture JSON. */
    private static final String INDENT = "  ";

    /** HTTP listener port, published as the {@code http.port} system property (json-to-jms.xml:4). */
    @Rule
    public DynamicPort port = new DynamicPort("http.port");

    @Override
    protected String getConfigResources()
    {
        return "json-to-jms.xml";
    }

    /**
     * Captures scenario {@code post-sales}: one {@code POST /sales} with the exact bytes of message.json, the
     * status line, headers and body of the reply, the message left on queue {@code sales} (absent when the
     * queue holds none after {@link #JMS_TIMEOUT} ms), and every message logged under {@link #CATEGORY}.
     *
     * @throws Exception when the request, the queue read or the fixture write fails
     */
    @Test
    public void capturePostSales() throws Exception
    {
        String out = System.getProperty(CAPTURE_OUT_PROPERTY);
        if (out == null || out.isEmpty())
        {
            Assert.fail("System property capture.out is required: the directory the fixture is written to");
        }

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LogCollector collector = new LogCollector(COLLECTOR_NAME);
        collector.start();
        ctx.getConfiguration().addAppender(collector);
        ctx.getConfiguration().getRootLogger().addAppender(collector, null, null);
        ctx.updateLoggers();
        try
        {
            byte[] body = Files.readAllBytes(new File(BODY_FILE).toPath());

            int portNumber = port.getNumber();
            HttpURLConnection conn = (HttpURLConnection) new URL("http://localhost:" + portNumber + PATH).openConnection();
            byte[] responseBody;
            String statusLine;
            StringBuilder rawHeaders = new StringBuilder();
            try
            {
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setInstanceFollowRedirects(false);
                conn.setFixedLengthStreamingMode(body.length);
                conn.setConnectTimeout(HTTP_TIMEOUT_MILLIS);
                conn.setReadTimeout(HTTP_TIMEOUT_MILLIS);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", ACCEPT);
                conn.setRequestProperty("Content-Type", CONTENT_TYPE);

                OutputStream requestStream = conn.getOutputStream();
                try
                {
                    requestStream.write(body);
                }
                finally
                {
                    requestStream.close();
                }
                int status = conn.getResponseCode();

                statusLine = conn.getHeaderField(0);
                Assert.assertNotNull("POST " + PATH + " returned no HTTP status line", statusLine);
                rawHeaders.append(statusLine).append(CRLF);
                for (int i = 1; conn.getHeaderFieldKey(i) != null; i++)
                {
                    rawHeaders.append(conn.getHeaderFieldKey(i)).append(": ").append(conn.getHeaderField(i)).append(CRLF);
                }
                rawHeaders.append(CRLF);

                InputStream responseStream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                responseBody = responseStream == null ? new byte[0] : readAll(responseStream);
            }
            finally
            {
                conn.disconnect();
            }

            MuleMessage msg = muleContext.getClient().request(JMS_ADDRESS, JMS_TIMEOUT);
            byte[] messageBody = null;
            if (msg != null)
            {
                Object payload = msg.getPayload();
                messageBody = payload instanceof byte[]
                        ? (byte[]) payload
                        : msg.getPayloadAsString().getBytes(StandardCharsets.UTF_8);
            }

            List<String> logMessages = collector.snapshot();
            if (logMessages.isEmpty())
            {
                Assert.fail("No log event of category " + CATEGORY + " reached the collector attached to LoggerContext "
                        + ctx.getName());
            }

            File outDir = new File(out);
            outDir.mkdirs();
            if (!outDir.isDirectory())
            {
                Assert.fail("capture.out is not a directory and cannot be created: " + outDir.getAbsolutePath());
            }
            File fixture = new File(outDir, SCENARIO + ".json");
            String json = toJson(buildFixture(portNumber, body, statusLine, rawHeaders.toString(), responseBody,
                    messageBody, logMessages));
            Files.write(fixture.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Assert.assertTrue("Fixture file was not written: " + fixture.getAbsolutePath(), fixture.isFile());
        }
        finally
        {
            ctx.getConfiguration().getRootLogger().removeAppender(COLLECTOR_NAME);
            collector.stop();
            ctx.updateLoggers();
        }
    }

    /**
     * Builds the fixture as ordered maps and lists of strings.
     *
     * @param portNumber   port the request was sent to
     * @param body         request body bytes
     * @param statusLine   received status line
     * @param rawHeaders   status line and header lines as received, CRLF-terminated, ending with an empty line
     * @param responseBody response body bytes
     * @param messageBody  bytes of the message taken from queue {@code sales}, or {@code null} when none was queued
     * @param logMessages  messages logged under {@link #CATEGORY}, in logging order
     * @return the fixture object, keys in fixture order
     */
    private static Map<String, Object> buildFixture(int portNumber, byte[] body, String statusLine, String rawHeaders,
            byte[] responseBody, byte[] messageBody, List<String> logMessages)
    {
        Base64.Encoder base64 = Base64.getEncoder();

        Map<String, Object> headers = new LinkedHashMap<String, Object>();
        headers.put("Host", "localhost:" + portNumber);
        headers.put("User-Agent", USER_AGENT);
        headers.put("Accept", ACCEPT);
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("Connection", CONNECTION);

        Map<String, Object> request = new LinkedHashMap<String, Object>();
        request.put("method", "POST");
        request.put("path", PATH);
        request.put("query", "");
        request.put("headers", headers);
        request.put("bodyBase64", base64.encodeToString(body));

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("statusLine", statusLine);
        response.put("rawHeaders", rawHeaders);
        response.put("bodyBase64", base64.encodeToString(responseBody));

        List<Object> outputs = new ArrayList<Object>();
        if (messageBody != null)
        {
            Map<String, Object> message = new LinkedHashMap<String, Object>();
            message.put("kind", "message");
            message.put("destination", DESTINATION);
            message.put("bodyBase64", base64.encodeToString(messageBody));
            outputs.add(message);
        }
        Map<String, Object> log = new LinkedHashMap<String, Object>();
        log.put("kind", "log");
        log.put("category", CATEGORY);
        log.put("messages", new ArrayList<Object>(logMessages));
        outputs.add(log);

        Map<String, Object> fixture = new LinkedHashMap<String, Object>();
        fixture.put("scenario", SCENARIO);
        fixture.put("setup", Collections.<Object>singletonList("http.port"));
        fixture.put("replay", "embedded");
        fixture.put("request", request);
        fixture.put("response", response);
        fixture.put("outputs", outputs);
        fixture.put("volatile", Collections.<Object>emptyList());
        return fixture;
    }

    /**
     * Serialises a value tree of {@link String}, {@link Map} and {@link List} nodes as two-space indented JSON,
     * terminated by a line feed.
     *
     * @param value root of the tree
     * @return the JSON text
     */
    private static String toJson(Object value)
    {
        StringBuilder json = new StringBuilder();
        writeJson(json, value, 0);
        return json.append('\n').toString();
    }

    /**
     * Appends one JSON value. Strings are quoted by {@link #quote(String)}, maps become objects with their keys in
     * iteration order, lists become arrays, {@code null} becomes {@code null}; an empty map or list is written as
     * {@code {}} or {@code []}. Entries are separated by commas, with none after the last.
     *
     * @param json  target buffer
     * @param value value to append
     * @param depth indentation level of the line the value starts on
     * @throws IllegalArgumentException for any other value type
     */
    private static void writeJson(StringBuilder json, Object value, int depth)
    {
        if (value == null)
        {
            json.append("null");
        }
        else if (value instanceof String)
        {
            json.append(quote((String) value));
        }
        else if (value instanceof Map)
        {
            Map<?, ?> map = (Map<?, ?>) value;
            if (map.isEmpty())
            {
                json.append("{}");
                return;
            }
            json.append("{\n");
            int remaining = map.size();
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                indent(json, depth + 1);
                json.append(quote(String.valueOf(entry.getKey()))).append(": ");
                writeJson(json, entry.getValue(), depth + 1);
                remaining--;
                json.append(remaining > 0 ? ",\n" : "\n");
            }
            indent(json, depth);
            json.append('}');
        }
        else if (value instanceof List)
        {
            List<?> list = (List<?>) value;
            if (list.isEmpty())
            {
                json.append("[]");
                return;
            }
            json.append("[\n");
            int remaining = list.size();
            for (Object element : list)
            {
                indent(json, depth + 1);
                writeJson(json, element, depth + 1);
                remaining--;
                json.append(remaining > 0 ? ",\n" : "\n");
            }
            indent(json, depth);
            json.append(']');
        }
        else
        {
            throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
        }
    }

    /**
     * Appends {@code depth} indentation units.
     *
     * @param json  target buffer
     * @param depth number of units
     */
    private static void indent(StringBuilder json, int depth)
    {
        for (int i = 0; i < depth; i++)
        {
            json.append(INDENT);
        }
    }

    /**
     * Quotes a string as a JSON string literal: {@code "} becomes {@code \"}, {@code \} becomes {@code \\}, and
     * every char below 0x20 becomes {@code \}{@code u} followed by four lowercase hex digits. Every other char is
     * emitted unchanged.
     *
     * @param text text to quote
     * @return the literal, including its enclosing double quotes
     */
    private static String quote(String text)
    {
        StringBuilder literal = new StringBuilder(text.length() + 2);
        literal.append('"');
        for (int i = 0; i < text.length(); i++)
        {
            char c = text.charAt(i);
            if (c == '"')
            {
                literal.append("\\\"");
            }
            else if (c == '\\')
            {
                literal.append("\\\\");
            }
            else if (c < 0x20)
            {
                literal.append(String.format("\\u%04x", (int) c));
            }
            else
            {
                literal.append(c);
            }
        }
        return literal.append('"').toString();
    }

    /**
     * Reads a stream to its end and closes it.
     *
     * @param in stream to read
     * @return every byte read, unchanged
     * @throws IOException when reading fails
     */
    private byte[] readAll(InputStream in) throws IOException
    {
        try
        {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1)
            {
                bytes.write(buffer, 0, count);
            }
            return bytes.toByteArray();
        }
        finally
        {
            in.close();
        }
    }

    /**
     * Log4j2 appender that keeps the formatted message of every event logged under {@link #CATEGORY}, in arrival
     * order. Events arrive on Mule's listener threads and are read on the test thread.
     */
    private static final class LogCollector extends AbstractAppender
    {
        private static final long serialVersionUID = 1L;

        private final CopyOnWriteArrayList<String> messages = new CopyOnWriteArrayList<String>();

        LogCollector(String name)
        {
            super(name, null, null, true);
        }

        @Override
        public void append(LogEvent e)
        {
            if (CATEGORY.equals(e.getLoggerName()))
            {
                messages.add(e.getMessage().getFormattedMessage());
            }
        }

        /**
         * @return a copy of the messages collected so far
         */
        List<String> snapshot()
        {
            return new ArrayList<String>(messages);
        }
    }
}
