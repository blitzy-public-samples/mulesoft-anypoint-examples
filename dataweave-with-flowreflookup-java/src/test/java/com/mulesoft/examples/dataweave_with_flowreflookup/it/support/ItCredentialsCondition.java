package com.mulesoft.examples.dataweave_with_flowreflookup.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
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

/**
 * JUnit 5 execution condition that evaluates {@link EnabledIfItCredentials} on a test class.
 *
 * <p>The condition reads the classpath resource {@code application-it.yml} as UTF-8 YAML. Nested maps are
 * resolved to dotted names, so {@code sfdc: {user: u}} and the literal key {@code sfdc.user: u} both define
 * {@code sfdc.user}. A key that is absent, or whose value is {@code null}, blank or {@code TODO} (compared
 * case-insensitively after trimming), is treated as missing. When the resource is absent, unreadable,
 * unparseable or not a mapping, or any key listed in {@link EnabledIfItCredentials#keys()} is missing, the
 * test class is disabled with a reason that names the resource and the missing keys but never a value from
 * the file. The condition creates no Spring context and reads no Spring configuration.
 *
 * <p>See D-021 in {@code DECISIONS.md}.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /**
     * Creates the condition; JUnit instantiates it through {@code @ExtendWith} on
     * {@link EnabledIfItCredentials} (D-021).
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the test class of the current context.
     *
     * <p>Class-level and method-level contexts both look the annotation up once on the test class. A test
     * class without the annotation is enabled. For an annotated class, {@code application-it.yml} is loaded
     * through the test class's own class loader, or through the thread context class loader when the test
     * class has none, read as UTF-8 and checked by {@link #evaluate(Reader, String[])} against the
     * annotation's keys. When reading or closing the resource throws an {@link IOException}, the class is
     * disabled with {@code "application-it.yml could not be read: <exception class>"};
     * the reason names the exception class only. No checked exception leaves this method.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every required key holds a usable value; disabled
     *         with a reason naming the failing check otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Class<?>> testClass = context.getTestClass();
        Optional<EnabledIfItCredentials> annotation =
                AnnotationSupport.findAnnotation(testClass, EnabledIfItCredentials.class);
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("@EnabledIfItCredentials not present");
        }
        String[] keys = annotation.get().keys();
        ClassLoader loader = testClass.get().getClassLoader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        InputStream stream = loader.getResourceAsStream(RESOURCE);
        if (stream == null) {
            return evaluate(null, keys);
        }
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return evaluate(reader, keys);
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(
                    RESOURCE + " could not be read: " + e.getClass().getName());
        }
    }

    /**
     * Checks an {@code application-it.yml} document against the required keys.
     *
     * <p>The checks run in this order, and the first that fails decides the disabled reason:
     * <ol>
     *   <li>{@code yamlOrNull} is {@code null}: {@code "application-it.yml not found on the test
     *       classpath"};</li>
     *   <li>SnakeYAML, with its safe constructor, throws while it loads the document, a failure of the
     *       reader included: {@code "application-it.yml could not be parsed: <exception class>"};
     *       the reason names the exception class only;</li>
     *   <li>the document is empty or its root is not a mapping: {@code "application-it.yml is empty or not
     *       a mapping"};</li>
     *   <li>a key is absent, or holds a {@code null}, blank or {@code TODO} value (compared
     *       case-insensitively after trimming): {@code "application-it.yml has missing or TODO keys
     *       [<key>, ...]"}, listing every such key once, in the given order.</li>
     * </ol>
     * Otherwise the result is enabled with {@code "application-it.yml defines all required keys
     * [<key>, ...]"}. Nested maps are flattened into dotted names before the keys are checked. No reason
     * contains a value from the document. The reader is left open.
     *
     * @param yamlOrNull the YAML document, or {@code null} when the resource is absent
     * @param keys       dotted property names that the document must define
     * @return enabled when every key holds a usable value; disabled with one of the reasons above otherwise
     */
    static ConditionEvaluationResult evaluate(Reader yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath");
        }
        Object document;
        try {
            document = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlOrNull);
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(
                    RESOURCE + " could not be parsed: " + e.getClass().getName());
        }
        if (!(document instanceof Map<?, ?> root)) {
            return ConditionEvaluationResult.disabled(RESOURCE + " is empty or not a mapping");
        }
        Map<String, String> properties = new HashMap<>();
        flatten("", root, properties);
        List<String> missing = new ArrayList<>();
        for (String key : keys) {
            if (!isUsable(properties.get(key)) && !missing.contains(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            return ConditionEvaluationResult.disabled(RESOURCE + " has missing or TODO keys " + missing);
        }
        return ConditionEvaluationResult.enabled(
                RESOURCE + " defines all required keys " + Arrays.toString(keys));
    }

    /**
     * Copies every scalar value of {@code source} into {@code target} under its dotted name.
     *
     * <p>The name of an entry is its key, through {@link String#valueOf(Object)}, when {@code prefix} is
     * empty and {@code prefix.key} otherwise; a literal dotted key and the equivalent nested maps therefore
     * give the same name. A map value is descended into, a sequence or set value is ignored, a {@code null}
     * value leaves the name absent, and any other scalar is stored through {@link String#valueOf(Object)}.
     * When two entries give the same name, a usable value replaces an unusable or absent one and is kept
     * against any later value.
     *
     * @param prefix dotted name of {@code source}, empty for the document root
     * @param source the map to copy
     * @param target the flattened names and their values
     */
    private static void flatten(String prefix, Map<?, ?> source, Map<String, String> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String name = prefix.isEmpty()
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(name, nested, target);
            } else if (value != null && !(value instanceof Collection<?>) && !isUsable(target.get(name))) {
                target.put(name, String.valueOf(value));
            }
        }
    }

    /**
     * Reports whether a flattened value counts as supplied.
     *
     * @param value the value under a dotted name, or {@code null} when the name is absent
     * @return {@code true} when the value is not {@code null}, not blank after trimming and, after trimming,
     *         not {@code TODO} in any letter case
     */
    private static boolean isUsable(String value) {
        return value != null && !value.trim().isEmpty() && !value.trim().equalsIgnoreCase("TODO");
    }
}
