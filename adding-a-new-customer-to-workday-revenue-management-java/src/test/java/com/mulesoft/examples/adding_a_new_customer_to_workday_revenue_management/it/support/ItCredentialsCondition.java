package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials} (D-021, D-196, D-352).
 *
 * <p>For an element annotated with {@link EnabledIfItCredentials}, the condition reads the test-classpath
 * resource {@value #RESOURCE} as UTF-8 with SnakeYAML, takes its first YAML document as the credential mapping,
 * and enables the element only when {@link #missingKeys(Map, String...)} reports none of the keys in
 * {@link EnabledIfItCredentials#keys()}. Each key resolves first as a nested path and then as a literal dotted
 * top-level key; a key is missing when it resolves to no value, to {@code null}, to a blank value or to
 * {@code TODO} in any letter case. JUnit evaluates the class-level condition before it instantiates the test
 * class, and a disabled class starts no Spring application context. A missing-keys reason names the file and the
 * keys, never their values. A first document in which a mapping key reaches, through an alias, a mapping or
 * sequence that contains itself disables the element with a fixed reason before that document is constructed
 * (D-352). A parse-failure reason names the exception type and, for a SnakeYAML syntax or structure error, the
 * line and column of its problem; it carries no exception message, no SnakeYAML problem text and no source
 * snippet (D-196, D-350).
 */
public class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /** Placeholder value, matched trimmed and in any letter case, that marks a credential as not supplied. */
    private static final String TODO = "TODO";

    /** Class loader given to the package-private constructor, or {@code null} for the default lookup. */
    private final ClassLoader classLoader;

    /**
     * Creates the condition that JUnit registers through {@code @ExtendWith} on {@link EnabledIfItCredentials}.
     * Each evaluation looks {@value #RESOURCE} up through the context class loader of the evaluating thread, or
     * through the class loader of this class when that thread has none.
     */
    public ItCredentialsCondition() {
        this.classLoader = null;
    }

    /**
     * Creates a condition that looks {@value #RESOURCE} up through {@code classLoader}; {@code null} selects the
     * lookup of {@link #ItCredentialsCondition()}.
     *
     * @param classLoader the class loader that resolves {@value #RESOURCE}, or {@code null}
     */
    ItCredentialsCondition(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021, D-196, D-352).
     *
     * <p>An element that does not carry the annotation, such as a test method of an annotated class, is enabled
     * with {@code "No @EnabledIfItCredentials on this element"}. For an annotated element, in this order:
     * <ol>
     *   <li>{@value #RESOURCE} is opened through the injected class loader, else the context class loader of the
     *       evaluating thread, else the class loader of this class; when it is not on the test classpath the
     *       element is disabled with {@code "application-it.yml not found on the test classpath"};</li>
     *   <li>the whole resource is read as UTF-8 text, and the first YAML document of that text is composed into
     *       SnakeYAML nodes;</li>
     *   <li>when a key of a mapping in the first document reaches, through an alias, a mapping or sequence that
     *       contains itself, the element is disabled with
     *       {@code "application-it.yml holds a recursive mapping key"} (D-352), and the document is not
     *       constructed;</li>
     *   <li>otherwise the first document is constructed and taken as the credential mapping; a file with no
     *       document, an empty document or a document that is not a mapping gives an empty mapping, and later
     *       documents are not parsed;</li>
     *   <li>when opening, reading, parsing or closing the resource throws, the element is disabled with
     *       {@code "application-it.yml could not be parsed: <detail>"} (D-196, D-350); the detail is the exception
     *       class name, followed for a SnakeYAML {@link MarkedYAMLException} with a problem mark by
     *       {@code (line <n>, column <n>)} of that mark, counted from 1; it holds no exception message, no
     *       SnakeYAML problem or context text and no source snippet, for any exception type;</li>
     *   <li>when {@link #missingKeys(Map, String...)} reports keys of {@link EnabledIfItCredentials#keys()}, the
     *       element is disabled with {@code "Missing or TODO credential keys in application-it.yml: <key>, <key>"},
     *       listing the reported keys in annotation order;</li>
     *   <li>otherwise the element is enabled with {@code "All credential keys present in application-it.yml"}.</li>
     * </ol>
     * The method returns a result for every file content and throws no exception of its own.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return the enabled or disabled result with the reason described above
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> gate =
                AnnotationSupport.findAnnotation(context.getElement(), EnabledIfItCredentials.class);
        if (gate.isEmpty()) {
            return ConditionEvaluationResult.enabled("No @EnabledIfItCredentials on this element");
        }
        Map<String, Object> yaml;
        try (InputStream stream = loader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath");
            }
            String text = read(stream);
            Yaml parser = new Yaml(new LoaderOptions());
            if (hasRecursiveKey(firstNode(parser, text))) {
                return ConditionEvaluationResult.disabled(RESOURCE + " holds a recursive mapping key");
            }
            yaml = firstDocument(parser, text);
        } catch (RuntimeException | IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed: " + describe(e));
        }
        List<String> missing = missingKeys(yaml, gate.get().keys());
        if (!missing.isEmpty()) {
            return ConditionEvaluationResult.disabled(
                    "Missing or TODO credential keys in " + RESOURCE + ": " + String.join(", ", missing));
        }
        return ConditionEvaluationResult.enabled("All credential keys present in " + RESOURCE);
    }

    /**
     * Lists the keys that {@code yaml} does not supply (D-021, D-196).
     *
     * <p>Each key resolves first as a nested path: it is split at every {@code .}, and each segment must name an
     * entry of the mapping reached so far, starting at {@code yaml}. When a segment names no entry, or the value
     * reached before the last segment is not a mapping, the path does not resolve and the key resolves as the
     * literal top-level entry {@code yaml.get(key)}, such as a quoted {@code "wday.status.id"} key. A key is
     * missing when it resolves to no value or to {@code null}, when the string form of its value is empty after
     * trimming, or when the trimmed string form equals {@code TODO} in any letter case. Numbers, booleans,
     * mappings and sequences are present when their string form is not blank; a mapping or sequence value always
     * has such a form. A {@code null} mapping holds no key, a {@code null} key is missing, and a {@code null} key
     * array lists nothing.
     *
     * @param yaml the first YAML document of {@value #RESOURCE} as a mapping, or {@code null}
     * @param keys the dotted keys to check, such as {@code wday.status.id}
     * @return a new list of the missing keys in argument order
     */
    public static List<String> missingKeys(Map<String, Object> yaml, String... keys) {
        List<String> missing = new ArrayList<>();
        if (keys == null) {
            return missing;
        }
        Map<String, Object> root = yaml != null ? yaml : Map.of();
        for (String key : keys) {
            if (key == null || isMissing(resolve(root, key))) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * Returns the class loader given to the constructor, else the context class loader of the current thread,
     * else the class loader of this class.
     *
     * @return the class loader that resolves {@value #RESOURCE}
     */
    private ClassLoader loader() {
        if (classLoader != null) {
            return classLoader;
        }
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ItCredentialsCondition.class.getClassLoader();
    }

    /**
     * Reads the whole of {@code stream} as UTF-8 text and leaves the stream open.
     *
     * @param stream the resource content
     * @return the decoded text
     * @throws IOException when reading the stream fails
     */
    private static String read(InputStream stream) throws IOException {
        StringWriter text = new StringWriter();
        new InputStreamReader(stream, StandardCharsets.UTF_8).transferTo(text);
        return text.toString();
    }

    /**
     * Composes the first YAML document of {@code text} into SnakeYAML nodes with {@code yaml}, without
     * constructing Java objects, and leaves any later document unparsed.
     *
     * @param yaml the SnakeYAML instance, created with {@code new Yaml(new LoaderOptions())}
     * @param text the YAML text
     * @return the root node of the first document, or {@code null} when the text holds no document
     */
    private static Node firstNode(Yaml yaml, String text) {
        Iterator<Node> nodes = yaml.composeAll(new StringReader(text)).iterator();
        return nodes.hasNext() ? nodes.next() : null;
    }

    /**
     * Constructs the first YAML document of {@code text} with {@code yaml} and leaves any later document unparsed.
     *
     * @param yaml the SnakeYAML instance, created with {@code new Yaml(new LoaderOptions())}
     * @param text the YAML text
     * @return the entries of the first document keyed by the string form of each key, or an empty mapping when
     *         the text holds no document, an empty document or a document that is not a mapping
     */
    private static Map<String, Object> firstDocument(Yaml yaml, String text) {
        Iterator<Object> documents = yaml.loadAll(new StringReader(text)).iterator();
        Map<String, Object> root = new LinkedHashMap<>();
        if (documents.hasNext() && documents.next() instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> root.put(String.valueOf(key), value));
        }
        return root;
    }

    /**
     * Tells whether a key of a mapping reachable from {@code root} reaches a mapping or sequence that contains
     * itself (D-352). Mapping values and sequence items are walked iteratively, each node once by identity, and
     * every mapping key met on that walk is checked with {@link #reachesCycle(Node, Set, Map)}; scalar keys never
     * reach a cycle.
     *
     * @param root the root node of a composed document, or {@code null}
     * @return {@code true} when some mapping key reaches a node that contains itself; {@code false} for
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
     * Tells whether {@code node}, or a key, value or item reachable from it, is a mapping or sequence that
     * contains itself. Nodes are compared by identity; {@code onPath} holds the nodes on the current descent and
     * {@code known} records the answer for every node already finished.
     *
     * @param node   the node to check
     * @param onPath the nodes on the current descent, empty on the first call and restored on return
     * @param known  the answers for finished nodes, shared across calls of one document
     * @return {@code true} when a node on a descent from {@code node} is met again on that same descent
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
     * Resolves {@code key} in {@code yaml} as a nested path and, when that path does not resolve, as a literal
     * top-level key.
     *
     * @param yaml the document mapping
     * @param key  the dotted key
     * @return the value found, or {@code null} when neither form holds one
     */
    private static Object resolve(Map<String, Object> yaml, String key) {
        Object node = yaml;
        for (String segment : key.split("\\.", -1)) {
            if (!(node instanceof Map<?, ?> mapping) || !mapping.containsKey(segment)) {
                return yaml.get(key);
            }
            node = mapping.get(segment);
        }
        return node;
    }

    /**
     * Tells whether a resolved value leaves its key unsupplied: {@code null}, a string form that is empty after
     * trimming, or a trimmed string form equal to {@code TODO} in any letter case. A mapping or sequence value is
     * present without forming its string, which starts with a bracket and is never blank.
     *
     * @param value the value from {@link #resolve(Map, String)}
     * @return {@code true} when the key is missing
     */
    private static boolean isMissing(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() || text.equalsIgnoreCase(TODO);
    }

    /**
     * Builds the detail of a parse-failure reason (D-196, D-350): the exception class name and, for a
     * {@link MarkedYAMLException} with a problem mark, the line and column of that mark. The detail carries no
     * exception message, no SnakeYAML problem or context text and no source snippet, for any exception type.
     *
     * @param e the exception thrown while the resource was opened, read, parsed or closed
     * @return {@code <class name>[ (line <n>, column <n>)]}, with the line and column of the problem mark counted
     *         from 1 and present only for a {@link MarkedYAMLException} whose problem mark is not {@code null}
     */
    private static String describe(Exception e) {
        StringBuilder detail = new StringBuilder(e.getClass().getName());
        if (e instanceof MarkedYAMLException marked) {
            var mark = marked.getProblemMark();
            if (mark != null) {
                detail.append(" (line ").append(mark.getLine() + 1)
                        .append(", column ").append(mark.getColumn() + 1).append(')');
            }
        }
        return detail.toString();
    }
}
