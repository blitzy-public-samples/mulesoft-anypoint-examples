package com.mulesoft.examples.authenticating_salesforce_using_oauth2.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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
 * Disables a test class annotated with {@link EnabledIfItCredentials} unless {@code application-it.yml} on the
 * test classpath supplies each named key with a non-blank value other than {@code TODO} (D-021).
 *
 * <p>Nested mappings are read as dotted keys: {@code sfdc: {key: k}} supplies {@code sfdc.key}. A test element
 * without the annotation is enabled. A file that SnakeYAML cannot read, or that holds a recursive alias, disables
 * the class with a fixed reason (D-369). A disabled reason names the file and the unsupplied keys, never a value
 * read from the file or a parser message (D-012).
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /** Value that marks a key of {@code application-it.yml} as not supplied. */
    static final String PLACEHOLDER = "TODO";

    /** Result of {@link #resolve(String, Map, String)} for a key that the document gives no value. */
    private static final Object ABSENT = new Object();

    /**
     * Creates the condition; JUnit instantiates it through {@code @ExtendWith} on
     * {@link EnabledIfItCredentials}.
     */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>An element without the annotation, such as a test method of an enabled class, is enabled. For an
     * annotated element, {@code application-it.yml} is opened through the thread context class loader, or
     * through the class loader of this class when the thread has none, checked by
     * {@link #evaluate(InputStream, String[])} against the annotation's keys, and closed. An element whose file
     * cannot be closed is disabled.
     *
     * @param context the extension context of the test class or method being evaluated
     * @return enabled when the annotation is absent or every named key is supplied; disabled otherwise
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
        try (InputStream stream = loader.getResourceAsStream(RESOURCE)) {
            return evaluate(stream, annotation.get().keys());
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": unreadable");
        }
    }

    /**
     * Checks an {@code application-it.yml} document against the named keys (D-021).
     *
     * <p>The stream is read, and left open, as a single UTF-8 YAML document, composed into SnakeYAML nodes and
     * then constructed through SnakeYAML's {@link SafeConstructor}; an empty document is an empty mapping. A
     * document in which an alias refers to a mapping or sequence that contains that alias, found by
     * {@link #isRecursive(Node, Set, Set)}, is not constructed (D-369). Any {@link RuntimeException} raised while
     * the document is read, composed or constructed, such as the {@link NumberFormatException} of an explicitly
     * typed {@code !!int} or {@code !!float} scalar, gives a reason without its message (D-012, D-369). Each key
     * is looked up by {@link #resolve(String, Map, String)}, which gives it the value that flattening nested
     * mappings into dotted keys gives it, and checked by {@link #isSupplied(Object)}: a sequence or set
     * supplies its key, and any other value supplies it when it is not {@code null} and its string form,
     * trimmed, is neither empty nor {@code TODO}. A {@code null} key is unsupplied.
     *
     * @param yamlOrNull the document, or {@code null} when the file is not on the test classpath
     * @param keys       dotted property names the document must supply
     * @return disabled with {@code application-it.yml: not found on the test classpath} when the stream is
     *         {@code null}, {@code application-it.yml: unreadable YAML} when it is not a single well-formed YAML
     *         document, {@code application-it.yml: recursive alias} when it holds a recursive alias,
     *         {@code application-it.yml: root is not a mapping} when its root is not a mapping, and with a reason
     *         listing every unsupplied key in the order of {@code keys} when it leaves a key unsupplied; enabled
     *         with a reason listing {@code keys} otherwise
     */
    static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": not found on the test classpath");
        }
        Object document;
        try {
            DocumentConstructor constructor = new DocumentConstructor();
            Node node = new Yaml(constructor).compose(new InputStreamReader(yamlOrNull, StandardCharsets.UTF_8));
            if (node != null && isRecursive(node, Collections.newSetFromMap(new IdentityHashMap<>()),
                    Collections.newSetFromMap(new IdentityHashMap<>()))) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": recursive alias");
            }
            document = node == null ? null : constructor.construct(node);
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": unreadable YAML");
        }
        Map<?, ?> root;
        if (document == null) {
            root = Map.of();
        } else if (document instanceof Map<?, ?> mapping) {
            root = mapping;
        } else {
            return ConditionEvaluationResult.disabled(RESOURCE + ": root is not a mapping");
        }
        List<String> unsatisfied = new ArrayList<>();
        for (String key : keys) {
            Object value = key == null ? ABSENT : resolve("", root, key);
            if (!isSupplied(value)) {
                unsatisfied.add(key);
            }
        }
        if (!unsatisfied.isEmpty()) {
            return ConditionEvaluationResult.disabled(RESOURCE + ": missing or TODO keys " + unsatisfied);
        }
        return ConditionEvaluationResult.enabled(RESOURCE + " supplies " + Arrays.toString(keys));
    }

    /**
     * Returns the value that flattening {@code source} into dotted keys gives {@code key}, or {@link #ABSENT}
     * when no entry gives it one (D-021, D-369).
     *
     * <p>The dotted key of an entry is the string form of its key when {@code prefix} is empty and
     * {@code prefix.key} otherwise. An entry whose dotted key is longer than {@code key} is skipped, its string
     * form written only up to that length by {@link #nameOf(Object, int)}. A mapping value is searched only
     * when its dotted key is empty or, followed by a dot, begins {@code key}; any other value, {@code null}
     * included, is the value of its dotted key. A later entry that gives {@code key} a value replaces an
     * earlier one.
     *
     * @param prefix dotted key of {@code source}, empty for the document root; otherwise {@code key} begins
     *               with {@code prefix} followed by a dot
     * @param source the mapping to search
     * @param key    the dotted key to look up
     * @return the value of {@code key}, or {@link #ABSENT}
     */
    private static Object resolve(String prefix, Map<?, ?> source, String key) {
        Object found = ABSENT;
        int limit = prefix.isEmpty() ? key.length() : key.length() - prefix.length() - 1;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String part = nameOf(entry.getKey(), limit);
            if (part == null) {
                continue;
            }
            String name = prefix.isEmpty() ? part : prefix + "." + part;
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                if (name.isEmpty() || key.startsWith(name + ".")) {
                    Object inner = resolve(name, nested, key);
                    if (inner != ABSENT) {
                        found = inner;
                    }
                }
            } else if (name.equals(key)) {
                found = value;
            }
        }
        return found;
    }

    /**
     * Returns the string form of a mapping key, or {@code null} when it is longer than {@code limit}
     * characters (D-369).
     *
     * @param value the mapping key
     * @param limit the greatest length returned
     * @return the string form of {@code value}, or {@code null}
     */
    private static String nameOf(Object value, int limit) {
        StringBuilder text = new StringBuilder();
        write(value, text, limit);
        return text.length() > limit ? null : text.toString();
    }

    /**
     * Appends the string form of {@code value} to {@code text}, stopping once {@code text} holds more than
     * {@code limit} characters (D-369).
     *
     * <p>A mapping is written as {@code {key=value, ...}} and a sequence or set as {@code [item, ...]}, the
     * forms of {@link java.util.AbstractMap#toString()} and {@link java.util.AbstractCollection#toString()};
     * any other value is written as {@link String#valueOf(Object)}.
     *
     * @param value the value to write
     * @param text  the text written so far
     * @param limit the length past which writing stops
     */
    private static void write(Object value, StringBuilder text, int limit) {
        if (value instanceof Map<?, ?> mapping) {
            text.append('{');
            String separator = "";
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                if (text.length() > limit) {
                    return;
                }
                text.append(separator);
                write(entry.getKey(), text, limit);
                text.append('=');
                write(entry.getValue(), text, limit);
                separator = ", ";
            }
            text.append('}');
        } else if (value instanceof Collection<?> collection) {
            text.append('[');
            String separator = "";
            for (Object item : collection) {
                if (text.length() > limit) {
                    return;
                }
                text.append(separator);
                write(item, text, limit);
                separator = ", ";
            }
            text.append(']');
        } else {
            text.append(String.valueOf(value));
        }
    }

    /**
     * Reports whether a value returned by {@link #resolve(String, Map, String)} supplies its key (D-021).
     *
     * <p>{@link #ABSENT} and {@code null} supply nothing. A sequence or set supplies its key. Any other value
     * supplies its key when its string form, trimmed, is neither empty nor {@code TODO}.
     *
     * @param value the resolved value
     * @return {@code true} when the value supplies its key
     */
    private static boolean isSupplied(Object value) {
        if (value == ABSENT || value == null) {
            return false;
        }
        if (value instanceof Collection<?>) {
            return true;
        }
        String text = String.valueOf(value).trim();
        return !text.isEmpty() && !PLACEHOLDER.equals(text);
    }

    /**
     * Reports whether {@code node} reaches itself through its children (D-369).
     *
     * <p>The children of a mapping node are the key node and the value node of each of its entries, and the
     * children of a sequence node are its items; a scalar node has none. Nodes are compared by identity, and a
     * node in {@code checked} is not explored again.
     *
     * @param node    the node to explore
     * @param path    the nodes from the document root down to the parent of {@code node}
     * @param checked the nodes already explored without finding a recursion
     * @return {@code true} when a node reachable from {@code node}, {@code node} included, is its own
     *         descendant
     */
    private static boolean isRecursive(Node node, Set<Node> path, Set<Node> checked) {
        if (checked.contains(node)) {
            return false;
        }
        if (!path.add(node)) {
            return true;
        }
        List<Node> children = new ArrayList<>();
        if (node instanceof MappingNode mapping) {
            for (NodeTuple tuple : mapping.getValue()) {
                children.add(tuple.getKeyNode());
                children.add(tuple.getValueNode());
            }
        } else if (node instanceof SequenceNode sequence) {
            children.addAll(sequence.getValue());
        }
        for (Node child : children) {
            if (isRecursive(child, path, checked)) {
                return true;
            }
        }
        path.remove(node);
        checked.add(node);
        return false;
    }

    /**
     * SnakeYAML {@link SafeConstructor} with default {@link LoaderOptions} that builds the Java value of a
     * document already composed into nodes.
     */
    private static final class DocumentConstructor extends SafeConstructor {

        /** Creates a constructor with default {@link LoaderOptions}. */
        DocumentConstructor() {
            super(new LoaderOptions());
        }

        /**
         * Builds the Java value of a composed document, such as a {@code Map} for a mapping and a {@code List}
         * for a sequence.
         *
         * @param document the root node of the document
         * @return the constructed value, {@code null} for a null scalar
         */
        Object construct(Node document) {
            return constructDocument(document);
        }
    }
}
