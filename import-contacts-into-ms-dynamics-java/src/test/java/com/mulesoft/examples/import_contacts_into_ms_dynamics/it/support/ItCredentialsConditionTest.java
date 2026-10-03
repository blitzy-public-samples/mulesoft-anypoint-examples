package com.mulesoft.examples.import_contacts_into_ms_dynamics.it.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

/**
 * Unit tests for {@link ItCredentialsCondition#evaluate(InputStream, String[])}, the credentials gate behind
 * {@link EnabledIfItCredentials} on the live-sandbox ITs of this project (D-021).
 *
 * <p>Each test passes an inline YAML document, or {@code null} for an absent {@code application-it.yml}, together
 * with the four Dataverse keys, and checks whether the result is disabled and which reason it carries. No test
 * reads a file from the classpath or starts a Spring application context. Every credential value is an obvious
 * fake (D-012).
 */
final class ItCredentialsConditionTest {

    /** The keys {@code ImportContactsIntoMsDynamicsIT} requires, in the order the condition checks them. */
    private static final String[] KEYS = {
        "dynamics.oauth.tenant-id",
        "dynamics.oauth.client-id",
        "dynamics.oauth.client-secret",
        "dynamics.service-url"
    };

    /** Reason of an enabled result. */
    private static final String ENABLED_REASON = "all IT credentials present in application-it.yml";

    /** Reason of a document that is empty or whose root is not a mapping. */
    private static final String NOT_A_MAPPING_REASON = "application-it.yml is empty or not a YAML mapping";

    /**
     * Returns {@code text} encoded as UTF-8 as a stream, standing in for {@code application-it.yml}.
     *
     * @param text the YAML document
     * @return a stream over the UTF-8 bytes of {@code text}
     */
    private static InputStream yaml(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** An absent {@code application-it.yml} disables the class, and the reason names the file. */
    @Test
    void fileAbsentDisables() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(null, KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason().orElse(""))
                .contains("application-it.yml")
                .isEqualTo("application-it.yml not found on the test classpath");
    }

    /** A document without {@code dynamics.oauth.client-secret} disables the class, and the reason names that key. */
    @Test
    void missingKeyDisables() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(yaml("""
                dynamics:
                  service-url: https://fake.example.invalid
                  oauth:
                    tenant-id: fake-tenant
                    client-id: fake-client
                """), KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason().orElse(""))
                .contains("dynamics.oauth.client-secret")
                .isEqualTo("dynamics.oauth.client-secret is missing or TODO in application-it.yml");
    }

    /**
     * A {@code dynamics.oauth.client-id} of {@code TODO}, plain or quoted with surrounding spaces, disables the
     * class, and the reason names that key.
     */
    @Test
    void todoValueDisables() {
        ConditionEvaluationResult plain = ItCredentialsCondition.evaluate(yaml("""
                dynamics:
                  service-url: https://fake.example.invalid
                  oauth:
                    tenant-id: fake-tenant
                    client-id: TODO
                    client-secret: fake-secret
                """), KEYS);

        assertThat(plain.isDisabled()).isTrue();
        assertThat(plain.getReason().orElse(""))
                .contains("dynamics.oauth.client-id")
                .isEqualTo("dynamics.oauth.client-id is missing or TODO in application-it.yml");

        ConditionEvaluationResult padded = ItCredentialsCondition.evaluate(yaml("""
                dynamics:
                  service-url: https://fake.example.invalid
                  oauth:
                    tenant-id: fake-tenant
                    client-id: "  TODO  "
                    client-secret: fake-secret
                """), KEYS);

        assertThat(padded.isDisabled()).isTrue();
        assertThat(padded.getReason().orElse(""))
                .contains("dynamics.oauth.client-id")
                .isEqualTo("dynamics.oauth.client-id is missing or TODO in application-it.yml");
    }

    /**
     * A {@code dynamics.oauth.tenant-id} that is empty or holds only spaces disables the class, and the reason names
     * that key.
     */
    @Test
    void blankValueDisables() {
        ConditionEvaluationResult empty = ItCredentialsCondition.evaluate(yaml("""
                dynamics:
                  service-url: https://fake.example.invalid
                  oauth:
                    tenant-id: ""
                    client-id: fake-client
                    client-secret: fake-secret
                """), KEYS);

        assertThat(empty.isDisabled()).isTrue();
        assertThat(empty.getReason().orElse(""))
                .contains("dynamics.oauth.tenant-id")
                .isEqualTo("dynamics.oauth.tenant-id is missing or TODO in application-it.yml");

        ConditionEvaluationResult spaces = ItCredentialsCondition.evaluate(yaml("""
                dynamics:
                  service-url: https://fake.example.invalid
                  oauth:
                    tenant-id: "   "
                    client-id: fake-client
                    client-secret: fake-secret
                """), KEYS);

        assertThat(spaces.isDisabled()).isTrue();
        assertThat(spaces.getReason().orElse(""))
                .contains("dynamics.oauth.tenant-id")
                .isEqualTo("dynamics.oauth.tenant-id is missing or TODO in application-it.yml");
    }

    /**
     * All four keys nested under {@code dynamics:}, the layout of {@code application-it.example.yml}, enable the
     * class.
     */
    @Test
    void nestedKeysEnable() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(yaml("""
                dynamics:
                  service-url: https://fake.example.invalid
                  oauth:
                    tenant-id: fake-tenant
                    client-id: fake-client
                    client-secret: fake-secret
                """), KEYS);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason().orElse("")).isEqualTo(ENABLED_REASON);
    }

    /** All four keys written as quoted dotted top-level keys enable the class. */
    @Test
    void dottedTopLevelKeysEnable() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(yaml("""
                "dynamics.service-url": https://fake.example.invalid
                "dynamics.oauth.tenant-id": fake-tenant
                "dynamics.oauth.client-id": fake-client
                "dynamics.oauth.client-secret": fake-secret
                """), KEYS);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason().orElse("")).isEqualTo(ENABLED_REASON);
    }

    /** An empty document and a document whose root is a scalar disable the class, and the reason names the file. */
    @Test
    void emptyDocumentDisables() {
        ConditionEvaluationResult empty = ItCredentialsCondition.evaluate(yaml(""), KEYS);

        assertThat(empty.isDisabled()).isTrue();
        assertThat(empty.getReason().orElse(""))
                .contains("application-it.yml")
                .isEqualTo(NOT_A_MAPPING_REASON);

        ConditionEvaluationResult scalar = ItCredentialsCondition.evaluate(yaml("just-a-string"), KEYS);

        assertThat(scalar.isDisabled()).isTrue();
        assertThat(scalar.getReason().orElse(""))
                .contains("application-it.yml")
                .isEqualTo(NOT_A_MAPPING_REASON);
    }
}
