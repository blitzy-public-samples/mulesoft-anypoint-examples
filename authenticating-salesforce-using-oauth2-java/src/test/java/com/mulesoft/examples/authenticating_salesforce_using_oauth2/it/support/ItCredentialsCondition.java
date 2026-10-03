package com.mulesoft.examples.authenticating_salesforce_using_oauth2.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Disables a test class annotated with {@link EnabledIfItCredentials} unless {@code application-it.yml} on the
 * test classpath supplies each named key with a non-blank value other than {@code TODO} (D-021).
 *
 * <p>Nested mappings are read as dotted keys: {@code sfdc: {key: k}} supplies {@code sfdc.key}. A test element
 * without the annotation is enabled. A disabled reason names the file and the unsupplied keys, never a value
 * read from the file.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /** Value that marks a key of {@code application-it.yml} as not supplied. */
    static final String PLACEHOLDER = "TODO";

    /**
     * Creates the condition; JUnit instantiates it through {@code @ExtendWith} on
     * {@link EnabledIfItCredentials}.
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation, such as a test method of an enabled class, is enabled. For an
     * annotated element, {@code application-it.yml} is opened through the thread context class loader, or
     * through the class loader of this class when the thread has none, checked by
     * {@link #evaluate(InputStream, String[])} against the annotation's keys, and closed. An element whose file
     * cannot be closed is disabled.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every named key is supplied; disabled otherwise
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
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            return evaluate(stream, annotation.get().keys());
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": unreadable");
        }
    }

    /**
     * Checks an {@code application-it.yml} document against the named keys (D-021).
     *
     * <p>The stream is read, and left open, as a single UTF-8 YAML document through SnakeYAML's
     * {@link SafeConstructor}; an empty document is an empty mapping. Nested mappings are flattened into
     * dotted keys. A key is supplied when its value is not {@code null}, not blank and, trimmed, not
     * {@code TODO}; a value that is not a string is checked through its string form.
     *
     * @param yamlOrNull the document, or {@code null} when the file is not on the test classpath
     * @param keys       dotted property names the document must supply
     * @return disabled when the stream is {@code null}, is not a single well-formed YAML document, has a root
     *         that is not a mapping, or leaves a key unsupplied, in which case the reason lists every unsupplied
     *         key in the order of {@code keys}; enabled with a reason listing {@code keys} otherwise
     */
    static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": not found on the test classpath");
        }
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new InputStreamReader(yamlOrNull, StandardCharsets.UTF_8));
        } catch (YAMLException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": unreadable YAML");
        }
        Map<?, ?> root;
        if (document == null) {
            root = Map.of();
        } else if (document instanceof Map<?, ?> mapping) {
            root = mapping;
        } else {
            return ConditionEvaluationResult.disabled(RESOURCE + ": root is not a mapping");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        flatten("", root, properties);
        List<String> unsatisfied = new ArrayList<>();
        for (String key : keys) {
            String value = properties.get(key);
            if (value == null || value.trim().isEmpty() || PLACEHOLDER.equals(value.trim())) {
                unsatisfied.add(key);
            }
        }
        if (!unsatisfied.isEmpty()) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": missing or TODO keys " + unsatisfied);
        }
        return ConditionEvaluationResult.enabled(RESOURCE + " supplies " + Arrays.toString(keys));
    }

    /**
     * Copies every leaf of {@code source} into {@code target} under its dotted key (D-021).
     *
     * <p>The key of an entry is its own key when {@code prefix} is empty and {@code prefix.key} otherwise. A
     * mapping value is descended into, a {@code null} value is stored as {@code null}, and any other value is
     * stored as its string form. A later entry that resolves to the same dotted key replaces an earlier one.
     *
     * @param prefix dotted key of {@code source}, empty for the document root
     * @param source the mapping to copy
     * @param target the flattened keys and values
     */
    private static void flatten(String prefix, Map<?, ?> source, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty()
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + String.valueOf(entry.getKey());
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else if (value == null) {
                target.put(key, null);
            } else {
                target.put(key, String.valueOf(value));
            }
        }
    }
}
