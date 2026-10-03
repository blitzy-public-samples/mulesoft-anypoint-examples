package com.mulesoft.examples.hello_world.parity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tier 2A completeness gate and byte-level replay of the captured fixtures of hello-world-java (D-021, D-023,
 * D-081).
 *
 * <p>A fixture is a regular {@code <identity>.json} file directly inside the test-classpath folder
 * {@code fixtures/}; subfolders such as {@code diagnostic/} and the files {@code .gitkeep}, {@code SCENARIOS.txt}
 * and {@code UNCAPTURABLE.txt} are never fixtures. {@link FixturesPresent} disables the class before any Spring
 * application context starts while no fixture exists, and surefire then reports the class as skipped. A skipped
 * class is never parity evidence (D-073).
 *
 * <p>When at least one fixture exists:
 * <ul>
 *   <li>{@link #gateMatchesScenarioInventory()} checks that the fixture identities plus the identities of
 *       {@code UNCAPTURABLE.txt} equal the identities of {@code SCENARIOS.txt}, with none missing, extra or
 *       duplicated, and that each fixture's {@code scenario} field equals its file identity;</li>
 *   <li>{@link #replay()} yields one dynamic test {@code replay <identity>} per fixture. Each sends the recorded
 *       HTTP request over a raw socket to the application on its random port and compares the status line, every
 *       recorded header and the body bytes after {@code volatile} masking. A {@code live} fixture is aborted as
 *       {@code not replayed: <identity>} unless the system property {@code parity.live} is {@code true}.</li>
 * </ul>
 *
 * <p>Replay commands, from {@code hello-world-java/}:
 * <pre>
 * mvn -B test -Dtest=FixtureParityTest
 * mvn -B test -Dtest=FixtureParityTest -Dparity.live=true
 * </pre>
 */
@ExtendWith(FixtureParityTest.FixturesPresent.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class FixtureParityTest {

    /** Test-classpath folder holding the fixtures, {@code SCENARIOS.txt} and {@code UNCAPTURABLE.txt}. */
    private static final String FIXTURES_RESOURCE = "fixtures";

    /** File name suffix of a fixture; the identity is the file name without it. */
    private static final String FIXTURE_SUFFIX = ".json";

    /** Identity inventory: one {@code <example>_<scenario>} identity per line. */
    private static final String SCENARIOS_FILE = "SCENARIOS.txt";

    /**
     * Uncapturable scenarios: one entry per line, the identity, then {@link #UNCAPTURABLE_SEPARATOR}, then the
     * missing input.
     */
    private static final String UNCAPTURABLE_FILE = "UNCAPTURABLE.txt";

    /** Separator between identity and missing input in {@code UNCAPTURABLE.txt}: space, U+2014, space. */
    private static final String UNCAPTURABLE_SEPARATOR = " \u2014 ";

    /** Reason reported while no fixture file exists. */
    private static final String NOT_CAPTURED = "Tier 2A fixtures not captured";

    /** Reason reported while at least one fixture file exists. */
    private static final String PRESENT = "Tier 2A fixtures present";

    /** Failure message for a fixture that is not a single HTTP request/response pair without outputs. */
    private static final String UNSUPPORTED_KIND = "unsupported fixture kind for hello-world-java";

    /** System property whose value {@code true} enables the replay of {@code live} fixtures (D-081). */
    private static final String LIVE_PROPERTY = "parity.live";

    /** Replacement text of every {@code volatile} match, identical on the recorded and the live side. */
    private static final String MASK = "<volatile>";

    /** Read timeout of the replay socket, in milliseconds. */
    private static final int SOCKET_TIMEOUT_MILLIS = 30_000;

    /** Initial size of the response read buffer, in bytes; the buffer doubles while a response outgrows it. */
    private static final int READ_BUFFER_BYTES = 8192;

    /**
     * Lower-cased recorded request header names that are not sent; the replay writes its own {@code Host} and
     * {@code Content-Length}. A recorded {@code Connection} header is sent as recorded, no {@code Connection}
     * header is added, and the response is read up to the end of its message framing (D-586).
     */
    private static final Set<String> REPLACED_REQUEST_HEADERS = Set.of("host", "content-length");

    /** Chunk size token of a chunked body: one or more hexadecimal digits. */
    private static final Pattern CHUNK_SIZE = Pattern.compile("[0-9A-Fa-f]+");

    /** Leading zero digits of a chunk size token. */
    private static final Pattern LEADING_ZEROS = Pattern.compile("^0+");

    /** Maximum count of significant hexadecimal digits in a chunk size: at most 0xFFFFFFF bytes per chunk. */
    private static final int MAX_CHUNK_SIZE_DIGITS = 7;

    /** {@code Content-Length} value: one or more decimal digits. */
    private static final Pattern DECIMAL = Pattern.compile("[0-9]+");

    /** Maximum count of significant decimal digits in a {@code Content-Length} value. */
    private static final int MAX_CONTENT_LENGTH_DIGITS = 10;

    /** End of an HTTP message head: CR LF CR LF. */
    private static final byte[] HEAD_END = {'\r', '\n', '\r', '\n'};

    /** End of an HTTP framing line: CR LF. */
    private static final byte[] CRLF = {'\r', '\n'};

    /** Reader of the fixture files. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    int port;

    /**
     * Class-level condition that enables {@link FixtureParityTest} only while at least one fixture file exists
     * (D-021). JUnit evaluates it before {@code SpringExtension} builds the application context, so a disabled
     * class starts no context. It reads the file system only and uses no Spring type.
     */
    public static final class FixturesPresent implements ExecutionCondition {

        /**
         * Lists the test-classpath folder {@code fixtures/} without descending into subfolders.
         *
         * @param context the JUnit extension context of the evaluated class or method
         * @return {@code disabled("Tier 2A fixtures not captured")} when the folder is absent, is not a
         *         {@code file:} resource, or holds no regular {@code *.json} file; otherwise
         *         {@code enabled("Tier 2A fixtures present")}
         * @throws IllegalStateException naming the folder when its location cannot be converted to a path or the
         *                               folder cannot be listed; a broken folder is never reported as a skip
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            Optional<Path> directory = fixturesDirectory();
            if (directory.isEmpty()) {
                return ConditionEvaluationResult.disabled(NOT_CAPTURED);
            }
            boolean present;
            try (Stream<Path> entries = Files.list(directory.get())) {
                present = entries.anyMatch(FixtureParityTest::isFixtureFile);
            } catch (IOException e) {
                throw new IllegalStateException("fixtures directory " + directory.get() + " cannot be listed", e);
            }
            return present
                    ? ConditionEvaluationResult.enabled(PRESENT)
                    : ConditionEvaluationResult.disabled(NOT_CAPTURED);
        }
    }

    /**
     * Compares the fixture identities and the {@code UNCAPTURABLE.txt} identities with {@code SCENARIOS.txt}
     * (D-023). Every problem found is collected before the test fails with all of them, one per line:
     * <ul>
     *   <li>{@code SCENARIOS.txt missing};</li>
     *   <li>{@code duplicate in SCENARIOS.txt: <id>} and {@code duplicate in UNCAPTURABLE.txt: <id>};</li>
     *   <li>{@code fixture and uncapturable: <id>} for an identity with a fixture that is also uncapturable;</li>
     *   <li>{@code missing fixture: <id>} for an inventory identity with neither a fixture nor an uncapturable
     *       entry;</li>
     *   <li>{@code not in SCENARIOS.txt: <id>} for a fixture or uncapturable identity outside the inventory;</li>
     *   <li>{@code scenario field <value> in <id>.json} for a fixture whose {@code scenario} differs from its file
     *       identity.</li>
     * </ul>
     *
     * @throws IOException if a file of the fixtures folder cannot be read or a fixture is not JSON
     */
    @Test
    public void gateMatchesScenarioInventory() throws IOException {
        Path directory = requireFixturesDirectory();
        List<String> problems = new ArrayList<>();

        Path scenariosFile = directory.resolve(SCENARIOS_FILE);
        List<String> scenarios = new ArrayList<>();
        if (Files.isRegularFile(scenariosFile)) {
            scenarios.addAll(nonBlankTrimmedLines(scenariosFile));
        } else {
            problems.add(SCENARIOS_FILE + " missing");
        }

        List<Path> fixtureFiles = fixtureFiles(directory);
        List<String> fixtures = fixtureFiles.stream()
                .map(FixtureParityTest::identity)
                .collect(Collectors.toList());

        Path uncapturableFile = directory.resolve(UNCAPTURABLE_FILE);
        List<String> uncapturable = new ArrayList<>();
        if (Files.isRegularFile(uncapturableFile)) {
            for (String line : nonBlankTrimmedLines(uncapturableFile)) {
                uncapturable.add(uncapturableIdentity(line));
            }
        }

        for (String id : duplicates(scenarios)) {
            problems.add("duplicate in " + SCENARIOS_FILE + ": " + id);
        }
        for (String id : duplicates(uncapturable)) {
            problems.add("duplicate in " + UNCAPTURABLE_FILE + ": " + id);
        }
        for (String id : fixtures) {
            if (uncapturable.contains(id)) {
                problems.add("fixture and uncapturable: " + id);
            }
        }
        for (String id : new LinkedHashSet<>(scenarios)) {
            if (!fixtures.contains(id) && !uncapturable.contains(id)) {
                problems.add("missing fixture: " + id);
            }
        }
        Set<String> accounted = new LinkedHashSet<>(fixtures);
        accounted.addAll(uncapturable);
        for (String id : accounted) {
            if (!scenarios.contains(id)) {
                problems.add("not in " + SCENARIOS_FILE + ": " + id);
            }
        }
        for (Path file : fixtureFiles) {
            String id = identity(file);
            String scenario = MAPPER.readTree(file.toFile()).path("scenario").asText();
            if (!scenario.equals(id)) {
                problems.add("scenario field " + scenario + " in " + id + FIXTURE_SUFFIX);
            }
        }

        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    /**
     * Yields one dynamic test {@code replay <identity>} per fixture, in file name order (D-081).
     *
     * @return the replay tests; the fixture folder is listed once, before the first test runs
     * @throws IOException if the fixtures folder cannot be listed
     */
    @TestFactory
    public Stream<DynamicTest> replay() throws IOException {
        List<Path> files = fixtureFiles(requireFixturesDirectory());
        return files.stream().map(file -> {
            String identity = identity(file);
            return DynamicTest.dynamicTest("replay " + identity, () -> replayFixture(file, identity));
        });
    }

    /**
     * Replays one fixture against the running application and compares the live response with the recorded one.
     *
     * <p>Steps:
     * <ol>
     *   <li>a fixture carrying {@code steps} or {@code trigger}, a non-empty {@code outputs}, or no
     *       {@code request} or {@code response} fails with
     *       {@code unsupported fixture kind for hello-world-java};</li>
     *   <li>a {@code live} fixture is aborted as {@code not replayed: <identity>} unless {@code parity.live} is
     *       {@code true} (D-081);</li>
     *   <li>the recorded request is sent over a new connection with its own method, path, query, headers and body
     *       bytes, plus {@code Host: localhost:<port>} and, for a non-empty body, {@code Content-Length}; the
     *       recorded {@code Host} and {@code Content-Length} headers are not sent, and no {@code Connection}
     *       header is added (D-586);</li>
     *   <li>the response is read up to the end of its message (RFC 9112, section 6.3): no body for a response to
     *       HEAD or a 1xx, 204 or 304 status, the last chunk and trailer section of a chunked body, the
     *       {@code Content-Length} bytes of any other body, or every byte up to the connection close when
     *       neither header is present; the connection is then closed;</li>
     *   <li>a chunked live body is de-chunked;</li>
     *   <li>the {@code volatile} regexes mask the status line and header lines ({@code where} starting with
     *       {@code header}) and the body ({@code where} starting with {@code body}) on both sides;</li>
     *   <li>the status line must be equal, each recorded header name (case-insensitive) must carry the same
     *       ordered values on both sides, and the body bytes must be equal. Headers present only in the live
     *       response are not compared, and no header is excluded unless a {@code volatile} entry masks it.</li>
     * </ol>
     *
     * @param file     the fixture file
     * @param identity the fixture identity, its file name without {@code .json}
     * @throws IOException if the fixture cannot be read or the socket exchange fails
     */
    private void replayFixture(Path file, String identity) throws IOException {
        JsonNode fixture = MAPPER.readTree(Files.readAllBytes(file));
        if (fixture.has("steps")
                || fixture.has("trigger")
                || fixture.path("outputs").size() > 0
                || !fixture.has("request")
                || !fixture.has("response")) {
            fail(UNSUPPORTED_KIND);
        }
        if ("live".equals(fixture.path("replay").asText()) && !"true".equals(System.getProperty(LIVE_PROPERTY))) {
            Assumptions.abort("not replayed: " + identity);
        }

        JsonNode request = fixture.path("request");
        String method = text(request.path("method"));
        String target = text(request.path("path"));
        // A fixture without request.method or request.path fails by name, and no request is sent.
        if (method.isEmpty() || target.isEmpty()) {
            fail("request.method or request.path missing in " + identity + FIXTURE_SUFFIX);
        }
        byte[] liveResponse = exchange(requestBytes(request, method, target), method, identity);

        int headEnd = indexOf(liveResponse, liveResponse.length, HEAD_END, 0);
        if (headEnd < 0) {
            fail("response head of " + identity + " has no CR LF CR LF");
        }
        List<String> liveHead = headLines(liveResponse, headEnd);
        String liveStatus = liveHead.get(0);
        List<String> liveHeaderLines = liveHead.subList(1, liveHead.size());
        byte[] liveBody = Arrays.copyOfRange(liveResponse, headEnd + HEAD_END.length, liveResponse.length);
        if (isChunked(parseHeaders(liveHeaderLines)) && hasMessageBody(method, liveStatus)) {
            liveBody = dechunk(liveBody, identity);
        }

        JsonNode response = fixture.path("response");
        String recordedStatus = text(response.path("statusLine"));
        List<String> recordedHeaderLines = recordedHeaderLines(text(response.path("rawHeaders")));
        byte[] recordedBody = decodeBase64(text(response.path("bodyBase64")));

        JsonNode volatileEntries = fixture.path("volatile");
        List<Pattern> headerMasks = volatilePatterns(volatileEntries, "header");
        List<Pattern> bodyMasks = volatilePatterns(volatileEntries, "body");

        String expectedStatus = mask(recordedStatus, headerMasks);
        String actualStatus = mask(liveStatus, headerMasks);
        Map<String, List<String>> expectedHeaders =
                groupByName(parseHeaders(maskAll(recordedHeaderLines, headerMasks)));
        Map<String, List<String>> liveHeaders = groupByName(parseHeaders(maskAll(liveHeaderLines, headerMasks)));
        Map<String, List<String>> actualHeaders = new LinkedHashMap<>();
        for (String name : expectedHeaders.keySet()) {
            actualHeaders.put(name, liveHeaders.getOrDefault(name, List.of()));
        }
        byte[] expectedBody = maskBody(recordedBody, bodyMasks);
        byte[] actualBody = maskBody(liveBody, bodyMasks);

        assertAll("replay " + identity,
                () -> assertEquals(expectedStatus, actualStatus, "status line of " + identity),
                () -> assertEquals(expectedHeaders, actualHeaders, "recorded headers of " + identity),
                () -> assertArrayEquals(expectedBody, actualBody, "body of " + identity));
    }

    /**
     * Builds the request bytes: the head in ISO-8859-1 with CR LF line ends, then the decoded body.
     *
     * @param request the fixture's {@code request} object
     * @param method  the request method
     * @param target  the request path
     * @return the bytes written to the socket
     */
    private byte[] requestBytes(JsonNode request, String method, String target) {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(target);
        String query = text(request.path("query"));
        if (!query.isEmpty()) {
            head.append('?').append(query);
        }
        head.append(" HTTP/1.1\r\n");
        JsonNode headers = request.path("headers");
        if (headers.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = headers.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> header = it.next();
                if (!REPLACED_REQUEST_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    head.append(header.getKey()).append(": ").append(text(header.getValue())).append("\r\n");
                }
            }
        }
        head.append("Host: localhost:").append(port).append("\r\n");
        byte[] body = decodeBase64(text(request.path("bodyBase64")));
        if (body.length > 0) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        head.append("\r\n");
        byte[] headBytes = head.toString().getBytes(StandardCharsets.ISO_8859_1);
        byte[] bytes = Arrays.copyOf(headBytes, headBytes.length + body.length);
        System.arraycopy(body, 0, bytes, headBytes.length, body.length);
        return bytes;
    }

    /**
     * Sends {@code request} to {@code localhost:<port>} over a new connection, reads one response with a read
     * timeout of 30 seconds and closes the connection.
     *
     * @param request  the request bytes
     * @param method   the request method, which decides whether the response carries a body
     * @param identity the fixture identity named in a failure
     * @return the bytes of the response message, from its status line to the end of its body
     * @throws IOException if the connection, the write or the read fails or times out
     */
    private byte[] exchange(byte[] request, String method, String identity) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(request);
            out.flush();
            return readResponse(socket.getInputStream(), method, identity);
        }
    }

    /**
     * Reads one HTTP/1.1 response message from {@code in}, up to the end given by its framing (RFC 9112,
     * section 6.3): the end of the head for a response to HEAD or a 1xx, 204 or 304 status; the end of the
     * trailer section for a chunked body; the {@code Content-Length} bytes after the head otherwise; and the end
     * of the stream when the response has neither header. Bytes after the end of the message are not read.
     *
     * @param in       the connection's input stream
     * @param method   the request method
     * @param identity the fixture identity named in a failure
     * @return the message bytes; every byte up to the end of the stream when the stream ends before a head
     * @throws IOException if the read fails or times out
     */
    private static byte[] readResponse(InputStream in, String method, String identity) throws IOException {
        byte[] data = new byte[READ_BUFFER_BYTES];
        int length = 0;
        int bodyStart = -1;
        int messageEnd = -1;
        boolean chunked = false;
        boolean untilClose = false;
        while (true) {
            if (bodyStart < 0) {
                int headEnd = indexOf(data, length, HEAD_END, 0);
                if (headEnd >= 0) {
                    bodyStart = headEnd + HEAD_END.length;
                    List<String> head = headLines(data, headEnd);
                    List<Map.Entry<String, String>> headers = parseHeaders(head.subList(1, head.size()));
                    if (!hasMessageBody(method, head.get(0))) {
                        messageEnd = bodyStart;
                    } else if (isChunked(headers)) {
                        chunked = true;
                    } else {
                        int contentLength = contentLength(headers, Integer.MAX_VALUE - bodyStart, identity);
                        untilClose = contentLength < 0;
                        messageEnd = untilClose ? -1 : bodyStart + contentLength;
                    }
                }
            }
            if (chunked && messageEnd < 0) {
                messageEnd = parseChunked(data, length, bodyStart, null, identity);
            }
            if (messageEnd >= 0 && length >= messageEnd) {
                return Arrays.copyOf(data, messageEnd);
            }
            if (length == data.length) {
                data = Arrays.copyOf(data, Math.multiplyExact(data.length, 2));
            }
            int read = in.read(data, length, data.length - length);
            if (read < 0) {
                if (bodyStart >= 0 && !untilClose) {
                    return fail("response of " + identity + " ended after " + length
                            + " bytes, before the end of its message");
                }
                return Arrays.copyOf(data, length);
            }
            length += read;
        }
    }

    /**
     * Splits a response head into lines.
     *
     * @param data    the response bytes
     * @param headEnd the index of the CR LF CR LF that ends the head
     * @return the ISO-8859-1 lines of the head split at CR LF; the first is the status line
     */
    private static List<String> headLines(byte[] data, int headEnd) {
        return Arrays.asList(new String(data, 0, headEnd, StandardCharsets.ISO_8859_1).split("\r\n", -1));
    }

    /**
     * Returns the {@code Content-Length} of a response.
     *
     * @param headers  the parsed response headers
     * @param maximum  the largest accepted length
     * @param identity the fixture identity named in a failure
     * @return the length, or -1 when the response has no {@code Content-Length}; a response whose
     *         {@code Content-Length} values are not one and the same decimal number up to {@code maximum} fails
     */
    private static int contentLength(List<Map.Entry<String, String>> headers, int maximum, String identity) {
        String value = null;
        for (Map.Entry<String, String> header : headers) {
            if ("content-length".equalsIgnoreCase(header.getKey())) {
                if (value != null && !value.equals(header.getValue())) {
                    return fail("response of " + identity + " has conflicting Content-Length values");
                }
                value = header.getValue();
            }
        }
        if (value == null) {
            return -1;
        }
        String digits = LEADING_ZEROS.matcher(value).replaceFirst("");
        if (!DECIMAL.matcher(value).matches() || digits.length() > MAX_CONTENT_LENGTH_DIGITS
                || (!digits.isEmpty() && Long.parseLong(digits) > maximum)) {
            return fail("response of " + identity + " has the invalid Content-Length " + value);
        }
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    /**
     * Resolves the test-classpath folder {@code fixtures/}.
     *
     * @return the folder, or empty when it is absent or is not a {@code file:} resource
     * @throws IllegalStateException naming the folder when its URL cannot be converted to a path
     */
    private static Optional<Path> fixturesDirectory() {
        URL url = FixtureParityTest.class.getClassLoader().getResource(FIXTURES_RESOURCE);
        if (url == null || !"file".equals(url.getProtocol())) {
            return Optional.empty();
        }
        try {
            return Optional.of(Path.of(url.toURI()));
        } catch (URISyntaxException e) {
            throw new IllegalStateException("fixtures directory " + url + " cannot be resolved", e);
        }
    }

    /**
     * Resolves the test-classpath folder {@code fixtures/}, which {@link FixturesPresent} has found.
     *
     * @return the folder
     * @throws IllegalStateException when the folder is absent or is not a {@code file:} resource
     */
    private static Path requireFixturesDirectory() {
        return fixturesDirectory().orElseThrow(
                () -> new IllegalStateException("fixtures directory not found on the test classpath"));
    }

    /**
     * Lists the fixture files directly inside {@code directory}, sorted by path.
     *
     * @param directory the fixtures folder
     * @return the regular {@code *.json} files of the folder, excluding subfolders
     * @throws IOException if the folder cannot be listed
     */
    private static List<Path> fixtureFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(FixtureParityTest::isFixtureFile).sorted().collect(Collectors.toList());
        }
    }

    /**
     * Tells whether {@code path} is a fixture file.
     *
     * @param path a folder entry
     * @return {@code true} for a regular file whose name ends with {@code .json}
     */
    private static boolean isFixtureFile(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(FIXTURE_SUFFIX);
    }

    /**
     * Returns the identity of a fixture file.
     *
     * @param file a fixture file
     * @return the file name without {@code .json}
     */
    private static String identity(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - FIXTURE_SUFFIX.length());
    }

    /**
     * Reads a UTF-8 text file as trimmed lines, skipping blank ones.
     *
     * @param file the file
     * @return the non-blank lines, trimmed, in file order
     * @throws IOException if the file cannot be read or is not UTF-8
     */
    private static List<String> nonBlankTrimmedLines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Returns the identity of an {@code UNCAPTURABLE.txt} line.
     *
     * @param line a trimmed, non-blank line
     * @return the text before the first {@link #UNCAPTURABLE_SEPARATOR}, trimmed, or the whole line when it has
     *         no separator
     */
    private static String uncapturableIdentity(String line) {
        int separator = line.indexOf(UNCAPTURABLE_SEPARATOR);
        return separator < 0 ? line : line.substring(0, separator).trim();
    }

    /**
     * Returns the values that occur more than once in {@code values}.
     *
     * @param values the values in their original order
     * @return each repeated value once, in the order of its second occurrence
     */
    private static List<String> duplicates(List<String> values) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (String value : values) {
            if (!seen.add(value)) {
                repeated.add(value);
            }
        }
        return new ArrayList<>(repeated);
    }

    /**
     * Returns the text of a fixture value.
     *
     * @param node a fixture value
     * @return the empty string for a missing or JSON {@code null} value, otherwise {@link JsonNode#asText()}
     */
    private static String text(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? "" : node.asText();
    }

    /**
     * Decodes a Base64 fixture value.
     *
     * @param base64 the standard Base64 text, possibly empty
     * @return the decoded bytes; empty for empty text
     * @throws IllegalArgumentException if the text is not valid Base64
     */
    private static byte[] decodeBase64(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    /**
     * Returns the header lines of a recorded {@code rawHeaders} dump.
     *
     * @param rawHeaders the dump: a status line, then {@code Name: value} lines with CR LF ends, then an empty
     *                   line
     * @return every line after the first, without its trailing CR, skipping empty lines
     */
    private static List<String> recordedHeaderLines(String rawHeaders) {
        String[] lines = rawHeaders.split("\n", -1);
        List<String> headerLines = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].endsWith("\r") ? lines[i].substring(0, lines[i].length() - 1) : lines[i];
            if (!line.isEmpty()) {
                headerLines.add(line);
            }
        }
        return headerLines;
    }

    /**
     * Compiles the {@code volatile} regexes whose {@code where} starts with {@code location}.
     *
     * @param volatileEntries the fixture's {@code volatile} array; any other node yields no pattern
     * @param location        {@code header} or {@code body}
     * @return the patterns in fixture order
     * @throws java.util.regex.PatternSyntaxException if a selected regex is invalid
     */
    private static List<Pattern> volatilePatterns(JsonNode volatileEntries, String location) {
        List<Pattern> patterns = new ArrayList<>();
        for (JsonNode entry : volatileEntries) {
            if (text(entry.path("where")).startsWith(location)) {
                patterns.add(Pattern.compile(text(entry.path("regex"))));
            }
        }
        return patterns;
    }

    /**
     * Replaces every match of every pattern in {@code value} with {@link #MASK}, pattern by pattern.
     *
     * @param value    the text to mask
     * @param patterns the {@code volatile} patterns
     * @return the masked text
     */
    private static String mask(String value, List<Pattern> patterns) {
        String masked = value;
        for (Pattern pattern : patterns) {
            masked = pattern.matcher(masked).replaceAll(MASK);
        }
        return masked;
    }

    /**
     * Masks each line of {@code lines} with {@link #mask(String, List)}.
     *
     * @param lines    the header lines
     * @param patterns the {@code header} patterns
     * @return the masked lines, in order
     */
    private static List<String> maskAll(List<String> lines, List<Pattern> patterns) {
        return lines.stream().map(line -> mask(line, patterns)).collect(Collectors.toList());
    }

    /**
     * Masks a body through its ISO-8859-1 view, which maps each byte to one character and back unchanged.
     *
     * @param body     the body bytes
     * @param patterns the {@code body} patterns
     * @return {@code body} itself when there is no pattern, otherwise the masked bytes
     */
    private static byte[] maskBody(byte[] body, List<Pattern> patterns) {
        if (patterns.isEmpty()) {
            return body;
        }
        return mask(new String(body, StandardCharsets.ISO_8859_1), patterns).getBytes(StandardCharsets.ISO_8859_1);
    }

    /**
     * Parses header lines into names and values.
     *
     * @param lines the header lines
     * @return per line with a colon, the text before the first colon and the text after it with leading and
     *         trailing SP and HT removed; lines without a colon are skipped
     */
    private static List<Map.Entry<String, String>> parseHeaders(List<String> lines) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon >= 0) {
                headers.add(Map.entry(line.substring(0, colon), trimSpHt(line.substring(colon + 1))));
            }
        }
        return headers;
    }

    /**
     * Removes leading and trailing SP and HT characters.
     *
     * @param value the header value text
     * @return the value without surrounding SP and HT
     */
    private static String trimSpHt(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && (value.charAt(start) == ' ' || value.charAt(start) == '\t')) {
            start++;
        }
        while (end > start && (value.charAt(end - 1) == ' ' || value.charAt(end - 1) == '\t')) {
            end--;
        }
        return value.substring(start, end);
    }

    /**
     * Groups header values by lower-cased name.
     *
     * @param headers the parsed headers
     * @return lower-cased name to its values in message order, names in first-seen order
     */
    private static Map<String, List<String>> groupByName(List<Map.Entry<String, String>> headers) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : headers) {
            grouped.computeIfAbsent(header.getKey().toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                    .add(header.getValue());
        }
        return grouped;
    }

    /**
     * Tells whether a response uses chunked transfer coding.
     *
     * @param headers the parsed live response headers
     * @return {@code true} when a {@code Transfer-Encoding} value contains {@code chunked}, ignoring case
     */
    private static boolean isChunked(List<Map.Entry<String, String>> headers) {
        for (Map.Entry<String, String> header : headers) {
            if ("transfer-encoding".equalsIgnoreCase(header.getKey())
                    && header.getValue().toLowerCase(Locale.ROOT).contains("chunked")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a response carries a message body (RFC 9112, section 6.3).
     *
     * @param method     the request method
     * @param statusLine the live status line
     * @return {@code false} for a response to HEAD and for a 1xx, 204 or 304 status, otherwise {@code true}
     */
    private static boolean hasMessageBody(String method, String statusLine) {
        if ("HEAD".equalsIgnoreCase(method)) {
            return false;
        }
        String[] parts = statusLine.split(" ", 3);
        String status = parts.length > 1 ? parts[1] : "";
        return !(status.startsWith("1") || "204".equals(status) || "304".equals(status));
    }

    /**
     * Decodes a complete chunked body with {@link #parseChunked(byte[], int, int, ByteArrayOutputStream, String)}.
     *
     * @param body     the body bytes as received
     * @param identity the fixture identity named in a failure
     * @return the concatenated chunk data; a body that ends before its last chunk fails
     */
    private static byte[] dechunk(byte[] body, String identity) {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        if (parseChunked(body, body.length, 0, data, identity) < 0) {
            return fail("chunked body of " + identity + " ends before its last chunk");
        }
        return data.toByteArray();
    }

    /**
     * Parses a chunked body: a hexadecimal size line, whose {@code ;} extensions are ignored, then that many
     * bytes and CR LF, repeated until the size 0, then the trailer section up to an empty line. Trailer fields
     * are skipped. Malformed framing fails the replay.
     *
     * @param data     the bytes holding the body
     * @param length   the count of valid bytes in {@code data}
     * @param from     the index where the body starts
     * @param sink     receives the chunk data, or {@code null} when only the end is needed
     * @param identity the fixture identity named in a failure
     * @return the index after the empty line that ends the trailer section, or -1 when {@code data} does not yet
     *         hold the whole body
     */
    private static int parseChunked(
            byte[] data, int length, int from, ByteArrayOutputStream sink, String identity) {
        int position = from;
        while (true) {
            int lineEnd = indexOf(data, length, CRLF, position);
            if (lineEnd < 0) {
                return -1;
            }
            String sizeLine = new String(data, position, lineEnd - position, StandardCharsets.ISO_8859_1);
            int extension = sizeLine.indexOf(';');
            String sizeToken = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            String sizeDigits = LEADING_ZEROS.matcher(sizeToken).replaceFirst("");
            if (!CHUNK_SIZE.matcher(sizeToken).matches() || sizeDigits.length() > MAX_CHUNK_SIZE_DIGITS) {
                return fail("chunked body of " + identity + " has the invalid chunk size line " + sizeLine);
            }
            int size = sizeDigits.isEmpty() ? 0 : Integer.parseInt(sizeDigits, 16);
            position = lineEnd + CRLF.length;
            if (size == 0) {
                return trailerSectionEnd(data, length, position);
            }
            if (length - position < size + CRLF.length) {
                return -1;
            }
            if (data[position + size] != '\r' || data[position + size + 1] != '\n') {
                return fail("chunked body of " + identity + " has a chunk of " + size + " bytes without CR LF");
            }
            if (sink != null) {
                sink.write(data, position, size);
            }
            position += size + CRLF.length;
        }
    }

    /**
     * Finds the end of the trailer section of a chunked body: zero or more field lines, then an empty line.
     *
     * @param data   the bytes holding the body
     * @param length the count of valid bytes in {@code data}
     * @param from   the index after the CR LF of the last chunk's size line
     * @return the index after the empty line, or -1 when {@code data} does not yet hold it
     */
    private static int trailerSectionEnd(byte[] data, int length, int from) {
        int position = from;
        while (true) {
            int lineEnd = indexOf(data, length, CRLF, position);
            if (lineEnd < 0) {
                return -1;
            }
            if (lineEnd == position) {
                return lineEnd + CRLF.length;
            }
            position = lineEnd + CRLF.length;
        }
    }

    /**
     * Finds the first occurrence of {@code pattern} in the first {@code length} bytes of {@code data}, at or after
     * {@code from}.
     *
     * @param data    the bytes to search
     * @param length  the count of valid bytes in {@code data}
     * @param pattern the bytes to find
     * @param from    the first index to search
     * @return the index of the first match, or -1 when there is none
     */
    private static int indexOf(byte[] data, int length, byte[] pattern, int from) {
        for (int i = from; i <= length - pattern.length; i++) {
            int j = 0;
            while (j < pattern.length && data[i + j] == pattern[j]) {
                j++;
            }
            if (j == pattern.length) {
                return i;
            }
        }
        return -1;
    }
}
