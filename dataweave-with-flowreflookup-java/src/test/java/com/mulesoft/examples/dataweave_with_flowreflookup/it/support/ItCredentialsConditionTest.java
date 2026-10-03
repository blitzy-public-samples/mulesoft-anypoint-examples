package com.mulesoft.examples.dataweave_with_flowreflookup.it.support;

import static org.junit.jupiter.api.Assertions.*;

import java.io.StringReader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;

/**
 * Unit test of {@link ItCredentialsCondition#evaluate(java.io.Reader, String[])} with in-memory YAML
 * documents: an absent {@code application-it.yml}, a missing key, {@code TODO} and blank values, an enabled
 * document, and nested and literal dotted keys resolving to the same names.
 *
 * <p>Every document is a text block read through a {@link StringReader}; no file, classpath resource,
 * socket or Spring context is used. Each disabled case stores {@link #SECRET} as {@code sfdc.password} and
 * asserts that the disabled reason does not contain it.
 *
 * <p>See D-021 in {@code DECISIONS.md}.
 */
class ItCredentialsConditionTest {

    /** The keys and key order that {@code DataWeaveWithFlowRefIT} passes to {@link EnabledIfItCredentials}. */
    static final String[] SFDC_KEYS = {
        "sfdc.user", "sfdc.password", "sfdc.securityToken", "sfdc.key", "sfdc.secret", "sfdc.login-url"
    };

    /** Distinctive {@code sfdc.password} value that no disabled reason may contain. */
    static final String SECRET = "s3cr3t-Value-9f2";

    /**
     * A {@code null} reader stands for an absent resource: the result is disabled with the exact
     * not-found reason.
     */
    @Test
    void disabledWhenFileAbsent() {
        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(null, SFDC_KEYS);

        assertTrue(result.isDisabled());
        assertEquals("application-it.yml not found on the test classpath", reasonOf(result));
    }

    /**
     * A nested {@code sfdc:} mapping without {@code secret} is disabled with a reason that names
     * {@code sfdc.secret}, does not name the present {@code sfdc.user} and does not contain the password
     * value.
     */
    @Test
    void disabledWhenKeyMissingAndReasonNamesKey() {
        String yaml = """
                sfdc:
                  user: placeholder-user@example.com
                  password: %s
                  securityToken: placeholder-token
                  key: placeholder-consumer-key
                  login-url: https://login.salesforce.com
                """.formatted(SECRET);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(new StringReader(yaml), SFDC_KEYS);

        assertTrue(result.isDisabled());
        String reason = reasonOf(result);
        assertTrue(reason.contains("sfdc.secret"), reason);
        assertFalse(reason.contains("sfdc.user"), reason);
        assertFalse(reason.contains(SECRET), reason);
    }

    /**
     * A {@code securityToken} of {@code TODO} with a trailing comment, as in
     * {@code application-it.example.yml}, is disabled with a reason that names {@code sfdc.securityToken}
     * and does not contain the password value; a quoted, lower-case, padded {@code " todo "} is disabled
     * with the same checks on its reason.
     */
    @Test
    void disabledWhenKeyIsTodo() {
        String templateTodo = """
                sfdc:
                  user: placeholder-user@example.com
                  password: %s
                  securityToken: TODO  # TODO: supply the Salesforce security token
                  key: placeholder-consumer-key
                  secret: placeholder-consumer-secret
                  login-url: https://login.salesforce.com
                """.formatted(SECRET);
        String paddedLowerCaseTodo = """
                sfdc:
                  user: placeholder-user@example.com
                  password: %s
                  securityToken: " todo "
                  key: placeholder-consumer-key
                  secret: placeholder-consumer-secret
                  login-url: https://login.salesforce.com
                """.formatted(SECRET);

        ConditionEvaluationResult result =
                ItCredentialsCondition.evaluate(new StringReader(templateTodo), SFDC_KEYS);

        assertTrue(result.isDisabled());
        String reason = reasonOf(result);
        assertTrue(reason.contains("sfdc.securityToken"), reason);
        assertFalse(reason.contains(SECRET), reason);

        ConditionEvaluationResult padded =
                ItCredentialsCondition.evaluate(new StringReader(paddedLowerCaseTodo), SFDC_KEYS);

        assertTrue(padded.isDisabled());
        String paddedReason = reasonOf(padded);
        assertTrue(paddedReason.contains("sfdc.securityToken"), paddedReason);
        assertFalse(paddedReason.contains(SECRET), paddedReason);
    }

