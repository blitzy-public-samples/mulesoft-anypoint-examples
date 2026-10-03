package com.mulesoft.examples.import_leads_into_salesforce.it.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Unit tests of {@link ItCredentialsCondition}, the class-level gate behind {@link EnabledIfItCredentials}.
 * See D-021.
 *
 * <p>Each test writes {@code application-it.yml}, when it needs one, into its own temporary directory and
 * reads it through a {@link URLClassLoader} over that directory whose parent is the platform class loader.
 * A copy of the file on the test classpath is not visible to these loaders. Every loader a test opens is
 * closed after the test.
 */
class ItCredentialsConditionTest {

    /** Nested-form file supplying both keys of {@link GatedSample}. */
    private static final String NESTED = """
            sfdc:
              user: alice@example.com
              login-url: https://login.salesforce.com
            """;

    /** Flat dotted-form file supplying both keys of {@link GatedSample}. */
    private static final String FLAT = """
            sfdc.user: alice@example.com
            sfdc.login-url: https://login.salesforce.com
            """;

    /** Nested-form file supplying {@code sfdc.user} only. */
    private static final String MISSING_LOGIN_URL = """
            sfdc:
              user: alice@example.com
            """;

    /** Nested-form file whose {@code sfdc.user} is {@code TODO} surrounded by spaces. */
    private static final String TODO_USER = """
            sfdc:
              user: "  TODO "
              login-url: https://login.salesforce.com
            """;

    /** Nested-form file whose {@code sfdc.user} holds spaces only. */
    private static final String BLANK_USER = """
            sfdc:
              user: "   "
              login-url: https://login.salesforce.com
            """;

    /** File holding an unclosed flow sequence, which SnakeYAML cannot parse. */
    private static final String UNCLOSED = """
            sfdc: [unclosed
            """;

    /** Fresh temporary directory of the current test. */
    @TempDir
    Path dir;

    /** Class loaders opened by the current test, closed by {@link #closeLoaders()}. */
    private final List<URLClassLoader> loaders = new ArrayList<>();

    /**
     * Closes every class loader the test opened. The first close failure is rethrown after all loaders have
     * been closed, with any later failure attached as suppressed.
     *
     * @throws IOException when a loader cannot be closed
     */
    @AfterEach
    void closeLoaders() throws IOException {
        IOException failure = null;
        for (URLClassLoader loader : loaders) {
            try {
                loader.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        loaders.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /** No {@code application-it.yml}: the class is disabled with a reason naming the file. See D-021. */
    @Test
    void fileAbsentDisables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(null), GatedSample.class);

        assertTrue(result.isDisabled(), reason(result));
        assertTrue(reason(result).contains("application-it.yml"), reason(result));
    }

    /** A listed key absent from the file disables the class with a reason naming that key. See D-021. */
    @Test
    void missingKeyDisables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(MISSING_LOGIN_URL), GatedSample.class);

