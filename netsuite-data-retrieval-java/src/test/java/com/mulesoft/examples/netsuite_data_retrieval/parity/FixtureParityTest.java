package com.mulesoft.examples.netsuite_data_retrieval.parity;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Tier 2A completeness gate of netsuite-data-retrieval-java (AAP 0.7.2, D-023).
 *
 * <p>The class checks the Tier 2A fixture set in {@code fixtures/} on the test classpath against
 * {@code fixtures/SCENARIOS.txt}. It is disabled by {@link FixturesPresentCondition} while {@code fixtures/} holds no
 * {@code *.json} fixture file; JUnit evaluates that condition before it creates a test instance, and the class
 * creates no Spring context. A disabled class is reported as skipped and is not parity evidence (AAP 0.7.2).
 *
 * <p>The class sends no request, replays no fixture and never reads a fixture's content: it compares identities
 * only. A fixture's identity is its file name without the {@code .json} suffix.
 */
@ExtendWith(FixtureParityTest.FixturesPresentCondition.class)
public class FixtureParityTest {

    /** Test-classpath resource whose parent directory is the fixtures directory. */
    private static final String FIXTURES_ANCHOR = "fixtures/.gitkeep";

    /** File name suffix of a fixture file; the identity is the file name without it. */
    private static final String FIXTURE_SUFFIX = ".json";

    /** File of the fixtures directory that lists one scenario identity per line. */
    private static final String SCENARIOS_FILE = "SCENARIOS.txt";

    /** File of the fixtures directory that lists one {@code identity \u2014 missing input} entry per line. */
    private static final String UNCAPTURABLE_FILE = "UNCAPTURABLE.txt";

    /** Separator between identity and missing input in an {@code UNCAPTURABLE.txt} line: space, U+2014, space. */
    private static final String UNCAPTURABLE_SEPARATOR = " \u2014 ";

    /** Skip reason reported while {@code fixtures/} holds no fixture file (D-023). */
    private static final String DISABLED_REASON = "netsuite-data-retrieval-java is RAML-backed: parity evidence is the "
            + "Tier 1 RAML DIFF of RamlContractTest; no Tier 2A fixtures exist in fixtures/ (AAP 0.7.2, D-023)";

    /** Reason reported when {@code fixtures/} holds at least one fixture file. */
    private static final String ENABLED_REASON = "Tier 2A fixtures present in fixtures/ (AAP 0.7.2)";

    /** First line of the failure message of {@link #fixtureSetMatchesScenarios()}. */
    private static final String MISMATCH_HEADER = "Fixture set does not equal fixtures/SCENARIOS.txt (AAP 0.7.2)";

    /**
     * Class-level execution condition of {@link FixtureParityTest}: enabled only when {@code fixtures/} holds at
     * least one {@code *.json} fixture file (AAP 0.7.2, D-023).
     *
     * <p>The condition uses no Spring type. It reads only the top-level entry names of the fixtures directory.
     */
    public static final class FixturesPresentCondition implements ExecutionCondition {

        /** Creates the condition. JUnit instantiates it through {@code @ExtendWith} on {@link FixtureParityTest}. */
        public FixturesPresentCondition() {
        }

