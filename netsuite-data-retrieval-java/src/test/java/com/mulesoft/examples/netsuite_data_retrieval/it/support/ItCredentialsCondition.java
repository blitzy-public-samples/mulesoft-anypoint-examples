package com.mulesoft.examples.netsuite_data_retrieval.it.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

/**
 * JUnit 5 execution condition registered by {@link EnabledIfItCredentials}: the Tier 2B credential gate (D-021).
 *
 * <p>A test element without the annotation is enabled. For an annotated test class the condition reads
 * {@value #RESOURCE} from the test classpath and enables the class only when every key named in
 * {@link EnabledIfItCredentials#keys()} resolves to a value that is neither blank nor {@code TODO}.
 * {@value #RESOURCE} is the git-ignored copy of the committed {@code application-it.example.yml} (D-012).
 *
 * <p>The condition uses no Spring type, and JUnit evaluates it before any Spring context is created: a disabled
 * class starts no application context and is reported as skipped with the returned reason. A reason names the
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
     * <p>The stream is read with SnakeYAML's {@link SafeConstructor}, and only its first YAML document is used;
     * the stream is not closed. The checks run in this order, and the first failing one decides the result:
     * <ol>
     *   <li>the stream is {@code null}: the file is not on the test classpath;</li>
     *   <li>the first document is not valid YAML;</li>
     *   <li>for each key in the given order: the key resolves to no entry, or to a mapping or a list, and is
     *       missing; its value is {@code null} or blank; or its trimmed value is exactly {@code TODO}.</li>
     * </ol>
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
            Iterator<Object> documents =
                    new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(yamlOrNull).iterator();
            root = documents.hasNext() ? documents.next() : null;
        } catch (YAMLException e) {
            return ConditionEvaluationResult.disabled(RESOURCE + " is not valid YAML");
        }
        for (String key : keys) {
            Objects.requireNonNull(key, "keys element");
            Object value = resolve(root, key);
            if (value == NOT_RESOLVED || value instanceof Map<?, ?> || value instanceof Collection<?>) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": key " + key + " is missing");
            }
            String text = value == null ? "" : String.valueOf(value);
            if (text.isBlank()) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": key " + key + " is blank");
            }
            if (text.trim().equals("TODO")) {
                return ConditionEvaluationResult.disabled(RESOURCE + ": key " + key + " is TODO");
            }
        }
        return ConditionEvaluationResult.enabled(RESOURCE + " holds all " + keys.length + " keys");
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
