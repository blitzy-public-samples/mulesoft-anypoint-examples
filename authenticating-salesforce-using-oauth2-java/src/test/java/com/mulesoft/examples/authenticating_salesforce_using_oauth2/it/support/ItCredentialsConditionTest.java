package com.mulesoft.examples.authenticating_salesforce_using_oauth2.it.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.AnnotatedElement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;

/**
 * Unit tests for {@link ItCredentialsCondition}, the class-level condition behind
 * {@link EnabledIfItCredentials} (D-021).
 *
 * <p>Each document test passes an in-memory UTF-8 stream to
 * {@link ItCredentialsCondition#evaluate(InputStream, String[])} with the four keys of
 * {@code application-it.example.yml}: {@code sfdc.key}, {@code sfdc.secret}, {@code sfdc.user} and
 * {@code sfdc.password}. The covered cases are a file absent from the test classpath, a missing key, a
 * {@code TODO} value, a blank or null value, every key present in nested and in flat form, an empty document,
 * malformed YAML, a root that is not a mapping, reasons free of credential values, and a test element without the
 * annotation. No test reads a file, opens a connection, reads an environment variable or starts a Spring
 * application context, and the classpath lookup of an annotated element is not exercised.
 */
public final class ItCredentialsConditionTest {

    /** Keys that {@code SalesforceOauthIT} names in {@code @EnabledIfItCredentials}. */
    private static final String[] KEYS = {"sfdc.key", "sfdc.secret", "sfdc.user", "sfdc.password"};

    /** Value of {@code sfdc.key} in every document that supplies it. */
    private static final String KEY_VALUE = "key-value-1";

    /** Value of {@code sfdc.secret} in every document that supplies it. */
    private static final String SECRET_VALUE = "secret-value-2";

    /** Value of {@code sfdc.user} in every document that supplies it. */
    private static final String USER_VALUE = "user@example.com";

    /** Value of {@code sfdc.password} in every document that supplies it. */
    private static final String PASSWORD_VALUE = "p4ss-value-3";

    /** Reason prefix of a document that leaves at least one key unsupplied. */
    private static final String MISSING_PREFIX = "application-it.yml: missing or TODO keys ";

    /** Nested document that supplies every key. */
    private static final String NESTED_COMPLETE = "sfdc:\n"
            + "  key: " + KEY_VALUE + "\n"
            + "  secret: " + SECRET_VALUE + "\n"
            + "  user: " + USER_VALUE + "\n"
            + "  password: " + PASSWORD_VALUE + "\n";

    /** Flat document whose root holds every key as a dotted name. */
    private static final String FLAT_COMPLETE = "sfdc.key: " + KEY_VALUE + "\n"
            + "sfdc.secret: " + SECRET_VALUE + "\n"
            + "sfdc.user: " + USER_VALUE + "\n"
            + "sfdc.password: " + PASSWORD_VALUE + "\n";

    /** Nested document without {@code sfdc.secret}. */
    private static final String MISSING_SECRET = "sfdc:\n"
            + "  key: " + KEY_VALUE + "\n"
            + "  user: " + USER_VALUE + "\n"
            + "  password: " + PASSWORD_VALUE + "\n";

    /** Nested document whose {@code sfdc.password} is the bare value {@code TODO}. */
    private static final String TODO_PASSWORD = "sfdc:\n"
            + "  key: " + KEY_VALUE + "\n"
            + "  secret: " + SECRET_VALUE + "\n"
            + "  user: " + USER_VALUE + "\n"
            + "  password: TODO\n";

    /**
     * Nested document whose {@code sfdc.password} is {@code TODO} followed by the comment of
     * {@code application-it.example.yml}.
     */
    private static final String TODO_PASSWORD_WITH_COMMENT = "sfdc:\n"
            + "  key: " + KEY_VALUE + "\n"
            + "  secret: " + SECRET_VALUE + "\n"
            + "  user: " + USER_VALUE + "\n"
            + "  password: TODO  # TODO: supply the Salesforce password\n";

    /** Nested document with a null {@code sfdc.secret} and a quoted blank {@code sfdc.user}. */
    private static final String BLANK_AND_NULL = "sfdc:\n"
            + "  key: " + KEY_VALUE + "\n"
            + "  secret:\n"
            + "  user: \"  \"\n"
            + "  password: " + PASSWORD_VALUE + "\n";

    /** Document that holds every credential value and ends inside an unclosed flow sequence. */
    private static final String MALFORMED_WITH_VALUES = "sfdc:\n"
            + "  key: " + KEY_VALUE + "\n"
            + "  secret: " + SECRET_VALUE + "\n"
            + "  user: " + USER_VALUE + "\n"
            + "  password: [" + PASSWORD_VALUE + "\n";

