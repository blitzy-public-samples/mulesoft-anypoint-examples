package com.mulesoft.examples.import_leads_into_salesforce.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
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
 * Disables a test class annotated with {@link EnabledIfItCredentials} unless {@code application-it.yml} on
 * the test classpath supplies every listed key with a value that is neither blank nor {@code TODO}. Keys
 * resolve in nested-map or flat dotted form. See D-021.
 */
public class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    private final ClassLoader classLoader;

    /**
     * Creates a condition that reads {@value #RESOURCE} through the thread context class loader, or through
     * the class loader of this class when the thread has none.
     */
    public ItCredentialsCondition() {
        this(null);
    }

    /**
     * Creates a condition that reads {@value #RESOURCE} through {@code classLoader}.
     *
     * @param classLoader the loader to read through; {@code null} selects the thread context class loader,
     *                    or the class loader of this class when the thread has none
     */
    ItCredentialsCondition(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} for the current test class or method.
     *
     * <p>The annotation is looked up on the context's element and, when absent there, on its test class;
     * without it the element is enabled. Otherwise {@value #RESOURCE} is loaded with SnakeYAML's
     * {@link SafeConstructor} and the annotation's keys are checked in declared order. The first key that
     * resolves to nothing, {@code null}, a mapping or a sequence, to a blank value, or to the trimmed value
     * {@code TODO} disables the element with a reason naming that key; non-string values are compared
     * through {@link String#valueOf(Object)}. A missing or unreadable file disables the element. A document
     * that is empty or whose root is not a mapping supplies no key. This method never throws.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every listed key holds a usable value; disabled with
     *         the first failing check otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> annotation = findAnnotation(context.getElement());
        if (annotation.isEmpty()) {
            annotation = findAnnotation(context.getTestClass());
        }
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("@EnabledIfItCredentials not present");
        }
        String[] keys = annotation.get().keys();
        Map<?, ?> root;
        try (InputStream stream = loader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath (D-021)");
            }
            Object document = new Yaml(new SafeConstructor(new LoaderOptions())).load(stream);
            root = document instanceof Map<?, ?> map ? map : Map.of();
        } catch (YAMLException | IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read: " + e.getMessage());
        }
        for (String key : keys) {
            Object value = resolve(root, key);
            if (value == null || value instanceof Map || value instanceof List) {
                return ConditionEvaluationResult.disabled(RESOURCE + " has no value for key '" + key + "'");
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return ConditionEvaluationResult.disabled(RESOURCE + " key '" + key + "' is blank");
            }
            if (text.equals("TODO")) {
                return ConditionEvaluationResult.disabled(RESOURCE + " key '" + key + "' is TODO");
            }
        }
        return ConditionEvaluationResult.enabled(RESOURCE + " supplies " + String.join(", ", keys));
    }

    /**
     * Finds {@link EnabledIfItCredentials} on {@code element}.
     *
     * @param element the element to search; {@code null} and empty count as absent
     * @return the annotation, or empty when the element is absent or does not carry it
     */
    private static Optional<EnabledIfItCredentials> findAnnotation(Optional<? extends AnnotatedElement> element) {
        if (element == null || element.isEmpty()) {
            return Optional.empty();
        }
        return AnnotationSupport.findAnnotation(element, EnabledIfItCredentials.class);
    }

    /**
     * Returns the class loader {@value #RESOURCE} is read through: the one this condition was created with,
     * else the thread context class loader, else the class loader of this class.
     *
     * @return the class loader to read through
     */
    private ClassLoader loader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader contextLoader = Thread.currentThread().getContextClassLoader();
        return contextLoader != null ? contextLoader : ItCredentialsCondition.class.getClassLoader();
    }

    /**
     * Resolves a dotted key against a mapping.
     *
     * <p>A literal entry for the whole {@code key} wins and its value is returned, {@code null} included.
     * Otherwise {@code key} is split at each {@code .}, from left to right, into a prefix and the rest; where
     * the prefix maps to a nested mapping, the rest is resolved against it, and the first non-null result is
     * returned. Keys match exactly and case-sensitively.
     *
     * @param map the mapping to search
     * @param key the dotted key
     * @return the value, or {@code null} when no form of the key resolves
     */
    private static Object resolve(Map<?, ?> map, String key) {
        if (map.containsKey(key)) {
            return map.get(key);
        }
        for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
            if (map.get(key.substring(0, dot)) instanceof Map<?, ?> nested) {
                Object value = resolve(nested, key.substring(dot + 1));
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }
}