        assertTrue(result.isDisabled(), reason(result));
        assertTrue(reason(result).contains("sfdc.login-url"), reason(result));
    }

    /**
     * A {@code TODO} value, compared after trimming, disables the class with a reason naming the key and
     * {@code TODO}. See D-021.
     */
    @Test
    void todoKeyDisables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(TODO_USER), GatedSample.class);

        assertTrue(result.isDisabled(), reason(result));
        assertTrue(reason(result).contains("sfdc.user"), reason(result));
        assertTrue(reason(result).contains("TODO"), reason(result));
    }

    /** A value of spaces only disables the class with a reason naming the key as blank. See D-021. */
    @Test
    void blankKeyDisables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(BLANK_USER), GatedSample.class);

        assertTrue(result.isDisabled(), reason(result));
        assertTrue(reason(result).contains("sfdc.user"), reason(result));
        assertTrue(reason(result).contains("blank"), reason(result));
    }

    /** Both keys supplied in nested-map form enable the class. See D-021. */
    @Test
    void nestedFormEnables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(NESTED), GatedSample.class);

        assertFalse(result.isDisabled(), reason(result));
    }

    /** Both keys supplied as flat dotted keys enable the class. See D-021. */
    @Test
    void flatDottedFormEnables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(FLAT), GatedSample.class);

        assertFalse(result.isDisabled(), reason(result));
    }

    /** A class without {@link EnabledIfItCredentials} is enabled when no file exists. See D-021. */
    @Test
    void noAnnotationEnables() throws IOException {
        ConditionEvaluationResult result = evaluate(loaderWith(null), UngatedSample.class);

        assertFalse(result.isDisabled(), reason(result));
    }

    /**
     * A file SnakeYAML cannot parse disables the class with a reason naming the file, and the evaluation
     * throws nothing. See D-021 and D-353.
     */
    @Test
    void unreadableFileDisables() throws IOException {
        ItCredentialsCondition condition = new ItCredentialsCondition(loaderWith(UNCLOSED));
        ExtensionContext context = contextFor(GatedSample.class);

        ConditionEvaluationResult result = assertDoesNotThrow(() -> condition.evaluateExecutionCondition(context));

        assertTrue(result.isDisabled(), reason(result));
        assertTrue(reason(result).contains("application-it.yml"), reason(result));
    }

    /**
     * The public constructor reads the file through the thread context class loader: a context loader that
     * holds a complete file enables the class, and one over an empty directory disables it. The previous
     * context loader is restored afterwards. See D-021.
     */
    @Test
    void publicConstructorUsesContextClassLoader() throws IOException {
        ClassLoader withFile = loaderWith(NESTED);
        ClassLoader withoutFile = loaderOn(Files.createDirectory(dir.resolve("empty")));
        ExtensionContext context = contextFor(GatedSample.class);
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(withFile);
            ConditionEvaluationResult enabled = new ItCredentialsCondition().evaluateExecutionCondition(context);
            thread.setContextClassLoader(withoutFile);
            ConditionEvaluationResult disabled = new ItCredentialsCondition().evaluateExecutionCondition(context);

            assertFalse(enabled.isDisabled(), reason(enabled));
            assertTrue(disabled.isDisabled(), reason(disabled));
            assertTrue(reason(disabled).contains("application-it.yml"), reason(disabled));
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    /**
     * Evaluates a condition created over {@code loader} for the class {@code type}.
     *
     * @param loader the class loader the condition reads {@code application-it.yml} through
     * @param type   the test class being evaluated
     * @return the evaluation result
     */
    private static ConditionEvaluationResult evaluate(ClassLoader loader, Class<?> type) {
        return new ItCredentialsCondition(loader).evaluateExecutionCondition(contextFor(type));
    }

    /**
     * Returns the reason text of {@code result}.
     *
     * @param result an evaluation result
     * @return the reason, or the empty string when the result has none
     */
    private static String reason(ConditionEvaluationResult result) {
        return result.getReason().orElse("");
    }

    /**
     * Returns a class loader over {@link #dir}, after writing {@code yamlOrNull} there as
     * {@code application-it.yml} in UTF-8 when it is not {@code null}.
     *
     * @param yamlOrNull the file content, or {@code null} to leave the file absent
     * @return a class loader that finds resources under {@link #dir} and through the platform class loader only
     * @throws IOException when the file cannot be written or the directory URL cannot be formed
     */
    private ClassLoader loaderWith(String yamlOrNull) throws IOException {
        if (yamlOrNull != null) {
            Files.writeString(dir.resolve(ItCredentialsCondition.RESOURCE), yamlOrNull, StandardCharsets.UTF_8);
        }
        return loaderOn(dir);
    }

    /**
     * Opens a class loader over the directory {@code root}, with the platform class loader as its parent, and
     * registers it for {@link #closeLoaders()}.
     *
     * @param root an existing directory
     * @return a class loader that finds resources under {@code root} and through the platform class loader only
     * @throws IOException when the directory URL cannot be formed
     */
    private ClassLoader loaderOn(Path root) throws IOException {
        URLClassLoader loader = new URLClassLoader(
                new URL[] {root.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        loaders.add(loader);
        return loader;
    }

    /**
     * Returns a mock extension context whose element and test class are both {@code type}.
     *
     * @param type the test class to report
     * @return the mock context
     */
    private static ExtensionContext contextFor(Class<?> type) {
        ExtensionContext ctx = Mockito.mock(ExtensionContext.class);
        when(ctx.getElement()).thenReturn(Optional.<AnnotatedElement>of(type));
        when(ctx.getTestClass()).thenReturn(Optional.<Class<?>>of(type));
        return ctx;
    }

    /** Sample class gated on {@code sfdc.user} and {@code sfdc.login-url}; it declares no tests. */
    @EnabledIfItCredentials(keys = {"sfdc.user", "sfdc.login-url"})
    static class GatedSample {
    }

    /** Sample class without {@link EnabledIfItCredentials}; it declares no tests. */
    static class UngatedSample {
    }
}
