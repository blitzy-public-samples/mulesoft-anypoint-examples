package com.mulesoft.examples.http_multipart_request.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Disables the annotated class unless {@code fixtures/} holds a {@code *.json} Tier 2A fixture; see D-023,
 * D-073.
 *
 * <p>The fixtures directory is the parent of the test-classpath resource {@code fixtures/SCENARIOS.txt},
 * {@code target/test-classes/fixtures/} in a Maven build. Only its direct children are inspected: a child
 * counts as a fixture when it is a regular file whose name ends with {@code .json}. {@code .gitkeep},
 * {@code SCENARIOS.txt}, {@code UNCAPTURABLE.txt} and every subdirectory, {@code diagnostic/} included,
 * never count. The condition imports nothing from Spring and is evaluated by JUnit before any extension
 * creates an application context.
 *
 * <p>Usage: {@code @ExtendWith(FixturesPresentCondition.class)} on {@code FixtureParityTest}.
 */
public final class FixturesPresentCondition implements ExecutionCondition {

    /** Test-classpath resource whose parent directory holds the Tier 2A fixtures. */
    private static final String SCENARIOS_RESOURCE = "fixtures/SCENARIOS.txt";

    /** File-name suffix of a Tier 2A fixture file. */
    private static final String FIXTURE_SUFFIX = ".json";

    /** Reason reported when the fixtures directory holds no fixture file. */
    private static final String NO_FIXTURES_REASON = "no Tier 2A fixtures captured (D-023)";

    /** Reason reported when {@code fixtures/SCENARIOS.txt} is not on the test classpath. */
    private static final String SCENARIOS_MISSING_REASON = SCENARIOS_RESOURCE + " not found; gate runs";

    /** Suffix of the reason reported when at least one fixture file is present. */
    private static final String FIXTURES_PRESENT_SUFFIX = " Tier 2A fixture file(s) present";

    /** Creates the condition; JUnit instantiates it through {@code @ExtendWith}. */
    public FixturesPresentCondition() {
    }

    /**
     * Decides whether the annotated test class or method runs.
     *
     * <ul>
     *   <li>{@code fixtures/SCENARIOS.txt} is not on the test classpath: enabled, with the reason
     *       {@code fixtures/SCENARIOS.txt not found; gate runs}, and the directory is not inspected
     *       (D-396).</li>
     *   <li>No direct child of the fixtures directory is a regular {@code *.json} file: disabled, with the
     *       reason {@code no Tier 2A fixtures captured (D-023)}.</li>
     *   <li>Otherwise: enabled, with the reason {@code <n> Tier 2A fixture file(s) present}.</li>
     * </ul>
     *
     * @param context the extension context of the element being evaluated; not read
     * @return the enabled or disabled result described above
     * @throws IllegalStateException when the resource location of {@code fixtures/SCENARIOS.txt} is not a
     *                               valid URI
     * @throws UncheckedIOException  when the fixtures directory cannot be listed
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        URL scenarios = FixturesPresentCondition.class.getClassLoader().getResource(SCENARIOS_RESOURCE);
        if (scenarios == null) {
            return ConditionEvaluationResult.enabled(SCENARIOS_MISSING_REASON);
        }
        long fixtures = countFixtures(fixturesDirectory(scenarios));
        if (fixtures == 0) {
            return ConditionEvaluationResult.disabled(NO_FIXTURES_REASON);
        }
        return ConditionEvaluationResult.enabled(fixtures + FIXTURES_PRESENT_SUFFIX);
    }

    /**
     * Returns the directory that contains {@code fixtures/SCENARIOS.txt}.
     *
     * @param scenarios resource location of {@code fixtures/SCENARIOS.txt}
     * @return the parent directory of that resource on the file system
     * @throws IllegalStateException when {@code scenarios} cannot be converted to a URI
     */
    private static Path fixturesDirectory(URL scenarios) {
        try {
            return Path.of(scenarios.toURI()).getParent();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(
                    "invalid resource location of " + SCENARIOS_RESOURCE + ": " + scenarios, e);
        }
    }

    /**
     * Counts the direct children of {@code fixturesDir} that are regular files named {@code *.json}.
     * Subdirectories are not descended into.
     *
     * @param fixturesDir the fixtures directory
     * @return the number of fixture files
     * @throws UncheckedIOException when the directory cannot be listed
     */
    private static long countFixtures(Path fixturesDir) {
        try (Stream<Path> children = Files.list(fixturesDir)) {
            return children.filter(FixturesPresentCondition::isFixture).count();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list Tier 2A fixtures directory " + fixturesDir, e);
        }
    }

    /**
     * Reports whether {@code child} is a regular file whose name ends with {@code .json}.
     *
     * @param child a direct child of the fixtures directory
     * @return {@code true} for a fixture file
     */
    private static boolean isFixture(Path child) {
        return Files.isRegularFile(child) && child.getFileName().toString().endsWith(FIXTURE_SUFFIX);
    }
}
