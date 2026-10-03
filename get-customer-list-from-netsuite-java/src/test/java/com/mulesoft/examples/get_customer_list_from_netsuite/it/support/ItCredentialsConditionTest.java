package com.mulesoft.examples.get_customer_list_from_netsuite.it.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.lang.reflect.AnnotatedElement;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Unit tests of {@link ItCredentialsCondition}, the execution condition behind {@link EnabledIfItCredentials}
 * (D-021).
 *
 * <p>Each file-based test writes {@code application-it.yml} into a JUnit temporary directory, in the layout of
 * the committed {@code application-it.example.yml}, and passes its URL with {@link #KEYS} to
 * {@link ItCredentialsCondition#evaluate(URL, String[])}. The tests cover
 * <ul>
 *   <li>the file absent, a key missing, a key holding {@code TODO} and a key holding an empty string, each
 *       disabling with a reason that names the file and the failing key;</li>
 *   <li>all keys present, nested or as a flat dotted key, enabling;</li>
 *   <li>an element without {@link EnabledIfItCredentials}, enabled by
 *       {@link ItCredentialsCondition#evaluateExecutionCondition(ExtensionContext)};</li>
 *   <li>no reason holding any value written to the file.</li>
 * </ul>
 * No Spring application context starts, and every value written is fake.
 */
public class ItCredentialsConditionTest {

    /** The four {@code application-it.yml} keys that {@code it/GetCustomerListFromNetsuiteIT} requires (D-021). */
    private static final String[] KEYS = {
        "netsuite.account",
        "netsuite.oauth.client-id",
        "netsuite.oauth.certificate-id",
        "netsuite.oauth.private-key-path"
    };

    /** Every fake non-placeholder value the tests write; none may appear in a reason. */
    private static final String[] FAKE_VALUES = {"1234567_SB1", "abc-client", "cert-01", "/tmp/k.pem"};

    /** Reason of a disabled result when {@code application-it.yml} is not on the test classpath. */
    private static final String ABSENT_REASON = "application-it.yml not found on the test classpath (D-021)";

    /** Reason of an enabled result when every required key holds a usable value. */
    private static final String ENABLED_REASON = "application-it.yml holds all required keys";

    /** Reason of an enabled result for an element without {@link EnabledIfItCredentials}. */
    private static final String NO_ANNOTATION_REASON = "No @EnabledIfItCredentials";

    /** Temporary directory that receives the {@code application-it.yml} of each file-based test. */
    @TempDir
    Path dir;

    /** A {@code null} resource disables with the file-not-found reason (D-021). */
    @Test
    void fileAbsent() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(null, KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).get().asString()
                .contains("application-it.yml")
                .isEqualTo(ABSENT_REASON);
    }

    /** A file without {@code netsuite.oauth.certificate-id} disables with a reason naming that key (D-021). */
    @Test
    void keyMissing() throws IOException {
        URL resource = write("""
                netsuite:
                  account: 1234567_SB1
                  oauth:
                    client-id: abc-client
                    private-key-path: /tmp/k.pem
                """);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(resource, KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).get().asString()
                .contains("netsuite.oauth.certificate-id")
                .isEqualTo(failingKeyReason("netsuite.oauth.certificate-id"))
                .doesNotContain(FAKE_VALUES);
    }

    /** A {@code netsuite.oauth.client-id} holding {@code TODO} disables with a reason naming that key (D-021). */
    @Test
    void valueTodo() throws IOException {
        URL resource = write("""
                netsuite:
                  account: 1234567_SB1
                  oauth:
                    client-id: TODO
                    certificate-id: cert-01
                    private-key-path: /tmp/k.pem
                """);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(resource, KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).get().asString()
                .contains("netsuite.oauth.client-id")
                .isEqualTo(failingKeyReason("netsuite.oauth.client-id"))
                .doesNotContain(FAKE_VALUES);
    }

    /** A {@code netsuite.account} holding an empty string disables with a reason naming that key (D-021). */
    @Test
    void valueBlank() throws IOException {
        URL resource = write("""
                netsuite:
                  account: ""
                  oauth:
                    client-id: abc-client
                    certificate-id: cert-01
                    private-key-path: /tmp/k.pem
                """);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(resource, KEYS);

        assertThat(result.isDisabled()).isTrue();
        assertThat(result.getReason()).get().asString()
                .contains("netsuite.account")
                .isEqualTo(failingKeyReason("netsuite.account"))
                .doesNotContain(FAKE_VALUES);
    }

    /** All four keys nested under {@code netsuite:} and {@code oauth:} with usable values enable (D-021). */
    @Test
    void allKeysNested() throws IOException {
        URL resource = write("""
                netsuite:
                  account: 1234567_SB1
                  oauth:
                    client-id: abc-client
                    certificate-id: cert-01
                    private-key-path: /tmp/k.pem
                """);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(resource, KEYS);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason()).get().asString()
                .isEqualTo(ENABLED_REASON)
                .doesNotContain(FAKE_VALUES);
    }

    /**
     * {@code netsuite.oauth.private-key-path} as a top-level flat dotted key, beside the other three keys nested
     * under {@code netsuite:} and {@code oauth:}, enables (D-021).
     */
    @Test
    void flatDottedKeyAccepted() throws IOException {
        URL resource = write("""
                netsuite:
                  account: 1234567_SB1
                  oauth:
                    client-id: abc-client
                    certificate-id: cert-01
                netsuite.oauth.private-key-path: /tmp/k.pem
                """);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(resource, KEYS);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason()).get().asString()
                .isEqualTo(ENABLED_REASON)
                .doesNotContain(FAKE_VALUES);
    }

    /**
     * {@link ItCredentialsCondition#evaluateExecutionCondition(ExtensionContext)} enables an element that does
     * not carry {@link EnabledIfItCredentials} (D-021).
     */
    @Test
    void noAnnotationPassesThrough() {
        ExtensionContext context = Mockito.mock(ExtensionContext.class);
        when(context.getElement()).thenReturn(Optional.<AnnotatedElement>of(String.class));

        ConditionEvaluationResult result = new ItCredentialsCondition().evaluateExecutionCondition(context);

        assertThat(result.isDisabled()).isFalse();
        assertThat(result.getReason()).get().asString()
                .isEqualTo(NO_ANNOTATION_REASON);
    }

    /**
     * Writes {@code yaml} as UTF-8 to {@code application-it.yml} in {@link #dir}.
     *
     * @param yaml the file content
     * @return the URL of the written file
     * @throws IOException if the file cannot be written or its path cannot be converted to a URL
     */
    private URL write(String yaml) throws IOException {
        Path path = dir.resolve("application-it.yml");
        Files.writeString(path, yaml, StandardCharsets.UTF_8);
        return path.toUri().toURL();
    }

    /**
     * Builds the reason of a disabled result whose first failing key is {@code key}.
     *
     * @param key the dotted key the reason names
     * @return the expected reason text
     */
    private static String failingKeyReason(String key) {
        return "application-it.yml key '" + key + "' is missing, blank or TODO (D-021)";
    }
}
