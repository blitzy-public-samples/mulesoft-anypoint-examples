package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * JUnit 5 condition behind {@code @EnabledIfItCredentials}: reads {@code application-it.yml} from the test
 * classpath and disables the test class unless every listed key is present and not {@code TODO} (D-021).
 * Disabled reasons name keys, never values.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /** Template value of a key that has not been supplied. */
    static final String PLACEHOLDER = "TODO";

    /** Creates the condition that {@link EnabledIfItCredentials} registers through {@code @ExtendWith}. */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>{@code application-it.yml} is looked up through the class loader of the test class, else the thread
     * context class loader, checked by {@link #evaluate(InputStream, String[])} against the annotation's keys,
     * and closed before this method returns.
     *
     * @param context the extension context of the element being evaluated
     * @return enabled with {@code no @EnabledIfItCredentials} when the element carries no annotation; disabled
     *         with {@code application-it.yml could not be read} when closing the resource fails; otherwise the
     *         result of {@link #evaluate(InputStream, String[])}
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> annotation =
                AnnotationSupport.findAnnotation(context.getElement(), EnabledIfItCredentials.class);
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("no @EnabledIfItCredentials");
        }
        try (InputStream in = classLoaderOf(context).getResourceAsStream(RESOURCE)) {
            return evaluate(in, annotation.get().keys());
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read");
        }
    }

    /**
     * Checks the content of {@code application-it.yml} against the required keys (D-021). The stream is read
     * and left open.
     *
     * <p>Every YAML document in the stream that is a mapping is flattened and merged into one set of dotted
     * keys, a later entry replacing an earlier one under the same key; any other document, and an empty
     * stream, contributes no key. A mapping under {@code k} contributes {@code k.<child key>}, a literal
     * dotted key is kept as written, and a sequence is one value. A key is satisfied when its value is not
     * {@code null} and its trimmed string form is neither empty nor {@code TODO}.
     *
     * @param yamlOrNull the content of {@code application-it.yml}, or {@code null} when the file is absent
     * @param keys       the dotted keys to check, in order; {@code null} or empty checks none
     * @return enabled with {@code application-it.yml holds every required key} when every key is satisfied;
     *         disabled with {@code application-it.yml not found} for a {@code null} stream, with
     *         {@code application-it.yml could not be parsed} when the stream is not well-formed YAML, and
     *         otherwise with {@code application-it.yml lacks or has TODO for: } followed by the unsatisfied
     *         keys in order, without duplicates, separated by {@code ", "}. No reason contains a value (D-012)
     */
    public static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found");
        }
        List<Map<?, ?>> mappings = new ArrayList<>();
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object document : yaml.loadAll(new InputStreamReader(yamlOrNull, StandardCharsets.UTF_8))) {
                if (document instanceof Map<?, ?> mapping) {
                    mappings.add(mapping);
                }
            }
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        for (Map<?, ?> mapping : mappings) {
            flatten(null, mapping, properties);
        }
        Set<String> unsatisfied = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys) {
                if (!isSupplied(properties.get(key))) {
                    unsatisfied.add(key);
                }
            }
        }
        if (unsatisfied.isEmpty()) {
            return ConditionEvaluationResult.enabled(RESOURCE + " holds every required key");
        }
        return ConditionEvaluationResult.disabled(
                RESOURCE + " lacks or has TODO for: " + String.join(", ", unsatisfied));
    }

    /**
     * Returns the class loader that {@code application-it.yml} is looked up through.
     *
     * @param context the extension context of the element being evaluated
     * @return the test class's class loader, else the thread context class loader
     */
    private static ClassLoader classLoaderOf(ExtensionContext context) {
        return context.getTestClass()
                .map(Class::getClassLoader)
                .orElseGet(() -> Thread.currentThread().getContextClassLoader());
    }

    /**
     * Copies every non-mapping value of {@code source} into {@code target} under its dotted key.
     *
     * <p>An entry's key is {@code String.valueOf} of its own key at the document root and
     * {@code prefix + "." + String.valueOf(key)} below it. A mapping value is descended into; any other
     * value, {@code null} and sequences included, is stored under the key, replacing an earlier value.
     *
     * @param prefix the dotted key of {@code source}, or {@code null} for the document root
     * @param source the mapping to copy
     * @param target the flattened keys and values
     */
    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix == null
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + String.valueOf(entry.getKey());
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else {
                target.put(key, entry.getValue());
            }
        }
    }

    /**
     * Reports whether a flattened value counts as a supplied credential.
     *
     * @param value the value stored under a key, or {@code null} when the key is absent or holds {@code null}
     * @return {@code true} when the trimmed string form of {@code value} is neither empty nor {@code TODO}
     */
    private static boolean isSupplied(Object value) {
        if (value == null) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !PLACEHOLDER.equals(text);
    }
}