    /** Nested document that supplies every key beside a mapping holding an alias to itself. */
    private static final String RECURSIVE_WITH_VALUES = NESTED_COMPLETE
            + "loop: &loop\n"
            + "  self: *loop\n";

    /** Condition instance for {@link ItCredentialsCondition#evaluateExecutionCondition(ExtensionContext)}. */
    private final ItCredentialsCondition condition = new ItCredentialsCondition();

    /**
     * A {@code null} stream, the result of a classpath lookup that finds no {@code application-it.yml}, disables the
     * class with the not-found reason.
     */
    @Test
    @DisplayName("file absent disables the class")
    public void fileAbsentDisablesClass() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(null, KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(reasonOf(result))
                .contains("application-it.yml")
                .isEqualTo("application-it.yml: not found on the test classpath");
    }

    /**
     * A nested document without {@code sfdc.secret} disables the class, and the bracketed key list of the reason is
     * exactly {@code [sfdc.secret]}.
     */
    @Test
    @DisplayName("missing key disables the class and names the key")
    public void missingKeyDisablesClassAndNamesKey() {
        ConditionEvaluationResult result = evaluate(MISSING_SECRET);

        assertThat(result.isDisabled()).isTrue();
        String reason = reasonOf(result);
        assertThat(reason).contains("sfdc.secret").isEqualTo(MISSING_PREFIX + "[sfdc.secret]");
        assertThat(bracketedKeys(reason))
                .isEqualTo("[sfdc.secret]")
                .doesNotContain("sfdc.key", "sfdc.user", "sfdc.password");
    }

    /**
     * {@code sfdc.password: TODO}, bare and followed by the template's YAML comment, disables the class and names
     * {@code sfdc.password} alone.
     */
    @Test
    @DisplayName("TODO value disables the class")
    public void todoValueDisablesClass() {
        ConditionEvaluationResult bare = evaluate(TODO_PASSWORD);
        ConditionEvaluationResult commented = evaluate(TODO_PASSWORD_WITH_COMMENT);

        assertThat(bare.isDisabled()).isTrue();
        assertThat(reasonOf(bare)).contains("sfdc.password").isEqualTo(MISSING_PREFIX + "[sfdc.password]");
        assertThat(commented.isDisabled()).isTrue();
        assertThat(reasonOf(commented)).contains("sfdc.password").isEqualTo(MISSING_PREFIX + "[sfdc.password]");
    }

    /**
     * A null {@code sfdc.secret} and a quoted blank {@code sfdc.user} disable the class, and the reason lists both
     * keys in the order of {@link #KEYS}.
     */
    @Test
    @DisplayName("blank or null value disables the class")
    public void blankValueDisablesClass() {
        ConditionEvaluationResult result = evaluate(BLANK_AND_NULL);

        assertThat(result.isDisabled()).isTrue();
        assertThat(bracketedKeys(reasonOf(result))).isEqualTo("[sfdc.secret, sfdc.user]");
        assertThat(reasonOf(result)).isEqualTo(MISSING_PREFIX + "[sfdc.secret, sfdc.user]");
    }

    /** A nested {@code sfdc:} mapping that supplies every key enables the class. */
    @Test
    @DisplayName("all keys nested enables the class")
    public void allKeysNestedEnablesClass() {
        ConditionEvaluationResult result = evaluate(NESTED_COMPLETE);

        assertThat(result.isDisabled()).isFalse();
    }

    /** Four dotted keys at the document root enable the class. */
    @Test
    @DisplayName("all keys flat enables the class")
    public void allKeysFlatEnablesClass() {
        ConditionEvaluationResult result = evaluate(FLAT_COMPLETE);

        assertThat(result.isDisabled()).isFalse();
    }

    /**
     * An empty document, and one that holds only a comment, disable the class, and the reason lists all four keys
     * in the order of {@link #KEYS}.
     */
    @Test
    @DisplayName("empty document disables the class and lists every key")
    public void emptyDocumentDisablesClassListingAllKeys() {
        String allKeys = "[sfdc.key, sfdc.secret, sfdc.user, sfdc.password]";

        ConditionEvaluationResult empty = evaluate("");
        ConditionEvaluationResult commentOnly = evaluate("# sfdc keys follow\n");

        assertThat(empty.isDisabled()).isTrue();
        assertThat(reasonOf(empty)).isEqualTo(MISSING_PREFIX + allKeys);
        assertThat(commentOnly.isDisabled()).isTrue();
        assertThat(reasonOf(commentOnly)).isEqualTo(MISSING_PREFIX + allKeys);
    }

