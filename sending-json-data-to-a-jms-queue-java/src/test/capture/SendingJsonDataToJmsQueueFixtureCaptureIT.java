package org.mule.examples;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.mule.api.MuleException;
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
 * response and message bytes are stored as base64 exactly as sent or received. The response {@code rawHeaders} are
 * the header block exactly as received, recorded by {@link HeaderRecordingRelay}, a loopback SOCKS5 relay the
 * {@link HttpURLConnection} connects through, and {@code statusLine} is its first line (D-335). The
 * {@code volatile} list is always written empty; masks are added to a captured fixture by hand, each with its own
 * DECISIONS.md row.
 * <p>
 * Run from the root of the isolated copy, with this file under {@code src/test/java/org/mule/examples/}:
 * <pre>
 * env -u MULE_HOME mvn -B -s "$EE_SETTINGS" -DMULE_HOME="$MULE_HOME" \
 *     -Dit.test=SendingJsonDataToJmsQueueFixtureCaptureIT -Dcapture.out="$FX" verify
 * </pre>
 * The run leaves exactly one file, {@code $FX/sending-json-data-to-a-jms-queue_post-sales.json}. A run in which
 * queue {@code sales} yields no message within 5000 ms fails and writes no file (D-336).
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

    /**
     * Connect and read timeout of the HTTP request and of every {@link HeaderRecordingRelay} socket, and the bound of
     * the wait for the recorded header block, in milliseconds.
     */
    private static final int HTTP_TIMEOUT_MILLIS = 30000;

    /** Request header values, set in this order: User-Agent, Accept, Content-Type. */
    private static final String USER_AGENT = "fixture-capture";
    private static final String ACCEPT = "*/*";
    private static final String CONTENT_TYPE = "application/json";

    /** Connection header value the JDK HTTP client adds to a request by default. */
    private static final String CONNECTION = "keep-alive";

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
     * header block of the reply exactly as received through {@link HeaderRecordingRelay} and its first line as the
     * status line, the reply body, the message left on queue {@code sales}, and every message logged under
     * {@link #CATEGORY}. The test fails before any fixture file is written when queue {@code sales} yields no message
     * within {@link #JMS_TIMEOUT} ms (D-336).
     *
     * @throws Exception an {@link AssertionError} whose message starts with {@link #SCENARIO} when a capture step
     *                   fails, with the exception that step raised as its cause
     */
    @Test
    public void capturePostSales() throws Exception
    {
        String out = System.getProperty(CAPTURE_OUT_PROPERTY);
        if (out == null || out.isEmpty())
        {
            Assert.fail(SCENARIO
                    + ": system property capture.out is required: the directory the fixture is written to");
        }

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        LogCollector collector = new LogCollector(COLLECTOR_NAME);
        collector.start();
        ctx.getConfiguration().addAppender(collector);
        ctx.getConfiguration().getRootLogger().addAppender(collector, null, null);
        ctx.updateLoggers();
        try
        {
            byte[] body;
            try
            {
                body = Files.readAllBytes(new File(BODY_FILE).toPath());
            }
            catch (IOException e)
            {
                throw failure("read of the request body " + BODY_FILE, e.toString(), e);
            }

            int portNumber = port.getNumber();
            byte[] responseBody;
            String statusLine;
            String rawHeaders;
            try (HeaderRecordingRelay relay = new HeaderRecordingRelay(portNumber, HTTP_TIMEOUT_MILLIS))
            {
                relay.start();
                HttpURLConnection conn = null;
                try
                {
                    conn = (HttpURLConnection) new URL("http://localhost:" + portNumber + PATH)
                            .openConnection(relay.proxy());
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

                    byte[] headerBlock = relay.awaitHeaderBlock();
                    if (relay.statusCode() != status)
                    {
                        Assert.fail(SCENARIO + ": HttpURLConnection reports status " + status
                                + " but the recorded header block ends with status " + relay.statusCode());
                    }
                    rawHeaders = new String(headerBlock, StandardCharsets.ISO_8859_1);
                    statusLine = firstLine(rawHeaders);

                    InputStream responseStream = status < 400 ? conn.getInputStream() : conn.getErrorStream();
                    responseBody = responseStream == null ? new byte[0] : readAll(responseStream);
                }
                catch (IOException e)
                {
                    String relayError = relay.error();
                    throw failure("POST " + PATH + " to port " + portNumber,
                            e + (relayError == null ? "" : "; relay: " + relayError), e);
                }
                finally
                {
                    if (conn != null)
                    {
                        conn.disconnect();
                    }
                }
            }
            catch (IOException e)
            {
                throw failure("header-recording relay for port " + portNumber, e.toString(), e);
            }

            MuleMessage msg;
            try
            {
                msg = muleContext.getClient().request(JMS_ADDRESS, JMS_TIMEOUT);
            }
            catch (MuleException e)
            {
                throw failure("request on " + JMS_ADDRESS, e.toString(), e);
            }
            if (msg == null)
            {
                Assert.fail(SCENARIO + ": no message on queue " + DESTINATION + " (" + JMS_ADDRESS + ") within "
                        + JMS_TIMEOUT + " ms; no fixture file is written");
            }
            byte[] messageBody;
            Object payload = msg.getPayload();
            if (payload instanceof byte[])
            {
                messageBody = (byte[]) payload;
            }
            else
            {
                try
                {
                    messageBody = msg.getPayloadAsString().getBytes(StandardCharsets.UTF_8);
                }
                catch (Exception e)
                {
                    throw failure("payload of the message from " + JMS_ADDRESS, e.toString(), e);
                }
            }

            List<String> logMessages = collector.snapshot();
            if (logMessages.isEmpty())
            {
                Assert.fail(SCENARIO + ": no log event of category " + CATEGORY
                        + " reached the collector attached to LoggerContext " + ctx.getName());
            }

            File outDir = new File(out);
            outDir.mkdirs();
            if (!outDir.isDirectory())
            {
                Assert.fail(SCENARIO + ": capture.out is not a directory and cannot be created: "
                        + outDir.getAbsolutePath());
            }
            File fixture = new File(outDir, SCENARIO + ".json");
            String json = toJson(buildFixture(portNumber, body, statusLine, rawHeaders, responseBody,
                    messageBody, logMessages));
            try
            {
                Files.write(fixture.toPath(), json.getBytes(StandardCharsets.UTF_8));
            }
            catch (IOException e)
            {
                throw failure("write of the fixture " + fixture.getAbsolutePath(), e.toString(), e);
            }
            Assert.assertTrue(SCENARIO + ": fixture file was not written: " + fixture.getAbsolutePath(),
                    fixture.isFile());
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
     * @param statusLine   first line of {@code rawHeaders}, without its line terminator
     * @param rawHeaders   received header block, from the first response byte through the empty line ending the
     *                     first header block whose status code is not 1xx, each byte decoded as the ISO-8859-1
     *                     char of the same value (D-335)
     * @param responseBody response body bytes
     * @param messageBody  bytes of the message taken from queue {@code sales}
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
        Map<String, Object> message = new LinkedHashMap<String, Object>();
        message.put("kind", "message");
        message.put("destination", DESTINATION);
        message.put("bodyBase64", base64.encodeToString(messageBody));
        outputs.add(message);
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
     * Returns {@code text} up to its first LF, without that LF and without a CR directly before it.
     *
     * @param text header block text
     * @return the first line
     */
    private static String firstLine(String text)
    {
        int end = text.indexOf('\n');
        if (end < 0)
        {
            end = text.length();
        }
        if (end > 0 && text.charAt(end - 1) == '\r')
        {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * Builds the failure of one capture step, with the message {@code <SCENARIO>: <step> failed: <detail>} (D-337).
     *
     * @param step   capture step that failed
     * @param detail what failed
     * @param cause  exception the step raised
     * @return the failure, with {@code cause} as its cause
     */
    private static AssertionError failure(String step, String detail, Throwable cause)
    {
        return new AssertionError(SCENARIO + ": " + step + " failed: " + detail, cause);
    }

    /**
     * Loopback SOCKS5 relay that records the response header block of one HTTP exchange byte for byte (D-335).
     * <p>
     * It listens on an ephemeral port of the loopback address and accepts one connection. The greeting must be
     * SOCKS version 5 and offer method {@code 00}, no authentication, which is selected. The request must be
     * {@code CONNECT} with address type 1 (IPv4), 3 (domain name) or 4 (IPv6) naming a loopback address and the
     * target port. Any other greeting or request is answered with a SOCKS5 failure reply where the protocol defines
     * one, and becomes the relay error. The success reply carries the address and port the relay connected to the
     * target from. Bytes are then copied unchanged in both directions on daemon threads; each byte from the target
     * is appended to the record before it is forwarded to the client.
     * <p>
     * The record runs from the first response byte through the empty line that ends the first header block whose
     * status code is not 1xx; interim 1xx blocks before it are part of it. A line ends at LF, with or without a CR
     * before it. The first line of each block must be a status line {@code HTTP/<major>.<minor> <code>}, with or
     * without a reason phrase; any other first line becomes the relay error. The connect to the target and every
     * socket read time out after the relay timeout. An error raised once the record is complete or the relay is
     * closed leaves the record and the relay error unchanged.
     */
    static final class HeaderRecordingRelay implements Closeable
    {
        private static final int SOCKS_VERSION = 5;
        private static final int METHOD_NO_AUTHENTICATION = 0x00;
        private static final int METHOD_NONE_ACCEPTABLE = 0xFF;
        private static final int COMMAND_CONNECT = 1;
        private static final int ADDRESS_IPV4 = 1;
        private static final int ADDRESS_DOMAIN_NAME = 3;
        private static final int ADDRESS_IPV6 = 4;
        private static final int REPLY_SUCCEEDED = 0x00;
        private static final int REPLY_NOT_ALLOWED = 0x02;
        private static final int REPLY_HOST_UNREACHABLE = 0x04;
        private static final int REPLY_CONNECTION_REFUSED = 0x05;
        private static final int REPLY_COMMAND_NOT_SUPPORTED = 0x07;
        private static final int REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;

        /** HTTP/1.x status line: version, three-digit status code, optional space and reason phrase. */
        private static final Pattern STATUS_LINE = Pattern.compile("HTTP/\\d+\\.\\d+ ([1-9]\\d\\d)(?: .*)?",
                Pattern.DOTALL);

        private static final int BUFFER_SIZE = 8192;

        private final int targetPort;
        private final int timeoutMillis;
        private final ServerSocket server;

        /** Guards every field below and is notified when the record completes, an error is set or the relay closes. */
        private final Object lock = new Object();

        /** Response bytes recorded so far. */
        private final ByteArrayOutputStream record = new ByteArrayOutputStream();

        /** Bytes of the response line being recorded, without its LF. */
        private final ByteArrayOutputStream line = new ByteArrayOutputStream();

        /** Status code of the header block being recorded, or 0 before its status line. */
        private int blockStatus;

        private boolean complete;
        private String error;
        private boolean closed;
        private Socket client;
        private Socket target;

        /**
         * Binds the relay to an ephemeral port of the loopback address.
         *
         * @param targetPort    the only port a {@code CONNECT} request may name
         * @param timeoutMillis accept, connect and read timeout of every relay socket, and bound of
         *                      {@link #awaitHeaderBlock()}
         * @throws IOException when the listening socket cannot be bound or configured
         */
        HeaderRecordingRelay(int targetPort, int timeoutMillis) throws IOException
        {
            this.targetPort = targetPort;
            this.timeoutMillis = timeoutMillis;
            ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            try
            {
                listener.setSoTimeout(timeoutMillis);
            }
            catch (IOException e)
            {
                try
                {
                    listener.close();
                }
                catch (IOException closeFailure)
                {
                    e.addSuppressed(closeFailure);
                }
                throw e;
            }
            this.server = listener;
        }

        /**
         * Returns the SOCKS proxy that connects through this relay.
         *
         * @return a proxy whose address is the literal IP address and port the relay listens on
         * @throws UnknownHostException when the listening address has no IPv4 or IPv6 form
         */
        Proxy proxy() throws UnknownHostException
        {
            InetAddress literal = InetAddress.getByAddress(server.getInetAddress().getAddress());
            return new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(literal, server.getLocalPort()));
        }

        /** Starts the daemon thread that accepts the single connection and relays it. */
        void start()
        {
            Thread acceptor = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    serve();
                }
            }, "header-recording-relay-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        /**
         * Waits until the header block is recorded, the relay fails or closes, or the relay timeout passes.
         *
         * @return the recorded bytes, from the first response byte through the empty line that ends the first
         *         header block whose status code is not 1xx
         * @throws IOException with the relay error, when the relay closed first, when the timeout passed, or, as an
         *                     {@link InterruptedIOException}, when the waiting thread is interrupted
         */
        byte[] awaitHeaderBlock() throws IOException
        {
            long deadline = System.currentTimeMillis() + timeoutMillis;
            synchronized (lock)
            {
                while (!complete && error == null && !closed)
                {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0)
                    {
                        throw new IOException("no complete response header block within " + timeoutMillis + " ms ("
                                + record.size() + " response bytes recorded)");
                    }
                    try
                    {
                        lock.wait(remaining);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        InterruptedIOException interrupted =
                                new InterruptedIOException("interrupted while waiting for the response header block");
                        interrupted.initCause(e);
                        throw interrupted;
                    }
                }
                if (!complete)
                {
                    throw new IOException(error != null ? error
                            : "relay closed before the response header block was complete (" + record.size()
                                    + " response bytes recorded)");
                }
                return record.toByteArray();
            }
        }

        /**
         * Returns the status code of the header block that completed the record.
         *
         * @return the status code, or 0 while the record is incomplete
         */
        int statusCode()
        {
            synchronized (lock)
            {
                return complete ? blockStatus : 0;
            }
        }

        /**
         * Returns the relay error.
         *
         * @return the first error raised before the record completed and before the relay closed, or {@code null}
         */
        String error()
        {
            synchronized (lock)
            {
                return error;
            }
        }

        /**
         * Closes the listening socket and both relayed sockets; the copy threads then end.
         *
         * @throws IOException the first close failure, with any later ones suppressed
         */
        @Override
        public void close() throws IOException
        {
            Socket clientSocket;
            Socket targetSocket;
            synchronized (lock)
            {
                closed = true;
                clientSocket = client;
                targetSocket = target;
                lock.notifyAll();
            }
            IOException failure = null;
            for (Closeable socket : new Closeable[] {server, clientSocket, targetSocket})
            {
                if (socket == null)
                {
                    continue;
                }
                try
                {
                    socket.close();
                }
                catch (IOException e)
                {
                    if (failure == null)
                    {
                        failure = e;
                    }
                    else
                    {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null)
            {
                throw failure;
            }
        }

        /**
         * Accepts one connection, closes the listening socket, completes the SOCKS5 handshake, then copies target
         * bytes to the client on a new daemon thread and client bytes to the target on this one.
         */
        private void serve()
        {
            Socket accepted;
            try
            {
                accepted = server.accept();
            }
            catch (IOException e)
            {
                fail("no connection accepted within " + timeoutMillis + " ms: " + e);
                return;
            }
            finally
            {
                closeSocket(server, "listening socket");
            }
            if (!register(accepted, true))
            {
                return;
            }
            final Socket connected;
            try
            {
                accepted.setSoTimeout(timeoutMillis);
                connected = handshake(accepted);
            }
            catch (IOException e)
            {
                fail("SOCKS5 handshake failed: " + e);
                closeSockets();
                return;
            }
            Thread responses = new Thread(new Runnable()
            {
                @Override
                public void run()
                {
                    pump(connected, accepted, true);
                }
            }, "header-recording-relay-response");
            responses.setDaemon(true);
            responses.start();
            pump(accepted, connected, false);
        }

        /**
         * Runs the SOCKS5 exchange on the accepted socket and connects to the requested target.
         *
         * @param socket accepted client socket
         * @return the socket connected to the target, already registered
         * @throws IOException when the greeting or the request is refused, the target cannot be connected, the
         *                     relay closed, or the client socket fails
         */
        private Socket handshake(Socket socket) throws IOException
        {
            DataInputStream in = new DataInputStream(socket.getInputStream());
            OutputStream out = socket.getOutputStream();

            int version = in.readUnsignedByte();
            if (version != SOCKS_VERSION)
            {
                throw new IOException("greeting has SOCKS version " + version + ", expected " + SOCKS_VERSION);
            }
            byte[] methods = readBytes(in, in.readUnsignedByte());
            boolean noAuthentication = false;
            for (byte method : methods)
            {
                noAuthentication |= (method & 0xFF) == METHOD_NO_AUTHENTICATION;
            }
            if (!noAuthentication)
            {
                throw refuse(out, new byte[] {SOCKS_VERSION, (byte) METHOD_NONE_ACCEPTABLE},
                        "greeting offers no method 00 (no authentication)");
            }
            send(out, new byte[] {SOCKS_VERSION, METHOD_NO_AUTHENTICATION});

            int requestVersion = in.readUnsignedByte();
            int command = in.readUnsignedByte();
            // Reserved byte of the request; its value is not checked.
            in.readUnsignedByte();
            int addressType = in.readUnsignedByte();
            if (requestVersion != SOCKS_VERSION || command != COMMAND_CONNECT)
            {
                throw refuse(out, failureReply(REPLY_COMMAND_NOT_SUPPORTED), "request version " + requestVersion
                        + " command " + command + " refused; only CONNECT (1) of version 5 is relayed");
            }
            byte[] addressBytes = null;
            String hostName = null;
            if (addressType == ADDRESS_IPV4)
            {
                addressBytes = readBytes(in, 4);
            }
            else if (addressType == ADDRESS_IPV6)
            {
                addressBytes = readBytes(in, 16);
            }
            else if (addressType == ADDRESS_DOMAIN_NAME)
            {
                hostName = new String(readBytes(in, in.readUnsignedByte()), StandardCharsets.ISO_8859_1);
            }
            else
            {
                throw refuse(out, failureReply(REPLY_ADDRESS_TYPE_NOT_SUPPORTED),
                        "address type " + addressType + " refused; only 1, 3 and 4 are relayed");
            }
            int port = in.readUnsignedShort();

            InetAddress address;
            if (addressBytes != null)
            {
                address = InetAddress.getByAddress(addressBytes);
            }
            else if (hostName.isEmpty())
            {
                throw refuse(out, failureReply(REPLY_HOST_UNREACHABLE), "CONNECT names an empty host name");
            }
            else
            {
                try
                {
                    address = InetAddress.getByName(hostName);
                }
                catch (UnknownHostException e)
                {
                    IOException refusal = refuse(out, failureReply(REPLY_HOST_UNREACHABLE),
                            "CONNECT host " + hostName + " cannot be resolved");
                    refusal.initCause(e);
                    throw refusal;
                }
            }
            String requested = (hostName != null ? hostName : address.getHostAddress()) + ":" + port;
            if (port != targetPort || !address.isLoopbackAddress())
            {
                throw refuse(out, failureReply(REPLY_NOT_ALLOWED),
                        "CONNECT " + requested + " refused; only loopback port " + targetPort + " is relayed");
            }

            Socket connected = new Socket();
            if (!register(connected, false))
            {
                throw new IOException("relay closed before CONNECT " + requested);
            }
            try
            {
                connected.connect(new InetSocketAddress(address, port), timeoutMillis);
                connected.setSoTimeout(timeoutMillis);
            }
            catch (IOException e)
            {
                IOException refusal = refuse(out, failureReply(REPLY_CONNECTION_REFUSED),
                        "CONNECT " + requested + " failed: " + e);
                refusal.initCause(e);
                throw refusal;
            }

            InetAddress bound = connected.getLocalAddress();
            byte[] boundAddress = bound.getAddress();
            int boundPort = connected.getLocalPort();
            ByteArrayOutputStream reply = new ByteArrayOutputStream();
            reply.write(SOCKS_VERSION);
            reply.write(REPLY_SUCCEEDED);
            reply.write(0);
            reply.write(bound instanceof Inet4Address ? ADDRESS_IPV4 : ADDRESS_IPV6);
            reply.write(boundAddress, 0, boundAddress.length);
            reply.write((boundPort >> 8) & 0xFF);
            reply.write(boundPort & 0xFF);
            send(out, reply.toByteArray());
            return connected;
        }

        /**
         * Copies bytes from one socket to the other until the end of stream, then shuts down the output of the
         * receiving socket. With {@code recorded}, each chunk is passed to {@link #record(byte[], int)} before it is
         * written, and an end of stream before the record is complete becomes the relay error. A failure becomes
         * the relay error and closes both relayed sockets.
         */
        private void pump(Socket from, Socket to, boolean recorded)
        {
            byte[] buffer = new byte[BUFFER_SIZE];
            try
            {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int count;
                while ((count = in.read(buffer)) != -1)
                {
                    if (recorded)
                    {
                        record(buffer, count);
                    }
                    out.write(buffer, 0, count);
                    out.flush();
                }
                if (recorded)
                {
                    fail("the target closed the connection before the response header block was complete");
                }
                to.shutdownOutput();
            }
            catch (IOException e)
            {
                fail((recorded ? "target to client" : "client to target") + " copy failed: " + e);
                closeSockets();
            }
        }

        /** Appends the first {@code count} bytes of {@code bytes} to the record until the record is complete. */
        private void record(byte[] bytes, int count)
        {
            synchronized (lock)
            {
                for (int i = 0; i < count && !complete && error == null; i++)
                {
                    record.write(bytes[i]);
                    if (bytes[i] == '\n')
                    {
                        endLine();
                    }
                    else
                    {
                        line.write(bytes[i]);
                    }
                }
                lock.notifyAll();
            }
        }

        /**
         * Handles the line the recorded LF ends: the first line of a block sets its status code or the relay error;
         * an empty line ends the block, which completes the record unless the status code is 1xx. Called with
         * {@link #lock} held.
         */
        private void endLine()
        {
            byte[] content = line.toByteArray();
            line.reset();
            int length = content.length > 0 && content[content.length - 1] == '\r' ? content.length - 1
                    : content.length;
            if (blockStatus == 0)
            {
                String text = new String(content, 0, length, StandardCharsets.ISO_8859_1);
                Matcher matcher = STATUS_LINE.matcher(text);
                if (matcher.matches())
                {
                    blockStatus = Integer.parseInt(matcher.group(1));
                }
                else
                {
                    error = "response header block does not start with an HTTP status line: \"" + text + "\" ("
                            + record.size() + " response bytes recorded)";
                }
            }
            else if (length == 0)
            {
                if (blockStatus < 200)
                {
                    blockStatus = 0;
                }
                else
                {
                    complete = true;
                }
            }
        }

        /**
         * Sets the relay error to {@code detail} and the number of response bytes recorded, unless the record is
         * complete, an error is already set or the relay is closed.
         */
        private void fail(String detail)
        {
            synchronized (lock)
            {
                if (!complete && !closed && error == null)
                {
                    error = detail + " (" + record.size() + " response bytes recorded)";
                }
                lock.notifyAll();
            }
        }

        /**
         * Keeps {@code socket} as the client or target socket.
         *
         * @return {@code false}, with {@code socket} closed, when the relay is already closed
         */
        private boolean register(Socket socket, boolean isClient)
        {
            synchronized (lock)
            {
                if (!closed)
                {
                    if (isClient)
                    {
                        client = socket;
                    }
                    else
                    {
                        target = socket;
                    }
                    return true;
                }
            }
            closeSocket(socket, isClient ? "client socket" : "target socket");
            return false;
        }

        /** Closes both relayed sockets. */
        private void closeSockets()
        {
            Socket clientSocket;
            Socket targetSocket;
            synchronized (lock)
            {
                clientSocket = client;
                targetSocket = target;
            }
            closeSocket(clientSocket, "client socket");
            closeSocket(targetSocket, "target socket");
        }

        /** Closes {@code socket} when it is not {@code null}; a close failure goes to {@link #fail(String)}. */
        private void closeSocket(Closeable socket, String name)
        {
            if (socket == null)
            {
                return;
            }
            try
            {
                socket.close();
            }
            catch (IOException e)
            {
                fail("closing the " + name + " failed: " + e);
            }
        }

        /** SOCKS5 reply {@code 05 <code> 00 01 0.0.0.0:0}. */
        private static byte[] failureReply(int code)
        {
            return new byte[] {SOCKS_VERSION, (byte) code, 0, ADDRESS_IPV4, 0, 0, 0, 0, 0, 0};
        }

        /**
         * Sends a refusal reply and builds the exception that reports the refusal.
         *
         * @return an exception with {@code reason} as its message and any failure to send the reply suppressed
         */
        private static IOException refuse(OutputStream out, byte[] reply, String reason)
        {
            IOException refusal = new IOException(reason);
            try
            {
                send(out, reply);
            }
            catch (IOException e)
            {
                refusal.addSuppressed(e);
            }
            return refusal;
        }

        /** Writes and flushes {@code bytes}. */
        private static void send(OutputStream out, byte[] bytes) throws IOException
        {
            out.write(bytes);
            out.flush();
        }

        /** Reads exactly {@code count} bytes. */
        private static byte[] readBytes(DataInputStream in, int count) throws IOException
        {
            byte[] bytes = new byte[count];
            in.readFully(bytes);
            return bytes;
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
