package com.mulesoft.examples.dataweave_with_flowreflookup.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
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

/**
 * JUnit 5 execution condition that evaluates {@link EnabledIfItCredentials} on a test class.
 *
 * <p>The condition reads the classpath resource {@code application-it.yml} as UTF-8 YAML. Nested maps are
 * resolved to dotted names, so {@code sfdc: {user: u}} and the literal key {@code sfdc.user: u} both define
 * {@code sfdc.user}. A key that is absent, or whose value is {@code null}, blank or {@code TODO} (compared
 * case-insensitively after trimming), is treated as missing. When the resource is absent, unreadable,
 * unparseable or not a mapping, a mapping key of the resource reaches a mapping or sequence that contains
 * itself through a YAML alias, a mapping of the resource contains itself through a YAML alias, flattening
 * visits more than {@value #MAX_FLATTENED_ENTRIES} mapping entries, or any key listed in
 * {@link EnabledIfItCredentials#keys()} is missing, the test class is disabled with a reason that names the
 * resource and the missing keys but never a value from the file. The condition creates no Spring context and
 * reads no Spring configuration.
 *
 * <p>See D-021, D-351, D-352 and D-371 in {@code DECISIONS.md}.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /**
     * Largest number of mapping entries that {@link #flatten(String, Map, Map, Set, int[])} visits for one
     * document, counting each visit of a mapping that several aliases share (D-371).
     */
    static final int MAX_FLATTENED_ENTRIES = 10_000;

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
     * annotation's keys; that method also reports a failure while reading the resource. When closing the
     * resource throws an {@link IOException}, the class is disabled with {@code "application-it.yml could
     * not be read: <exception class>"}; the reason names the exception class only. No checked exception
     * leaves this method.
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
     *   <li>reading {@code yamlOrNull} to its end throws an {@link IOException}: {@code "application-it.yml
     *       could not be parsed: <exception class>"}; the reason names the exception class only;</li>
     *   <li>SnakeYAML throws while it composes the text into a node graph, as for a syntax error or a
     *       stream of more than one document: {@code "application-it.yml could not be parsed: <exception
     *       class>"}; the reason names the exception class only;</li>
     *   <li>a key of a mapping, at any depth of the composed graph, reaches through a YAML alias a mapping
     *       or sequence that contains itself, as in {@code ? [&q {z: *q}]}: {@code "application-it.yml
     *       holds a recursive mapping"}; the document is not constructed, and the reason names no key or
     *       value of the document (D-351, D-352);</li>
     *   <li>SnakeYAML, with its safe constructor, throws while it constructs the document from the text:
     *       {@code "application-it.yml could not be parsed: <exception class>"}; the reason names the
     *       exception class only;</li>
     *   <li>the document is empty or its root is not a mapping: {@code "application-it.yml is empty or not
     *       a mapping"};</li>
     *   <li>while the document is flattened in document order, a mapping that contains itself through a
     *       YAML alias, directly or through nested mappings, is met first: {@code "application-it.yml holds
     *       a recursive mapping"} (D-351); or more than {@value #MAX_FLATTENED_ENTRIES} mapping entries are
     *       visited first, each visit of a mapping that several aliases share counted again:
     *       {@code "application-it.yml holds more than 10000 mapping entries"} (D-371); neither reason
     *       names a key or value of the document;</li>
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
        StringWriter content = new StringWriter();
        try {
            yamlOrNull.transferTo(content);
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(
                    RESOURCE + " could not be parsed: " + e.getClass().getName());
        }
        String text = content.toString();
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object document;
        try {
            Node node = yaml.compose(new StringReader(text));
            if (hasRecursiveKey(node)) {
                return ConditionEvaluationResult.disabled(RESOURCE + " holds a recursive mapping");
            }
            document = yaml.load(text);
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(
                    RESOURCE + " could not be parsed: " + e.getClass().getName());
        }
        if (!(document instanceof Map<?, ?> root)) {
            return ConditionEvaluationResult.disabled(RESOURCE + " is empty or not a mapping");
        }
        Map<String, String> properties = new HashMap<>();
        Set<Map<?, ?>> path = Collections.newSetFromMap(new IdentityHashMap<>());
        int[] budget = {MAX_FLATTENED_ENTRIES};
        if (!flatten("", root, properties, path, budget)) {
            return ConditionEvaluationResult.disabled(budget[0] < 0
                    ? RESOURCE + " holds more than " + MAX_FLATTENED_ENTRIES + " mapping entries"
                    : RESOURCE + " holds a recursive mapping");
        }
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
     * Reports whether a key of any mapping in a composed YAML node graph reaches a mapping or sequence that
     * contains itself through an alias.
     *
     * <p>The graph is walked from {@code root} through mapping values and sequence items with an explicit
     * stack, visiting each node once by identity. The key of every mapping tuple reached is checked with
     * {@link #reachesCycle(Node, Set, Map)}, sharing one identity-based result cache across all keys; the
     * walk stops at the first key that reaches a cycle. Scalar nodes end their branch. A mapping that
     * contains itself only through its values, and is reached through no key, does not count.
     *
     * @param root the composed root node of the document, or {@code null} for an empty document
     * @return {@code true} when some mapping key reaches a cycle; {@code false} otherwise, and always for
     *         {@code null}
     */
    private static boolean hasRecursiveKey(Node root) {
        if (root == null) {
            return false;
        }
        Map<Node, Boolean> known = new IdentityHashMap<>();
        Set<Node> onPath = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Node> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!seen.add(node)) {
                continue;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    if (reachesCycle(tuple.getKeyNode(), onPath, known)) {
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
     * Reports whether a cycle of mappings and sequences is reachable from {@code node}.
     *
     * <p>The method descends depth-first into the keys and values of a mapping node and the items of a
     * sequence node; a scalar node reaches no cycle. {@code onPath} holds, by identity, the nodes on the
     * current descent: reaching a node already in it is a cycle. {@code known} caches, by identity, the
     * result of every node whose descent has finished, and a cached node is answered without descending
     * again. On return {@code onPath} holds the same nodes as on entry.
     *
     * @param node   the node to check
     * @param onPath the nodes on the current descent, held in an identity-based set
     * @param known  the finished nodes and their results, held in an identity-based map
     * @return {@code true} when {@code node} is on the current descent or a cycle is reachable from it;
     *         {@code false} otherwise
     */
    private static boolean reachesCycle(Node node, Set<Node> onPath, Map<Node, Boolean> known) {
        Boolean cached = known.get(node);
        if (cached != null) {
            return cached;
        }
        if (!onPath.add(node)) {
            return true;
        }
        boolean result = false;
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                if (reachesCycle(tuple.getKeyNode(), onPath, known)
                        || reachesCycle(tuple.getValueNode(), onPath, known)) {
                    result = true;
                    break;
                }
            }
        } else if (node instanceof SequenceNode sequence) {
            for (Node item : sequence.getValue()) {
                if (reachesCycle(item, onPath, known)) {
                    result = true;
                    break;
                }
            }
        }
        onPath.remove(node);
        known.put(node, result);
        return result;
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
     * <p>{@code path} holds, by identity, the maps on the current descent from the document root. A
     * {@code source} not yet in {@code path} is added on entry and removed again when this method returns,
     * whatever the result, so a map that several aliases share without containing itself is flattened once
     * under each of its names. A {@code source} already in {@code path} is one of its own ancestors: the
     * method then returns {@code false} without descending and leaves {@code path} unchanged, and each
     * enclosing call returns {@code false} at once, leaving {@code target} with only the names copied before
     * the cycle was reached (D-351).
     *
     * <p>{@code budget[0]} is decremented once for every entry visited, on every visit of a map that several
     * aliases share. When it falls below zero, the method returns {@code false} before handling that entry,
     * and each enclosing call returns {@code false} at once; {@code budget[0]} then stays negative, which
     * tells this outcome apart from a map that contains itself (D-371).
     *
     * @param prefix dotted name of {@code source}, empty for the document root
     * @param source the map to copy
     * @param target the flattened names and their values
     * @param path   the maps from the document root down to the parent of {@code source}, held in an
     *               identity-based set; empty for the document root
     * @param budget a one-element array holding the number of entries that may still be visited
     * @return {@code true} when {@code source} and every map nested in it were flattened; {@code false} when
     *         {@code source} is already in {@code path}, a map nested in {@code source} contains itself
     *         through an alias, or the budget ran out
     */
    private static boolean flatten(String prefix, Map<?, ?> source, Map<String, String> target,
            Set<Map<?, ?>> path, int[] budget) {
        if (!path.add(source)) {
            return false;
        }
        try {
            for (Map.Entry<?, ?> entry : source.entrySet()) {
                if (--budget[0] < 0) {
                    return false;
                }
                String name = prefix.isEmpty()
                        ? String.valueOf(entry.getKey())
                        : prefix + "." + entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Map<?, ?> nested) {
                    if (!flatten(name, nested, target, path, budget)) {
                        return false;
                    }
                } else if (value != null && !(value instanceof Collection<?>)
                        && !isUsable(target.get(name))) {
                    target.put(name, String.valueOf(value));
                }
            }
            return true;
        } finally {
            path.remove(source);
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
