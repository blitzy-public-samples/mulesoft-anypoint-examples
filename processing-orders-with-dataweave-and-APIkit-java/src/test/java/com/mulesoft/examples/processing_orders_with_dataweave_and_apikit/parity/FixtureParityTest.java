package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.parity;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;

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
 * Tier 2A fixture completeness gate and replay of processing-orders-with-dataweave-and-APIkit-java (AAP 0.4.1,
 * 0.7.2; D-021, D-023).
 *
 * <p>{@link FixturesPresentCondition} disables the class when {@code fixtures/} on the test classpath holds no
 * {@code *.json} fixture file. JUnit evaluates that class-level condition before {@code SpringExtension} builds an
 * application context, and a disabled class starts no context (D-021). This project ships only
 * {@code fixtures/.gitkeep}: the class is reported as skipped, a skipped class is not parity evidence, and the
 * project's parity label is {@code RAML DIFF: 1:1 (Tier 1)}.
 *
 * <p>With at least one fixture file present (D-023):
 * <ul>
 *   <li>{@link #fixturesMatchScenarioInventory()} checks the fixture identities, plus the identities of
 *       {@code fixtures/UNCAPTURABLE.txt}, against {@code fixtures/SCENARIOS.txt};</li>
 *   <li>{@link #replayFixtures()} sends each fixture's recorded request to the application on its random port
 *       and compares the status line, the recorded headers and the body with the recorded response, after the
 *       fixture's {@code volatile} masking.</li>
 * </ul>
 *
 * <p>Only the top-level files of {@code fixtures/} are read; {@code fixtures/diagnostic/} is never entered, and the
 * class writes no file.
 *
 * <p>Replay commands, from the project folder:
 * <pre>{@code
 * mvn -B test -Dtest=FixtureParityTest                     # embedded fixtures
 * mvn -B test -Dtest=FixtureParityTest -Dparity.live=true  # embedded and live fixtures
 * }</pre>
 */
@ExtendWith(FixtureParityTest.FixturesPresentCondition.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public class FixtureParityTest {

    /** Test-classpath resource whose parent directory is the fixtures directory. */
    private static final String FIXTURES_ANCHOR = "fixtures/.gitkeep";

    /** File name suffix of a fixture file; the fixture identity is the file name without it. */
    private static final String FIXTURE_SUFFIX = ".json";

    /** File of the fixtures directory that lists one scenario identity per line. */
    private static final String SCENARIOS_FILE = "SCENARIOS.txt";

    /** File of the fixtures directory that lists one {@code identity \u2014 missing input} entry per line. */
    private static final String UNCAPTURABLE_FILE = "UNCAPTURABLE.txt";

    /** Separator between identity and missing input in an {@code UNCAPTURABLE.txt} line: space, U+2014, space. */
    private static final String UNCAPTURABLE_SEPARATOR = " \u2014 ";

    /** Skip reason of the class while {@code fixtures/} holds no fixture file. */
    private static final String DISABLED_REASON = "no Tier 2A fixtures";

    /** Reason reported when {@code fixtures/} holds at least one fixture file. */
    private static final String ENABLED_REASON = "Tier 2A fixtures present";

    /** System property that enables the replay of fixtures whose {@code replay} is {@code live}. */
    private static final String LIVE_PROPERTY = "parity.live";

    /** {@code replay} value of a fixture replayed only with {@value #LIVE_PROPERTY} set to {@code true}. */
    private static final String LIVE_REPLAY = "live";

    /** {@code where} value of a {@code volatile} entry masked in header values. */
    private static final String VOLATILE_HEADER = "header";

    /** {@code where} value of a {@code volatile} entry masked in bodies. */
    private static final String VOLATILE_BODY = "body";

    /** Replacement of every {@code volatile} regex match, on the recorded and on the actual side. */
    private static final String VOLATILE_MARK = "<volatile>";

    /** Line separator of a recorded {@code response.rawHeaders} block. */
    private static final Pattern RAW_HEADER_LINE_BREAK = Pattern.compile("\r?\n");

    /** Maximum number of characters shown on each side of a body difference. */
    private static final int EXCERPT_LENGTH = 64;

    /** Parser of the UTF-8 JSON fixture files. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Port of the application started by {@code @SpringBootTest} on a random port. */
    @LocalServerPort
    private int port;

    /**
     * Class-level execution condition of {@link FixtureParityTest} (D-021, D-023).
     *
     * <p>Disables the class when the fixtures directory cannot be resolved or holds no fixture file; enables it
     * otherwise. The condition reads only the top-level entry names of the fixtures directory and uses no Spring
     * type.
     */
    public static class FixturesPresentCondition implements ExecutionCondition {

        /**
         * Evaluates the fixture gate for the test class or method of {@code context}.
         *
         * @param context the extension context being evaluated; not read
         * @return {@code disabled("no Tier 2A fixtures")} when the fixtures directory is absent or holds no
         *         top-level regular file ending in {@code .json}; {@code enabled("Tier 2A fixtures present")}
         *         otherwise
         * @throws UncheckedIOException if listing the fixtures directory fails
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            Optional<Path> dir = fixturesDirectory();
            if (dir.isEmpty() || fixtureFiles(dir.get()).isEmpty()) {
                return ConditionEvaluationResult.disabled(DISABLED_REASON);
            }
            return ConditionEvaluationResult.enabled(ENABLED_REASON);
        }
    }

    /**
     * Checks the Tier 2A fixture set against the scenario inventory (D-023).
     *
     * <p>The covered identities are the fixture identities (fixture file names without {@code .json}) followed by
     * the identities of {@code UNCAPTURABLE.txt}, kept as a list. The test passes only when all three of these
     * sorted sets are empty:
     * <ul>
     *   <li>{@code missing}: {@code SCENARIOS.txt} identities that are not covered;</li>
     *   <li>{@code extra}: covered identities that are not in {@code SCENARIOS.txt};</li>
     *   <li>{@code duplicate}: identities occurring more than once in {@code SCENARIOS.txt}, or more than once
     *       among the covered identities, such as a fixture also listed in {@code UNCAPTURABLE.txt}.</li>
     * </ul>
     * Otherwise it fails with one message listing {@code missing: [...]}, {@code extra: [...]} and
     * {@code duplicate: [...]}. An absent {@code SCENARIOS.txt} or {@code UNCAPTURABLE.txt} contributes no
     * identity, so with no {@code SCENARIOS.txt} every fixture is listed as extra.
     *
     * @throws IOException if reading {@code SCENARIOS.txt} or {@code UNCAPTURABLE.txt} fails
     */
    @Test
    public void fixturesMatchScenarioInventory() throws IOException {
        Path dir = requiredFixturesDirectory();

        List<String> scenarios = scenarioIdentities(dir);
        List<String> covered = new ArrayList<>();
        for (Path file : fixtureFiles(dir)) {
            covered.add(fixtureIdentity(file));
        }
        covered.addAll(uncapturableIdentities(dir));

        Set<String> scenarioSet = new HashSet<>(scenarios);
        Set<String> coveredSet = new HashSet<>(covered);

        SortedSet<String> missing = scenarios.stream()
                .filter(identity -> !coveredSet.contains(identity))
                .collect(Collectors.toCollection(TreeSet::new));
        SortedSet<String> extra = covered.stream()
                .filter(identity -> !scenarioSet.contains(identity))
                .collect(Collectors.toCollection(TreeSet::new));
        SortedSet<String> duplicate = repeatedIdentities(scenarios);
        duplicate.addAll(repeatedIdentities(covered));

        if (!missing.isEmpty() || !extra.isEmpty() || !duplicate.isEmpty()) {
            fail("Tier 2A fixtures do not match " + SCENARIOS_FILE + ": missing: " + missing
                    + ", extra: " + extra + ", duplicate: " + duplicate);
        }
    }

    /**
     * Replays every Tier 2A fixture against the running application, one dynamic test per fixture file in file
     * name order (D-023).
     *
     * <p>Each dynamic test is named by the fixture's {@code scenario} field, or by the fixture's file name when
     * that field is absent or blank. Inside each dynamic test, in this order:
     * <ol>
     *   <li>a fixture with {@code steps}, with {@code trigger}, with a non-empty {@code outputs} array, or without
     *       a {@code request} or {@code response} object fails with {@code unsupported fixture kind: <scenario>};</li>
     *   <li>a fixture whose {@code replay} is {@code live} is aborted with {@code not replayed (live): <scenario>}
     *       unless the system property {@code parity.live} is {@code true}; an aborted test is reported by name
     *       and never counts as passing;</li>
     *   <li>the recorded request is sent over {@link HttpURLConnection}, and the status line, every recorded
     *       header and the body are compared with the recorded response after {@code volatile} masking. All
     *       differences are reported in one failure naming the scenario.</li>
     * </ol>
     *
     * @return one dynamic test per fixture file
     * @throws IOException if a fixture file cannot be read or is not valid JSON
     */
    @TestFactory
    public Stream<DynamicTest> replayFixtures() throws IOException {
        Path dir = requiredFixturesDirectory();
        List<DynamicTest> tests = new ArrayList<>();
        for (Path file : fixtureFiles(dir)) {
            JsonNode fixture = readFixture(file);
            String scenario = scenarioName(fixture, file);
            tests.add(DynamicTest.dynamicTest(scenario, file.toUri(), () -> replay(scenario, fixture)));
        }
        return tests.stream();
    }

    /**
     * Resolves the fixtures directory as the parent of the {@code fixtures/.gitkeep} test-classpath resource,
     * looked up through the class loader of {@link FixtureParityTest}.
     *
     * @return the directory; empty when the resource is not found
     * @throws IllegalStateException if the resource URL is not a valid URI
     */
    private static Optional<Path> fixturesDirectory() {
        URL anchor = FixtureParityTest.class.getClassLoader().getResource(FIXTURES_ANCHOR);
        if (anchor == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Path.of(anchor.toURI()).getParent());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Test-classpath resource " + FIXTURES_ANCHOR + " has an invalid URI: "
                    + anchor, e);
        }
    }

    /**
     * Returns the fixtures directory, failing the calling test when it cannot be resolved.
     *
     * @return the fixtures directory
     */
    private static Path requiredFixturesDirectory() {
        return fixturesDirectory().orElseGet(() -> fail(FIXTURES_ANCHOR + " not found on the test classpath"));
    }

    /**
     * Lists the fixture files of a fixtures directory: its top-level regular files whose name ends with
     * {@code .json}, sorted by file name. Subdirectories such as {@code diagnostic/} are never entered, and
     * {@code .gitkeep}, {@code SCENARIOS.txt} and {@code UNCAPTURABLE.txt} are not fixtures.
     *
     * @param dir the fixtures directory
     * @return the fixture files; empty when {@code dir} is not an existing directory
     * @throws UncheckedIOException if listing {@code dir} fails
     */
    private static List<Path> fixtureFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(entry -> entry.getFileName().toString().endsWith(FIXTURE_SUFFIX))
                    .sorted(Comparator.comparing(entry -> entry.getFileName().toString()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Listing " + dir + " failed", e);
        }
    }

    /**
     * Returns the identity of a fixture file: its file name without the {@code .json} suffix.
     *
     * @param file a fixture file listed by {@link #fixtureFiles(Path)}
     * @return the fixture identity
     */
    private static String fixtureIdentity(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - FIXTURE_SUFFIX.length());
    }

    /**
     * Reads the identities of {@code SCENARIOS.txt}: each non-blank line, trimmed.
     *
     * @param dir the fixtures directory
     * @return the identities in file order, repeated lines kept; empty when the file is absent
     * @throws IOException if reading the file fails
     */
    private static List<String> scenarioIdentities(Path dir) throws IOException {
        return nonBlankLines(dir.resolve(SCENARIOS_FILE));
    }

    /**
     * Reads the identities of {@code UNCAPTURABLE.txt}: for each non-blank line, the text before the first
     * {@code " \u2014 "} separator, trimmed, or the whole trimmed line when it holds no separator.
     *
     * @param dir the fixtures directory
     * @return the identities in file order, repeated lines kept; empty when the file is absent
     * @throws IOException if reading the file fails
     */
    private static List<String> uncapturableIdentities(Path dir) throws IOException {
        List<String> identities = new ArrayList<>();
        for (String line : nonBlankLines(dir.resolve(UNCAPTURABLE_FILE))) {
            int separator = line.indexOf(UNCAPTURABLE_SEPARATOR);
            identities.add(separator < 0 ? line : line.substring(0, separator).trim());
        }
        return identities;
    }

    /**
     * Reads a UTF-8 text file as its trimmed non-blank lines.
     *
     * @param file the file to read
     * @return the trimmed non-blank lines in file order; empty when {@code file} is not an existing regular file
     * @throws IOException if reading the file fails
     */
    private static List<String> nonBlankLines(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                lines.add(trimmed);
            }
        }
        return lines;
    }

    /**
     * Collects the identities occurring more than once in a list.
     *
     * @param identities the identities to inspect
     * @return a mutable sorted set of every identity occurring at least twice
     */
    private static SortedSet<String> repeatedIdentities(List<String> identities) {
        Set<String> seen = new HashSet<>();
        SortedSet<String> repeated = new TreeSet<>();
        for (String identity : identities) {
            if (!seen.add(identity)) {
                repeated.add(identity);
            }
        }
        return repeated;
    }

    /**
     * Parses a fixture file as JSON.
     *
     * @param file the fixture file
     * @return the JSON tree; a missing node for an empty file
     * @throws IOException if the file cannot be read or is not valid JSON
     */
    private static JsonNode readFixture(Path file) throws IOException {
        JsonNode fixture = MAPPER.readTree(Files.readAllBytes(file));
        return fixture == null ? MissingNode.getInstance() : fixture;
    }

    /**
     * Returns the dynamic test name of a fixture: its non-blank {@code scenario} text, or the fixture's file name.
     *
     * @param fixture the parsed fixture
     * @param file    the fixture file
     * @return the dynamic test name
     */
    private static String scenarioName(JsonNode fixture, Path file) {
        String scenario = text(fixture.path("scenario"));
        return scenario.isBlank() ? file.getFileName().toString() : scenario;
    }


    /**
     * Replays one fixture and compares the actual response with the recorded one.
     *
     * @param scenario the dynamic test name of the fixture
     * @param fixture  the parsed fixture
     * @throws IOException if the request cannot be sent or the response cannot be read
     */
    private void replay(String scenario, JsonNode fixture) throws IOException {
        if (!isRequestResponseFixture(fixture)) {
            fail("unsupported fixture kind: " + scenario);
        }
        if (LIVE_REPLAY.equals(text(fixture.path("replay"))) && !Boolean.getBoolean(LIVE_PROPERTY)) {
            Assumptions.abort("not replayed (live): " + scenario);
        }

        JsonNode request = fixture.path("request");
        JsonNode response = fixture.path("response");
        List<Pattern> headerMasks = volatilePatterns(fixture, VOLATILE_HEADER);
        List<Pattern> bodyMasks = volatilePatterns(fixture, VOLATILE_BODY);
        List<String> differences = new ArrayList<>();

        HttpURLConnection connection = send(request);
        try {
            int status = connection.getResponseCode();

            String expectedStatusLine = text(response.path("statusLine"));
            String actualStatusLine = connection.getHeaderField(0);
            if (!expectedStatusLine.equals(actualStatusLine)) {
                differences.add("status line: expected \"" + expectedStatusLine + "\" but was \"" + actualStatusLine
                        + "\"");
            }

            compareHeaders(recordedHeaders(text(response.path("rawHeaders")), headerMasks),
                    actualHeaders(connection, headerMasks), differences);

            compareBodies(decodeBase64(response.path("bodyBase64")), readBody(connection, status), bodyMasks,
                    differences);
        } finally {
            connection.disconnect();
        }

        if (!differences.isEmpty()) {
            fail("Tier 2A replay of " + scenario + " differs from its fixture:\n  "
                    + String.join("\n  ", differences));
        }
    }

    /**
     * Tells whether a fixture is a single HTTP request/response pair: no {@code steps}, no {@code trigger}, no
     * non-empty {@code outputs} array, and both a {@code request} and a {@code response} object.
     *
     * @param fixture the parsed fixture
     * @return {@code true} for a request/response fixture
     */
    private static boolean isRequestResponseFixture(JsonNode fixture) {
        JsonNode outputs = fixture.path("outputs");
        return !fixture.hasNonNull("steps")
                && !fixture.hasNonNull("trigger")
                && !(outputs.isArray() && outputs.size() > 0)
                && fixture.path("request").isObject()
                && fixture.path("response").isObject();
    }

    /**
     * Opens a connection to the application on {@link #port}, sends the recorded request and returns the
     * connection with its request written.
     *
     * <p>The URL is {@code http://localhost:<port><path>}, followed by {@code ?<query>} when {@code query} is not
     * empty; path and query are used as recorded. Redirects are not followed and caches are not used. Every entry
     * of {@code headers} is sent as a request property. The decoded {@code bodyBase64} bytes are written with a
     * fixed-length body only when they are not empty.
     *
     * @param request the fixture's {@code request} object
     * @return the connection, ready for its response to be read
     * @throws IOException if the connection cannot be opened or the body cannot be written
     */
    private HttpURLConnection send(JsonNode request) throws IOException {
        String query = text(request.path("query"));
        String file = text(request.path("path")) + (query.isEmpty() ? "" : "?" + query);
        HttpURLConnection connection = (HttpURLConnection) new URL("http", "localhost", port, file).openConnection();
        connection.setRequestMethod(text(request.path("method")));
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);

        Iterator<Map.Entry<String, JsonNode>> headers = request.path("headers").fields();
        while (headers.hasNext()) {
            Map.Entry<String, JsonNode> header = headers.next();
            connection.setRequestProperty(header.getKey(), text(header.getValue()));
        }

        byte[] body = decodeBase64(request.path("bodyBase64"));
        if (body.length > 0) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body);
            }
        }
        return connection;
    }

    /**
     * Reads the response body: from the input stream for a status below 400, from the error stream otherwise.
     *
     * @param connection the connection whose response is read
     * @param status     the response status code
     * @return the body bytes; empty when the stream is absent
     * @throws IOException if reading the body fails
     */
    private static byte[] readBody(HttpURLConnection connection, int status) throws IOException {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            return new byte[0];
        }
        try (InputStream in = stream) {
            return in.readAllBytes();
        }
    }

    /**
     * Parses a recorded {@code rawHeaders} block into masked header values grouped by lower-cased name.
     *
     * <p>The block is split into lines at {@code \r?\n}. The first line, the status line, and blank lines are
     * skipped. Each other line is split at its first {@code :} into name and trimmed value; a line without
     * {@code :} names no header and is skipped.
     *
     * @param rawHeaders the recorded header block
     * @param masks      the {@code header} volatile patterns
     * @return the masked values of each header name, in recorded order
     */
    private static Map<String, List<String>> recordedHeaders(String rawHeaders, List<Pattern> masks) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String[] lines = RAW_HEADER_LINE_BREAK.split(rawHeaders, -1);
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            int colon = line.indexOf(':');
            if (line.isBlank() || colon < 0) {
                continue;
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = mask(line.substring(colon + 1).trim(), masks);
            headers.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
        }
        return headers;
    }

    /**
     * Reads the actual response headers in wire order into masked values grouped by lower-cased name.
     *
     * <p>Header {@code i} is read with {@link HttpURLConnection#getHeaderFieldKey(int)} and
     * {@link HttpURLConnection#getHeaderField(int)} for {@code i = 1, 2, ...} until both are {@code null}; an
     * entry without a name is skipped.
     *
     * @param connection the connection whose response headers are read
     * @param masks      the {@code header} volatile patterns
     * @return the masked values of each header name, in wire order
     */
    private static Map<String, List<String>> actualHeaders(HttpURLConnection connection, List<Pattern> masks) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int index = 1; ; index++) {
            String name = connection.getHeaderFieldKey(index);
            String value = connection.getHeaderField(index);
            if (name == null && value == null) {
                return headers;
            }
            if (name != null) {
                headers.computeIfAbsent(name.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(mask(value == null ? "" : value, masks));
            }
        }
    }

    /**
     * Adds a difference for each recorded header name whose ordered values differ from the actual ones.
     *
     * @param recorded    the masked recorded values by lower-cased name
     * @param actual      the masked actual values by lower-cased name
     * @param differences the differences collected for the fixture
     */
    private static void compareHeaders(Map<String, List<String>> recorded, Map<String, List<String>> actual,
            List<String> differences) {
        for (Map.Entry<String, List<String>> header : recorded.entrySet()) {
            List<String> actualValues = actual.getOrDefault(header.getKey(), List.of());
            if (!header.getValue().equals(actualValues)) {
                differences.add("header " + header.getKey() + ": expected " + header.getValue() + " but was "
                        + actualValues);
            }
        }
    }


    /**
     * Adds a difference when the recorded and actual bodies differ.
     *
     * <p>Without {@code body} volatile patterns, the bytes are compared as {@code assertArrayEquals} does: same
     * length and same byte at every index. With them, both bodies are decoded as ISO-8859-1, one character per
     * byte, every pattern match is replaced with {@code <volatile>} on both sides, and the masked strings are
     * compared in place of the bytes.
     *
     * @param expected    the decoded recorded body
     * @param actual      the actual body
     * @param masks       the {@code body} volatile patterns
     * @param differences the differences collected for the fixture
     */
    private static void compareBodies(byte[] expected, byte[] actual, List<Pattern> masks, List<String> differences) {
        if (masks.isEmpty()) {
            int index = Arrays.mismatch(expected, actual);
            if (index >= 0) {
                differences.add("body: expected " + expected.length + " bytes but was " + actual.length
                        + " bytes, first difference at byte " + index + describeExcerpts(
                                new String(expected, StandardCharsets.ISO_8859_1),
                                new String(actual, StandardCharsets.ISO_8859_1), index));
            }
            return;
        }
        String maskedExpected = mask(new String(expected, StandardCharsets.ISO_8859_1), masks);
        String maskedActual = mask(new String(actual, StandardCharsets.ISO_8859_1), masks);
        if (!maskedExpected.equals(maskedActual)) {
            int index = firstDifference(maskedExpected, maskedActual);
            differences.add("body after volatile masking: expected " + maskedExpected.length() + " characters but was "
                    + maskedActual.length() + " characters, first difference at character " + index
                    + describeExcerpts(maskedExpected, maskedActual, index));
        }
    }

    /**
     * Returns the index of the first character at which two different strings differ, or the length of the
     * shorter one when it is a prefix of the other.
     *
     * @param expected the masked recorded body
     * @param actual   the masked actual body
     * @return the index of the first difference
     */
    private static int firstDifference(String expected, String actual) {
        int limit = Math.min(expected.length(), actual.length());
        for (int index = 0; index < limit; index++) {
            if (expected.charAt(index) != actual.charAt(index)) {
                return index;
            }
        }
        return limit;
    }

    /**
     * Formats the recorded and actual text starting at a difference, at most {@value #EXCERPT_LENGTH} characters
     * each, with control characters escaped.
     *
     * @param expected the recorded side, one character per byte for raw bodies
     * @param actual   the actual side, one character per byte for raw bodies
     * @param from     the index of the first difference
     * @return the formatted excerpts, prefixed with {@code "; "}
     */
    private static String describeExcerpts(String expected, String actual, int from) {
        return "; expected from there \"" + excerpt(expected, from) + "\" but was \"" + excerpt(actual, from) + "\"";
    }

    /**
     * Returns at most {@value #EXCERPT_LENGTH} characters of {@code text} starting at {@code from}, with
     * {@code \r}, {@code \n} and {@code \t} written as escapes and other control characters as {@code \}{@code uXXXX}.
     *
     * @param text the text to excerpt
     * @param from the start index; past the end yields an empty excerpt
     * @return the escaped excerpt
     */
    private static String excerpt(String text, int from) {
        int start = Math.min(from, text.length());
        int end = Math.min(text.length(), start + EXCERPT_LENGTH);
        StringBuilder escaped = new StringBuilder();
        for (int index = start; index < end; index++) {
            char c = text.charAt(index);
            if (c == '\r') {
                escaped.append("\\r");
            } else if (c == '\n') {
                escaped.append("\\n");
            } else if (c == '\t') {
                escaped.append("\\t");
            } else if (Character.isISOControl(c)) {
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Compiles the {@code regex} of every {@code volatile} entry whose {@code where} equals {@code where}.
     * Entries with another {@code where} value, such as {@code output} or {@code log}, are not returned.
     *
     * @param fixture the parsed fixture
     * @param where   {@code header} or {@code body}
     * @return the compiled patterns in fixture order
     * @throws java.util.regex.PatternSyntaxException if a selected {@code regex} is not a valid pattern
     */
    private static List<Pattern> volatilePatterns(JsonNode fixture, String where) {
        List<Pattern> patterns = new ArrayList<>();
        for (JsonNode entry : fixture.path("volatile")) {
            if (where.equals(text(entry.path("where")))) {
                patterns.add(Pattern.compile(text(entry.path("regex"))));
            }
        }
        return patterns;
    }

    /**
     * Replaces every match of each pattern, in order, with {@code <volatile>}.
     *
     * @param value the text to mask
     * @param masks the patterns to apply
     * @return the masked text
     */
    private static String mask(String value, List<Pattern> masks) {
        String masked = value;
        for (Pattern pattern : masks) {
            masked = pattern.matcher(masked).replaceAll(VOLATILE_MARK);
        }
        return masked;
    }

    /**
     * Decodes a base64 text node with the basic decoder of {@link Base64}.
     *
     * @param node the {@code bodyBase64} node
     * @return the decoded bytes; empty for an absent, {@code null} or empty node
     * @throws IllegalArgumentException if the text is not valid base64
     */
    private static byte[] decodeBase64(JsonNode node) {
        return Base64.getDecoder().decode(text(node));
    }

    /**
     * Returns the text of a JSON value node.
     *
     * @param node the node to read
     * @return the node's text; empty for a missing node, a {@code null} node or a container node
     */
    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() ? node.asText() : "";
    }
}

