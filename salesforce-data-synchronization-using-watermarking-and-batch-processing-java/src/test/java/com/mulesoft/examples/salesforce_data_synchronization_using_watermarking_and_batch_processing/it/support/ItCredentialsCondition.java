package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
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
 * JUnit 5 condition behind {@code @EnabledIfItCredentials}: reads {@code application-it.yml} from the test
 * classpath and disables the test class unless every listed key is present and not {@code TODO} (D-021).
 * A file that holds a recursive alias disables the class (D-369). Disabled reasons name keys, never values.
 */
public final class ItCredentialsCondition implements ExecutionCondition {

    /** Test-classpath resource that holds the live-sandbox credentials. */
    static final String RESOURCE = "application-it.yml";

    /** Template value of a key that has not been supplied. */
    static final String PLACEHOLDER = "TODO";

    /** Result of a lookup in which no entry yields a value for the key. */
    private static final Object ABSENT = new Object();

    /** Creates the condition that {@link EnabledIfItCredentials} registers through {@code @ExtendWith}. */
    public ItCredentialsCondition() {
    }

    /**
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021).
     *
     * <p>{@code application-it.yml} is looked up through the class loader of the test class, else the thread
     * context class loader, checked by {@link #evaluate(InputStream, String[])} against the annotation's keys,
     * and closed before this method returns.
     *
     * @param context the extension context of the element being evaluated
     * @return enabled with {@code no @EnabledIfItCredentials} when the element carries no annotation; disabled
     *         with {@code application-it.yml could not be read} when closing the resource fails; otherwise the
     *         result of {@link #evaluate(InputStream, String[])}
     */
    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        Optional<EnabledIfItCredentials> annotation =
                AnnotationSupport.findAnnotation(context.getElement(), EnabledIfItCredentials.class);
        if (annotation.isEmpty()) {
            return ConditionEvaluationResult.enabled("no @EnabledIfItCredentials");
        }
        try (InputStream in = classLoaderOf(context).getResourceAsStream(RESOURCE)) {
            return evaluate(in, annotation.get().keys());
        } catch (IOException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be read");
        }
    }

    /**
     * Checks the content of {@code application-it.yml} against the required keys (D-021). The stream is read
     * and left open.
     *
     * <p>The YAML documents in the stream are composed into nodes and checked in order. A document in which
     * an alias refers to a mapping or sequence that contains that alias is not constructed (D-369). Every
     * other document that is a mapping is searched for each key; any other document, and an empty stream,
     * contributes no key. A mapping under {@code k} holds {@code k.<child key>}, a literal dotted key is kept
     * as written, and a sequence is one value; only the mappings along a key are read. Each key takes the
     * value of the last entry under it, a later document replacing an earlier one (D-369). A key is satisfied
     * when its value is a sequence or set, or is not {@code null} and its trimmed string form is neither empty
     * nor {@code TODO}.
     *
     * @param yamlOrNull the content of {@code application-it.yml}, or {@code null} when the file is absent
     * @param keys       the dotted keys to check, in order; {@code null} or empty checks none, and a
     *                   {@code null} element is unsatisfied
     * @return enabled with {@code application-it.yml holds every required key} when every key is satisfied;
     *         disabled with {@code application-it.yml not found} for a {@code null} stream; disabled at the
     *         first document, in stream order, that SnakeYAML fails to read, compose or construct, with
     *         {@code application-it.yml could not be parsed}, or that holds a recursive alias, with
     *         {@code application-it.yml holds a recursive alias}; otherwise disabled with
     *         {@code application-it.yml lacks or has TODO for: } followed by the unsatisfied keys in order,
     *         without duplicates, separated by {@code ", "}. No reason contains a value (D-012)
     */
    public static ConditionEvaluationResult evaluate(InputStream yamlOrNull, String[] keys) {
        if (yamlOrNull == null) {
            return ConditionEvaluationResult.disabled(RESOURCE + " not found");
        }
        List<Map<?, ?>> mappings = new ArrayList<>();
        try {
            DocumentConstructor constructor = new DocumentConstructor();
            Yaml yaml = new Yaml(constructor);
            for (Node document : yaml.composeAll(new InputStreamReader(yamlOrNull, StandardCharsets.UTF_8))) {
                Set<Node> path = Collections.newSetFromMap(new IdentityHashMap<>());
                Set<Node> checked = Collections.newSetFromMap(new IdentityHashMap<>());
                if (isRecursive(document, path, checked)) {
                    return ConditionEvaluationResult.disabled(RESOURCE + " holds a recursive alias");
                }
                if (constructor.construct(document) instanceof Map<?, ?> mapping) {
                    mappings.add(mapping);
                }
            }
        } catch (RuntimeException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " could not be parsed");
        }
        Set<String> unsatisfied = new LinkedHashSet<>();
        if (keys != null) {
            for (String key : keys) {
                Object value = ABSENT;
                if (key != null) {
                    for (Map<?, ?> mapping : mappings) {
                        Object found = resolve(mapping, key);
                        if (found != ABSENT) {
                            value = found;
                        }
                    }
                }
                if (!isSupplied(value)) {
                    unsatisfied.add(key);
                }
            }
        }
        if (unsatisfied.isEmpty()) {
            return ConditionEvaluationResult.enabled(RESOURCE + " holds every required key");
        }
        return ConditionEvaluationResult.disabled(
                RESOURCE + " lacks or has TODO for: " + String.join(", ", unsatisfied));
    }

    /**
     * Returns the class loader that {@code application-it.yml} is looked up through.
     *
     * @param context the extension context of the element being evaluated
     * @return the test class's class loader, else the thread context class loader
     */
    private static ClassLoader classLoaderOf(ExtensionContext context) {
        return context.getTestClass()
                .map(Class::getClassLoader)
                .orElseGet(() -> Thread.currentThread().getContextClassLoader());
    }

    /**
     * Reports whether a composed node reaches a cycle through mapping keys, mapping values or sequence items.
     *
     * <p>Nodes are compared by identity, and an alias is the very node its anchor names, so a mapping or
     * sequence that holds an alias to itself, or to a node that contains it, forms a cycle. Each node is
     * examined once.
     *
     * @param node    the node to examine
     * @param path    the nodes from the document root down to, and excluding, {@code node}
     * @param checked the nodes already found to reach no cycle
     * @return {@code true} when a cycle is reachable from {@code node}
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
     * Returns the value that flattening {@code source} into dotted keys gives {@code key} (D-369).
     *
     * <p>An entry's name is the string form of its key, written by {@link #nameOf(Object, int)} only up to
     * the length of {@code key}; an entry whose name is longer than {@code key} is skipped. An entry whose
     * value is a mapping is descended into, with the rest of {@code key}, when {@code key} begins with its
     * name followed by {@code "."}; any other entry, {@code null} and sequences included, matches when its
     * name equals {@code key}. Of the entries that yield a value, the last one in iteration order wins.
     *
     * @param source the mapping to search
     * @param key    the dotted key to look up
     * @return the value of the last entry that yields one, or {@link #ABSENT} when none does
     */
    private static Object resolve(Map<?, ?> source, String key) {
        Object found = ABSENT;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String name = nameOf(entry.getKey(), key.length());
            if (name == null) {
                continue;
            }
            if (entry.getValue() instanceof Map<?, ?> nested) {
                if (key.length() > name.length() && key.startsWith(name) && key.charAt(name.length()) == '.') {
                    Object inner = resolve(nested, key.substring(name.length() + 1));
                    if (inner != ABSENT) {
                        found = inner;
                    }
                }
            } else if (name.equals(key)) {
                found = entry.getValue();
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
     * @return the string form of {@code value} written by {@link #write(Object, StringBuilder, int)}, or
     *         {@code null}
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
     * Reports whether a looked-up value counts as a supplied credential.
     *
     * @param value the value a key resolves to, or {@link #ABSENT} when no entry yields one
     * @return {@code false} for {@link #ABSENT} and {@code null}; {@code true} for a sequence, set or other
     *         collection; otherwise {@code true} when the trimmed string form of {@code value} is neither empty
     *         nor {@code TODO}
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

    /** {@link SafeConstructor} that builds the Java value of one composed YAML document. */
    private static final class DocumentConstructor extends SafeConstructor {

        /** Creates the constructor with default {@link LoaderOptions}. */
        DocumentConstructor() {
            super(new LoaderOptions());
        }

        /**
         * Builds the Java value of a composed document.
         *
         * @param document the root node of the document
         * @return the value {@link SafeConstructor} builds for {@code document}
         */
        Object construct(Node document) {
            return constructDocument(document);
        }
    }
}
