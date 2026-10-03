package com.mulesoft.examples.http_oauth_provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests of the construction checks of {@link RedirectService} (D-362), with no Spring
 * application context.
 *
 * <p>Each test calls the constructor directly and asserts either the exact
 * {@link IllegalStateException} message naming the offending key of {@code http.provider.port},
 * {@code http.listener.port}, {@code oauth2-provider.client.id} or
 * {@code oauth2-provider.client.secret}, or the exact {@link RedirectService#redirectFlow(String)}
 * result, which shows the accepted values stored unchanged.
 *
 * <p>The class and its test methods are public (D-133).
 */
public class RedirectServiceValidationTest {

    /** Key of the token-endpoint port. */
    private static final String PROVIDER_PORT_KEY = "http.provider.port";

    /** Key of the {@code /redirect} listener port. */
    private static final String LISTENER_PORT_KEY = "http.listener.port";

    /** Key of the OAuth client id. */
    private static final String CLIENT_ID_KEY = "oauth2-provider.client.id";

    /** Key of the OAuth client secret. */
    private static final String CLIENT_SECRET_KEY = "oauth2-provider.client.secret";

    /** Valid token-endpoint port, the {@code application.yml} default of {@code http.provider.port}. */
    private static final int PROVIDER_PORT = 8081;

    /** Valid listener port, the {@code application.yml} default of {@code http.listener.port}. */
    private static final int LISTENER_PORT = 8082;

    /** Valid client id, the client id of the embedded provider in {@code application-test.yml}. */
    private static final String CLIENT_ID = "myclientid";

    /** Valid client secret used wherever a test varies another value. */
    private static final String CLIENT_SECRET = "s3cret";

    /** Authorization code passed to {@link RedirectService#redirectFlow(String)}. */
    private static final String CODE = "abc";

    /** Message suffix for a blank or {@code TODO} client id or client secret. */
    private static final String BLANK_OR_TODO_MESSAGE = " must not be blank or TODO";

    /** Message suffix for a client id or client secret holding a character that is not accepted. */
    private static final String ALLOWED_CHARACTERS_MESSAGE =
            " must contain only letters, digits and the characters - . _ ~ ! $ ' ( ) * , ; = : @ / ?";

    /** Every punctuation character accepted in a client id or client secret. */
    private static final String ALLOWED_PUNCTUATION = "-._~!$'()*,;=:@/?";

    /**
     * Ports at both ends of {@code 1..65535}, in either role, are accepted and appear in the
     * {@code Location} value.
     *
     * @param providerPort the token-endpoint port
     * @param listenerPort the listener port
     */
    @ParameterizedTest(name = "provider port {0}, listener port {1}")
    @CsvSource({"1, 65535", "65535, 1"})
    @DisplayName("Ports 1 and 65535 are accepted and written into the Location value")
    public void acceptsBoundaryPorts(int providerPort, int listenerPort) {
        RedirectService service = new RedirectService(providerPort, listenerPort, CLIENT_ID, CLIENT_SECRET);

        assertEquals(
                "http://localhost:" + providerPort
                        + "/token?grant_type=authorization_code&&client_id=myclientid&client_secret=s3cret"
                        + "&code=abc&redirect_uri=http://localhost:" + listenerPort + "/redirect",
                service.redirectFlow(CODE));
    }

    /**
     * A provider port outside {@code 1..65535} is rejected with a message naming
     * {@code http.provider.port} and the port.
     *
     * @param providerPort the rejected port
     */
    @ParameterizedTest(name = "provider port {0}")
    @ValueSource(ints = {0, -1, 65536})
    @DisplayName("Provider port outside 1..65535 is rejected naming http.provider.port")
    public void rejectsProviderPortOutsideRange(int providerPort) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(providerPort, LISTENER_PORT, CLIENT_ID, CLIENT_SECRET));

        assertEquals(PROVIDER_PORT_KEY + " must be between 1 and 65535, was " + providerPort,
                failure.getMessage());
    }

    /**
     * A listener port outside {@code 1..65535} is rejected with a message naming
     * {@code http.listener.port} and the port.
     *
     * @param listenerPort the rejected port
     */
    @ParameterizedTest(name = "listener port {0}")
    @ValueSource(ints = {0, 70000})
    @DisplayName("Listener port outside 1..65535 is rejected naming http.listener.port")
    public void rejectsListenerPortOutsideRange(int listenerPort) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(PROVIDER_PORT, listenerPort, CLIENT_ID, CLIENT_SECRET));

        assertEquals(LISTENER_PORT_KEY + " must be between 1 and 65535, was " + listenerPort,
                failure.getMessage());
    }

    /** With both ports outside {@code 1..65535}, the message names {@code http.provider.port}. */
    @Test
    @DisplayName("Provider port is checked before listener port")
    public void checksProviderPortFirst() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(0, 70000, CLIENT_ID, CLIENT_SECRET));

        assertEquals(PROVIDER_PORT_KEY + " must be between 1 and 65535, was 0", failure.getMessage());
    }

    /** Equal provider and listener ports are rejected with a message naming both keys and the port. */
    @Test
    @DisplayName("Equal provider and listener ports are rejected naming both keys")
    public void rejectsEqualPorts() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(PROVIDER_PORT, PROVIDER_PORT, CLIENT_ID, CLIENT_SECRET));

        assertEquals("http.provider.port and http.listener.port must differ, both are 8081",
                failure.getMessage());
    }

    /**
     * A {@code null}, blank or {@code TODO} client id is rejected with a message naming
     * {@code oauth2-provider.client.id}.
     *
     * @param clientId the rejected client id
     */
    @ParameterizedTest(name = "client id [{0}]")
    @NullSource
    @ValueSource(strings = {"", "  ", "TODO", "todo", " TODO "})
    @DisplayName("Null, blank or TODO client id is rejected naming oauth2-provider.client.id")
    public void rejectsBlankOrPlaceholderClientId(String clientId) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(PROVIDER_PORT, LISTENER_PORT, clientId, CLIENT_SECRET));

        assertEquals(CLIENT_ID_KEY + BLANK_OR_TODO_MESSAGE, failure.getMessage());
    }

    /**
     * A {@code null}, blank or {@code TODO} client secret is rejected with a message naming
     * {@code oauth2-provider.client.secret}.
     *
     * @param clientSecret the rejected client secret
     */
    @ParameterizedTest(name = "client secret [{0}]")
    @NullSource
    @ValueSource(strings = {"", "  ", "TODO", "todo", " TODO "})
    @DisplayName("Null, blank or TODO client secret is rejected naming oauth2-provider.client.secret")
    public void rejectsBlankOrPlaceholderClientSecret(String clientSecret) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(PROVIDER_PORT, LISTENER_PORT, CLIENT_ID, clientSecret));

        assertEquals(CLIENT_SECRET_KEY + BLANK_OR_TODO_MESSAGE, failure.getMessage());
    }

    /**
     * A client id holding a character that is not accepted is rejected with a message naming
     * {@code oauth2-provider.client.id} and not containing the value.
     *
     * @param clientId the rejected client id
     */
    @ParameterizedTest(name = "client id [{0}]")
    @ValueSource(strings = {"a b", "a&b", "a+b", "a%20b", "a#b", "a\nb", "\u00e9"})
    @DisplayName("Client id with a character that is not accepted is rejected without echoing it")
    public void rejectsClientIdWithCharacterNotAccepted(String clientId) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(PROVIDER_PORT, LISTENER_PORT, clientId, CLIENT_SECRET));

        assertEquals(CLIENT_ID_KEY + ALLOWED_CHARACTERS_MESSAGE, failure.getMessage());
        assertFalse(failure.getMessage().contains(clientId));
    }

    /**
     * A client secret holding a character that is not accepted is rejected with a message naming
     * {@code oauth2-provider.client.secret} and not containing the value.
     *
     * @param clientSecret the rejected client secret
     */
    @ParameterizedTest(name = "client secret [{0}]")
    @ValueSource(strings = {"a b", "a&b", "a+b", "a%20b", "a#b", "a\nb", "\u00e9"})
    @DisplayName("Client secret with a character that is not accepted is rejected without echoing it")
    public void rejectsClientSecretWithCharacterNotAccepted(String clientSecret) {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new RedirectService(PROVIDER_PORT, LISTENER_PORT, CLIENT_ID, clientSecret));

        assertEquals(CLIENT_SECRET_KEY + ALLOWED_CHARACTERS_MESSAGE, failure.getMessage());
        assertFalse(failure.getMessage().contains(clientSecret));
    }

    /** The client id and client secret of {@code application-test.yml} are stored unchanged. */
    @Test
    @DisplayName("Embedded-provider client id and secret are accepted and stored unchanged")
    public void acceptsEmbeddedProviderCredentials() {
        RedirectService service =
                new RedirectService(PROVIDER_PORT, LISTENER_PORT, "myclientid", "test-client-secret");

        assertEquals("http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid"
                        + "&client_secret=test-client-secret&code=abc&redirect_uri=http://localhost:8082/redirect",
                service.redirectFlow(CODE));
    }

    /** The default fixture client id and client secret are stored unchanged. */
    @Test
    @DisplayName("Client id myclientid and secret s3cret are accepted and stored unchanged")
    public void acceptsDefaultFixtureCredentials() {
        RedirectService service = new RedirectService(PROVIDER_PORT, LISTENER_PORT, CLIENT_ID, CLIENT_SECRET);

        assertEquals("http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid"
                        + "&client_secret=s3cret&code=abc&redirect_uri=http://localhost:8082/redirect",
                service.redirectFlow(CODE));
    }

    /** The client id and client secret of the {@link RedirectService} Javadoc example are stored unchanged. */
    @Test
    @DisplayName("Client id example-id and secret example-secret are accepted and stored unchanged")
    public void acceptsJavadocExampleCredentials() {
        RedirectService service =
                new RedirectService(PROVIDER_PORT, LISTENER_PORT, "example-id", "example-secret");

        assertEquals("http://localhost:8081/token?grant_type=authorization_code&&client_id=example-id"
                        + "&client_secret=example-secret&code=abc&redirect_uri=http://localhost:8082/redirect",
                service.redirectFlow(CODE));
    }

    /**
     * Values holding every accepted punctuation character, letters of both cases and digits are
     * stored unchanged, with no trimming or encoding.
     */
    @Test
    @DisplayName("Client id and secret with every accepted punctuation character are stored unchanged")
    public void acceptsEveryAllowedPunctuationCharacter() {
        String clientId = "Az09" + ALLOWED_PUNCTUATION;
        String clientSecret = ALLOWED_PUNCTUATION + "s3cret";
        RedirectService service = new RedirectService(PROVIDER_PORT, LISTENER_PORT, clientId, clientSecret);

        assertEquals("http://localhost:8081/token?grant_type=authorization_code&&client_id=Az09-._~!$'()*,;=:@/?"
                        + "&client_secret=-._~!$'()*,;=:@/?s3cret&code=abc&redirect_uri=http://localhost:8082/redirect",
                service.redirectFlow(CODE));
    }

    /** Values that contain {@code TODO} among other characters are accepted and stored unchanged. */
    @Test
    @DisplayName("Client id and secret containing TODO among other characters are accepted")
    public void acceptsValuesContainingPlaceholderText() {
        RedirectService service = new RedirectService(PROVIDER_PORT, LISTENER_PORT, "TODO-id", "todo2");

        assertEquals("http://localhost:8081/token?grant_type=authorization_code&&client_id=TODO-id"
                        + "&client_secret=todo2&code=abc&redirect_uri=http://localhost:8082/redirect",
                service.redirectFlow(CODE));
    }
}
