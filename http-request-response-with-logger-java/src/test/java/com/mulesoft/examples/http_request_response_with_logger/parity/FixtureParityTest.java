package com.mulesoft.examples.http_request_response_with_logger.parity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.http_request_response_with_logger.controller.EchoController;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tier 2A completeness gate and replay of the captured fixtures of http-request-response-with-logger
 * (D-081), whose single flow {@code EchoFlow} answers any method on {@code /*} with the request path and
 * logs {@code About to echo <path>} at INFO.
 *
 * <p>The class runs only when at least one {@code fixtures/*.json} file is on the test classpath; otherwise
 * {@link FixturesPresentCondition} disables it before any Spring context starts (D-021). When it runs:
 * <ul>
 *   <li>{@link #fixtureSetMatchesScenarioInventory()} compares the fixture file names plus the identities
 *       of {@code fixtures/UNCAPTURABLE.txt} with {@code fixtures/SCENARIOS.txt};</li>
 *   <li>{@link #replayFixtures()} replays every fixture as one dynamic test against the application on a
 *       random port, over a raw socket with ISO-8859-1 bytes, and compares the status line, every recorded
 *       header, the body bytes and the {@code EchoController} log messages after {@code volatile}
 *       masking.</li>
 * </ul>
 *
 * <p>Fixture files are UTF-8 JSON objects with the fields {@code scenario}, {@code setup},
 * {@code replay}, {@code request}, {@code response}, {@code steps}, {@code trigger}, {@code outputs} and
 * {@code volatile}. A {@code live} fixture is replayed only with {@code -Dparity.live=true}; without it,
 * the dynamic test is aborted and reported by name as not replayed. Run with
 * {@code mvn -B test -Dtest=FixtureParityTest}.
 */
@ExtendWith(FixtureParityTest.FixturesPresentCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FixtureParityTest {

    /** Reader of the fixture files. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Location pattern of the fixture files: the top-level {@code *.json} files of {@code fixtures/}. */
    private static final String FIXTURES = "classpath:fixtures/*.json";

    /** Resolver of the fixture files and of the {@code SCENARIOS.txt} and {@code UNCAPTURABLE.txt} lists. */
    private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();

    /** Read timeout, in milliseconds, of every replay socket. */
    private static final int READ_TIMEOUT_MILLIS = 30_000;

    /** Port of the embedded server started for this class. */
    @LocalServerPort
    int port;

    /**
     * Compares the fixture identities with {@code fixtures/SCENARIOS.txt} (D-081).
     *
     * <p>The identities are the trimmed non-blank lines of {@code SCENARIOS.txt}; the base names of the
     * {@code fixtures/*.json} files; and, for each trimmed non-blank line of {@code fixtures/UNCAPTURABLE.txt}
     * when that file exists, the text before the first {@code " — "} (the whole line without it), trimmed.
     * The test fails once, naming every identity of {@code SCENARIOS.txt} that is neither captured nor
     * uncapturable ({@code missing}), every captured or uncapturable identity absent from
     * {@code SCENARIOS.txt} ({@code extra}), and every identity listed twice in {@code SCENARIOS.txt} or
     * twice across the fixture and uncapturable identities ({@code duplicated}).
     *
     * @throws IOException if a list file cannot be read
     */
    @Test
    void fixtureSetMatchesScenarioInventory() throws IOException {
        Resource scenariosFile = RESOLVER.getResource("classpath:fixtures/SCENARIOS.txt");
        assertTrue(scenariosFile.exists(), "fixtures/SCENARIOS.txt not found on the test classpath");
        List<String> scenarios = trimmedLines(scenariosFile);

        List<String> covered = new ArrayList<>();
        for (Resource fixture : fixtureResources()) {
            covered.add(baseName(fixture));
        }
        Resource uncapturableFile = RESOLVER.getResource("classpath:fixtures/UNCAPTURABLE.txt");
        if (uncapturableFile.exists()) {
            for (String line : trimmedLines(uncapturableFile)) {
                int separator = line.indexOf(" \u2014 ");
                covered.add((separator < 0 ? line : line.substring(0, separator)).trim());
            }
        }

        Set<String> missing = new TreeSet<>(scenarios);
        missing.removeAll(covered);
        Set<String> extra = new TreeSet<>(covered);
        extra.removeAll(scenarios);
        Set<String> duplicated = new TreeSet<>(repeated(scenarios));
        duplicated.addAll(repeated(covered));
        if (!missing.isEmpty() || !extra.isEmpty() || !duplicated.isEmpty()) {
            fail("fixture set differs from SCENARIOS.txt: missing=" + missing + ", extra=" + extra
                    + ", duplicated=" + duplicated);
        }
    }

    /**
     * One dynamic test per {@code fixtures/*.json} file, in file-name order, named after the fixture's
     * non-blank {@code scenario} field, else after the file's base name. Each test replays the fixture
     * against the running application (D-081).
     *
     * @return the dynamic tests
     * @throws UncheckedIOException if the fixture files cannot be listed
     */
    @TestFactory
    Stream<DynamicTest> replayFixtures() {
        return fixtureResources().stream().map(resource -> {
            String name = testName(resource);
            return DynamicTest.dynamicTest(name, () -> replayFixture(name, resource));
        });
    }

    /**
     * Replays one fixture file.
     *
     * <ol>
     *   <li>The file is parsed as a JSON object; a parse failure fails the test.</li>
     *   <li>A {@code live} fixture is aborted as not replayed unless {@code parity.live} is {@code true}.</li>
     *   <li>A fixture with a {@code trigger}, or with neither {@code steps} nor {@code request}, fails.</li>
     *   <li>The {@code volatile} regexes are grouped by location.</li>
     *   <li>A Logback {@link ListAppender} is attached to the {@link EchoController} logger, every
     *       {@code steps} pair (else the single {@code request}/{@code response} pair) is sent and compared
     *       in order, then the {@code log} outputs are compared; the appender is detached and stopped
     *       afterwards.</li>
     * </ol>
     *
     * @param name     the dynamic test name, used in failure messages
     * @param resource the fixture file
     * @throws IOException if an exchange with the application fails
     */
    private void replayFixture(String name, Resource resource) throws IOException {
        JsonNode fixture = null;
        try (InputStream in = resource.getInputStream()) {
            fixture = JSON.readTree(in);
        } catch (IOException e) {
            fail("fixture " + name + " is not valid JSON: " + e.getMessage());
        }
        if (fixture == null || !fixture.isObject()) {
            fail("fixture " + name + " is not a JSON object");
        }
        if ("live".equals(fixture.path("replay").asText()) && !Boolean.getBoolean("parity.live")) {
            Assumptions.abort(name + " live fixture not replayed");
        }
        if (fixture.has("trigger")) {
            fail("unsupported trigger for http-request-response-with-logger");
        }
        if (!fixture.has("steps") && !fixture.has("request")) {
            fail("fixture has no request");
        }
        Map<String, List<String>> masks = volatileMasks(fixture);

        Logger logger = (Logger) LoggerFactory.getLogger(EchoController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            if (fixture.has("steps")) {
                JsonNode steps = fixture.get("steps");
                if (!steps.isArray()) {
                    fail("fixture steps is not an array");
                }
                for (int i = 0; i < steps.size(); i++) {
                    JsonNode step = steps.get(i);
                    compareExchange("step " + (i + 1) + ": ", step.path("request"), step.path("response"), masks);
                }
            } else {
                compareExchange("", fixture.path("request"), fixture.path("response"), masks);
            }
            compareLogs(fixture, appender, masks);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    /**
     * The {@code volatile} regexes of a fixture by location: the keys {@code header}, {@code body},
     * {@code log} and {@code output}, each mapped to its regexes in fixture order. Any other {@code where}
     * value, an entry without a non-empty {@code regex}, or a non-array {@code volatile} field fails.
     *
     * @param fixture the fixture
     * @return the regexes by location; every list is empty when the fixture has no {@code volatile} entry
     */
    private static Map<String, List<String>> volatileMasks(JsonNode fixture) {
        Map<String, List<String>> masks = new HashMap<>();
        for (String where : List.of("header", "body", "log", "output")) {
            masks.put(where, new ArrayList<>());
        }
        JsonNode entries = fixture.path("volatile");
        if (entries.isMissingNode() || entries.isNull()) {
            return masks;
        }
        if (!entries.isArray()) {
            fail("fixture volatile is not an array");
        }
        for (JsonNode entry : entries) {
            String where = entry.path("where").asText();
            List<String> regexes = masks.get(where);
            if (regexes == null) {
                fail("unsupported volatile location: " + where);
            }
            JsonNode regex = entry.path("regex");
            if (!regex.isTextual() || regex.asText().isEmpty()) {
                fail("volatile entry without a regex: " + entry);
            }
            regexes.add(regex.asText());
        }
        return masks;
    }

    /**
     * Sends one recorded request to the application and compares the response with the recorded one.
     *
     * <ul>
     *   <li>Status line: equal to {@code response.statusLine}, unmasked.</li>
     *   <li>Headers: the {@code header} masks are applied to the recorded and the received header blocks
     *       (each without its status line); every recorded header then has a received header with an
     *       equal value and a case-insensitively equal name, each received header matching at most one
     *       recorded header. Received headers that were not recorded are allowed.</li>
     *   <li>Body: byte-equal to the decoded {@code response.bodyBase64}; with {@code body} masks, both
     *       sides are compared as ISO-8859-1 text after masking.</li>
     * </ul>
     *
     * @param label    prefix of the failure messages; empty for a single exchange
     * @param request  the recorded request
     * @param response the recorded response
     * @param masks    the {@code volatile} regexes by location
     * @throws IOException if the exchange fails
     */
    private void compareExchange(String label, JsonNode request, JsonNode response,
            Map<String, List<String>> masks) throws IOException {
        if (!request.isObject()) {
            fail(label + "fixture has no request");
        }
        if (!response.isObject()) {
            fail(label + "fixture has no response");
        }
        byte[][] received = exchange(port, request);
        String head = new String(received[0], StandardCharsets.ISO_8859_1);
        int statusEnd = head.indexOf("\r\n");
        String statusLine = statusEnd < 0 ? head : head.substring(0, statusEnd);
        String headerBlock = statusEnd < 0 ? "" : head.substring(statusEnd + 2);
        assertEquals(response.path("statusLine").asText(), statusLine, label + "status line");

        String rawHeaders = response.path("rawHeaders").asText();
        int recordedStatusEnd = rawHeaders.indexOf("\r\n");
        String recordedBlock = recordedStatusEnd < 0 ? "" : rawHeaders.substring(recordedStatusEnd + 2);
        List<String> headerMasks = masks.get("header");
        List<String[]> actualHeaders = headerFields(mask(headerBlock, headerMasks));
        for (String[] expected : headerFields(mask(recordedBlock, headerMasks))) {
            boolean found = false;
            Iterator<String[]> candidates = actualHeaders.iterator();
            while (!found && candidates.hasNext()) {
                String[] candidate = candidates.next();
                if (candidate[0].equalsIgnoreCase(expected[0]) && candidate[1].equals(expected[1])) {
                    candidates.remove();
                    found = true;
                }
            }
            assertTrue(found, label + "missing or different header: " + expected[0] + ": " + expected[1]);
        }

        byte[] expectedBody = decodeBase64(response.path("bodyBase64"), label + "response.bodyBase64");
        List<String> bodyMasks = masks.get("body");
        if (bodyMasks.isEmpty()) {
            assertArrayEquals(expectedBody, received[1], label + "body");
        } else {
            assertEquals(mask(new String(expectedBody, StandardCharsets.ISO_8859_1), bodyMasks),
                    mask(new String(received[1], StandardCharsets.ISO_8859_1), bodyMasks), label + "body");
        }
    }

    /**
     * Compares the {@code log} outputs of a fixture with the messages the appender captured.
     *
     * <p>A log output is an {@code outputs} element whose {@code type} is {@code log}, holding
     * {@code messages} itself, or an element with a {@code log} object holding them. Any other element
     * fails. The expected messages are the {@code messages} of every log output in fixture order; the
     * actual ones are the formatted messages of the captured events in order. The {@code log} masks, then
     * the {@code output} masks, are applied to every message on both sides before the lists are compared.
     * {@code category} is not compared. Without a log output no comparison is made.
     *
     * @param fixture  the fixture
     * @param appender the appender attached to the {@link EchoController} logger
     * @param masks    the {@code volatile} regexes by location
     */
    private static void compareLogs(JsonNode fixture, ListAppender<ILoggingEvent> appender,
            Map<String, List<String>> masks) {
        JsonNode outputs = fixture.path("outputs");
        if (outputs.isMissingNode() || outputs.isNull()) {
            return;
        }
        if (!outputs.isArray()) {
            fail("fixture outputs is not an array");
        }
        List<String> expected = new ArrayList<>();
        boolean logOutput = false;
        for (JsonNode output : outputs) {
            JsonNode holder;
            if ("log".equals(output.path("type").asText())) {
                holder = output;
            } else if (output.path("log").isObject()) {
                holder = output.get("log");
            } else {
                holder = fail("unsupported output for http-request-response-with-logger");
            }
            JsonNode messages = holder.path("messages");
            if (!messages.isArray()) {
                fail("log output without a messages array: " + output);
            }
            for (JsonNode message : messages) {
                if (!message.isTextual()) {
                    fail("log message is not a string: " + message);
                }
                expected.add(message.asText());
            }
            logOutput = true;
        }
        if (!logOutput) {
            return;
        }
        List<String> actual;
        synchronized (appender) {
            actual = appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
        }
        assertEquals(maskMessages(expected, masks), maskMessages(actual, masks), "log messages");
    }

    /**
     * Applies the {@code log} masks, then the {@code output} masks, to each message.
     *
     * @param messages the messages
     * @param masks    the {@code volatile} regexes by location
     * @return the masked messages, in order
     */
    private static List<String> maskMessages(List<String> messages, Map<String, List<String>> masks) {
        List<String> masked = new ArrayList<>(messages.size());
        for (String message : messages) {
            masked.add(mask(mask(message, masks.get("log")), masks.get("output")));
        }
        return masked;
    }

    /**
     * Replaces every match of each regex, in order, with {@code <volatile>}.
     *
     * @param text    the text
     * @param regexes the regexes
     * @return the masked text
     */
    private static String mask(String text, List<String> regexes) {
        String masked = text;
        for (String regex : regexes) {
            masked = masked.replaceAll(regex, "<volatile>");
        }
        return masked;
    }

    /**
     * Header fields of a header block: the block is split on CRLF, blank lines are skipped, and each line
     * is split at its first {@code :} into a name and a trimmed value (the whole line and an empty value
     * when it has no {@code :}).
     *
     * @param block the header lines without the status line
     * @return modifiable list of {@code {name, value}} pairs, in block order
     */
    private static List<String[]> headerFields(String block) {
        List<String[]> fields = new ArrayList<>();
        for (String line : block.split("\r\n")) {
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                fields.add(new String[] {line.trim(), ""});
            } else {
                fields.add(new String[] {line.substring(0, colon).trim(), line.substring(colon + 1).trim()});
            }
        }
        return fields;
    }


    /**
     * Sends one recorded request over a new socket to {@code localhost:port} and reads the response.
     *
     * <p>Written as ISO-8859-1: the request line {@code <method> <path>[?<query>] HTTP/1.1}; a
     * {@code Host: localhost:<port>} header when none was recorded; every recorded header in recorded
     * order, a recorded {@code Host} carrying {@code localhost:<port>} as its value; a
     * {@code Content-Length} header when the body is not empty and none was recorded; an empty line; and
     * the body decoded from {@code bodyBase64}. No {@code Connection} header is added.
     *
     * <p>The response head is read up to the first empty line. The body is empty for a {@code HEAD}
     * request and for status 204 or 304; otherwise it is the decoded chunks when
     * {@code Transfer-Encoding} contains {@code chunked}, else {@code Content-Length} bytes, else every
     * byte up to the end of the stream.
     *
     * @param port    the application port
     * @param request the recorded request
     * @return the response head without its terminating empty line, and the response body
     * @throws IOException if the socket cannot be opened, written or read, or the response is malformed
     */
    private static byte[][] exchange(int port, JsonNode request) throws IOException {
        String method = request.path("method").asText();
        String path = request.path("path").asText();
        if (method.isEmpty() || path.isEmpty()) {
            fail("fixture request needs a method and a path: " + request);
        }
        String query = request.path("query").asText();
        byte[] body = decodeBase64(request.path("bodyBase64"), "request.bodyBase64");
        String host = "localhost:" + port;

        StringBuilder recorded = new StringBuilder();
        boolean hostRecorded = false;
        boolean lengthRecorded = false;
        Iterator<Map.Entry<String, JsonNode>> headers = request.path("headers").fields();
        while (headers.hasNext()) {
            Map.Entry<String, JsonNode> header = headers.next();
            String name = header.getKey();
            String value = header.getValue().asText();
            if ("Host".equalsIgnoreCase(name)) {
                hostRecorded = true;
                value = host;
            } else if ("Content-Length".equalsIgnoreCase(name)) {
                lengthRecorded = true;
            }
            recorded.append(name).append(": ").append(value).append("\r\n");
        }

        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path);
        if (!query.isEmpty()) {
            head.append('?').append(query);
        }
        head.append(" HTTP/1.1\r\n");
        if (!hostRecorded) {
            head.append("Host: ").append(host).append("\r\n");
        }
        head.append(recorded);
        if (body.length > 0 && !lengthRecorded) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            InputStream in = new BufferedInputStream(socket.getInputStream());
            byte[] responseHead = readHead(in);
            byte[] responseBody = readBody(in, method, new String(responseHead, StandardCharsets.ISO_8859_1));
            return new byte[][] {responseHead, responseBody};
        }
    }

    /**
     * Reads a response head: every byte up to, and without, the first CRLF CRLF.
     *
     * @param in the response stream
     * @return the status line and header lines
     * @throws IOException if the stream cannot be read or ends before the empty line
     */
    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("response ended inside its head: "
                        + head.toString(StandardCharsets.ISO_8859_1));
            }
            head.write(next);
            if (next == (matched % 2 == 0 ? '\r' : '\n')) {
                matched++;
            } else {
                matched = next == '\r' ? 1 : 0;
            }
        }
        byte[] bytes = head.toByteArray();
        return Arrays.copyOf(bytes, bytes.length - 4);
    }

    /**
     * Reads a response body as framed by the request method, the status and the head's headers.
     *
     * @param in     the response stream, positioned after the head
     * @param method the request method
     * @param head   the response head, decoded as ISO-8859-1
     * @return the body bytes; empty for {@code HEAD}, 204 and 304
     * @throws IOException if the stream cannot be read, ends early, or the framing is malformed
     */
    private static byte[] readBody(InputStream in, String method, String head) throws IOException {
        String[] lines = head.split("\r\n");
        String[] statusTokens = lines[0].split(" ");
        String status = statusTokens.length > 1 ? statusTokens[1] : "";
        if ("HEAD".equalsIgnoreCase(method) || "204".equals(status) || "304".equals(status)) {
            return new byte[0];
        }
        boolean chunked = false;
        String contentLength = null;
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon < 0) {
                continue;
            }
            String name = lines[i].substring(0, colon).trim();
            String value = lines[i].substring(colon + 1).trim();
            if ("Transfer-Encoding".equalsIgnoreCase(name) && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            } else if ("Content-Length".equalsIgnoreCase(name)) {
                contentLength = value;
            }
        }
        if (chunked) {
            return readChunked(in);
        }
        if (contentLength != null) {
            int length;
            try {
                length = Integer.parseInt(contentLength);
            } catch (NumberFormatException e) {
                throw new IOException("invalid Content-Length: " + contentLength, e);
            }
            if (length < 0) {
                throw new IOException("invalid Content-Length: " + contentLength);
            }
            return readExactly(in, length);
        }
        return in.readAllBytes();
    }

    /**
     * Reads a chunked body: hex size lines (extensions after {@code ;} ignored), each followed by that many
     * bytes and CRLF, up to the size-0 chunk, then the trailer lines up to the empty line.
     *
     * @param in the response stream, positioned after the head
     * @return the concatenated chunk bytes
     * @throws IOException if the stream cannot be read, ends early, or a size line is malformed
     */
    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(in);
            int extension = sizeLine.indexOf(';');
            String hex = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            int size;
            try {
                size = Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw new IOException("invalid chunk size line: " + sizeLine, e);
            }
            if (size < 0) {
                throw new IOException("invalid chunk size line: " + sizeLine);
            }
            if (size == 0) {
                break;
            }
            body.write(readExactly(in, size));
            String chunkEnd = readLine(in);
            if (!chunkEnd.isEmpty()) {
                throw new IOException("chunk not followed by CRLF: " + chunkEnd);
            }
        }
        String trailer = readLine(in);
        while (!trailer.isEmpty()) {
            trailer = readLine(in);
        }
        return body.toByteArray();
    }

    /**
     * Reads exactly {@code length} bytes.
     *
     * @param in     the response stream
     * @param length the byte count
     * @return the bytes
     * @throws IOException if the stream cannot be read or ends before {@code length} bytes
     */
    private static byte[] readExactly(InputStream in, int length) throws IOException {
        byte[] bytes = in.readNBytes(length);
        if (bytes.length < length) {
            throw new EOFException("response ended after " + bytes.length + " of " + length + " body bytes");
        }
        return bytes;
    }

    /**
     * Reads one CRLF-terminated line as ISO-8859-1, without its CRLF.
     *
     * @param in the response stream
     * @return the line
     * @throws IOException if the stream cannot be read or ends before CRLF
     */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int next = in.read();
            if (next < 0) {
                throw new EOFException("response ended inside a line: " + line.toString(StandardCharsets.ISO_8859_1));
            }
            if (previous == '\r' && next == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            line.write(next);
            previous = next;
        }
    }

    /**
     * Decodes a base64 field; a missing, null or empty field gives zero bytes, and text that is not base64
     * fails.
     *
     * @param field the field
     * @param label the field name used in the failure message
     * @return the decoded bytes
     */
    private static byte[] decodeBase64(JsonNode field, String label) {
        String text = field.isMissingNode() || field.isNull() ? "" : field.asText();
        if (text.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return fail(label + " is not valid base64: " + e.getMessage());
        }
    }

    /**
     * The fixture files: the existing resources matching {@code classpath:fixtures/*.json}, ordered by
     * base name.
     *
     * @return the fixture files
     * @throws UncheckedIOException if the resources cannot be resolved
     */
    private static List<Resource> fixtureResources() {
        Resource[] resources;
        try {
            resources = RESOLVER.getResources(FIXTURES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Arrays.stream(resources)
                .filter(Resource::exists)
                .sorted(Comparator.comparing(FixtureParityTest::baseName))
                .collect(Collectors.toList());
    }

    /**
     * The base name of a fixture file: its file name without {@code .json}.
     *
     * @param resource the fixture file
     * @return the base name
     */
    private static String baseName(Resource resource) {
        String fileName = resource.getFilename();
        if (fileName == null) {
            return fail("fixture resource without a file name: " + resource.getDescription());
        }
        return fileName.endsWith(".json") ? fileName.substring(0, fileName.length() - ".json".length()) : fileName;
    }

    /**
     * The dynamic test name of a fixture file: its non-blank {@code scenario} field, else its base name
     * (also when the file cannot be read or parsed).
     *
     * @param resource the fixture file
     * @return the name
     */
    private static String testName(Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            JsonNode fixture = JSON.readTree(in);
            String scenario = fixture == null ? "" : fixture.path("scenario").asText();
            return scenario.isBlank() ? baseName(resource) : scenario;
        } catch (IOException e) {
            return baseName(resource);
        }
    }

    /**
     * The trimmed non-blank UTF-8 lines of a text resource, in file order, duplicates kept.
     *
     * @param resource the text resource
     * @return the lines
     * @throws IOException if the resource cannot be read
     */
    private static List<String> trimmedLines(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toList());
        }
    }

    /**
     * The values that occur more than once in a list.
     *
     * @param values the values
     * @return each repeated value once
     */
    private static Set<String> repeated(List<String> values) {
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new TreeSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                repeated.add(value);
            }
        }
        return repeated;
    }

    /**
     * Disables the class when no Tier 2A fixture is on the classpath (D-021).
     *
     * <p>The condition resolves {@code classpath:fixtures/*.json}, top-level files only ({@code fixtures/diagnostic/}
     * is never matched), and counts the resources that exist. It reads no Spring application context and no
     * {@link ExtensionContext} store.
     */
    static class FixturesPresentCondition implements ExecutionCondition {

        /**
         * Evaluates the fixture folder.
         *
         * @param context the JUnit extension context, not read
         * @return disabled with {@code no Tier 2A fixtures captured} when no fixture file exists or the
         *         resources cannot be resolved; enabled with {@code Tier 2A fixtures present} otherwise
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            Resource[] resources;
            try {
                resources = new PathMatchingResourcePatternResolver().getResources(FIXTURES);
            } catch (IOException e) {
                return ConditionEvaluationResult.disabled("no Tier 2A fixtures captured");
            }
            for (Resource resource : resources) {
                if (resource.exists()) {
                    return ConditionEvaluationResult.enabled("Tier 2A fixtures present");
                }
            }
            return ConditionEvaluationResult.disabled("no Tier 2A fixtures captured");
        }
    }
}