        /**
         * Evaluates the fixture gate for the test class or method in {@code context} (AAP 0.7.2, D-023).
         *
         * <p>The fixtures directory is the parent of the {@code fixtures/.gitkeep} test-classpath resource. When that
         * directory cannot be resolved, or holds no top-level regular file ending in {@code .json}, the result is
         * disabled with the reason {@value FixtureParityTest#DISABLED_REASON}.
         *
         * @param context the extension context of the test class or method being evaluated; not read
         * @return enabled when at least one fixture file exists; disabled otherwise
         * @throws UncheckedIOException if listing the fixtures directory fails
         */
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            List<String> fixtures = fixturesDirectory()
                    .map(FixtureParityTest::fixtureIdentities)
                    .orElse(List.of());
            if (fixtures.isEmpty()) {
                return ConditionEvaluationResult.disabled(DISABLED_REASON);
            }
            return ConditionEvaluationResult.enabled(ENABLED_REASON);
        }
    }

    /**
     * Asserts that the fixture identities plus the {@code UNCAPTURABLE.txt} identities equal the
     * {@code SCENARIOS.txt} identities exactly (AAP 0.7.2, D-023).
     *
     * <p>The provided identities are the fixture identities followed by the identities of {@code UNCAPTURABLE.txt},
     * kept as a list. The test fails when any of these sorted sets is non-empty:
     * <ul>
     *   <li>{@code missing}: distinct {@code SCENARIOS.txt} identities that are not provided;</li>
     *   <li>{@code extra}: distinct provided identities that are not in {@code SCENARIOS.txt};</li>
     *   <li>{@code duplicated}: identities occurring more than once among the provided identities, such as an
     *       identity that is both a fixture and uncapturable, together with identities occurring more than once in
     *       {@code SCENARIOS.txt}.</li>
     * </ul>
     * The failure message starts with {@value #MISMATCH_HEADER} and lists each non-empty set on its own line,
     * prefixed {@code missing:}, {@code extra:} or {@code duplicated:}. An absent {@code SCENARIOS.txt} or
     * {@code UNCAPTURABLE.txt} contributes no identity.
     *
     * @throws IOException if reading {@code SCENARIOS.txt} or {@code UNCAPTURABLE.txt} fails
     */
    @Test
    @DisplayName("Tier 2A fixture set equals SCENARIOS.txt")
    public void fixtureSetMatchesScenarios() throws IOException {
        Optional<Path> dir = fixturesDirectory();
        assertTrue(dir.isPresent(), "fixtures/ directory not found on the test classpath");
        Path fixturesDir = dir.get();

        List<String> scenarios = scenarioIdentities(fixturesDir);
        List<String> uncapturable = uncapturableIdentities(fixturesDir);
        List<String> fixtures = fixtureIdentities(fixturesDir);

        List<String> provided = new ArrayList<>(fixtures.size() + uncapturable.size());
        provided.addAll(fixtures);
        provided.addAll(uncapturable);

        Set<String> duplicated = repeatedIdentities(provided);
        duplicated.addAll(repeatedIdentities(scenarios));

        Set<String> missing = new TreeSet<>(scenarios);
        missing.removeAll(new HashSet<>(provided));

        Set<String> extra = new TreeSet<>(provided);
        extra.removeAll(new HashSet<>(scenarios));

        if (!missing.isEmpty() || !extra.isEmpty() || !duplicated.isEmpty()) {
            StringBuilder message = new StringBuilder(MISMATCH_HEADER);
            appendListing(message, "missing", missing);
            appendListing(message, "extra", extra);
            appendListing(message, "duplicated", duplicated);
            fail(message.toString());
        }
    }

    /**
     * Resolves the fixtures directory as the parent of the {@code fixtures/.gitkeep} test-classpath resource.
     *
     * <p>The resource is looked up through the thread context class loader, and through the class loader of this
     * class when the thread has none or the context class loader does not find it.
     *
     * @return the directory; empty when the resource is not found, its URL protocol is not {@code file}, or its URL
     *         is not a valid URI
     */
    private static Optional<Path> fixturesDirectory() {
        URL anchor = null;
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        if (contextLoader != null) {
            anchor = contextLoader.getResource(FIXTURES_ANCHOR);
        }
        if (anchor == null) {
            ClassLoader ownLoader = FixtureParityTest.class.getClassLoader();
            anchor = ownLoader == null ? null : ownLoader.getResource(FIXTURES_ANCHOR);
        }
        if (anchor == null || !"file".equals(anchor.getProtocol())) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(Paths.get(anchor.toURI()).getParent());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    /**
     * Lists the fixture identities of a fixtures directory.
     *
     * <p>Only the top-level entries of {@code dir} are read; subdirectories such as {@code diagnostic/} are never
     * entered. A regular file whose name ends with {@code .json} yields its name without that suffix; every other
     * entry, {@code .gitkeep}, {@code *.txt} files and directories included, is ignored.
     *
     * @param dir the fixtures directory
     * @return the identities in natural order; empty when {@code dir} is not an existing directory
     * @throws UncheckedIOException if listing {@code dir} fails
     */
    private static List<String> fixtureIdentities(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(dir)) {
            return entries
                    .filter(Files::isRegularFile)
                    .map(entry -> entry.getFileName().toString())
                    .filter(name -> name.endsWith(FIXTURE_SUFFIX))
                    .map(name -> name.substring(0, name.length() - FIXTURE_SUFFIX.length()))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (NoSuchFileException e) {
            return List.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Listing " + dir + " failed", e);
        }
    }

    /**
     * Reads the scenario identities of {@code SCENARIOS.txt}: one identity per non-blank line, trimmed.
     *
     * @param dir the fixtures directory
     * @return the identities in file order, repeated lines kept; empty when the file is absent
     * @throws IOException if reading the file fails
     */
    private static List<String> scenarioIdentities(Path dir) throws IOException {
        return identityLines(dir.resolve(SCENARIOS_FILE));
    }

    /**
     * Reads the identities of {@code UNCAPTURABLE.txt}.
     *
     * <p>Each non-blank trimmed line yields the text before the first {@code " \u2014 "} separator, trimmed; a line
     * without the separator yields the whole line.
     *
     * @param dir the fixtures directory
     * @return the identities in file order, repeated lines kept; empty when the file is absent
     * @throws IOException if reading the file fails
     */
    private static List<String> uncapturableIdentities(Path dir) throws IOException {
        List<String> identities = new ArrayList<>();
        for (String line : identityLines(dir.resolve(UNCAPTURABLE_FILE))) {
            int separator = line.indexOf(UNCAPTURABLE_SEPARATOR);
            identities.add(separator < 0 ? line : line.substring(0, separator).trim());
        }
        return identities;
    }

    /**
     * Reads a UTF-8 text file as trimmed, non-blank lines.
     *
     * @param file the file to read
     * @return the trimmed non-blank lines in file order; empty when {@code file} is not an existing regular file
     * @throws IOException if reading the file fails
     */
    private static List<String> identityLines(Path file) throws IOException {
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
     * Collects the identities that occur more than once in a list.
     *
     * @param identities the identities to inspect
     * @return a mutable sorted set of every identity occurring at least twice
     */
    private static Set<String> repeatedIdentities(List<String> identities) {
        Set<String> seen = new HashSet<>();
        Set<String> repeated = new TreeSet<>();
        for (String identity : identities) {
            if (!seen.add(identity)) {
                repeated.add(identity);
            }
        }
        return repeated;
    }

    /**
     * Appends {@code "\n<label>: <identities>"} to a failure message when {@code identities} is not empty.
     *
     * @param message    the message being built
     * @param label      the listing prefix: {@code missing}, {@code extra} or {@code duplicated}
     * @param identities the sorted identities of the listing
     */
    private static void appendListing(StringBuilder message, String label, Set<String> identities) {
        if (!identities.isEmpty()) {
            message.append('\n').append(label).append(": ").append(identities);
        }
    }
}