    /**
     * A whitespace-only {@code key} is disabled with a reason that names {@code sfdc.key} and does not
     * contain the password value; an empty {@code key:}, which YAML reads as {@code null}, is disabled with
     * the same checks on its reason.
     */
    @Test
    void disabledWhenValueBlank() {
        String blankKey = """
                sfdc:
                  user: placeholder-user@example.com
                  password: %s
                  securityToken: placeholder-token
                  key: "   "
                  secret: placeholder-consumer-secret
                  login-url: https://login.salesforce.com
                """.formatted(SECRET);
        String nullKey = """
                sfdc:
                  user: placeholder-user@example.com
                  password: %s
                  securityToken: placeholder-token
                  key:
                  secret: placeholder-consumer-secret
                  login-url: https://login.salesforce.com
                """.formatted(SECRET);

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(new StringReader(blankKey), SFDC_KEYS);

        assertTrue(result.isDisabled());
        String reason = reasonOf(result);
        assertTrue(reason.contains("sfdc.key"), reason);
        assertFalse(reason.contains(SECRET), reason);

        ConditionEvaluationResult empty = ItCredentialsCondition.evaluate(new StringReader(nullKey), SFDC_KEYS);

        assertTrue(empty.isDisabled());
        String emptyReason = reasonOf(empty);
        assertTrue(emptyReason.contains("sfdc.key"), emptyReason);
        assertFalse(emptyReason.contains(SECRET), emptyReason);
    }

    /** One nested {@code sfdc:} mapping that defines all six keys with usable values is enabled. */
    @Test
    void enabledWhenAllSalesforceKeysPresent() {
        String yaml = """
                sfdc:
                  user: placeholder-user@example.com
                  password: placeholder-password
                  securityToken: tok123
                  key: placeholder-consumer-key
                  secret: placeholder-consumer-secret
                  login-url: https://login.salesforce.com
                """;

        ConditionEvaluationResult result = ItCredentialsCondition.evaluate(new StringReader(yaml), SFDC_KEYS);

        assertFalse(result.isDisabled(), () -> result.getReason().orElse(""));
    }

    /**
     * A literal flat key {@code sfdc.user} and a nested {@code sfdc:} mapping holding the other five keys
     * resolve together: the document is enabled for all six keys, and for {@code sfdc.user} with
     * {@code sfdc.login-url} alone.
     */
    @Test
    void flatDottedAndNestedKeysBothResolve() {
        String yaml = """
                sfdc.user: flat-user
                sfdc:
                  password: placeholder-password
                  securityToken: tok123
                  key: placeholder-consumer-key
                  secret: placeholder-consumer-secret
                  login-url: https://login.salesforce.com
                """;

        ConditionEvaluationResult all = ItCredentialsCondition.evaluate(new StringReader(yaml), SFDC_KEYS);

        assertFalse(all.isDisabled(), () -> all.getReason().orElse(""));

        ConditionEvaluationResult flatAndNested = ItCredentialsCondition.evaluate(
                new StringReader(yaml), new String[] {"sfdc.user", "sfdc.login-url"});

        assertFalse(flatAndNested.isDisabled(), () -> flatAndNested.getReason().orElse(""));
    }

    /**
     * Returns the reason of {@code result}, failing the test when the result carries none.
     *
     * @param result the evaluation result to read
     * @return the reason text
     */
    private static String reasonOf(ConditionEvaluationResult result) {
        assertTrue(result.getReason().isPresent(), "reason is present");
        return result.getReason().get();
    }
}
