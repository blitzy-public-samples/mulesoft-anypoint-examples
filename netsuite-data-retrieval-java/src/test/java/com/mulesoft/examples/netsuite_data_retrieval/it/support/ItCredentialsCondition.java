package com.mulesoft.examples.netsuite_data_retrieval.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
 * JUnit 5 execution condition registered by {@link EnabledIfItCredentials}: the Tier 2B credential gate (D-021).
 *
 * <p>A test element without the annotation is enabled. For an annotated test class the condition reads
 * {@value #RESOURCE} from the test classpath and enables the class only when every key named in
 * {@link EnabledIfItCredentials#keys()} resolves to a value that is neither blank nor {@code TODO}.
 * {@value #RESOURCE} is the git-ignored copy of the committed {@code application-it.example.yml} (D-012).
 *
 * <p>The condition uses no Spring type, and JUnit evaluates this class-level condition before
 * {@code SpringExtension} creates the application context: a disabled class starts no application context,
 * authenticates no client, runs no poller and is reported as skipped with the returned reason. A reason names the
 * file and at most one key, never a value read from the file.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials (D-012). */
    static final String RESOURCE = "application-it.yml";

    /** Result of a key that matches no entry, distinct from an entry whose value is {@code null}. */
    private static final Object NOT_RESOLVED = new Object();

    /**
     * Creates the condition. JUnit instantiates it through the {@code @ExtendWith} meta-annotation of
     * {@link EnabledIfItCredentials} (D-021).
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>Without the annotation the element is enabled. With it, {@value #RESOURCE} is opened through the thread
     * context class loader, or through the class loader of this class when the thread has none, checked by
     * {@link #evaluate(InputStream, String[])} against the annotation's keys, and closed.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every required key holds a usable value; disabled with
     *         the reason of the first failing check otherwise
     * @throws UncheckedIOException if closing {@value #RESOURCE} fails
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
        try (InputStream in = loader.getResourceAsStream(RESOURCE)) {
            return evaluate(in, annotation.get().keys());
        } catch (IOException e) {
            throw new UncheckedIOException("Closing " + RESOURCE + " failed", e);
        }
    }

    /**
     * Checks a {@value #RESOURCE} stream against the required keys (D-021).
     *
     * <p>The stream is read to its end through SnakeYAML's {@link UnicodeReader}, which detects a UTF-8, UTF-16BE
     * or UTF-16LE byte order mark and otherwise decodes UTF-8 (D-616); the stream is not closed. Only the first YAML
     * document is composed and constructed, with SnakeYAML's {@link SafeConstructor}; later documents are neither
     * composed nor constructed. The checks run in this order, and the first failing one decides the result:
     * <ol>
     *   <li>the stream is {@code null}: the file is not on the test classpath;</li>
     *   <li>reading the stream, or composing the first document, throws, on an undecodable byte or on invalid
     *       syntax: the file is not valid YAML;</li>
     *   <li>a key of a mapping in the first document reaches a mapping or a sequence that contains itself through
     *       an alias, such as {@code ? [&q {z: *q}]}: the file holds a recursive mapping key (D-352);</li>
     *   <li>constructing the first document throws, on a scalar that its explicit tag cannot convert, such as
     *       {@code !!float abc}: the file is not valid YAML (D-616);</li>
     *   <li>for each key in the given order: a key that resolves to no entry, or to a mapping or a list, is
     *       missing; a key whose value is {@code null}, or whose string form is empty once
     *       {@link String#trim() trimmed}, is blank; a key whose trimmed string form is exactly {@code TODO} is
     *       {@code TODO}.</li>
     * </ol>
     * Every reason names the file and at most the failing key, never a value read from the file; the reasons of the
     * second, third and fourth checks name only the file.
     *
     * <p>A key resolves first as a literal entry of the current mapping, then, for each dot from left to right,
     * as the part before the dot naming a nested mapping in which the rest of the key resolves. The keys
     * {@code nets.item.quantity: 5}, {@code nets: {item: {quantity: 5}}} and {@code nets.item: {quantity: 5}}
     * therefore all hold {@code nets.item.quantity}. An empty document, or a document whose root is not a mapping,
     * holds no key. A scalar value that is not a string is checked through its string form.
     *
     * @param yamlOrNull the {@value #RESOURCE} stream, or {@code null} when the file is absent
     * @param keys       dotted property names the document must hold
     * @return enabled when every key holds a usable value; disabled with a reason naming the file and the first
     *         failing key otherwise
     * @throws NullPointerException if {@code keys} or one of its elements is {@code null}
     */
    static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        Objects.requireNonNull(keys, "keys");
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath");
        }
        Object root;
        try {
            StringWriter content = new StringWriter();
            new UnicodeReader(yamlOrNull).transferTo(content);
            String yamlText = content.toString();
            Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
            Iterator<Node> nodes = yaml.composeAll(new StringReader(yamlText)).iterator();
            if (hasRecursiveKey(nodes.hasNext() ? nodes.next() : null)) {
                return ConditionEvaluationResult.disabled(RESOURCE + " holds a recursive mapping key");
            }
            Iterator<Object> documents = yaml.loadAll(yamlText).iterator();
            root = documents.hasNext() ? documents.next() : null;
        } catch (RuntimeException | IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " is not valid YAML");
        }
        for (String key : keys) {
            Objects.requireNonNull(key, "keys element");
            Object value = resolve(root, key);
            if (value == NOT_RESOLVED || value instanceof Map<?, ?> || value instanceof Collection<?>) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": key " + key + " is missing");
            }
            String text = value == null ? "" : value.toString().trim();
            if (text.isEmpty()) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": key " + key + " is blank");
            }
            if (text.equals("TODO")) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": key " + key + " is TODO");
            }
        }
        return ConditionEvaluationResult.enabled(RESOURCE + " holds all " + keys.length + " keys");
    }

    /**
     * Tells whether a key of a mapping in a composed YAML node graph reaches a cycle (D-352).
     *
     * <p>Every mapping and sequence reachable from {@code root} through mapping values and sequence items is visited
     * once, through an explicit stack. For each entry of a visited mapping, its key node is checked with
     * {@link #reachesCycle(Node, Set, Map)}. Nodes are compared by identity.
     *
     * @param root the composed root node of a document, or {@code null} when the stream holds no document
     * @return {@code true} when a mapping key reaches a mapping or sequence that contains itself through an alias;
     *         {@code false} for {@code null} and for a graph without such a key
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
     * Tells whether a composed YAML node reaches a cycle through its keys, values and items (D-352).
     *
     * <p>A node already present in {@code onPath} closes a cycle. A mapping reaches a cycle when one of its key or
     * value nodes does, a sequence when one of its items does, and a scalar never does. Each result is stored in
     * {@code known} and returned for later visits of the same node. Nodes are compared by identity.
     *
     * @param node   the node to check
     * @param onPath the nodes on the current path from the checked key, identity-based; restored on return
     * @param known  results of nodes already checked, identity-based; extended with the result for {@code node}
     * @return {@code true} when {@code node} or a node it reaches contains itself through an alias
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
     * Resolves a dotted key in a parsed YAML node (D-021).
     *
     * <p>A node that is not a mapping resolves nothing. In a mapping, an entry whose key equals {@code key} is
     * returned first; otherwise each dot of {@code key}, from left to right, splits it into a prefix naming a
     * nested node and a rest resolved in that node, and the first split that resolves is returned.
     *
     * @param node the parsed node, possibly {@code null}
     * @param key  the dotted key to resolve
     * @return the value of the matching entry, {@code null} included, or {@link #NOT_RESOLVED} when no entry
     *         matches
     */
    private static Object resolve(Object node, String key) {
        if (!(node instanceof Map<?, ?> mapping)) {
            return NOT_RESOLVED;
        }
        if (mapping.containsKey(key)) {
            return mapping.get(key);
        }
        for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
            Object value = resolve(mapping.get(key.substring(0, dot)), key.substring(dot + 1));
            if (value != NOT_RESOLVED) {
                return value;
            }
        }
        return NOT_RESOLVED;
    }
}
