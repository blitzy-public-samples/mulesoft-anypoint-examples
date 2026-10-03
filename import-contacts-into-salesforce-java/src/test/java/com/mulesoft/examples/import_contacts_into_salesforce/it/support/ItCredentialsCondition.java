package com.mulesoft.examples.import_contacts_into_salesforce.it.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.reader.UnicodeReader;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials} (D-021).
 *
 * <p>Reads {@value #IT_CREDENTIALS_RESOURCE} from the test classpath with SnakeYAML and enables the
 * annotated element only when the file exists, can be read and parsed, holds no recursive alias, its root
 * is a mapping and every key named in {@link EnabledIfItCredentials#keys()} is present with a value that
 * is neither blank nor {@code TODO}. Nested mappings and literal dotted keys both resolve to the same
 * dotted key. Reasons name the file and the failing key, never a value, other file content or an
 * exception message.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    public static final String IT_CREDENTIALS_RESOURCE = "application-it.yml";

    /**
     * Creates the condition; JUnit instantiates it through {@code @ExtendWith} on
     * {@link EnabledIfItCredentials} (D-021).
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation is enabled. For an annotated element,
     * {@value #IT_CREDENTIALS_RESOURCE} is looked up through the thread context class loader, or through
     * the class loader of this class when the thread has none, and checked by
     * {@link #evaluate(InputStream, String...)} against the annotation's keys.
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
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ItCredentialsCondition.class.getClassLoader();
        }
        InputStream yaml = loader.getResourceAsStream(IT_CREDENTIALS_RESOURCE);
        return evaluate(yaml, annotation.get().keys());
    }

    /**
     * Checks a {@value #IT_CREDENTIALS_RESOURCE} document against the required keys (D-021).
     *
     * <p>The stream is closed before this method returns. Checks run in this order, and the first
     * failure decides the result:
     * <ol>
     *   <li>the stream is {@code null}: the file is not on the test classpath;</li>
     *   <li>reading or closing the stream fails: the file could not be read;</li>
     *   <li>composing the document into SnakeYAML's node graph raises a runtime exception, malformed YAML
     *       and a stream of more than one document included: the file could not be parsed;</li>
     *   <li>the node graph holds a recursive alias, an alias to a mapping or a sequence from inside that
     *       same mapping or sequence: the file contains a recursive alias, and no value is constructed
     *       (D-353);</li>
     *   <li>constructing the document's values raises a runtime exception, an explicitly typed scalar that
     *       does not convert to its type included: the file could not be parsed;</li>
     *   <li>the document root is not a mapping (an empty document counts as an empty mapping);</li>
     *   <li>for each key in the given order: the key is missing, its value is {@code null} or blank, or
     *       its trimmed value is {@code TODO}. Non-string values are compared through
     *       {@link Object#toString()}.</li>
     * </ol>
     * Nested mappings are flattened into dotted keys, so {@code sfdc: {user: u}} and {@code sfdc.user: u}
     * both satisfy the key {@code sfdc.user}. With no keys, any mapping passes. Reasons name the file and
     * the failing key and never include file content or an exception message.
     *
     * @param yamlOrNull the YAML document, or {@code null} when the file is absent
     * @param keys       dotted property keys that the document must hold
     * @return enabled when every key holds a usable value; disabled with a reason naming the first failing
     *         check and key otherwise
     * @throws NullPointerException if {@code keys} is {@code null} and {@code yamlOrNull} is not
     */
    public static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String... keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " not found on the test classpath");
        }
        byte[] bytes;
        try (InputStream stream = yamlOrNull) {
            Objects.requireNonNull(keys, "keys");
            bytes = stream.readAllBytes();
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " could not be read");
        }
        Object document;
        try {
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Node node = yaml.compose(new UnicodeReader(new ByteArrayInputStream(bytes)));
            if (node != null && isRecursive(node)) {
                return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " contains a recursive alias");
            }
            document = yaml.load(new ByteArrayInputStream(bytes));
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " could not be parsed");
        }
        Map<?, ?> root;
        if (document == null) {
            root = Map.of();
        } else if (document instanceof Map<?, ?> mapping) {
            root = mapping;
        } else {
            return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " root is not a mapping");
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        flatten("", root, properties);
        for (String key : keys) {
            if (!properties.containsKey(key)) {
                return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " is missing key " + key);
            }
            Object value = properties.get(key);
            if (value == null || value.toString().isBlank()) {
                return ConditionEvaluationResult.disabled(
                        IT_CREDENTIALS_RESOURCE + " has an empty value for key " + key);
            }
            if (value.toString().trim().equals("TODO")) {
                return ConditionEvaluationResult.disabled(
                        IT_CREDENTIALS_RESOURCE + " has a TODO value for key " + key);
            }
        }
        return ConditionEvaluationResult.enabled(IT_CREDENTIALS_RESOURCE + " holds every required key");
    }

    /**
     * Copies every leaf of {@code source} into {@code target} under its dotted key (D-021).
     *
     * <p>The key of an entry is its own key when {@code prefix} is empty and {@code prefix.key} otherwise.
     * A mapping value is descended into; any other value, {@code null} included, is stored under the key.
     * A later entry that resolves to the same dotted key replaces an earlier one.
     *
     * @param prefix dotted key of {@code source}, empty for the document root
     * @param source the mapping to copy
     * @param target the flattened keys and values
     */
    private static void flatten(String prefix, Map<?, ?> source, Map<String, Object> target) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty()
                    ? String.valueOf(entry.getKey())
                    : prefix + "." + entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(key, nested, target);
            } else {
                target.put(key, entry.getValue());
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
