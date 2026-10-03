package com.mulesoft.examples.get_customer_list_from_netsuite.it.support;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials} (D-021).
 *
 * <p>Reads {@code application-it.yml} from the test classpath with SnakeYAML and enables the annotated test
 * class only when the file exists, its first YAML document is a mapping and every key named in
 * {@link EnabledIfItCredentials#keys()} holds a scalar value that, trimmed, is neither empty nor
 * {@code TODO}. JUnit evaluates it before any test instance or Spring application context is created.
 * Disabled reasons name the file and the failing key, never a value.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    private static final String IT_CREDENTIALS_RESOURCE = "application-it.yml";

    /** Placeholder value of the committed template that marks a credential not yet supplied. */
    private static final String TODO = "TODO";

    /** Result of a key lookup that found no entry, distinct from an entry whose value is {@code null}. */
    private static final Object MISSING = new Object();

    /**
     * Creates the condition; JUnit instantiates it through the {@code @ExtendWith} meta-annotation on
     * {@link EnabledIfItCredentials}.
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation is enabled. For an annotated element, {@code application-it.yml}
     * is looked up through the thread context class loader, or through the class loader of this class when
     * the thread has none, and checked by {@link #evaluate(URL, String[])} against the annotation's keys.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every required key holds a usable value; disabled with
     *         the reason of the first failing check otherwise
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> annotation =
                AnnotationSupport.findAnnotation(context.getElement(), EnabledIfItCredentials.class);
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("No @EnabledIfItCredentials");
        }
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = ItCredentialsCondition.class.getClassLoader();
        }
        return evaluate(loader.getResource(IT_CREDENTIALS_RESOURCE), annotation.get().keys());
    }

    /**
     * Checks the {@code application-it.yml} resource at {@code resource} against the required keys (D-021).
     * It never throws; every failure is reported as a disabled result.
     *
     * <p>Checks run in this order, and the first failure decides the result:
     * <ol>
     *   <li>{@code resource} is {@code null}: the file is not on the test classpath;</li>
     *   <li>opening, reading or parsing the resource fails with an {@link IOException}, a
     *       {@link YAMLException} or any other {@link RuntimeException}: the reason names the exception's
     *       simple class name. A first document holding a mapping key that reaches, through aliases, a
     *       mapping or sequence containing itself, such as {@code ? [&q {z: *q}] : v}, fails with a
     *       {@link YAMLException} before it is constructed (D-507);</li>
     *   <li>the resource holds no YAML document, or its first document is {@code null}: the file is empty;</li>
     *   <li>the first document is not a mapping. Documents after the first are not parsed;</li>
     *   <li>for each key in array order: the key is {@code null}, has no entry, or its value is {@code null}, a
     *       mapping, a sequence or set, empty after {@code String.valueOf(value).trim()}, or, after that trim,
     *       exactly {@code TODO}.</li>
     * </ol>
     * A dotted key resolves first as a literal key of the mapping; otherwise each dotted prefix, shortest
     * first, that names a nested mapping is descended into with the remainder of the key, and the first entry
     * found decides the value. {@code netsuite: {account: a}}, {@code netsuite.account: a} and mixed forms
     * such as {@code netsuite: {oauth.client-id: c}} therefore all satisfy their dotted keys. With
     * {@code null} or no keys, any readable mapping passes.
     *
     * @param resource location of {@code application-it.yml}, or {@code null} when the file is absent
     * @param keys     dotted property names that the first document must hold; {@code null} or empty requires
     *                 none
     * @return enabled when every key holds a usable value; disabled with a reason naming the file and, for a
     *         key check, the first failing key otherwise
     */
    public static ConditionEvaluationResult evaluate(URL resource, String[] keys) {
        if (resource == null) {
            return ConditionEvaluationResult.disabled(
                    IT_CREDENTIALS_RESOURCE + " not found on the test classpath (D-021)");
        }
        Object document;
        try {
            document = readFirstDocument(resource);
        } catch (IOException | RuntimeException e) {
            return ConditionEvaluationResult.disabled(
                    IT_CREDENTIALS_RESOURCE + " could not be read: " + e.getClass().getSimpleName() + " (D-021)");
        }
        if (document == null) {
            return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " is empty (D-021)");
        }
        if (!(document instanceof Map<?, ?> root)) {
            return ConditionEvaluationResult.disabled(IT_CREDENTIALS_RESOURCE + " root is not a mapping (D-021)");
        }
        if (keys != null) {
            for (String key : keys) {
                Object value = key == null ? MISSING : resolve(root, key);
                if (!isUsable(value)) {
                    return ConditionEvaluationResult.disabled(
                            IT_CREDENTIALS_RESOURCE + " key '" + key + "' is missing, blank or TODO (D-021)");
                }
            }
        }
        return ConditionEvaluationResult.enabled(IT_CREDENTIALS_RESOURCE + " holds all required keys");
    }

    /**
     * Reads the YAML text at {@code resource} as UTF-8, composes its first document into SnakeYAML nodes,
     * rejects it when a mapping key reaches a mapping or sequence containing itself (D-507), then constructs
     * that document with a {@link SafeConstructor} and default {@link LoaderOptions}. The reader is closed
     * before the method returns.
     *
     * @param resource location of the YAML file
     * @return the first document, or {@code null} when the stream holds no document or the first is empty
     * @throws IOException   if the resource cannot be opened, read or closed
     * @throws YAMLException if the first document is not well-formed YAML, holds a construct the
     *                       {@link SafeConstructor} rejects, or holds a mapping key that reaches, through
     *                       aliases, a mapping or sequence containing itself
     */
    private static Object readFirstDocument(URL resource) throws IOException {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        try (Reader reader = new InputStreamReader(resource.openStream(), StandardCharsets.UTF_8)) {
            StringWriter content = new StringWriter();
            reader.transferTo(content);
            String text = content.toString();
            // The composed node graph of the first document is checked before construction (D-507).
            Iterator<Node> nodes = yaml.composeAll(new StringReader(text)).iterator();
            if (nodes.hasNext() && hasRecursiveKey(nodes.next())) {
                throw new YAMLException("Mapping key reaches a recursive mapping or sequence");
            }
            Iterator<Object> documents = yaml.loadAll(text).iterator();
            return documents.hasNext() ? documents.next() : null;
        }
    }

    /**
     * Tells whether a mapping key of the composed document {@code root} reaches, through aliases, a mapping or
     * sequence that contains itself (D-507). The graph is walked from {@code root} through mapping values and
     * sequence items, each node once by identity, and every mapping key met is checked with
     * {@link #reachesCycle(Node, Set, Map)}.
     *
     * @param root the composed first document
     * @return {@code true} when such a mapping key exists; {@code false} otherwise
     */
    private static boolean hasRecursiveKey(Node root) {
        Map<Node, Boolean> known = new IdentityHashMap<>();
        Set<Node> path = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    if (reachesCycle(tuple.getKeyNode(), path, known)) {
                        return true;
                    }
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

    /**
     * Tells whether {@code node}, its mapping keys and values, or its sequence items reach a node that is
     * already on the current descent {@code path}, that is, a mapping or sequence containing itself. Each
     * node's answer is stored in {@code known} and reused; {@code path} is restored before the method returns.
     *
     * @param node  the node to check
     * @param path  the nodes of the current descent, compared by identity
     * @param known the answers already computed, keyed by node identity
     * @return {@code true} when a mapping or sequence containing itself is reachable from {@code node}
     */
    private static boolean reachesCycle(Node node, Set<Node> path, Map<Node, Boolean> known) {
        Boolean cached = known.get(node);
        if (cached != null) {
            return cached;
        }
        if (!path.add(node)) {
            return true;
        }
        boolean result = false;
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (reachesCycle(tuple.getKeyNode(), path, known)
                        || reachesCycle(tuple.getValueNode(), path, known)) {
                    result = true;
                    break;
                }
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                if (reachesCycle(item, path, known)) {
                    result = true;
                    break;
                }
            }
        }
        path.remove(node);
        known.put(node, result);
        return result;
    }

    /**
     * Looks up the dotted {@code key} in {@code mapping}: first as a literal key, then by descending into
     * each nested mapping named by a dotted prefix of {@code key}, shortest prefix first, with the remainder
     * of the key.
     *
     * @param mapping the mapping to search
     * @param key     the dotted key
     * @return the value of the first entry found, {@code null} included, or {@link #MISSING} when no entry
     *         matches
     */
    private static Object resolve(Map<?, ?> mapping, String key) {
        if (mapping.containsKey(key)) {
            return mapping.get(key);
        }
        for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
            if (mapping.get(key.substring(0, dot)) instanceof Map<?, ?> nested) {
                Object value = resolve(nested, key.substring(dot + 1));
                if (value != MISSING) {
                    return value;
                }
            }
        }
        return MISSING;
    }

    /**
     * Tells whether a resolved value can serve as a credential.
     *
     * @param value the value returned by {@link #resolve(Map, String)}
     * @return {@code false} for {@link #MISSING}, {@code null}, a mapping, a collection, and a value whose
     *         {@code String.valueOf(value).trim()} form is empty or exactly {@code TODO}; {@code true}
     *         otherwise
     */
    private static boolean isUsable(Object value) {
        if (value == MISSING || value == null || value instanceof Map || value instanceof Collection) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !TODO.equals(text);
    }
}
