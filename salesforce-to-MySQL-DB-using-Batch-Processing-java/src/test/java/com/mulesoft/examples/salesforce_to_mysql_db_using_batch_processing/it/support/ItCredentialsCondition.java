package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * Enables a test class that carries {@link EnabledIfItCredentials} only when {@code application-it.yml}
 * on the test classpath holds every key the annotation lists with a value that is not blank and not
 * {@code TODO} (D-021).
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
     * <p>A context without a test class, or whose test class does not carry the annotation, is enabled.
     * Otherwise {@code application-it.yml} is looked up through the test class's class loader, or through
     * the thread context class loader when the test class has none, and checked by
     * {@link #evaluate(String, String[], ClassLoader)} against the annotation's keys.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every listed key holds a usable value; disabled
     *         with the first failing check otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<Class<?>> testClass = context.getTestClass();
        Optional<EnabledIfItCredentials> annotation = testClass
                .flatMap(type -> AnnotationSupport.findAnnotation(type, EnabledIfItCredentials.class));
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("no @EnabledIfItCredentials");
        }
        ClassLoader loader = testClass.get().getClassLoader();
        if (loader == null) {
            loader = Thread.currentThread().getContextClassLoader();
        }
        return evaluate(RESOURCE, annotation.get().keys(), loader);
    }

    /**
     * Checks a YAML resource on the given class loader against the required keys.
     *
     * <p>Every document of the resource is parsed with SnakeYAML's {@link SafeConstructor}, and documents
     * that are not mappings are ignored. The first failing check decides the result, and no reason
     * contains a value:
     * <ol>
     *   <li>the resource is absent: {@code <resource> not found on the test classpath};</li>
     *   <li>the resource cannot be read or parsed: {@code <resource> could not be parsed: <detail>}, where
     *       the detail is the parser's problem with its 1-based line and column, or the simple class name
     *       of the exception when it marks no problem;</li>
     *   <li>for each key in the given order, with its value taken from the first document that holds
     *       one: {@code <resource>: <key> is missing} when there is no value or the value is a mapping or
     *       a collection, {@code <resource>: <key> is blank} when its trimmed string form is empty, and
     *       {@code <resource>: <key> is TODO} when its trimmed string form is {@code TODO} in any letter
     *       case.</li>
     * </ol>
     * A dotted key resolves as a literal entry first, then through each dotted prefix, left to right,
     * that names a nested mapping, so {@code sfdc.user: u} and {@code sfdc: {user: u}} both hold
     * {@code sfdc.user}. When every key holds a usable value, or no key is listed, the result is enabled.
     *
     * @param resourceName classpath resource to read
     * @param keys         dotted property keys the resource must hold
     * @param classLoader  class loader the resource is looked up through
     * @return enabled when every key holds a usable value; disabled with a reason naming the resource and
     *         the first failing check otherwise
     * @throws NullPointerException if {@code resourceName}, {@code keys} or {@code classLoader} is
     *                              {@code null}
     */
    static ConditionEvaluationResult evaluate(String resourceName, String[] keys, ClassLoader classLoader) {
        Objects.requireNonNull(resourceName, "resourceName");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(classLoader, "classLoader");
        InputStream stream = classLoader.getResourceAsStream(resourceName);
        if (stream == null) {
            return ConditionEvaluationResult.disabled(resourceName + " not found on the test classpath");
        }
        List<Map<?, ?>> documents = new ArrayList<>();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            for (Object document : yaml.loadAll(reader)) {
                if (document instanceof Map<?, ?> mapping) {
                    documents.add(mapping);
                }
            }
        } catch (YAMLException | IOException e) {
            return ConditionEvaluationResult.disabled(resourceName + " could not be parsed: " + describe(e));
        }
        for (String key : keys) {
            Object value = lookup(documents, key);
            if (value == null || value instanceof Map || value instanceof Collection) {
                return ConditionEvaluationResult.disabled(resourceName + ": " + key + " is missing");
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return ConditionEvaluationResult.disabled(resourceName + ": " + key + " is blank");
            }
            if (text.equalsIgnoreCase("TODO")) {
                return ConditionEvaluationResult.disabled(resourceName + ": " + key + " is TODO");
            }
        }
        return ConditionEvaluationResult.enabled("all IT credential keys present");
    }

    /**
     * Returns the first non-null value that {@link #resolve(Map, String)} finds for a key, in document
     * order.
     *
     * @param documents the mapping documents of the resource, in the order they appear
     * @param key       dotted property key to look up
     * @return the value, or {@code null} when no document holds one
     */
    private static Object lookup(List<Map<?, ?>> documents, String key) {
        for (Map<?, ?> document : documents) {
            Object value = resolve(document, key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /**
     * Looks a dotted key up in a mapping: first as a literal entry, then, at each {@code .} from left to
     * right, as the rest of the key inside the nested mapping that the part before the {@code .} names.
     *
     * @param map the mapping to search
     * @param key dotted property key to look up
     * @return the first non-null value found, or {@code null} when there is none
     */
    private static Object resolve(Map<?, ?> map, String key) {
        Object literal = map.get(key);
        if (literal != null) {
            return literal;
        }
        for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
            if (map.get(key.substring(0, dot)) instanceof Map<?, ?> nested) {
                Object resolved = resolve(nested, key.substring(dot + 1));
                if (resolved != null) {
                    return resolved;
                }
            }
        }
        return null;
    }

    /**
     * Describes a read or parse failure without quoting the resource's content.
     *
     * @param failure the exception raised while reading or parsing the resource
     * @return the marked problem with its 1-based line and column when SnakeYAML marks one, otherwise the
     *         exception's simple class name
     */
    private static String describe(Exception failure) {
        if (failure instanceof MarkedYAMLException marked && marked.getProblem() != null) {
            if (marked.getProblemMark() == null) {
                return marked.getProblem();
            }
            return marked.getProblem()
                    + " at line " + (marked.getProblemMark().getLine() + 1)
                    + ", column " + (marked.getProblemMark().getColumn() + 1);
        }
        return failure.getClass().getSimpleName();
    }
}