    /**
     * An unclosed flow sequence is evaluated without an exception and disables the class with the unreadable-YAML
     * reason.
     */
    @Test
    @DisplayName("malformed YAML disables the class without throwing")
    public void malformedYamlDisablesClass() {
        assertThatCode(() -> ItCredentialsCondition.evaluate(yaml("sfdc: [unclosed"), KEYS))
                .doesNotThrowAnyException();

        ConditionEvaluationResult result = evaluate("sfdc: [unclosed");

        assertThat(result.isDisabled()).isTrue();
        assertThat(reasonOf(result))
                .contains("application-it.yml")
                .isEqualTo("application-it.yml: unreadable YAML");
    }

    /** A sequence root and a scalar root each disable the class with the not-a-mapping reason. */
    @Test
    @DisplayName("non-mapping root disables the class")
    public void nonMappingRootDisablesClass() {
        ConditionEvaluationResult sequence = evaluate("- a\n- b\n");
        ConditionEvaluationResult scalar = evaluate("sfdc\n");

        assertThat(sequence.isDisabled()).isTrue();
        assertThat(reasonOf(sequence)).isEqualTo("application-it.yml: root is not a mapping");
        assertThat(scalar.isDisabled()).isTrue();
        assertThat(reasonOf(scalar)).isEqualTo("application-it.yml: root is not a mapping");
    }

    /**
     * The reasons for the missing-key, {@code TODO}, blank, malformed and recursive-alias documents, and for the two
     * complete documents, contain none of the four credential values those documents hold (D-012, D-021). The
     * recursive-alias document gets the recursive-alias reason (D-369).
     */
    @Test
    @DisplayName("reasons never contain credential values")
    public void reasonsNeverContainCredentialValues() {
        List<String> disabling = List.of(MISSING_SECRET, TODO_PASSWORD, TODO_PASSWORD_WITH_COMMENT, BLANK_AND_NULL,
                MALFORMED_WITH_VALUES, RECURSIVE_WITH_VALUES);
        List<String> enabling = List.of(NESTED_COMPLETE, FLAT_COMPLETE);

        assertThat(reasonOf(evaluate(RECURSIVE_WITH_VALUES))).isEqualTo("application-it.yml: recursive alias");
        for (String document : disabling) {
            ConditionEvaluationResult result = evaluate(document);
            assertThat(result.isDisabled()).as(document).isTrue();
            assertThat(result.getReason().orElse(""))
                    .as(document)
                    .startsWith("application-it.yml")
                    .doesNotContain(KEY_VALUE, SECRET_VALUE, USER_VALUE, PASSWORD_VALUE);
        }
        for (String document : enabling) {
            ConditionEvaluationResult result = evaluate(document);
            assertThat(result.isDisabled()).as(document).isFalse();
            assertThat(result.getReason().orElse(""))
                    .as(document)
                    .doesNotContain(KEY_VALUE, SECRET_VALUE, USER_VALUE, PASSWORD_VALUE);
        }
    }

    /**
     * {@link ItCredentialsCondition#evaluateExecutionCondition(ExtensionContext)} enables an element that carries no
     * {@link EnabledIfItCredentials}.
     */
    @Test
    @DisplayName("element without the annotation is enabled")
    public void elementWithoutAnnotationIsEnabled() {
        ExtensionContext ctx = Mockito.mock(ExtensionContext.class);
        when(ctx.getElement()).thenReturn(Optional.<AnnotatedElement>of(String.class));

        ConditionEvaluationResult result = condition.evaluateExecutionCondition(ctx);

        assertThat(result.isDisabled()).isFalse();
    }

    /**
     * Returns {@code text} as a UTF-8 stream.
     *
     * @param text the YAML document
     * @return a stream over the UTF-8 bytes of {@code text}
     */
    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Evaluates {@code text} against {@link #KEYS}.
     *
     * @param text the YAML document
     * @return the result of {@link ItCredentialsCondition#evaluate(InputStream, String[])}
     */
    private static ConditionEvaluationResult evaluate(String text) {
        return ItCredentialsCondition.evaluate(yaml(text), KEYS);
    }

    /**
     * Returns the reason of {@code result}, or an empty string when it has none.
     *
     * @param result an evaluation result
     * @return the reason text
     */
    private static String reasonOf(ConditionEvaluationResult result) {
        return result.getReason().orElse("");
    }

    /**
     * Returns the part of {@code reason} from its first {@code [} to its end, or an empty string when it has none.
     *
     * @param reason a disabled reason
     * @return the bracketed key list
     */
    private static String bracketedKeys(String reason) {
        int start = reason.indexOf('[');
        return start < 0 ? "" : reason.substring(start);
    }
}
