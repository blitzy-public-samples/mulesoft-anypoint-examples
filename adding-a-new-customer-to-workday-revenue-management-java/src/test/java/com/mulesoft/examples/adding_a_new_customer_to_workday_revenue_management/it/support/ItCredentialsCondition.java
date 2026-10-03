package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.MarkedYAMLException;

/**
 * JUnit 5 execution condition behind {@link EnabledIfItCredentials} (D-021, D-196).
 *
 * <p>For an element annotated with {@link EnabledIfItCredentials}, the condition reads the test-classpath
 * resource {@value #RESOURCE} as UTF-8 with SnakeYAML, takes its first YAML document as the credential mapping,
 * and enables the element only when {@link #missingKeys(Map, String...)} reports none of the keys in
 * {@link EnabledIfItCredentials#keys()}. Each key resolves first as a nested path and then as a literal dotted
 * top-level key; a key is missing when it resolves to no value, to {@code null}, to a blank value or to
 * {@code TODO} in any letter case. JUnit evaluates the class-level condition before it instantiates the test
 * class, and a disabled class starts no Spring application context. A missing-keys reason names the file and the
 * keys, never their values. A parse-failure reason names the exception type; for a SnakeYAML syntax or structure
 * error it adds SnakeYAML's problem with its line and column and no source snippet, and for any other exception
 * it adds the exception message.
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
     * Evaluates {@link EnabledIfItCredentials} on the current test element (D-021, D-196).
     *
     * <p>An element that does not carry the annotation, such as a test method of an annotated class, is enabled
     * with {@code "No @EnabledIfItCredentials on this element"}. For an annotated element, in this order:
     * <ol>
     *   <li>{@value #RESOURCE} is opened through the injected class loader, else the context class loader of the
     *       evaluating thread, else the class loader of this class; when it is not on the test classpath the
     *       element is disabled with {@code "application-it.yml not found on the test classpath"};</li>
     *   <li>the resource is read as UTF-8 and its first YAML document is taken as the credential mapping; a file
     *       with no document, an empty document or a document that is not a mapping gives an empty mapping, and
     *       later documents are not read;</li>
     *   <li>when opening, reading, parsing or closing the resource throws, the element is disabled with
     *       {@code "application-it.yml could not be parsed: <detail>"}; for a SnakeYAML
     *       {@link MarkedYAMLException} the detail is the exception class name, SnakeYAML's problem and
     *       {@code (line <n>, column <n>)} of the problem, counted from 1, with no source snippet; for any other
     *       exception it is the exception class name and its message;</li>
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
            yaml = firstDocument(stream);
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
     * Parses the first YAML document of {@code stream}, read as UTF-8, with {@code new Yaml(new LoaderOptions())}
     * and leaves any later document unparsed.
     *
     * @param stream the YAML text
     * @return the entries of the first document keyed by the string form of each key, or an empty mapping when
     *         the stream holds no document, an empty document or a document that is not a mapping
     */
    private static Map<String, Object> firstDocument(InputStream stream) {
        Iterator<Object> documents = new Yaml(new LoaderOptions())
                .loadAll(new InputStreamReader(stream, StandardCharsets.UTF_8))
                .iterator();
        Map<String, Object> root = new LinkedHashMap<>();
        if (documents.hasNext() && documents.next() instanceof Map<?, ?> mapping) {
            mapping.forEach((key, value) -> root.put(String.valueOf(key), value));
        }
        return root;
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
     * Builds the detail of a parse-failure reason.
     *
     * @param e the exception thrown while the resource was opened, read, parsed or closed
     * @return for a {@link MarkedYAMLException}, {@code <class name>[: <problem>][ (line <n>, column <n>)]} with
     *         the problem mark counted from 1; for any other exception, {@code <class name>[: <message>]}
     */
    private static String describe(Exception e) {
        StringBuilder detail = new StringBuilder(e.getClass().getName());
        if (e instanceof MarkedYAMLException marked) {
            if (marked.getProblem() != null) {
                detail.append(": ").append(marked.getProblem());
            }
            var mark = marked.getProblemMark();
            if (mark != null) {
                detail.append(" (line ").append(mark.getLine() + 1)
                        .append(", column ").append(mark.getColumn() + 1).append(')');
            }
        } else if (e.getMessage() != null) {
            detail.append(": ").append(e.getMessage());
        }
        return detail.toString();
    }
}
