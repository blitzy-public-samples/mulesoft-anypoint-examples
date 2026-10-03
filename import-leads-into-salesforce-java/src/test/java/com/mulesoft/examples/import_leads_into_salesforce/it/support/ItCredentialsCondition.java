package com.mulesoft.examples.import_leads_into_salesforce.it.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
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
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.reader.UnicodeReader;

/**
 * Disables a test class annotated with {@link EnabledIfItCredentials} unless {@code application-it.yml} on
 * the test classpath supplies every listed key with a value that is neither blank nor {@code TODO}. Keys
 * resolve in nested-map or flat dotted form. A file with a mapping key that holds a recursive alias disables
 * the class before any value is constructed. See D-021, D-353 and D-372.
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
     * without it the element is enabled. Otherwise {@value #RESOURCE} is read in full and composed into
     * SnakeYAML's node graph. When a key of any mapping in that graph reaches, through its keys, values and
     * items, a mapping or a sequence that contains an alias to itself, as in {@code ? [&m {x: *m}] : v}, the
     * element is disabled with a reason naming only the file and no value is constructed (D-372); a
     * recursive alias reachable only from mapping values and sequence items leaves the file to the checks
     * below. The file is then loaded with SnakeYAML's {@link SafeConstructor} and the annotation's keys are
     * checked in declared order. The first key that resolves to nothing, {@code null}, a mapping or a
     * sequence, to a blank value, or to the trimmed value {@code TODO} disables the element with a reason
     * naming that key; non-string values are compared through {@link String#valueOf(Object)}. A missing,
     * unreadable or unparseable file disables the element; a file counts as unparseable when SnakeYAML raises
     * any runtime exception while composing or loading it, an explicitly typed scalar that does not convert
     * to its type included (D-353). A document that is empty or whose root is not a mapping supplies no key.
     * Reasons name the file and, where one applies, the key, and never include file content or an exception
     * message. This method throws no exception.
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
            byte[] bytes = stream.readAllBytes();
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Node node = yaml.compose(new UnicodeReader(new ByteArrayInputStream(bytes)));
            if (node != null && hasRecursiveKey(node)) {
                return ConditionEvaluationResult.disabled(
                        RESOURCE + " contains a recursive alias in a mapping key (D-021)");
            }
            Object document = yaml.load(new ByteArrayInputStream(bytes));
            root = document instanceof Map<?, ?> map ? map : Map.of();
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read (D-021)");
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed (D-021)");
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

    /**
     * Reports whether a key of a mapping in a composed YAML node graph reaches a recursive alias
     * (D-372).
     *
     * <p>The walk is iterative and compares nodes by identity. It first visits each node reachable from
     * {@code root} through mapping values and sequence items once and collects the key node of every entry
     * of each visited mapping. It then visits each node reachable from those key nodes through mapping keys,
     * mapping values and sequence items once. A node that SnakeYAML's composer marks for two-step
     * construction contains an alias to itself; reaching one in the second walk makes the result
     * {@code true}.
     *
     * @param root the composed document node
     * @return {@code true} when a mapping key reaches a mapping or a sequence that contains an alias to
     *         itself; {@code false} otherwise, a recursive alias reachable only from mapping values and
     *         sequence items included
     */
    private static boolean hasRecursiveKey(Node root) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        Deque<Node> keyReachable = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    keyReachable.push(tuple.getKeyNode());
                    pending.push(tuple.getValueNode());
                }
            } else if (node instanceof SequenceNode sequence) {
                for (Node item : sequence.getValue()) {
                    pending.push(item);
                }
            }
        }
        Set<Node> reached = Collections.newSetFromMap(new IdentityHashMap<>());
        while (!keyReachable.isEmpty()) {
            Node node = keyReachable.pop();
            if (node.isTwoStepsConstruction()) {
                return true;
            }
            if (!reached.add(node)) {
                continue;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    keyReachable.push(tuple.getKeyNode());
                    keyReachable.push(tuple.getValueNode());
                }
            } else if (node instanceof SequenceNode sequence) {
                for (Node item : sequence.getValue()) {
                    keyReachable.push(item);
                }
            }
        }
        return false;
    }
}
