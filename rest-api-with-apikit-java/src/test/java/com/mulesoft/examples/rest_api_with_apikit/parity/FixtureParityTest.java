package com.mulesoft.examples.rest_api_with_apikit.parity;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
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
import org.junit.jupiter.api.function.Executable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Tier 2A fixture completeness gate and replay of rest-api-with-apikit-java (AAP 0.7.2, D-023, D-073, D-464).
 *
 * <p>Fixtures are the top-level {@code <identity>.json} files of the test-classpath folder {@code fixtures/}. The
 * subfolder {@code fixtures/diagnostic/}, which receives the {@code POST /teams} conflict capture (D-008), is never
 * listed. {@link FixturesPresentCondition} disables the class before any Spring context starts while
 * {@code fixtures/SCENARIOS.txt} is absent, which is the committed state of this RAML-backed project, or while no
 * fixture file exists. A disabled class is reported as skipped and is never parity evidence.
 *
 * <p>When enabled, the class checks two things:
 * <ul>
 *   <li>{@link #fixtureSetMatchesScenarios()}: fixture names plus {@code UNCAPTURABLE.txt} identities equal
 *       {@code SCENARIOS.txt}, with no identity missing, extra or duplicated (D-073);</li>
 *   <li>{@link #replayFixtures()}: one dynamic test {@code "replay <identity>"} per fixture, which sends each
 *       recorded request to the application on its random port over a raw HTTP/1.1 socket and compares the status
 *       line read from the raw socket (D-010), every recorded header and the body byte for byte after
 *       {@code volatile} masking, plus the recorded {@code log} outputs.</li>
 * </ul>
 */
@ExtendWith(FixtureParityTest.FixturesPresentCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public class FixtureParityTest {

    /** Test-classpath folder of the Tier 2A fixtures. */
    private static final String FIXTURES_DIR = "fixtures/";

    /** Scenario identity list, one {@code <example>_<scenario>} identity per line. */
    private static final String SCENARIOS = "fixtures/SCENARIOS.txt";

    /** Uncapturable scenarios, one {@code identity — missing input} entry per line. */
    private static final String UNCAPTURABLE = "fixtures/UNCAPTURABLE.txt";

    /** Placeholder file of the fixtures folder; locates the folder and is never a fixture. */
    private static final String GITKEEP = "fixtures/.gitkeep";

    /** Replacement text of every {@code volatile} regex match, on both sides of a comparison. */
    private static final String MASK = "<volatile>";

    /** Separator between identity and missing input in {@code UNCAPTURABLE.txt}: space, U+2014, space. */
    private static final String UNCAPTURABLE_SEPARATOR = " \u2014 ";

    /** Reader of the fixture files. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** File name suffix of a fixture; the identity is the file name without it. */
    private static final String FIXTURE_SUFFIX = ".json";

    /** Disabled reason while {@code fixtures/SCENARIOS.txt} is absent (D-023). */
    private static final String SCENARIOS_ABSENT_REASON = "fixtures/SCENARIOS.txt absent; RAML-backed project, "
            + "parity from RamlContractTest (RAML DIFF: 1:1 (Tier 1)); D-023";

    /** Disabled reason while {@code fixtures/SCENARIOS.txt} exists and no fixture file does (AAP 0.7.2). */
    private static final String NO_FIXTURE_REASON = "no fixture captured; Tier 2A gate not opened (D-023)";

    /** Enabled reason. */
    private static final String ENABLED_REASON = "fixtures present";

    /** System property that enables the replay of {@code live} fixtures. */
    private static final String LIVE_PROPERTY = "parity.live";

    /** Project name used in failure messages. */
    private static final String PROJECT = "rest-api-with-apikit-java";

    /** Logger whose events are compared with the {@code log} outputs of a fixture. */
    private static final String APPLICATION_LOGGER = "com.mulesoft.examples.rest_api_with_apikit";

    /** Read timeout of the replay socket, in milliseconds. */
    private static final int SOCKET_TIMEOUT_MILLIS = 30000;

    /** Read buffer size of the replay socket, in bytes; the buffer doubles while a response outgrows it. */
    private static final int READ_BUFFER_BYTES = 65536;

    /** Recorded request headers not sent; the replay writes its own {@code Host} and body framing headers. */
    private static final Set<String> UNSENT_REQUEST_HEADERS =
            Set.of("host", "content-length", "expect", "transfer-encoding");

    /** {@code volatile} locations a fixture may name. */
    private static final Set<String> VOLATILE_LOCATIONS = Set.of("header", "body", "output", "log");

    /** End of an HTTP message head: CR LF CR LF. */
    private static final byte[] HEAD_END = {'\r', '\n', '\r', '\n'};

    /** Line end of HTTP framing: CR LF. */
    private static final byte[] LINE_END = {'\r', '\n'};

    @LocalServerPort
    private int port;

    /**
     * Class-level condition of {@link FixtureParityTest}, evaluated by JUnit before the Spring extension creates an
     * application context (AAP 0.7.2, D-021, D-023).
     *
     * <p>It reads only the test class loader and {@code java.nio.file}:
     * <ul>
     *   <li>{@code fixtures/SCENARIOS.txt} not on the classpath: disabled with
     *       {@value FixtureParityTest#SCENARIOS_ABSENT_REASON};</li>
     *   <li>fixtures folder not resolvable as a file path: disabled, the reason naming the resource URL;</li>
     *   <li>no top-level {@code *.json} file in the folder: disabled with
     *       {@value FixtureParityTest#NO_FIXTURE_REASON};</li>
     *   <li>otherwise enabled with {@value FixtureParityTest#ENABLED_REASON}.</li>
     * </ul>
     * A folder that cannot be listed raises {@link UncheckedIOException}.
     */
    public static class FixturesPresentCondition implements ExecutionCondition {

        /**
         * Evaluates the Tier 2A gate for the test class or method of {@code context}.
         *
         * @param context the extension context; its required test class supplies the class loader
         * @return disabled while the gate is not open, enabled otherwise
         * @throws UncheckedIOException if the fixtures folder cannot be listed
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            ClassLoader loader = context.getRequiredTestClass().getClassLoader();
            URL scenarios = loader.getResource(SCENARIOS);
            if (scenarios == null) {
                return ConditionEvaluationResult.disabled(SCENARIOS_ABSENT_REASON);
            }
            Optional<Path> directory = fixturesDirectory(loader);
            if (directory.isEmpty()) {
                return ConditionEvaluationResult.disabled(
                        "fixtures folder not resolvable as a file path: " + scenarios + " (D-023)");
            }
            List<Path> fixtures;
            try {
                fixtures = fixtureFiles(directory.get());
            } catch (IOException e) {
                throw new UncheckedIOException("cannot list " + directory.get(), e);
            }
            if (fixtures.isEmpty()) {
                return ConditionEvaluationResult.disabled(NO_FIXTURE_REASON);
            }
            return ConditionEvaluationResult.enabled(ENABLED_REASON);
        }
    }

    /**
     * Completeness gate: fixture names plus {@code UNCAPTURABLE.txt} identities equal {@code SCENARIOS.txt}
     * (AAP 0.7.2, D-073).
     *
     * <p>Checked, each failure listing the identities concerned:
     * <ul>
     *   <li>no identity occurs twice in {@code SCENARIOS.txt} (lines trimmed, blank lines skipped);</li>
     *   <li>the {@code scenario} field of every fixture equals its file name without {@code .json};</li>
     *   <li>no identity occurs twice among the fixture identities followed by the {@code UNCAPTURABLE.txt}
     *       identities;</li>
     *   <li>{@code missing fixtures}: the {@code SCENARIOS.txt} identities that neither source provides;</li>
     *   <li>{@code extra fixtures}: the provided identities that {@code SCENARIOS.txt} does not list.</li>
     * </ul>
     *
     * @throws IOException if a fixtures file cannot be read
     */
    @Test
    public void fixtureSetMatchesScenarios() throws Exception {
        Path directory = requiredFixturesDirectory();
        List<String> scenarios = scenarioIdentities(directory);

        List<String> fixtureIdentities = new ArrayList<>();
        List<String> scenarioFieldMismatches = new ArrayList<>();
        for (Path file : fixtureFiles(directory)) {
            String identity = identityOf(file);
            JsonNode scenario = readFixture(file).get("scenario");
            if (scenario == null || !scenario.isTextual() || !identity.equals(scenario.asText())) {
                scenarioFieldMismatches.add(identity + " (scenario field: " + scenario + ")");
            }
            fixtureIdentities.add(identity);
        }

        List<String> provided = new ArrayList<>(fixtureIdentities);
        provided.addAll(uncapturableIdentities(directory));

        Set<String> missing = new LinkedHashSet<>(scenarios);
        missing.removeAll(provided);
        Set<String> extra = new LinkedHashSet<>(provided);
        extra.removeAll(scenarios);
        List<String> duplicatedScenarios = repeated(scenarios);
        List<String> duplicatedProvided = repeated(provided);

        assertAll(
                () -> assertEquals(List.of(), duplicatedScenarios,
                        "duplicated identities in " + SCENARIOS + ": " + duplicatedScenarios),
                () -> assertEquals(List.of(), scenarioFieldMismatches,
                        "scenario field differs from the file name identity: " + scenarioFieldMismatches),
                () -> assertEquals(List.of(), duplicatedProvided,
                        "duplicated fixtures: " + duplicatedProvided),
                () -> assertEquals(Set.of(), missing, "missing fixtures: " + missing),
                () -> assertEquals(Set.of(), extra, "extra fixtures: " + extra));
    }

    /**
     * Replay: one dynamic test named {@code "replay " + identity} per top-level fixture file, in
     * {@code SCENARIOS.txt} order; fixtures that {@code SCENARIOS.txt} does not list follow, sorted by name
     * (AAP 0.7.2).
     *
     * <p>Each dynamic test runs {@link #replay(Path)}. A {@code live} fixture without {@code -Dparity.live=true} is
     * aborted with {@code "not replayed: <identity> (live fixture, -Dparity.live=true not set)"} and is reported as
     * aborted, never as passed.
     *
     * @return the dynamic tests
     * @throws IOException if a fixtures file cannot be read
     */
    @TestFactory
    public Stream<DynamicTest> replayFixtures() throws Exception {
        Path directory = requiredFixturesDirectory();
        List<String> scenarios = scenarioIdentities(directory);
        Map<String, Integer> rank = new HashMap<>();
        for (int i = 0; i < scenarios.size(); i++) {
            rank.putIfAbsent(scenarios.get(i), i);
        }
        List<Path> ordered = new ArrayList<>(fixtureFiles(directory));
        ordered.sort(Comparator.comparingInt(file -> rank.getOrDefault(identityOf(file), Integer.MAX_VALUE)));
        return ordered.stream()
                .map(file -> DynamicTest.dynamicTest("replay " + identityOf(file), () -> replay(file)));
    }

    /**
     * Replays one fixture file against the running application.
     *
     * <p>In order: the {@code replay} value is {@code embedded} or {@code live}; a {@code live} fixture without
     * {@code -Dparity.live=true} is aborted; a fixture carrying {@code trigger} fails; every {@code outputs} entry is
     * of kind {@code log}; every {@code volatile} regex compiles. The exchanges are the {@code steps} array when
     * present, else the single {@code request}/{@code response} pair; they are sent in order, verbatim, while a
     * Logback {@link ListAppender} on {@value #APPLICATION_LOGGER} collects the application's log events. Each
     * exchange is then compared by {@link #assertExchange}, and each {@code log} output with the collected
     * messages. {@code setup} entries are not executed; they are appended to every assertion message.
     *
     * @param file the fixture file
     * @throws IOException if the fixture cannot be read or the exchange fails on the socket
     */
    private void replay(Path file) throws IOException {
        String identity = identityOf(file);
        JsonNode fixture = readFixture(file);
        String setup = setupSuffix(identity, fixture);

        String replay = fixture.path("replay").asText("");
        if (!"embedded".equals(replay) && !"live".equals(replay)) {
            fail(identity + ": replay must be embedded or live, found " + fixture.get("replay") + setup);
        }
        if ("live".equals(replay) && !Boolean.getBoolean(LIVE_PROPERTY)) {
            Assumptions.abort("not replayed: " + identity + " (live fixture, -Dparity.live=true not set)");
        }
        JsonNode trigger = fixture.get("trigger");
        if (trigger != null && !trigger.isNull()) {
            fail(identity + ": trigger fixture; " + PROJECT + " replays HTTP fixtures only" + setup);
        }
        List<JsonNode> logOutputs = logOutputs(identity, fixture, setup);
        Map<String, List<Pattern>> masks = volatileMasks(identity, fixture, setup);
        List<JsonNode> exchanges = exchanges(identity, fixture, setup);

        List<byte[]> received = new ArrayList<>(exchanges.size());
        Logger logger = (Logger) LoggerFactory.getLogger(APPLICATION_LOGGER);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.setName(FixtureParityTest.class.getSimpleName() + ":" + identity);
        appender.start();
        logger.addAppender(appender);
        try {
            for (int i = 0; i < exchanges.size(); i++) {
                String where = exchangeLabel(identity, exchanges.size(), i);
                received.add(send(where, setup, exchanges.get(i).get("request")));
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
        List<String> logged = loggedMessages(appender);

        for (int i = 0; i < exchanges.size(); i++) {
            String where = exchangeLabel(identity, exchanges.size(), i);
            assertExchange(where, setup, exchanges.get(i), received.get(i), masks);
        }
        List<Pattern> logMasks = masks.getOrDefault("log", List.of());
        List<String> replayedMessages = mask(logged, logMasks);
        for (JsonNode output : logOutputs) {
            List<String> recordedMessages = mask(logMessages(identity, output, setup), logMasks);
            assertEquals(recordedMessages, replayedMessages, identity + ": log output" + setup);
        }
    }

    /**
     * Sends one recorded request to {@code localhost:<port>} over a raw HTTP/1.1 socket and reads one response with
     * {@link #readResponse} (D-010).
     *
     * <p>Written bytes: the request line {@code METHOD SP path[?query] SP HTTP/1.1} ({@code ?query} only when
     * {@code query} is non-empty); {@code Host: localhost:<port>}; every recorded header in recorded order except
     * {@code Host}, {@code Content-Length}, {@code Expect} and {@code Transfer-Encoding} (names compared ignoring
     * case); {@code Content-Length: <n>} when the decoded body is non-empty; a blank line; then the body decoded from
     * {@code bodyBase64}, unchanged. Each line ends with CR LF and the head is written as ISO-8859-1 bytes. The
     * socket read timeout is 30 s; the socket is closed after the response.
     *
     * @param where   the fixture identity, with the step number for a {@code steps} journey
     * @param setup   the {@code setup} suffix of assertion messages
     * @param request the recorded {@code request} object
     * @return the bytes of the response, interim 1xx blocks included
     * @throws IOException if connecting, writing or reading fails, the read timeout included
     */
    private byte[] send(String where, String setup, JsonNode request) throws IOException {
        String method = singleLine(where, setup, "request.method", request.get("method").asText());
        String path = singleLine(where, setup, "request.path", request.get("path").asText());
        String query = singleLine(where, setup, "request.query", optionalText(where, setup, request, "query"));
        byte[] body = base64(where, setup, request, "bodyBase64");

        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path);
        if (!query.isEmpty()) {
            head.append('?').append(query);
        }
        head.append(" HTTP/1.1\r\n");
        head.append("Host: localhost:").append(port).append("\r\n");
        JsonNode headers = request.get("headers");
        if (headers != null && !headers.isNull()) {
            if (!headers.isObject()) {
                fail(where + ": request.headers must be an object, found " + headers + setup);
            }
            Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> header = fields.next();
                String name = singleLine(where, setup, "request header name", header.getKey());
                if (UNSENT_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String value = singleLine(where, setup, "request header " + name, header.getValue().asText());
                head.append(name).append(": ").append(value).append("\r\n");
            }
        }
        if (body.length > 0) {
            head.append("Content-Length: ").append(body.length).append("\r\n");
        }
        // The replay adds no Connection header; a recorded Connection header is sent as recorded, and
        // readResponse ends the read at the response's framing (D-464).
        head.append("\r\n");

        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(body);
            out.flush();
            return readResponse(socket.getInputStream(), method);
        }
    }

    /**
     * Reads one HTTP/1.1 response from {@code in}: the bytes up to the end of the final response's framing, or up
     * to end of stream.
     *
     * <p>Interim 1xx blocks are kept and read past. The final response ends where {@link #responseEnd} places it;
     * a response without length framing, or a stream that ends early, is read to end of stream. Bytes after the end
     * are not returned.
     *
     * @param in     the socket input stream
     * @param method the request method
     * @return the response bytes
     * @throws IOException if reading fails, the read timeout included, or the response exceeds 1 GiB
     */
    private static byte[] readResponse(InputStream in, String method) throws IOException {
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int length = 0;
        while (true) {
            int end = responseEnd(buffer, length, method);
            if (end >= 0) {
                return Arrays.copyOf(buffer, end);
            }
            if (length == buffer.length) {
                if (buffer.length > (1 << 29)) {
                    throw new IOException("replayed response exceeds " + buffer.length + " bytes");
                }
                buffer = Arrays.copyOf(buffer, buffer.length * 2);
            }
            int read = in.read(buffer, length, buffer.length - length);
            if (read < 0) {
                return Arrays.copyOf(buffer, length);
            }
            length += read;
        }
    }

    /**
     * End of the final response in {@code data[0, length)}.
     *
     * <p>Leading 1xx blocks are passed over. The final response ends after its head for a {@code HEAD} request and
     * for status 204 and 304; after the zero-size chunk and the trailer section when a {@code Transfer-Encoding}
     * value lists {@code chunked}; after {@code Content-Length} body bytes when that header is present. A response
     * with neither header ends at end of stream. A {@code Content-Length} or chunk framing that does not parse ends
     * the response at {@code length}; {@link #replayBody} then reports it.
     *
     * @param data   the bytes received
     * @param length the number of bytes received
     * @param method the request method
     * @return the index after the final response; {@code -1} while more bytes are needed or end of stream decides
     */
    private static int responseEnd(byte[] data, int length, String method) {
        int start = 0;
        while (true) {
            int headEnd = indexOf(data, HEAD_END, start, length);
            if (headEnd < 0) {
                return -1;
            }
            List<String> head = headLines(data, start, headEnd);
            int bodyStart = headEnd + HEAD_END.length;
            int status = head.isEmpty() ? -1 : statusCode(head.get(0));
            if (status / 100 == 1) {
                start = bodyStart;
                continue;
            }
            if ("HEAD".equalsIgnoreCase(method) || status == 204 || status == 304) {
                return bodyStart;
            }
            if (chunked(head)) {
                return chunkedEnd(data, bodyStart, length);
            }
            List<String> lengths = headerValues(head, "Content-Length");
            if (lengths.isEmpty()) {
                return -1;
            }
            if (!lengths.get(0).matches("\\d{1,18}")) {
                return length;
            }
            long contentLength = Long.parseLong(lengths.get(0));
            return contentLength <= length - bodyStart ? bodyStart + (int) contentLength : -1;
        }
    }

    /**
     * End of a chunked body that starts at {@code data[from]}: after the zero-size chunk line and the trailer
     * section, which ends with an empty line.
     *
     * @param data the bytes received
     * @param from the first byte of the chunked body
     * @param to   the number of bytes received
     * @return the index after the body; {@code to} when a chunk size line does not parse or chunk data is not
     *         followed by CR LF; {@code -1} while more bytes are needed
     */
    private static int chunkedEnd(byte[] data, int from, int to) {
        int position = from;
        while (true) {
            int lineEnd = indexOf(data, LINE_END, position, to);
            if (lineEnd < 0) {
                return -1;
            }
            String sizeLine = new String(data, position, lineEnd - position, StandardCharsets.ISO_8859_1);
            int extension = sizeLine.indexOf(';');
            String hex = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).strip();
            if (!hex.matches("[0-9A-Fa-f]{1,15}")) {
                return to;
            }
            long size = Long.parseLong(hex, 16);
            position = lineEnd + LINE_END.length;
            if (size == 0) {
                while (true) {
                    int trailerEnd = indexOf(data, LINE_END, position, to);
                    if (trailerEnd < 0) {
                        return -1;
                    }
                    if (trailerEnd == position) {
                        return trailerEnd + LINE_END.length;
                    }
                    position = trailerEnd + LINE_END.length;
                }
            }
            if (size > to - position - (long) LINE_END.length) {
                return -1;
            }
            position += (int) size;
            if (data[position] != '\r' || data[position + 1] != '\n') {
                return to;
            }
            position += LINE_END.length;
        }
    }

    /**
     * Compares one replayed response with its recording.
     *
     * <p>Recorded side: the status line is {@code response.statusLine} without trailing CR/LF; the header lines come
     * from {@code response.rawHeaders}; the body is {@code response.bodyBase64} decoded. Both strings are taken as
     * the UTF-8 bytes of their JSON text. Replayed side: the bytes read from the socket, parsed by
     * {@link #splitMessage(byte[])} and framed by {@link #replayBody}. Checked, after {@code volatile} masking:
     * <ul>
     *   <li>the status line, after the {@code header} masks;</li>
     *   <li>for every header name of the recording (ignoring case), the ordered list of its {@code Name: value} lines,
     *       after the {@code header} masks; replayed headers the recording lacks are not compared;</li>
     *   <li>the body: byte arrays when no {@code body} mask exists, otherwise both bodies as ISO-8859-1 text (one char
     *       per byte) after the {@code body} masks. No JSON or XML normalisation is applied.</li>
     * </ul>
     *
     * @param where    the fixture identity, with the step number for a {@code steps} journey
     * @param setup    the {@code setup} suffix of assertion messages
     * @param exchange the recorded {@code request}/{@code response} pair
     * @param received the bytes read from the socket
     * @param masks    the compiled {@code volatile} regexes by location
     */
    private static void assertExchange(String where, String setup, JsonNode exchange, byte[] received,
            Map<String, List<Pattern>> masks) {
        JsonNode request = exchange.get("request");
        JsonNode response = exchange.get("response");
        List<Pattern> headerMasks = masks.getOrDefault("header", List.of());
        List<Pattern> bodyMasks = masks.getOrDefault("body", List.of());

        String recordedStatus = stripLineEnd(utf8AsLatin1(requiredText(where, setup, response, "statusLine")));
        List<String> recordedHead = splitMessage(
                requiredText(where, setup, response, "rawHeaders").getBytes(StandardCharsets.UTF_8)).getKey();
        byte[] recordedBody = base64(where, setup, response, "bodyBase64");

        Map.Entry<List<String>, byte[]> replayed = splitMessage(received);
        List<String> replayHead = replayed.getKey();
        if (replayHead.isEmpty() || replayHead.get(0).isEmpty()) {
            fail(where + ": no HTTP response received (" + received.length + " bytes)" + setup);
        }
        String replayStatus = replayHead.get(0);
        byte[] replayBody = replayBody(where, setup, request.get("method").asText(), replayHead, replayed.getValue());

        Map<String, List<String>> recordedHeaders = headerLinesByName(recordedHead, headerMasks);
        Map<String, List<String>> replayHeaders = headerLinesByName(replayHead, headerMasks);

        List<Executable> checks = new ArrayList<>();
        checks.add(() -> assertEquals(mask(recordedStatus, headerMasks), mask(replayStatus, headerMasks),
                where + ": status line" + setup));
        for (Map.Entry<String, List<String>> header : recordedHeaders.entrySet()) {
            checks.add(() -> assertEquals(header.getValue(), replayHeaders.getOrDefault(header.getKey(), List.of()),
                    where + ": header " + header.getKey() + setup));
        }
        if (bodyMasks.isEmpty()) {
            checks.add(() -> assertArrayEquals(recordedBody, replayBody, where + ": body" + setup));
        } else {
            checks.add(() -> assertEquals(mask(latin1(recordedBody), bodyMasks), mask(latin1(replayBody), bodyMasks),
                    where + ": body" + setup));
        }
        assertAll(where, checks.stream());
    }

    /**
     * Splits an HTTP message at the first CR LF CR LF into its head lines and the bytes after the head.
     *
     * <p>Leading blocks whose status code is 1xx are skipped. The head is read as ISO-8859-1 text, split into lines
     * at LF with any CR before it removed, and trailing empty lines are dropped; the first line is the status line.
     * Without CR LF CR LF the whole input is the head and no byte follows it. Used for the replayed bytes and for
     * the recorded {@code rawHeaders} alike.
     *
     * @param raw the message bytes
     * @return the head lines as key, the bytes after the head as value
     */
    private static Map.Entry<List<String>, byte[]> splitMessage(byte[] raw) {
        int start = 0;
        while (true) {
            int end = indexOf(raw, HEAD_END, start, raw.length);
            List<String> lines = headLines(raw, start, end < 0 ? raw.length : end);
            if (end >= 0 && !lines.isEmpty() && statusCode(lines.get(0)) / 100 == 1) {
                start = end + HEAD_END.length;
                continue;
            }
            byte[] rest = end < 0 ? new byte[0] : Arrays.copyOfRange(raw, end + HEAD_END.length, raw.length);
            return Map.entry(lines, rest);
        }
    }

    /**
     * Lines of {@code raw[from, to)} read as ISO-8859-1 text, split at LF, each without trailing CR, with trailing
     * empty lines dropped.
     *
     * @param raw  the message bytes
     * @param from first byte of the head
     * @param to   end of the head, exclusive
     * @return the head lines; empty for an empty range
     */
    private static List<String> headLines(byte[] raw, int from, int to) {
        List<String> lines = new ArrayList<>();
        for (String line : new String(raw, from, to - from, StandardCharsets.ISO_8859_1).split("\n", -1)) {
            lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
        }
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /**
     * Status code of a status line {@code HTTP-version SP code SP reason}.
     *
     * @param statusLine the status line
     * @return the three-digit code, or {@code -1} when the line holds none
     */
    private static int statusCode(String statusLine) {
        String[] parts = statusLine.split(" ", 3);
        if (parts.length >= 2 && parts[1].matches("\\d{3}")) {
            return Integer.parseInt(parts[1]);
        }
        return -1;
    }

    /**
     * Header lines of a parsed head, as {@code Name: value} with the leading spaces and tabs of the value and all
     * trailing whitespace removed, after the {@code header} masks, grouped by lower-cased name in order of first
     * appearance. The status line (first line) is not included; a line without a colon is kept whole as its own
     * name.
     *
     * @param head  the head lines, status line first
     * @param masks the {@code header} masks
     * @return the masked lines of each header name, in received order
     */
    private static Map<String, List<String>> headerLinesByName(List<String> head, List<Pattern> masks) {
        Map<String, List<String>> byName = new LinkedHashMap<>();
        for (int i = 1; i < head.size(); i++) {
            String line = head.get(i);
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            String name = colon < 0 ? line.strip() : line.substring(0, colon);
            String normalized = colon < 0 ? name : name + ": " + headerValue(line.substring(colon + 1));
            byName.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                    .add(mask(normalized, masks));
        }
        return byName;
    }

    /**
     * Unmasked values of every header named {@code name} (ignoring case), leading spaces and tabs and trailing
     * whitespace removed.
     *
     * @param head the head lines, status line first
     * @param name the header name
     * @return the values in received order
     */
    private static List<String> headerValues(List<String> head, String name) {
        List<String> values = new ArrayList<>();
        for (int i = 1; i < head.size(); i++) {
            String line = head.get(i);
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                values.add(headerValue(line.substring(colon + 1)));
            }
        }
        return values;
    }

    /**
     * Header value text after the colon, without leading spaces and tabs and without trailing whitespace.
     *
     * @param afterColon the text after the colon
     * @return the value
     */
    private static String headerValue(String afterColon) {
        int start = 0;
        while (start < afterColon.length() && (afterColon.charAt(start) == ' ' || afterColon.charAt(start) == '\t')) {
            start++;
        }
        return afterColon.substring(start).stripTrailing();
    }

    /**
     * Body of a replayed response, framed from its head.
     *
     * <p>Empty for a {@code HEAD} request and for status 1xx, 204 and 304. Otherwise de-chunked when a
     * {@code Transfer-Encoding} value lists {@code chunked}; else the first {@code Content-Length} bytes when that
     * header is present; else every remaining byte.
     *
     * @param where  the fixture identity, with the step number for a {@code steps} journey
     * @param setup  the {@code setup} suffix of assertion messages
     * @param method the request method
     * @param head   the replayed head lines, status line first
     * @param rest   the bytes after the head
     * @return the body bytes
     */
    private static byte[] replayBody(String where, String setup, String method, List<String> head, byte[] rest) {
        int status = statusCode(head.get(0));
        if ("HEAD".equalsIgnoreCase(method) || status / 100 == 1 || status == 204 || status == 304) {
            return new byte[0];
        }
        if (chunked(head)) {
            return dechunk(where, setup, rest);
        }
        List<String> lengths = headerValues(head, "Content-Length");
        if (!lengths.isEmpty()) {
            long length = parseNumber(where, setup, "Content-Length", lengths.get(0), 10);
            if (length > rest.length) {
                fail(where + ": replayed body holds " + rest.length + " bytes, Content-Length is " + length + setup);
            }
            return Arrays.copyOf(rest, (int) length);
        }
        return rest;
    }

    /**
     * Whether a {@code Transfer-Encoding} value of {@code head} lists the {@code chunked} coding (ignoring case).
     *
     * @param head the head lines, status line first
     * @return {@code true} for a chunked body
     */
    private static boolean chunked(List<String> head) {
        for (String value : headerValues(head, "Transfer-Encoding")) {
            for (String coding : value.split(",")) {
                if ("chunked".equalsIgnoreCase(coding.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decodes a chunked body: each chunk is a hexadecimal size, optional {@code ;} extensions, CR LF, the data and
     * CR LF; size 0 ends the body and any trailer after it is discarded.
     *
     * @param where the fixture identity, with the step number for a {@code steps} journey
     * @param setup the {@code setup} suffix of assertion messages
     * @param data  the bytes after the head
     * @return the concatenated chunk data
     */
    private static byte[] dechunk(String where, String setup, byte[] data) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int position = 0;
        while (true) {
            int lineEnd = indexOf(data, LINE_END, position, data.length);
            if (lineEnd < 0) {
                fail(where + ": chunked body ends without a chunk size line" + setup);
            }
            String sizeLine = new String(data, position, lineEnd - position, StandardCharsets.ISO_8859_1);
            int extension = sizeLine.indexOf(';');
            String hex = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).strip();
            long size = parseNumber(where, setup, "chunk size", hex, 16);
            position = lineEnd + LINE_END.length;
            if (size == 0) {
                return body.toByteArray();
            }
            if (size > data.length - position - (long) LINE_END.length) {
                fail(where + ": chunk of " + size + " bytes exceeds the received data" + setup);
            }
            body.write(data, position, (int) size);
            position += (int) size;
            if (data[position] != '\r' || data[position + 1] != '\n') {
                fail(where + ": chunk data not followed by CR LF" + setup);
            }
            position += LINE_END.length;
        }
    }

    /**
     * Parses a non-negative number of an HTTP framing field.
     *
     * @param where the fixture identity, with the step number for a {@code steps} journey
     * @param setup the {@code setup} suffix of assertion messages
     * @param field the framing field, for the failure message
     * @param text  the number text
     * @param radix 10 or 16
     * @return the number
     */
    private static long parseNumber(String where, String setup, String field, String text, int radix) {
        try {
            long value = Long.parseLong(text, radix);
            if (value < 0) {
                return fail(where + ": negative " + field + " " + text + setup);
            }
            return value;
        } catch (NumberFormatException e) {
            return fail(where + ": " + field + " is not a number: " + text + setup, e);
        }
    }

    /**
     * First index of {@code pattern} in {@code data[from, to)}.
     *
     * @param data    the bytes searched
     * @param pattern the bytes looked for
     * @param from    the first index searched
     * @param to      the end of the searched range, exclusive
     * @return the index, or {@code -1} when absent
     */
    private static int indexOf(byte[] data, byte[] pattern, int from, int to) {
        outer:
        for (int i = Math.max(from, 0); i <= to - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Applies every mask to {@code text}: each match is replaced by {@value #MASK}.
     *
     * @param text  the text
     * @param masks the compiled regexes, applied in fixture order
     * @return the masked text
     */
    private static String mask(String text, List<Pattern> masks) {
        String masked = text;
        for (Pattern pattern : masks) {
            masked = pattern.matcher(masked).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return masked;
    }

    /**
     * Applies every mask to each text of {@code texts}.
     *
     * @param texts the texts
     * @param masks the compiled regexes, applied in fixture order
     * @return the masked texts, in order
     */
    private static List<String> mask(List<String> texts, List<Pattern> masks) {
        return texts.stream().map(text -> mask(text, masks)).collect(Collectors.toList());
    }

    /**
     * Bytes as ISO-8859-1 text, one char per byte.
     *
     * @param bytes the bytes
     * @return the text
     */
    private static String latin1(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    /**
     * UTF-8 bytes of a JSON string value, as ISO-8859-1 text (one char per byte).
     *
     * @param text the JSON string value
     * @return the text
     */
    private static String utf8AsLatin1(String text) {
        return latin1(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Text without its trailing CR and LF characters.
     *
     * @param text the text
     * @return the text up to its last character that is neither CR nor LF
     */
    private static String stripLineEnd(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\r' || text.charAt(end - 1) == '\n')) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * A string field that must be present.
     *
     * @param where the fixture identity, with the step number for a {@code steps} journey
     * @param setup the {@code setup} suffix of assertion messages
     * @param node  the object holding the field
     * @param field the field name
     * @return the field's text
     */
    private static String requiredText(String where, String setup, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            return fail(where + ": " + field + " must be a string, found " + value + setup);
        }
        return value.asText();
    }

    /**
     * A string field that may be absent or null.
     *
     * @param where the fixture identity, with the step number for a {@code steps} journey
     * @param setup the {@code setup} suffix of assertion messages
     * @param node  the object holding the field
     * @param field the field name
     * @return the field's text; empty when absent or null
     */
    private static String optionalText(String where, String setup, JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return "";
        }
        if (!value.isTextual()) {
            return fail(where + ": " + field + " must be a string, found " + value + setup);
        }
        return value.asText();
    }

    /**
     * A request-head component that holds neither CR nor LF.
     *
     * @param where the fixture identity, with the step number for a {@code steps} journey
     * @param setup the {@code setup} suffix of assertion messages
     * @param label the component, for the failure message
     * @param text  the component text
     * @return {@code text}
     */
    private static String singleLine(String where, String setup, String label, String text) {
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            return fail(where + ": " + label + " holds CR or LF" + setup);
        }
        return text;
    }

    /**
     * Bytes of a base64 field, decoded with {@link Base64#getDecoder()}.
     *
     * @param where the fixture identity, with the step number for a {@code steps} journey
     * @param setup the {@code setup} suffix of assertion messages
     * @param node  the object holding the field
     * @param field the field name
     * @return the decoded bytes; empty when the field is absent, null or empty
     */
    private static byte[] base64(String where, String setup, JsonNode node, String field) {
        String text = optionalText(where, setup, node, field);
        try {
            return Base64.getDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return fail(where + ": " + field + " is not base64: " + e.getMessage() + setup, e);
        }
    }

    /**
     * The exchanges of a fixture: the {@code steps} array when present, else the fixture's own
     * {@code request}/{@code response} pair. Each exchange needs a {@code request} object with string
     * {@code method} and {@code path} and a {@code response} object.
     *
     * @param identity the fixture identity
     * @param fixture  the fixture
     * @param setup    the {@code setup} suffix of assertion messages
     * @return the exchanges, in replay order
     */
    private static List<JsonNode> exchanges(String identity, JsonNode fixture, String setup) {
        List<JsonNode> exchanges = new ArrayList<>();
        JsonNode steps = fixture.get("steps");
        if (steps != null && !steps.isNull()) {
            if (!steps.isArray() || steps.isEmpty()) {
                fail(identity + ": steps must be a non-empty array, found " + steps + setup);
            }
            steps.forEach(exchanges::add);
        } else if (fixture.hasNonNull("request") || fixture.hasNonNull("response")) {
            exchanges.add(fixture);
        } else {
            fail(identity + ": neither steps nor a request/response pair" + setup);
        }
        for (int i = 0; i < exchanges.size(); i++) {
            String where = exchangeLabel(identity, exchanges.size(), i);
            JsonNode request = exchanges.get(i).get("request");
            JsonNode response = exchanges.get(i).get("response");
            if (request == null || !request.isObject() || response == null || !response.isObject()) {
                fail(where + ": request and response must both be objects" + setup);
            }
            if (requiredText(where, setup, request, "method").isEmpty()
                    || requiredText(where, setup, request, "path").isEmpty()) {
                fail(where + ": request.method and request.path must not be empty" + setup);
            }
        }
        return exchanges;
    }

    /**
     * Label of an exchange in assertion messages: the identity, plus {@code step <n>} in a journey of several steps.
     *
     * @param identity the fixture identity
     * @param count    the number of exchanges
     * @param index    the zero-based exchange index
     * @return the label
     */
    private static String exchangeLabel(String identity, int count, int index) {
        return count == 1 ? identity : identity + " step " + (index + 1);
    }

    /**
     * The {@code outputs} entries of a fixture, each of kind {@code log}.
     *
     * <p>The kind of an entry is its {@code type} field, else its single top-level field name. Any other kind fails
     * with {@code "<identity>: output kind <kind> not replayable in rest-api-with-apikit-java"}.
     *
     * @param identity the fixture identity
     * @param fixture  the fixture
     * @param setup    the {@code setup} suffix of assertion messages
     * @return the {@code log} entries, in fixture order; empty when {@code outputs} is absent or null
     */
    private static List<JsonNode> logOutputs(String identity, JsonNode fixture, String setup) {
        JsonNode outputs = fixture.get("outputs");
        if (outputs == null || outputs.isNull()) {
            return List.of();
        }
        if (!outputs.isArray()) {
            return fail(identity + ": outputs must be an array, found " + outputs + setup);
        }
        List<JsonNode> logs = new ArrayList<>();
        for (JsonNode output : outputs) {
            String kind = outputKind(output);
            if (!"log".equals(kind)) {
                fail(identity + ": output kind " + kind + " not replayable in " + PROJECT + setup);
            }
            logs.add(output);
        }
        return logs;
    }

    /**
     * Kind of an {@code outputs} entry: its {@code type} field, else its single top-level field name.
     *
     * @param output the entry
     * @return the kind; the entry's JSON text when neither form applies
     */
    private static String outputKind(JsonNode output) {
        if (output.isObject()) {
            JsonNode type = output.get("type");
            if (type != null && type.isTextual()) {
                return type.asText();
            }
            if (output.size() == 1) {
                return output.fieldNames().next();
            }
        }
        return output.toString();
    }

    /**
     * Recorded messages of a {@code log} output: the {@code messages} array of the entry when its kind comes from
     * {@code type}, else of the object under its {@code log} field.
     *
     * @param identity the fixture identity
     * @param output   the {@code log} entry
     * @param setup    the {@code setup} suffix of assertion messages
     * @return the messages, in order
     */
    private static List<String> logMessages(String identity, JsonNode output, String setup) {
        JsonNode holder = output.get("type") != null && output.get("type").isTextual() ? output : output.get("log");
        JsonNode messages = holder == null ? null : holder.get("messages");
        if (messages == null || !messages.isArray()) {
            return fail(identity + ": log output needs a messages array, found " + output + setup);
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode message : messages) {
            if (!message.isTextual()) {
                fail(identity + ": log message must be a string, found " + message + setup);
            }
            texts.add(message.asText());
        }
        return texts;
    }

    /**
     * Formatted messages collected by {@code appender}, in order, read under the appender's lock.
     *
     * @param appender the detached appender
     * @return the messages
     */
    private static List<String> loggedMessages(ListAppender<ILoggingEvent> appender) {
        synchronized (appender) {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
        }
    }

    /**
     * The {@code volatile} entries of a fixture, each regex compiled once, grouped by {@code where}
     * ({@code header}, {@code body}, {@code output} or {@code log}).
     *
     * @param identity the fixture identity
     * @param fixture  the fixture
     * @param setup    the {@code setup} suffix of assertion messages
     * @return the compiled regexes by location, in fixture order
     */
    private static Map<String, List<Pattern>> volatileMasks(String identity, JsonNode fixture, String setup) {
        Map<String, List<Pattern>> masks = new HashMap<>();
        JsonNode entries = fixture.get("volatile");
        if (entries == null || entries.isNull()) {
            return masks;
        }
        if (!entries.isArray()) {
            return fail(identity + ": volatile must be an array, found " + entries + setup);
        }
        for (JsonNode entry : entries) {
            String location = requiredText(identity, setup, entry, "where");
            if (!VOLATILE_LOCATIONS.contains(location)) {
                fail(identity + ": volatile where must be one of " + VOLATILE_LOCATIONS + ", found " + location
                        + setup);
            }
            String regex = requiredText(identity, setup, entry, "regex");
            try {
                masks.computeIfAbsent(location, key -> new ArrayList<>()).add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                fail(identity + ": volatile regex does not compile: " + e.getMessage() + setup, e);
            }
        }
        return masks;
    }

    /**
     * Suffix appended to every assertion message of a fixture: {@code "; setup: [<entries>]"}, or empty when the
     * fixture has no {@code setup} entry. The entries are not executed.
     *
     * @param identity the fixture identity
     * @param fixture  the fixture
     * @return the suffix
     */
    private static String setupSuffix(String identity, JsonNode fixture) {
        JsonNode setup = fixture.get("setup");
        if (setup == null || setup.isNull() || (setup.isArray() && setup.isEmpty())) {
            return "";
        }
        if (!setup.isArray()) {
            return fail(identity + ": setup must be an array, found " + setup);
        }
        List<String> entries = new ArrayList<>();
        setup.forEach(entry -> entries.add(entry.isTextual() ? entry.asText() : entry.toString()));
        return "; setup: " + entries;
    }

    /**
     * The fixtures folder of this class's class loader; the test fails when it cannot be resolved.
     *
     * @return the folder
     */
    private static Path requiredFixturesDirectory() {
        Optional<Path> directory = fixturesDirectory(FixtureParityTest.class.getClassLoader());
        if (directory.isEmpty()) {
            return fail(FIXTURES_DIR + " not found on the test classpath as a file path");
        }
        return directory.get();
    }

    /**
     * Locates the fixtures folder: the parent of the {@code fixtures/SCENARIOS.txt} resource, else of the
     * {@code fixtures/.gitkeep} resource. {@code .gitkeep} only locates the folder and is never a fixture.
     *
     * @param loader the class loader the resources are looked up with
     * @return the folder; empty when neither resource exists, its URL protocol is not {@code file}, or its URL is
     *         not a file path
     */
    private static Optional<Path> fixturesDirectory(ClassLoader loader) {
        URL url = loader.getResource(SCENARIOS);
        if (url == null) {
            url = loader.getResource(GITKEEP);
        }
        if (url == null || !"file".equals(url.getProtocol())) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Paths.get(url.toURI()).getParent());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The fixture files of a fixtures folder: its direct regular-file children whose name ends with {@code .json},
     * sorted by name. Subfolders, {@code diagnostic/} included, are not entered; {@code .gitkeep},
     * {@code SCENARIOS.txt} and {@code UNCAPTURABLE.txt} are not fixtures.
     *
     * @param directory the fixtures folder
     * @return the fixture files; empty when {@code directory} is not a folder
     * @throws IOException if the folder cannot be listed
     */
    private static List<Path> fixtureFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(directory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(FIXTURE_SUFFIX))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Identity of a fixture file: its name without {@code .json}.
     *
     * @param file the fixture file
     * @return the identity
     */
    private static String identityOf(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - FIXTURE_SUFFIX.length());
    }

    /**
     * Reads a fixture file as one UTF-8 JSON object.
     *
     * @param file the fixture file
     * @return the fixture
     * @throws IOException if the file cannot be read
     */
    private static JsonNode readFixture(Path file) throws IOException {
        JsonNode fixture;
        try {
            fixture = MAPPER.readTree(Files.readAllBytes(file));
        } catch (JsonProcessingException e) {
            return fail(file.getFileName() + ": not valid JSON: " + e.getOriginalMessage(), e);
        }
        if (fixture == null || !fixture.isObject()) {
            return fail(file.getFileName() + ": fixture is not a JSON object");
        }
        return fixture;
    }

    /**
     * Identities of {@code SCENARIOS.txt}: its UTF-8 lines trimmed, blank lines skipped, in file order.
     *
     * @param directory the fixtures folder
     * @return the identities, duplicates kept
     * @throws IOException if the file cannot be read
     */
    private static List<String> scenarioIdentities(Path directory) throws IOException {
        Path file = fixturesFile(directory, SCENARIOS);
        if (!Files.isRegularFile(file)) {
            return fail(SCENARIOS + " not found in " + directory);
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(Collectors.toList());
    }

    /**
     * Identities of {@code UNCAPTURABLE.txt}: for each non-blank UTF-8 line, the text before the first
     * {@code " — "}, trimmed. A non-blank line without that separator, or with nothing before it, fails.
     *
     * @param directory the fixtures folder
     * @return the identities in file order, duplicates kept; empty when the file is absent
     * @throws IOException if the file cannot be read
     */
    private static List<String> uncapturableIdentities(Path directory) throws IOException {
        Path file = fixturesFile(directory, UNCAPTURABLE);
        if (!Files.exists(file)) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<String> identities = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            int separator = line.indexOf(UNCAPTURABLE_SEPARATOR);
            String identity = separator < 0 ? "" : line.substring(0, separator).trim();
            if (identity.isEmpty()) {
                fail(UNCAPTURABLE + " line " + (i + 1) + " is not 'identity" + UNCAPTURABLE_SEPARATOR
                        + "missing input': " + line);
            }
            identities.add(identity);
        }
        return identities;
    }

    /**
     * A file of the fixtures folder named by its classpath resource ({@code fixtures/<name>}).
     *
     * @param directory the fixtures folder
     * @param resource  the classpath resource name
     * @return the file path
     */
    private static Path fixturesFile(Path directory, String resource) {
        return directory.resolve(resource.substring(FIXTURES_DIR.length()));
    }

    /**
     * Identities that occur more than once, each listed once, in order of first occurrence.
     *
     * @param identities the identities
     * @return the repeated identities
     */
    private static List<String> repeated(List<String> identities) {
        Set<String> seen = new LinkedHashSet<>();
        Set<String> repeated = new LinkedHashSet<>();
        for (String identity : identities) {
            if (!seen.add(identity)) {
                repeated.add(identity);
            }
        }
        return new ArrayList<>(repeated);
    }
}
