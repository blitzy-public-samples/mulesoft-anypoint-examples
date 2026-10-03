package com.mulesoft.examples.testing_apikit_with_munit.parity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tier 2A completeness gate and fixture replay of testing-apikit-with-munit-java (AAP 0.7.2, D-023).
 *
 * <p>The fixtures directory is the parent of the test-classpath resource {@code fixtures/.gitkeep}. A
 * fixture is a regular file directly in that directory whose name ends with {@code .json}; its
 * identity is the file name without that suffix. Subdirectories, {@code .gitkeep},
 * {@code SCENARIOS.txt} and {@code UNCAPTURABLE.txt} are not fixtures.
 *
 * <p>JUnit's class-level condition {@code fixturesPresent} enables the class only while at least one
 * fixture exists (D-459). It is evaluated before {@code SpringExtension} creates the application
 * context, so a run without fixtures starts no context and reports the class as skipped. A skipped
 * class is never parity evidence; this RAML-backed project takes its parity from
 * {@code RamlContractTest} (AAP 0.7.4).
 *
 * <p>With fixtures present:
 * <ul>
 *   <li>{@link #fixtureSetMatchesScenarios()} checks that the fixture identities plus the identities of
 *       {@code UNCAPTURABLE.txt} equal the identities of {@code SCENARIOS.txt}, each exactly once;</li>
 *   <li>{@link #replayFixtures()} sends each fixture's recorded HTTP request to the application on its
 *       random port over a raw socket and compares the status line, the recorded headers and the body
 *       bytes with the recorded response, after the fixture's {@code volatile} masking (D-459). A
 *       {@code live} fixture in a run without {@code -Dparity.live=true} is aborted as not replayed
 *       and never counts as passing (D-073).</li>
 * </ul>
 *
 * <p>Replay commands, from {@code testing-apikit-with-munit-java/}:
 * <pre>{@code
 * mvn -B test -Dtest=FixtureParityTest
 * mvn -B test -Dtest=FixtureParityTest -Dparity.live=true
 * }</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIf("fixturesPresent")
class FixtureParityTest {

    /** Test-classpath resource whose parent directory is the fixtures directory. */
    private static final String FIXTURES_ANCHOR = "fixtures/.gitkeep";

    /** File name suffix of a fixture; the identity is the file name without it. */
    private static final String FIXTURE_SUFFIX = ".json";

    /** File of the fixtures directory listing one scenario identity per line. */
    private static final String SCENARIOS_FILE = "SCENARIOS.txt";

    /** File of the fixtures directory listing one {@code identity — missing input} entry per line. */
    private static final String UNCAPTURABLE_FILE = "UNCAPTURABLE.txt";

    /** Separator between identity and missing input in {@code UNCAPTURABLE.txt}: space, U+2014, space. */
    private static final String UNCAPTURABLE_SEPARATOR = " \u2014 ";

    /** Replacement written over every {@code volatile} regex match on both sides of a comparison. */
    private static final String VOLATILE_TOKEN = "<volatile>";

    /** System property that enables the replay of {@code live} fixtures. */
    private static final String LIVE_PROPERTY = "parity.live";

    /** Read timeout of the replay socket, in milliseconds. */
    private static final int SOCKET_TIMEOUT_MILLIS = 60_000;

    /** Lower-cased recorded request headers that the replay replaces with its own values. */
    private static final Set<String> REPLACED_REQUEST_HEADERS = Set.of("host", "content-length", "connection");

    /** Lower-cased recorded response headers that are not compared (D-459). */
    private static final Set<String> UNCOMPARED_RESPONSE_HEADERS = Set.of("date", "connection", "transfer-encoding");

    /** End of an HTTP message head: CR LF CR LF. */
    private static final byte[] HEAD_TERMINATOR = {'\r', '\n', '\r', '\n'};

    /** Line terminator of an HTTP message head and of a chunk-size line: CR LF. */
    private static final byte[] CRLF = {'\r', '\n'};

    /** Port of the application started by {@code @SpringBootTest} for this class. */
    @LocalServerPort
    int port;

    /**
     * Class-level condition of {@code @EnabledIf}: whether the fixtures directory holds at least one
     * fixture (AAP 0.7.2, D-459).
     *
     * @return {@code true} when the directory resolves and holds a regular {@code *.json} file directly;
     *         {@code false} when {@code fixtures/.gitkeep} is not on the test classpath, its location is
     *         not a file-system path, or the directory cannot be listed
     */
    private static boolean fixturesPresent() {
        Optional<Path> dir = fixturesDirectory();
        if (dir.isEmpty()) {
            return false;
        }
        try {
            return !fixtureFiles(dir.get()).isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolves the fixtures directory as the parent of the test-classpath resource
     * {@code fixtures/.gitkeep}.
     *
     * @return the directory; empty when the resource is absent or its URL is not a file-system path
     */
    private static Optional<Path> fixturesDirectory() {
        URL url = FixtureParityTest.class.getClassLoader().getResource(FIXTURES_ANCHOR);
        if (url == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Path.of(url.toURI()).getParent());
        } catch (URISyntaxException | IllegalArgumentException | FileSystemNotFoundException e) {
            return Optional.empty();
        }
    }

    /**
     * Lists the fixtures of a fixtures directory: its direct regular files whose name ends with
     * {@code .json}. Subdirectories are not entered.
     *
     * @param dir the fixtures directory
     * @return the fixture files sorted by file name
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> fixtureFiles(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(entry -> entry.getFileName().toString().endsWith(FIXTURE_SUFFIX))
                    .sorted(Comparator.comparing(entry -> entry.getFileName().toString()))
                    .collect(Collectors.toList());
        }
    }

    /**
     * Gate check: the fixture identities followed by the identities of {@code UNCAPTURABLE.txt},
     * taken as a multiset, equal the identities of {@code SCENARIOS.txt}, each present exactly once
     * (AAP 0.7.2, D-023).
     *
     * <p>{@code SCENARIOS.txt} contributes its trimmed non-blank lines, repeated lines kept; an absent
     * file contributes none (D-459). Each non-blank line of {@code UNCAPTURABLE.txt}, when the file
     * exists, contributes the trimmed text before its first {@code " — "}; a line without that
     * separator, or with no text before it, is a malformed line. The test fails once, listing the
     * sorted offenders of every category:
     * <ul>
     *   <li>{@code missing}: in {@code SCENARIOS.txt} and neither a fixture nor uncapturable;</li>
     *   <li>{@code extra}: a fixture or uncapturable identity not in {@code SCENARIOS.txt};</li>
     *   <li>{@code duplicated}: repeated in {@code SCENARIOS.txt}, or repeated among the fixture and
     *       uncapturable identities, such as a fixture that is also listed as uncapturable;</li>
     *   <li>{@code malformed UNCAPTURABLE lines}: the trimmed malformed lines.</li>
     * </ul>
     *
     * @throws IOException if a file of the fixtures directory cannot be read or listed
     */
    @Test
    void fixtureSetMatchesScenarios() throws IOException {
        Path dir = requireFixturesDirectory();

        Path scenariosFile = dir.resolve(SCENARIOS_FILE);
        boolean scenariosPresent = Files.isRegularFile(scenariosFile);
        List<String> scenarios = new ArrayList<>();
        if (scenariosPresent) {
            for (String line : Files.readAllLines(scenariosFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    scenarios.add(line.trim());
                }
            }
        }

        List<String> provided = new ArrayList<>();
        for (Path file : fixtureFiles(dir)) {
            String name = file.getFileName().toString();
            provided.add(name.substring(0, name.length() - FIXTURE_SUFFIX.length()));
        }

        List<String> malformed = new ArrayList<>();
        Path uncapturableFile = dir.resolve(UNCAPTURABLE_FILE);
        if (Files.isRegularFile(uncapturableFile)) {
            for (String line : Files.readAllLines(uncapturableFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                int separator = line.indexOf(UNCAPTURABLE_SEPARATOR);
                String identity = separator < 0 ? "" : line.substring(0, separator).trim();
                if (identity.isEmpty()) {
                    malformed.add(line.trim());
                } else {
                    provided.add(identity);
                }
            }
        }

        Set<String> duplicated = repeated(scenarios);
        duplicated.addAll(repeated(provided));
        Set<String> missing = new TreeSet<>(scenarios);
        missing.removeAll(provided);
        Set<String> extra = new TreeSet<>(provided);
        extra.removeAll(scenarios);
        malformed.sort(Comparator.naturalOrder());

        if (!scenariosPresent || !missing.isEmpty() || !extra.isEmpty() || !duplicated.isEmpty()
                || !malformed.isEmpty()) {
            Assertions.fail("Tier 2A fixture set does not equal fixtures/" + SCENARIOS_FILE + " (D-023): "
                    + (scenariosPresent ? "" : SCENARIOS_FILE + " absent; ")
                    + "missing: " + missing
                    + "; extra: " + extra
                    + "; duplicated: " + duplicated
                    + "; malformed UNCAPTURABLE lines: " + malformed);
        }
    }

    /**
     * Replays every fixture as one dynamic test named by its {@code scenario} member, in file-name order
     * (AAP 0.7.2).
     *
     * <p>Each fixture file is parsed with Jackson. A file that is not valid JSON, is not a JSON object, or
     * has no non-empty string member {@code scenario} yields a dynamic test named by its file name that
     * fails with that error. Every other fixture yields a dynamic test that runs
     * {@link #replay(String, JsonNode)}.
     *
     * @return one dynamic test per fixture file
     * @throws IOException if the fixtures directory cannot be listed
     */
    @TestFactory
    Stream<DynamicTest> replayFixtures() throws IOException {
        Path dir = requireFixturesDirectory();
        ObjectMapper mapper = new ObjectMapper();
        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : fixtureFiles(dir)) {
            String fileName = file.getFileName().toString();
            JsonNode parsed;
            try {
                parsed = mapper.readTree(file.toFile());
            } catch (IOException e) {
                tests.add(DynamicTest.dynamicTest(fileName,
                        () -> Assertions.fail(fileName + ": fixture is not valid JSON: " + e.getMessage(), e)));
                continue;
            }
            JsonNode fixture = parsed;
            if (fixture == null || !fixture.isObject()) {
                tests.add(DynamicTest.dynamicTest(fileName,
                        () -> Assertions.fail(fileName + ": fixture is not a JSON object")));
                continue;
            }
            JsonNode scenarioNode = fixture.get("scenario");
            if (scenarioNode == null || !scenarioNode.isTextual() || scenarioNode.textValue().isBlank()) {
                tests.add(DynamicTest.dynamicTest(fileName,
                        () -> Assertions.fail(fileName + ": fixture has no non-empty string member scenario")));
                continue;
            }
            String scenario = scenarioNode.textValue();
            tests.add(DynamicTest.dynamicTest(scenario, () -> replay(scenario, fixture)));
        }
        return tests.stream();
    }

    /**
     * Replays one fixture against the running application and compares the response with the recorded
     * one (AAP 0.7.2, D-459). Steps, in order:
     * <ol>
     *   <li>A fixture whose {@code replay} is {@code live} is aborted with {@code not replayed: <scenario>}
     *       unless the system property {@code parity.live} is {@code true}.</li>
     *   <li>The fixture fails when it has a {@code trigger} or {@code steps} member, an {@code outputs}
     *       member other than an empty array, a {@code replay} other than {@code embedded} or
     *       {@code live}, or a {@code volatile} entry whose {@code where} is neither {@code header} nor
     *       {@code body}. The replay sends one HTTP request per fixture, the shape of every flow of
     *       this project on the listener {@code api/*} [testing-apikit-with-munit/src/main/app/api.xml:38-60].</li>
     *   <li>The recorded request is written to {@code localhost:<port>} over a raw socket (see
     *       {@link #requestHead}) followed by the decoded {@code request.bodyBase64} bytes, and the whole
     *       response is read until the server closes the connection.</li>
     *   <li>The replay response head ends at the first CR LF CR LF and is decoded as UTF-8; its first
     *       line is the status line and every further line a {@code name: value} header. A body framed
     *       with {@code Transfer-Encoding: chunked} is de-chunked; any other body is the bytes after the
     *       head.</li>
     *   <li>The recorded {@code response.rawHeaders} is split on CR LF; its first line, the status line,
     *       and blank lines are skipped. The recorded body is the decoded {@code response.bodyBase64}.</li>
     * </ol>
     *
     * <p>Comparison, with every failure reported together under the scenario name:
     * <ul>
     *   <li>the status line of {@code response.statusLine} equals the replay status line exactly, reason
     *       phrase included;</li>
     *   <li>every recorded header name except {@code Date}, {@code Connection} and
     *       {@code Transfer-Encoding} is present in the replay, and its ordered values, grouped by
     *       case-insensitive name, are equal on both sides after each {@code header} regex has replaced
     *       its matches with {@value #VOLATILE_TOKEN}; replay headers absent from the recording are not
     *       checked (D-459);</li>
     *   <li>the bodies are byte-equal; with {@code body} regexes, their ISO-8859-1 views (one character
     *       per byte) are equal after each regex has replaced its matches with
     *       {@value #VOLATILE_TOKEN}.</li>
     * </ul>
     *
     * @param scenario the fixture's {@code scenario} identity, used in every message
     * @param fixture  the parsed fixture object
     * @throws IOException if the replay socket cannot be opened, written or read
     */
    private void replay(String scenario, JsonNode fixture) throws IOException {
        JsonNode replayNode = fixture.get("replay");
        String replayMode = replayNode != null && replayNode.isTextual() ? replayNode.textValue() : null;
        if ("live".equals(replayMode) && !Boolean.getBoolean(LIVE_PROPERTY)) {
            Assumptions.abort("not replayed: " + scenario);
        }

        for (String member : List.of("trigger", "steps")) {
            if (fixture.has(member)) {
                Assertions.fail(scenario + ": fixture member " + member
                        + " is not replayable; every flow of this project is one HTTP request and response on api/*");
            }
        }
        JsonNode outputs = fixture.get("outputs");
        if (outputs != null && !outputs.isNull() && !(outputs.isArray() && outputs.size() == 0)) {
            Assertions.fail(scenario + ": fixture member outputs is not replayable: " + outputs);
        }
        if (!"embedded".equals(replayMode) && !"live".equals(replayMode)) {
            Assertions.fail(scenario + ": fixture member replay is neither embedded nor live: " + replayNode);
        }
        List<Pattern> headerPatterns = new ArrayList<>();
        List<Pattern> bodyPatterns = new ArrayList<>();
        collectVolatilePatterns(scenario, fixture.get("volatile"), headerPatterns, bodyPatterns);

        JsonNode request = requiredObject(scenario, fixture, "request");
        String method = requiredText(scenario, request, "request", "method");
        String path = requiredText(scenario, request, "request", "path");
        String query = optionalText(scenario, request, "request", "query");
        byte[] requestBody = base64(scenario, request, "request", "bodyBase64");
        byte[] head = requestHead(scenario, port, method, path, query, request.get("headers"), requestBody.length);
        byte[] raw = exchange(head, requestBody);

        int headEnd = indexOf(raw, HEAD_TERMINATOR, 0);
        if (headEnd < 0) {
            Assertions.fail(scenario + ": replay response of " + raw.length
                    + " bytes has no CR LF CR LF head terminator");
        }
        String[] replayLines = new String(raw, 0, headEnd, StandardCharsets.UTF_8).split("\r\n", -1);
        String replayStatusLine = replayLines[0];
        List<String[]> replayHeaders = headerFields(scenario, "replay", replayLines);
        int bodyStart = headEnd + HEAD_TERMINATOR.length;
        byte[] replayBody = isChunked(replayHeaders)
                ? dechunk(scenario, raw, bodyStart)
                : Arrays.copyOfRange(raw, bodyStart, raw.length);

        JsonNode response = requiredObject(scenario, fixture, "response");
        String recordedStatusLine = requiredText(scenario, response, "response", "statusLine");
        String rawHeaders = requiredText(scenario, response, "response", "rawHeaders");
        List<String[]> recordedHeaders = headerFields(scenario, "recorded", rawHeaders.split("\r\n", -1));
        byte[] recordedBody = base64(scenario, response, "response", "bodyBase64");

        Map<String, List<String>> recordedValues = maskedValuesByName(recordedHeaders, headerPatterns);
        Map<String, List<String>> replayValues = maskedValuesByName(replayHeaders, headerPatterns);
        Map<String, String> recordedNames = new LinkedHashMap<>();
        for (String[] header : recordedHeaders) {
            recordedNames.putIfAbsent(header[0].toLowerCase(Locale.ROOT), header[0]);
        }

        List<Executable> checks = new ArrayList<>();
        checks.add(() -> Assertions.assertEquals(recordedStatusLine, replayStatusLine,
                scenario + ": status line"));
        for (Map.Entry<String, String> recordedName : recordedNames.entrySet()) {
            String key = recordedName.getKey();
            if (UNCOMPARED_RESPONSE_HEADERS.contains(key)) {
                continue;
            }
            String name = recordedName.getValue();
            List<String> expected = recordedValues.get(key);
            List<String> actual = replayValues.get(key);
            checks.add(() -> {
                Assertions.assertNotNull(actual, scenario + ": replay response has no header " + name);
                Assertions.assertEquals(expected, actual, scenario + ": values of header " + name);
            });
        }
        checks.add(() -> compareBodies(scenario, recordedBody, replayBody, bodyPatterns));
        Assertions.assertAll(scenario, checks);
    }


    /**
     * Writes one request to the application over a new socket and reads the whole response.
     *
     * @param head the request head bytes
     * @param body the request body bytes
     * @return every byte received until the server closed the connection
     * @throws IOException if the socket cannot be opened, written or read within
     *                     {@value #SOCKET_TIMEOUT_MILLIS} ms of inactivity
     */
    private byte[] exchange(byte[] head, byte[] body) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MILLIS);
            OutputStream out = socket.getOutputStream();
            out.write(head);
            out.write(body);
            out.flush();
            return socket.getInputStream().readAllBytes();
        }
    }

    /**
     * Builds the replay request head as UTF-8 bytes.
     *
     * <p>The request line is {@code <method> <path>}, then {@code ?<query>} when the query is not empty,
     * then {@code " HTTP/1.1"}; path and query are used as recorded. Every recorded header follows in
     * document order as {@code name: value}, except {@code Host}, {@code Content-Length} and
     * {@code Connection} (case-insensitive), which are replaced by {@code Host: localhost:<port>},
     * {@code Content-Length: <body length>} and {@code Connection: close}. Lines end with CR LF and the
     * head ends with an empty line.
     *
     * @param scenario   the scenario identity, used in failure messages
     * @param port       the application port
     * @param method     the recorded method
     * @param path       the recorded path
     * @param query      the recorded query, empty for none
     * @param headers    the recorded {@code request.headers} object; {@code null} or JSON {@code null} for none
     * @param bodyLength the number of body bytes to be sent
     * @return the head bytes
     */
    private static byte[] requestHead(String scenario, int port, String method, String path, String query,
            JsonNode headers, int bodyLength) {
        StringBuilder head = new StringBuilder();
        head.append(method).append(' ').append(path);
        if (!query.isEmpty()) {
            head.append('?').append(query);
        }
        head.append(" HTTP/1.1\r\n");
        if (headers != null && !headers.isNull()) {
            if (!headers.isObject()) {
                Assertions.fail(scenario + ": fixture member request.headers is not a JSON object");
            }
            Iterator<Map.Entry<String, JsonNode>> fields = headers.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                if (REPLACED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                JsonNode value = field.getValue();
                if (!value.isTextual()) {
                    Assertions.fail(scenario + ": request header " + name + " is not a string: " + value);
                }
                head.append(name).append(": ").append(value.textValue()).append("\r\n");
            }
        }
        head.append("Host: localhost:").append(port).append("\r\n");
        head.append("Content-Length: ").append(bodyLength).append("\r\n");
        head.append("Connection: close\r\n");
        head.append("\r\n");
        return head.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Reads the {@code volatile} entries of a fixture into compiled header and body patterns.
     *
     * @param scenario       the scenario identity, used in failure messages
     * @param entries        the {@code volatile} member; {@code null} or JSON {@code null} for none
     * @param headerPatterns receives the patterns of the entries whose {@code where} is {@code header}
     * @param bodyPatterns   receives the patterns of the entries whose {@code where} is {@code body}
     */
    private static void collectVolatilePatterns(String scenario, JsonNode entries, List<Pattern> headerPatterns,
            List<Pattern> bodyPatterns) {
        if (entries == null || entries.isNull()) {
            return;
        }
        if (!entries.isArray()) {
            Assertions.fail(scenario + ": fixture member volatile is not an array: " + entries);
        }
        for (JsonNode entry : entries) {
            JsonNode where = entry.get("where");
            String whereText = where != null && where.isTextual() ? where.textValue() : null;
            if (!"header".equals(whereText) && !"body".equals(whereText)) {
                Assertions.fail(scenario + ": volatile where " + where
                        + " is not replayable; only header and body apply to an HTTP replay");
            }
            JsonNode regex = entry.get("regex");
            if (regex == null || !regex.isTextual()) {
                Assertions.fail(scenario + ": volatile regex " + regex + " is not a string");
            }
            Pattern pattern;
            try {
                pattern = Pattern.compile(regex.textValue());
            } catch (PatternSyntaxException e) {
                pattern = Assertions.fail(scenario + ": volatile regex " + regex + " does not compile: "
                        + e.getDescription(), e);
            }
            ("header".equals(whereText) ? headerPatterns : bodyPatterns).add(pattern);
        }
    }

    /**
     * Splits the header lines of a message head, skipping its first line (the status line) and blank
     * lines. Each line is split at its first {@code ':'}; the value is trimmed.
     *
     * @param scenario the scenario identity, used in failure messages
     * @param side     {@code recorded} or {@code replay}, used in failure messages
     * @param lines    the head lines, status line first
     * @return the {@code {name, value}} pairs in head order
     */
    private static List<String[]> headerFields(String scenario, String side, String[] lines) {
        List<String[]> fields = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isBlank()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                Assertions.fail(scenario + ": " + side + " header line has no ':': " + line);
            }
            fields.add(new String[] {line.substring(0, colon), line.substring(colon + 1).trim()});
        }
        return fields;
    }

    /**
     * Groups header values by lower-cased name, in head order, each value masked with the given
     * patterns.
     *
     * @param headers  the {@code {name, value}} pairs
     * @param patterns the {@code header} patterns of the fixture
     * @return the masked values of each lower-cased name, names in order of first appearance
     */
    private static Map<String, List<String>> maskedValuesByName(List<String[]> headers, List<Pattern> patterns) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        for (String[] header : headers) {
            values.computeIfAbsent(header[0].toLowerCase(Locale.ROOT), name -> new ArrayList<>())
                    .add(mask(header[1], patterns));
        }
        return values;
    }

    /**
     * Whether a response body is framed with {@code Transfer-Encoding: chunked}: a
     * {@code Transfer-Encoding} header (case-insensitive) whose last transfer coding is {@code chunked}.
     *
     * @param headers the {@code {name, value}} pairs of the response head
     * @return {@code true} for a chunked body
     */
    private static boolean isChunked(List<String[]> headers) {
        for (String[] header : headers) {
            if ("transfer-encoding".equalsIgnoreCase(header[0])) {
                String[] codings = header[1].split(",");
                if (codings[codings.length - 1].trim().equalsIgnoreCase("chunked")) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Decodes a chunked body: a hexadecimal size line (any {@code ;} extension ignored) ending with
     * CR LF, that many data bytes, CR LF, repeated until a chunk of size 0. Trailer fields after the last
     * chunk are not read.
     *
     * @param scenario the scenario identity, used in failure messages
     * @param raw      the whole response
     * @param start    the offset of the first chunk-size line
     * @return the concatenated chunk data
     */
    private static byte[] dechunk(String scenario, byte[] raw, int start) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int position = start;
        while (true) {
            int lineEnd = indexOf(raw, CRLF, position);
            if (lineEnd < 0) {
                Assertions.fail(scenario + ": chunk-size line at offset " + position + " has no CR LF");
            }
            String sizeLine = new String(raw, position, lineEnd - position, StandardCharsets.US_ASCII);
            int extension = sizeLine.indexOf(';');
            String hex = (extension < 0 ? sizeLine : sizeLine.substring(0, extension)).trim();
            long size;
            try {
                size = Long.parseLong(hex, 16);
            } catch (NumberFormatException e) {
                size = -1;
            }
            if (size < 0) {
                Assertions.fail(scenario + ": chunk-size line at offset " + position + " is not a size: "
                        + sizeLine);
            }
            position = lineEnd + CRLF.length;
            if (size == 0) {
                return body.toByteArray();
            }
            if (size > raw.length - position) {
                Assertions.fail(scenario + ": chunk of " + size + " bytes at offset " + position
                        + " exceeds the " + (raw.length - position) + " bytes received");
            }
            body.write(raw, position, (int) size);
            position += (int) size;
            if (indexOf(raw, CRLF, position) != position) {
                Assertions.fail(scenario + ": chunk data ending at offset " + position + " is not followed by CR LF");
            }
            position += CRLF.length;
        }
    }

    /**
     * Compares two bodies: byte by byte without {@code body} patterns; otherwise their ISO-8859-1 views,
     * one character per byte, after masking.
     *
     * @param scenario     the scenario identity, used in failure messages
     * @param recorded     the recorded body bytes
     * @param replayed     the replay body bytes
     * @param bodyPatterns the {@code body} patterns of the fixture
     */
    private static void compareBodies(String scenario, byte[] recorded, byte[] replayed, List<Pattern> bodyPatterns) {
        if (bodyPatterns.isEmpty()) {
            Assertions.assertArrayEquals(recorded, replayed, scenario + ": body bytes (recorded "
                    + recorded.length + " bytes, replay " + replayed.length + " bytes)");
            return;
        }
        Assertions.assertEquals(
                mask(new String(recorded, StandardCharsets.ISO_8859_1), bodyPatterns),
                mask(new String(replayed, StandardCharsets.ISO_8859_1), bodyPatterns),
                scenario + ": body after volatile masking");
    }

    /**
     * Replaces every match of each pattern, applied in order, with {@value #VOLATILE_TOKEN}.
     *
     * @param text     the text to mask
     * @param patterns the patterns to apply
     * @return the masked text
     */
    private static String mask(String text, List<Pattern> patterns) {
        String masked = text;
        for (Pattern pattern : patterns) {
            masked = pattern.matcher(masked).replaceAll(Matcher.quoteReplacement(VOLATILE_TOKEN));
        }
        return masked;
    }

    /**
     * Finds the first occurrence of a byte sequence.
     *
     * @param data   the bytes to search
     * @param target the sequence to find
     * @param from   the offset to start at
     * @return the offset of the first occurrence at or after {@code from}; {@code -1} when absent
     */
    private static int indexOf(byte[] data, byte[] target, int from) {
        for (int i = Math.max(from, 0); i <= data.length - target.length; i++) {
            int matched = 0;
            while (matched < target.length && data[i + matched] == target[matched]) {
                matched++;
            }
            if (matched == target.length) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the fixtures directory, failing the calling test when it does not resolve.
     *
     * @return the fixtures directory
     */
    private static Path requireFixturesDirectory() {
        return fixturesDirectory().orElseGet(
                () -> Assertions.fail(FIXTURES_ANCHOR + " is not on the test classpath as a file-system path"));
    }

    /**
     * Collects the identities that occur more than once in a list.
     *
     * @param identities the identities to inspect
     * @return a mutable sorted set of every identity occurring at least twice
     */
    private static Set<String> repeated(List<String> identities) {
        Map<String, Integer> counts = new HashMap<>();
        for (String identity : identities) {
            counts.merge(identity, 1, Integer::sum);
        }
        Set<String> repeated = new TreeSet<>();
        counts.forEach((identity, count) -> {
            if (count > 1) {
                repeated.add(identity);
            }
        });
        return repeated;
    }

    /**
     * Returns a member of a fixture object that must be a JSON object.
     *
     * @param scenario the scenario identity, used in failure messages
     * @param parent   the fixture object
     * @param member   the member name
     * @return the member
     */
    private static JsonNode requiredObject(String scenario, JsonNode parent, String member) {
        JsonNode node = parent.get(member);
        if (node == null || !node.isObject()) {
            return Assertions.fail(scenario + ": fixture member " + member + " is not a JSON object");
        }
        return node;
    }

    /**
     * Returns a member that must be a non-empty JSON string.
     *
     * @param scenario the scenario identity, used in failure messages
     * @param parent   the object holding the member
     * @param where    the name of {@code parent} in failure messages
     * @param member   the member name
     * @return the string value
     */
    private static String requiredText(String scenario, JsonNode parent, String where, String member) {
        JsonNode node = parent.get(member);
        if (node == null || !node.isTextual() || node.textValue().isEmpty()) {
            return Assertions.fail(scenario + ": fixture member " + where + "." + member
                    + " is not a non-empty string");
        }
        return node.textValue();
    }

    /**
     * Returns a member that is a JSON string when present.
     *
     * @param scenario the scenario identity, used in failure messages
     * @param parent   the object holding the member
     * @param where    the name of {@code parent} in failure messages
     * @param member   the member name
     * @return the string value; empty when the member is absent or JSON {@code null}
     */
    private static String optionalText(String scenario, JsonNode parent, String where, String member) {
        JsonNode node = parent.get(member);
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            return Assertions.fail(scenario + ": fixture member " + where + "." + member + " is not a string");
        }
        return node.textValue();
    }

    /**
     * Decodes a base64 member with the standard decoder.
     *
     * @param scenario the scenario identity, used in failure messages
     * @param parent   the object holding the member
     * @param where    the name of {@code parent} in failure messages
     * @param member   the member name
     * @return the decoded bytes; empty when the member is absent, JSON {@code null} or empty
     */
    private static byte[] base64(String scenario, JsonNode parent, String where, String member) {
        String encoded = optionalText(scenario, parent, where, member);
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            return Assertions.fail(scenario + ": fixture member " + where + "." + member + " is not base64: "
                    + e.getMessage(), e);
        }
    }
}

