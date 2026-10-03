package com.mulesoft.examples.import_contacts_into_ms_dynamics.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
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
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.reader.UnicodeReader;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials}. It disables a live-sandbox test class
 * before any Spring context starts when its credentials are not supplied (D-021).
 *
 * <p>Reads {@value #RESOURCE}, the git-ignored credential file (D-012), from the test classpath with SnakeYAML
 * and enables the annotated class only when every key named in {@link EnabledIfItCredentials#keys()} holds a
 * scalar value that is neither blank nor {@code TODO}. A disabled reason names the file and, for a key check,
 * the first failing key; no reason contains text of the file (D-012).
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials (D-012). */
    public static final String RESOURCE = "application-it.yml";

    /**
     * Creates the condition; JUnit instantiates it through {@code @ExtendWith} on
     * {@link EnabledIfItCredentials} (D-021).
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation is enabled with the reason {@code @EnabledIfItCredentials not present}.
     * For an annotated element, {@value #RESOURCE} is opened through the thread context class loader, or through
     * the class loader of this class when the thread has none, checked by {@link #evaluate(InputStream, String[])}
     * against the annotation's keys, and closed. When closing the file fails, the element is disabled with
     * {@code application-it.yml could not be read: <message>}.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every required key holds a usable value; disabled with the
     *         reason of the first failing check otherwise
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
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read: " + e.getMessage());
        }
    }

    /**
     * Checks an {@value #RESOURCE} document against the required keys (D-021). The stream is read and left open
     * for the caller to close.
     *
     * <p>The checks run in this order, and the first one that applies gives the result:
     * <ol>
     *   <li>{@code yamlOrNull} is {@code null}: disabled, {@code application-it.yml not found on the test
     *       classpath};</li>
     *   <li>{@code keys} is {@code null} or empty: enabled, {@code all IT credentials present in
     *       application-it.yml};</li>
     *   <li>the stream cannot be read to its end, its bytes decoded through SnakeYAML's {@link UnicodeReader}
     *       included: disabled, {@code application-it.yml could not be read: <detail>};</li>
     *   <li>the text cannot be composed into the nodes of one YAML document, a syntax error, an undefined alias,
     *       a second document and a global tag such as {@code !!java.io.File} included: disabled,
     *       {@code application-it.yml could not be parsed: <detail>};</li>
     *   <li>a mapping key of the composed document reaches, through mapping keys, mapping values and sequence
     *       items, a mapping or sequence that holds an alias to itself, as in {@code ? [&q {z: *q}] : v}:
     *       disabled, {@code application-it.yml holds a recursive mapping key}. No value is constructed before
     *       this check; a recursive alias reached only from mapping values and sequence items does not
     *       disable;</li>
     *   <li>the composed document cannot be constructed, a local tag such as {@code !x} and a scalar that does
     *       not fit its explicit tag, such as {@code !!float 'x'}, included: disabled,
     *       {@code application-it.yml could not be parsed: <detail>};</li>
     *   <li>the document is empty or its root is not a mapping: disabled,
     *       {@code application-it.yml is empty or not a YAML mapping};</li>
     *   <li>the first key, in the given order, without a usable value: disabled,
     *       {@code <key> is missing or TODO in application-it.yml};</li>
     *   <li>otherwise: enabled, {@code all IT credentials present in application-it.yml}.</li>
     * </ol>
     * A key resolves first as a nested path, its {@code .}-separated segments looked up through nested mappings,
     * and, when that path yields no value, as a literal top-level key; {@code dynamics: {oauth: {client-id: x}}}
     * and {@code dynamics.oauth.client-id: x} both hold {@code dynamics.oauth.client-id}. A value is usable when
     * it is neither {@code null}, a mapping nor a collection, and its {@link String#valueOf(Object)} form, trimmed,
     * is neither empty nor exactly {@code TODO}. A {@code <detail>} is the simple class name of the exception and,
     * for a SnakeYAML exception that marks a position, {@code at line <n>, column <n>}, both 1-based, as in
     * {@code ScannerException at line 3, column 26}; it holds no exception message and no text of the file
     * (D-012, D-502). No exception from reading or parsing the stream propagates.
     *
     * @param yamlOrNull the {@value #RESOURCE} document, or {@code null} when the file is absent
     * @param keys       dotted property keys the document must hold, such as {@code dynamics.service-url}
     * @return enabled when every key holds a usable value; disabled with the reason of the first failing check
     *         otherwise
     */
    public static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found on the test classpath");
        }
        if (keys == null || keys.length == 0) {
            return ConditionEvaluationResult.enabled("all IT credentials present in " + RESOURCE);
        }
        String text;
        try {
            text = readFully(yamlOrNull);
        } catch (IOException | RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read: " + describe(e));
        }
        Object root;
        try {
            Yaml yaml = new Yaml(new LoaderOptions());
            // The node graph is composed and checked first; a key that reaches a self-referencing collection is
            // never constructed (D-021, D-502).
            Node document = yaml.compose(new StringReader(text));
            if (document != null && hasRecursiveMappingKey(document)) {
                return ConditionEvaluationResult.disabled(RESOURCE + " holds a recursive mapping key");
            }
            root = yaml.load(text);
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed: " + describe(e));
        }
        if (!(root instanceof Map<?, ?> mapping)) {
            return ConditionEvaluationResult.disabled(RESOURCE + " is empty or not a YAML mapping");
        }
        for (String key : keys) {
            if (!isUsable(resolve(mapping, key))) {
                return ConditionEvaluationResult.disabled(key + " is missing or TODO in " + RESOURCE);
            }
        }
        return ConditionEvaluationResult.enabled("all IT credentials present in " + RESOURCE);
    }

    /**
     * Returns the value of {@code key} in {@code root}: the value at its nested path or, when that path yields
     * {@code null}, the value of the literal top-level key. A {@code null} key has no value.
     *
     * @param root the document's root mapping
     * @param key  a dotted property key
     * @return the resolved value, or {@code null} when neither form holds one
     */
    private static Object resolve(Map<?, ?> root, String key) {
        if (key == null) {
            return null;
        }
        Object nested = nestedValue(root, key);
        return nested != null ? nested : root.get(key);
    }

    /**
     * Walks the {@code .}-separated segments of {@code key} through nested mappings, starting at {@code root}.
     *
     * @param root the document's root mapping
     * @param key  a dotted property key
     * @return the value of the last segment, or {@code null} when a segment is missing or an intermediate value
     *         is not a mapping
     */
    private static Object nestedValue(Map<?, ?> root, String key) {
        Object current = root;
        for (String segment : key.split("\\.", -1)) {
            if (!(current instanceof Map<?, ?> mapping)) {
                return null;
            }
            current = mapping.get(segment);
        }
        return current;
    }

    /**
     * Reports whether {@code value} is a usable credential value.
     *
     * @param value a resolved value, possibly {@code null}
     * @return {@code false} for {@code null}, a mapping or a collection, and for a value whose trimmed
     *         {@link String#valueOf(Object)} form is empty or exactly {@code TODO}; {@code true} otherwise
     */
    private static boolean isUsable(Object value) {
        if (value == null || value instanceof Map || value instanceof Collection) {
            return false;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !"TODO".equals(text);
    }

    /**
     * Reads {@code in} to its end as text. SnakeYAML's {@link UnicodeReader} detects a UTF-8, UTF-16 or UTF-32
     * byte order mark, decodes UTF-8 otherwise and reports malformed input. The stream is left open.
     *
     * @param in the {@value #RESOURCE} document
     * @return the decoded text, without a byte order mark
     * @throws IOException when the stream cannot be read or its bytes cannot be decoded
     */
    private static String readFully(InputStream in) throws IOException {
        Reader reader = new UnicodeReader(in);
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        for (int count = reader.read(buffer, 0, buffer.length); count != -1;
                count = reader.read(buffer, 0, buffer.length)) {
            text.append(buffer, 0, count);
        }
        return text.toString();
    }

    /**
     * Reports whether a key of any mapping in {@code document} reaches, through mapping keys, mapping values and
     * sequence items, a node that SnakeYAML's composer marks for two-step construction: a mapping or sequence
     * that holds an alias to itself. Nodes are compared by identity, and each pass visits a node at most once
     * (D-502).
     *
     * @param document the composed root node
     * @return {@code true} when a mapping key reaches a self-referencing mapping or sequence
     */
    private static boolean hasRecursiveMappingKey(Node document) {
        Set<Node> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Node> keyNodes = new ArrayList<>();
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(document);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (node instanceof MappingNode mapping) {
                for (NodeTuple tuple : mapping.getValue()) {
                    keyNodes.add(tuple.getKeyNode());
                }
            }
            pushChildren(node, pending);
        }
        Set<Node> checked = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Node keyNode : keyNodes) {
            if (reachesTwoStepNode(keyNode, checked)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the nodes reachable from {@code start} that are not yet in {@code checked}, adding each to it.
     *
     * @param start   a mapping key node
     * @param checked nodes already walked without meeting a two-step node
     * @return {@code true} when a walked node is marked for two-step construction
     */
    private static boolean reachesTwoStepNode(Node start, Set<Node> checked) {
        Deque<Node> pending = new ArrayDeque<>();
        pending.push(start);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!checked.add(node)) {
                continue;
            }
            if (node.isTwoStepsConstruction()) {
                return true;
            }
            pushChildren(node, pending);
        }
        return false;
    }

    /**
     * Pushes the key and value nodes of a mapping, or the items of a sequence, onto {@code pending}; a scalar
     * has no children.
     *
     * @param node    a composed node
     * @param pending the nodes still to visit
     */
    private static void pushChildren(Node node, Deque<Node> pending) {
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

    /**
     * Describes a read or parse failure by the exception's simple class name and, for a
     * {@link MarkedYAMLException} with a problem mark, the 1-based line and column of that mark. The description
     * holds no exception message, no SnakeYAML problem or context text and no text of the file (D-012, D-502).
     *
     * @param failure the exception raised while reading or parsing
     * @return for example {@code ScannerException at line 3, column 26} or {@code NumberFormatException}
     */
    private static String describe(Exception failure) {
        String name = failure.getClass().getSimpleName();
        if (failure instanceof MarkedYAMLException marked && marked.getProblemMark() != null) {
            Mark mark = marked.getProblemMark();
            return name + " at line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1);
        }
        return name;
    }
}
