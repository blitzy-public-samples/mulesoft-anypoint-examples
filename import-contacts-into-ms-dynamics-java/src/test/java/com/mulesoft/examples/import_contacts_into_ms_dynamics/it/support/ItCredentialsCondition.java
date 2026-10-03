package com.mulesoft.examples.import_contacts_into_ms_dynamics.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials}. It disables a live-sandbox test class
 * before any Spring context starts when its credentials are not supplied (D-021).
 *
 * <p>Reads {@value #RESOURCE}, the git-ignored credential file (D-012), from the test classpath with SnakeYAML
 * and enables the annotated class only when every key named in {@link EnabledIfItCredentials#keys()} holds a
 * scalar value that is neither blank nor {@code TODO}. A disabled reason names the file and, for a key check,
 * the first failing key; no reason contains a value from the file.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials (D-012). */
    public static final String RESOURCE = "application-it.yml";

    /**
     * Creates the condition; JUnit instantiates it through {@code @ExtendWith} on
     * {@link EnabledIfItCredentials} (D-021).
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation is enabled with the reason {@code @EnabledIfItCredentials not present}.
     * For an annotated element, {@value #RESOURCE} is opened through the thread context class loader, or through
     * the class loader of this class when the thread has none, checked by {@link #evaluate(InputStream, String[])}
     * against the annotation's keys, and closed. When closing the file fails, the element is disabled with
     * {@code application-it.yml could not be read: <message>}.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every required key holds a usable value; disabled with the
     *         reason of the first failing check otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> annotation =
                AnnotationSupport.findAnnotation(context.getElement(), EnabledIfItCredentials.class);
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("@EnabledIfItCredentials not present");
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ItCredentialsCondition.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            return evaluate(in, annotation.get().keys());
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read: " + e.getMessage());
        }
    }

    /**
     * Checks an {@value #RESOURCE} document against the required keys (D-021). The stream is read and left open
     * for the caller to close.
     *
     * <p>The checks run in this order, and the first one that applies gives the result:
     * <ol>
     *   <li>{@code yamlOrNull} is {@code null}: disabled, {@code application-it.yml not found on the test
     *       classpath};</li>
     *   <li>{@code keys} is {@code null} or empty: enabled, {@code all IT credentials present in
     *       application-it.yml};</li>
     *   <li>the stream cannot be read or parsed as one YAML document, a global tag such as {@code !!java.io.File}
     *       or a scalar that does not fit its explicit tag included: disabled,
     *       {@code application-it.yml could not be parsed: <message>}. For a failure SnakeYAML marks with a
     *       position, the message is its context and problem with the line and column; for any other failure it
     *       is the simple class name of the exception. It holds no text of the file;</li>
     *   <li>the document is empty or its root is not a mapping: disabled,
     *       {@code application-it.yml is empty or not a YAML mapping};</li>
     *   <li>the first key, in the given order, without a usable value: disabled,
     *       {@code <key> is missing or TODO in application-it.yml};</li>
     *   <li>otherwise: enabled, {@code all IT credentials present in application-it.yml}.</li>
     * </ol>
     * A key resolves first as a nested path, its {@code .}-separated segments looked up through nested mappings,
     * and, when that path yields no value, as a literal top-level key; {@code dynamics: {oauth: {client-id: x}}}
     * and {@code dynamics.oauth.client-id: x} both hold {@code dynamics.oauth.client-id}. A value is usable when
     * it is neither {@code null}, a mapping nor a collection, and its {@link String#valueOf(Object)} form, trimmed,
     * is neither empty nor exactly {@code TODO}. No exception from reading or parsing the stream propagates.
     *
     * @param yamlOrNull the {@value #RESOURCE} document, or {@code null} when the file is absent
     * @param keys       dotted property keys the document must hold, such as {@code dynamics.service-url}
     * @return enabled when every key holds a usable value; disabled with the reason of the first failing check
     *         otherwise
     */
    public static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath");
        }
        if (keys == null || keys.length == 0) {
            return ConditionEvaluationResult.enabled("all IT credentials present in " + RESOURCE);
        }
        Object root;
        try {
            root = new Yaml(new LoaderOptions()).load(yamlOrNull);
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed: " + describe(e));
        }
        if (!(root instanceof Map<?, ?> mapping)) {
            return ConditionEvaluationResult.disabled(RESOURCE + " is empty or not a YAML mapping");
        }
        for (String key : keys) {
            if (!isUsable(resolve(mapping, key))) {
                return ConditionEvaluationResult.disabled(key + " is missing or TODO in " + RESOURCE);
            }
        }
        return ConditionEvaluationResult.enabled("all IT credentials present in " + RESOURCE);
    }

    /**
     * Returns the value of {@code key} in {@code root}: the value at its nested path or, when that path yields
     * {@code null}, the value of the literal top-level key. A {@code null} key has no value.
     *
     * @param root the document's root mapping
     * @param key  a dotted property key
     * @return the resolved value, or {@code null} when neither form holds one
     */
    private static Object resolve(Map<?, ?> root, String key) {
        if (key == null) {
            return null;
        }
        Object nested = nestedValue(root, key);
        return nested != null ? nested : root.get(key);
    }

    /**
     * Walks the {@code .}-separated segments of {@code key} through nested mappings, starting at {@code root}.
     *
     * @param root the document's root mapping
     * @param key  a dotted property key
     * @return the value of the last segment, or {@code null} when a segment is missing or an intermediate value
     *         is not a mapping
     */
    private static Object nestedValue(Map<?, ?> root, String key) {
        Object current = root;
        for (String segment : key.split("\\.", -1)) {
            if (!(current instanceof Map<?, ?> mapping)) {
                return null;
            }
            current = mapping.get(segment);
        }
        return current;
    }

    /**
     * Reports whether {@code value} is a usable credential value.
     *
     * @param value a resolved value, possibly {@code null}
     * @return {@code false} for {@code null}, a mapping or a collection, and for a value whose trimmed
     *         {@link String#valueOf(Object)} form is empty or exactly {@code TODO}; {@code true} otherwise
     */
    private static boolean isUsable(Object value) {
        if (value == null || value instanceof Map || value instanceof Collection) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !"TODO".equals(text);
    }

    /**
     * Describes a read or parse failure with no text of the file.
     *
     * @param failure the exception SnakeYAML raised
     * @return for a {@link MarkedYAMLException}, its context and problem with the line and column of the
     *         problem; for any other exception, its simple class name
     */
    private static String describe(RuntimeException failure) {
        if (!(failure instanceof MarkedYAMLException marked)) {
            return failure.getClass().getSimpleName();
        }
        StringBuilder text = new StringBuilder();
        if (marked.getContext() != null) {
            text.append(marked.getContext()).append("; ");
        }
        text.append(marked.getProblem() != null ? marked.getProblem() : failure.getClass().getSimpleName());
        if (marked.getProblemMark() != null) {
            text.append(" at line ").append(marked.getProblemMark().getLine() + 1)
                    .append(", column ").append(marked.getProblemMark().getColumn() + 1);
        }
        return text.toString();
    }
}
