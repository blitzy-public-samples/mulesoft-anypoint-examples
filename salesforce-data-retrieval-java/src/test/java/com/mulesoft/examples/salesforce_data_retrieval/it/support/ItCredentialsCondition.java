package com.mulesoft.examples.salesforce_data_retrieval.it.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.reader.UnicodeReader;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials} (D-021).
 *
 * <p>Reads {@code application-it.yml} from the test classpath with SnakeYAML and enables the annotated
 * test class only when the file exists, holds no recursive alias, parses to a YAML map and holds every key
 * named in {@link EnabledIfItCredentials#keys()} with a value that is neither blank nor {@code TODO}. JUnit
 * evaluates the condition before any extension builds a Spring application context. Every disabled
 * reason names the file and, where one applies, the failing key; reasons never include file content,
 * and nothing is logged.
 */
public class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /** Placeholder value of the committed template that marks a key as not yet supplied. */
    static final String TODO = "TODO";

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation is enabled. For an annotated element,
     * {@code application-it.yml} is looked up on the test classpath and checked by
     * {@link #evaluate(URL, String[])} against the annotation's keys.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every required key holds a usable value; disabled
     *         with the first failing check otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> annotation =
                AnnotationSupport.findAnnotation(context.getElement(), EnabledIfItCredentials.class);
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("@EnabledIfItCredentials not present");
        }
        return evaluate(classpathResource(), annotation.get().keys());
    }

    /**
     * Reports whether {@code application-it.yml} on the test classpath holds every given key with a
     * non-blank value other than {@code TODO}, by the checks of {@link #evaluate(URL, String[])}. Used by
     * {@code parity.FixtureParityTest} to decide whether its live fixtures can be replayed (D-021).
     *
     * @param keys dotted property names, for example {@code sfdc.username} (key names per D-015)
     * @return {@code true} when the classpath {@code application-it.yml} holds every key with a non-blank,
     *         non-{@code TODO} value; {@code false} when the file is absent, cannot be read or parsed, holds
     *         a recursive alias, is not a YAML map, or any key is missing, blank or {@code TODO}
     */
    public static boolean credentialsPresent(String... keys) {
        return !evaluate(classpathResource(), keys).isDisabled();
    }

    /**
     * Checks an {@code application-it.yml} document against the required keys (D-021).
     *
     * <p>Checks run in this order, and the first failure decides the result:
     * <ol>
     *   <li>{@code yamlOrNull} is {@code null}: the file is not on the test classpath;</li>
     *   <li>the document cannot be opened or read, or composing it into SnakeYAML's node graph raises a
     *       {@link YAMLException} or any other runtime exception: the file could not be parsed;</li>
     *   <li>the node graph holds a recursive alias, an alias to a mapping or a sequence from inside that
     *       same mapping or sequence: the file contains a recursive alias, and no value is constructed
     *       (D-353);</li>
     *   <li>constructing the document's values raises a {@link YAMLException} or any other runtime
     *       exception: the file could not be parsed;</li>
     *   <li>the loaded document is not a YAML map, an empty document included;</li>
     *   <li>for each key in the given order: the key is missing; its value is {@code null} or its
     *       {@link String#valueOf(Object)} form is blank after trimming; or that trimmed form is exactly
     *       {@code TODO}.</li>
     * </ol>
     * Nested maps are flattened into dotted keys, so {@code sfdc: {username: u}} and
     * {@code sfdc.username: u} both satisfy the key {@code sfdc.username}; keys are matched exactly and
     * case-sensitively. A value that is not a map, a sequence or a set included, is kept unchanged under its
     * dotted key and checked through its {@link String#valueOf(Object)} form. With no keys, only the first
     * five checks apply. The stream opened on {@code yamlOrNull} is closed before this
     * method returns. Reasons name the file and the failing key and never include file content.
     *
     * @param yamlOrNull location of the YAML document, or {@code null} when the file is absent
     * @param keys       dotted property names that the document must hold
     * @return enabled when every key holds a usable value; disabled with a reason naming the first failing
     *         check, and its key where one applies, otherwise
     */
    static ConditionEvaluationResult evaluate(URL yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath (D-021)");
        }
        Object document;
        try (InputStream stream = yamlOrNull.openStream()) {
            byte[] bytes = stream.readAllBytes();
            Yaml yaml = new Yaml(new LoaderOptions());
            Node node = yaml.compose(new UnicodeReader(new ByteArrayInputStream(bytes)));
            if (node != null && isRecursive(node)) {
                return ConditionEvaluationResult.disabled(RESOURCE + " contains a recursive alias (D-021)");
            }
            document = yaml.load(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed (D-021)");
        }
        if (!(document instanceof Map<?, ?> root)) {
            return ConditionEvaluationResult.disabled(RESOURCE + " does not contain a YAML map (D-021)");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        flatten("", root, properties);
        for (String key : keys) {
            if (!properties.containsKey(key)) {
                return ConditionEvaluationResult.disabled(
                        "required key '" + key + "' is missing from " + RESOURCE + " (D-021)");
            }
            Object value = properties.get(key);
            String text = value == null ? "" : String.valueOf(value).trim();
            if (text.isEmpty()) {
                return ConditionEvaluationResult.disabled(
                        "required key '" + key + "' is blank in " + RESOURCE + " (D-021)");
            }
            if (TODO.equals(text)) {
                return ConditionEvaluationResult.disabled(
                        "required key '" + key + "' is still " + TODO + " in " + RESOURCE + " (D-021)");
            }
        }
        return ConditionEvaluationResult.enabled("all required keys present in " + RESOURCE + " (D-021)");
    }

    /**
     * Finds {@code application-it.yml} through the thread context class loader, or through the class
     * loader of this class when the thread has none.
     *
     * @return the resource location, or {@code null} when the test classpath has no such resource
     */
    private static URL classpathResource() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ItCredentialsCondition.class.getClassLoader();
        }
        return loader.getResource(RESOURCE);
    }

    /**
     * Copies every entry of {@code source} into {@code target} under its dotted key.
     *
     * <p>The key of an entry is its own key, through {@link String#valueOf(Object)}, when {@code prefix}
     * is empty, and {@code prefix + "." + key} otherwise; a key that already contains dots is kept as
     * written. A map value is descended into. Any other value, a scalar, a sequence or {@code null}, is
     * stored unchanged under the key, replacing an earlier entry with the same key.
     *
     * @param prefix dotted key of {@code source}, empty for the document root
     * @param source the map to copy
     * @param target the flattened keys and values
     */
    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String name = String.valueOf(entry.getKey());
            String key = prefix.isEmpty() ? name : prefix + "." + name;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else {
                target.put(key, value);
            }
        }
    }

    /**
     * Reports whether a composed YAML node graph holds a recursive alias (D-353).
     *
     * <p>Every node reachable from {@code root} through mapping keys, mapping values and sequence items is
     * visited once, iteratively and by identity. A node that SnakeYAML's composer marks for two-step
     * construction is the target of an alias from inside that same mapping or sequence, and makes the
     * graph recursive.
     *
     * @param root the composed document node
     * @return {@code true} when any reachable node is the target of a recursive alias
     */
    private static boolean isRecursive(Node root) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (node.isTwoStepsConstruction()) {
                return true;
            }
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    pending.push(tuple.getKeyNode());
                    pending.push(tuple.getValueNode());
                }
            } else if (node instanceof SequenceNode sequence) {
                for (Node item : sequence.getValue()) {
                    pending.push(item);
                }
            }
        }
        return false;
    }
}
