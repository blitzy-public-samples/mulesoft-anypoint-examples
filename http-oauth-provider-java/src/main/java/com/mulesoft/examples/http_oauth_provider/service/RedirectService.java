package com.mulesoft.examples.http_oauth_provider.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the {@code Location} value of the {@code /redirect} response of the OAuth 2.0 provider
 * (D-041).
 *
 * <p>The value is the token-endpoint URL of the provider, carrying the authorization code received
 * on {@code /redirect}:
 *
 * <pre>{@code
 * http://localhost:<http.provider.port>/token?grant_type=authorization_code&&client_id=<client id>&client_secret=<client secret>&code=<code>&redirect_uri=http://localhost:<http.listener.port>/redirect
 * }</pre>
 *
 * <p>The ports come from {@code http.provider.port} and {@code http.listener.port}; the client id and
 * client secret come from {@code oauth2-provider.client.id} and {@code oauth2-provider.client.secret}
 * (D-012). The host is the literal {@code localhost} in both URLs.
 *
 * <p>Construction fails with an {@link IllegalStateException} whose message names the offending key
 * when {@code http.provider.port} or {@code http.listener.port} lies outside {@code 1..65535}, when
 * the two ports are equal, when {@code oauth2-provider.client.id} or
 * {@code oauth2-provider.client.secret} is {@code null}, blank or {@code TODO} (ignoring case and
 * surrounding whitespace), or when the client id or client secret contains a character other than
 * an ASCII letter, an ASCII digit or one of {@code - . _ ~ ! $ ' ( ) * , ; = : @ / ?}. The message
 * never contains the client id or client secret. Accepted values are stored unchanged (D-362).
 *
 * <p>Instances are immutable and safe for concurrent use.
 */
@Service
public class RedirectService {

    /** Key of the token-endpoint port. */
    private static final String PROVIDER_PORT_KEY = "http.provider.port";

    /** Key of the {@code /redirect} listener port. */
    private static final String LISTENER_PORT_KEY = "http.listener.port";

    /** Key of the OAuth client id. */
    private static final String CLIENT_ID_KEY = "oauth2-provider.client.id";

    /** Key of the OAuth client secret. */
    private static final String CLIENT_SECRET_KEY = "oauth2-provider.client.secret";

    /** Lowest accepted port. */
    private static final int MIN_PORT = 1;

    /** Highest accepted port. */
    private static final int MAX_PORT = 65535;

    /** Placeholder value of an unset credential in {@code application.yml}, compared ignoring case. */
    private static final String PLACEHOLDER = "TODO";

    /** Characters accepted in the client id and client secret besides ASCII letters and digits. */
    private static final String ALLOWED_PUNCTUATION = "-._~!$'()*,;=:@/?";

    /** Message suffix for a client id or client secret holding a character that is not accepted. */
    private static final String ALLOWED_CHARACTERS_MESSAGE =
            " must contain only letters, digits and the characters - . _ ~ ! $ ' ( ) * , ; = : @ / ?";

    /** Port of the token endpoint in the returned URL, from {@code http.provider.port}. */
    private final int providerPort;

    /** Port of the {@code redirect_uri} in the returned URL, from {@code http.listener.port}. */
    private final int listenerPort;

    /** Value of the {@code client_id} parameter, from {@code oauth2-provider.client.id}. */
    private final String clientId;

    /** Value of the {@code client_secret} parameter, from {@code oauth2-provider.client.secret}. */
    private final String clientSecret;

    /**
     * Creates the service from the provider configuration.
     *
     * @param providerPort the port of the token endpoint, from {@code http.provider.port}
     * @param listenerPort the port of the {@code /redirect} listener, from {@code http.listener.port}
     * @param clientId the OAuth client id, from {@code oauth2-provider.client.id} (D-012)
     * @param clientSecret the OAuth client secret, from {@code oauth2-provider.client.secret} (D-012)
     * @throws IllegalStateException naming {@code http.provider.port} or {@code http.listener.port}
     *     when that port lies outside {@code 1..65535}, naming both port keys when the ports are
     *     equal, and naming {@code oauth2-provider.client.id} or
     *     {@code oauth2-provider.client.secret} when that value is {@code null}, blank or
     *     {@code TODO}, or contains a character that is not accepted (D-362)
     */
    public RedirectService(
            @Value("${http.provider.port}") int providerPort,
            @Value("${http.listener.port}") int listenerPort,
            @Value("${oauth2-provider.client.id}") String clientId,
            @Value("${oauth2-provider.client.secret}") String clientSecret) {
        requirePortInRange(PROVIDER_PORT_KEY, providerPort);
        requirePortInRange(LISTENER_PORT_KEY, listenerPort);
        requireDistinctPorts(providerPort, listenerPort);
        requireNotBlankOrPlaceholder(CLIENT_ID_KEY, clientId);
        requireNotBlankOrPlaceholder(CLIENT_SECRET_KEY, clientSecret);
        requireAllowedCharacters(CLIENT_ID_KEY, clientId);
        requireAllowedCharacters(CLIENT_SECRET_KEY, clientSecret);
        this.providerPort = providerPort;
        this.listenerPort = listenerPort;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    /**
     * Checks that a port lies in {@code 1..65535}.
     *
     * @param key the configuration key of the port
     * @param port the port value
     * @throws IllegalStateException {@code "<key> must be between 1 and 65535, was <port>"} when the
     *     port lies outside {@code 1..65535}
     */
    private static void requirePortInRange(String key, int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalStateException(key + " must be between " + MIN_PORT + " and " + MAX_PORT
                    + ", was " + port);
        }
    }

    /**
     * Checks that the provider port and the listener port differ.
     *
     * @param providerPort the value of {@code http.provider.port}
     * @param listenerPort the value of {@code http.listener.port}
     * @throws IllegalStateException
     *     {@code "http.provider.port and http.listener.port must differ, both are <port>"} when the
     *     two ports are equal
     */
    private static void requireDistinctPorts(int providerPort, int listenerPort) {
        if (providerPort == listenerPort) {
            throw new IllegalStateException(PROVIDER_PORT_KEY + " and " + LISTENER_PORT_KEY
                    + " must differ, both are " + providerPort);
        }
    }

    /**
     * Checks that a credential is neither {@code null}, blank nor the placeholder {@code TODO}, the
     * placeholder compared ignoring case after trimming.
     *
     * @param key the configuration key of the credential
     * @param value the credential value
     * @throws IllegalStateException {@code "<key> must not be blank or TODO"} when the value is
     *     {@code null}, blank or {@code TODO}
     */
    private static void requireNotBlankOrPlaceholder(String key, String value) {
        if (value == null || value.isBlank() || PLACEHOLDER.equalsIgnoreCase(value.trim())) {
            throw new IllegalStateException(key + " must not be blank or TODO");
        }
    }

    /**
     * Checks that every character of a credential is an ASCII letter, an ASCII digit or one of
     * {@code - . _ ~ ! $ ' ( ) * , ; = : @ / ?}. The exception message does not contain the value.
     *
     * @param key the configuration key of the credential
     * @param value the credential value, not {@code null}
     * @throws IllegalStateException
     *     {@code "<key> must contain only letters, digits and the characters - . _ ~ ! $ ' ( ) * , ; = : @ / ?"}
     *     when the value contains any other character
     */
    private static void requireAllowedCharacters(String key, String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!isAllowedCharacter(value.charAt(i))) {
                throw new IllegalStateException(key + ALLOWED_CHARACTERS_MESSAGE);
            }
        }
    }

    /**
     * Tells whether a character is an ASCII letter, an ASCII digit or one of
     * {@code - . _ ~ ! $ ' ( ) * , ; = : @ / ?}.
     *
     * @param c the character
     * @return {@code true} when the character is accepted in a client id or client secret
     */
    private static boolean isAllowedCharacter(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || ALLOWED_PUNCTUATION.indexOf(c) >= 0;
    }

    /**
     * Returns the {@code Location} value for the authorization code received on {@code /redirect}
     * (D-041).
     *
     * <p>The result is
     * {@code http://localhost:<providerPort>/token?grant_type=authorization_code&&client_id=<clientId>&client_secret=<clientSecret>&code=<code>&redirect_uri=http://localhost:<listenerPort>/redirect}.
     * The separator after {@code grant_type=authorization_code} is the literal {@code &&}. The code is
     * inserted exactly as received, with no URL encoding, trimming or validation, and a {@code null}
     * code is written as {@code code=null}. The {@code redirect_uri} value is not URL-encoded.
     *
     * <p>With ports 8081 and 8082, client id {@code example-id}, client secret
     * {@code example-secret} and code {@code abc}, the result is
     * {@code http://localhost:8081/token?grant_type=authorization_code&&client_id=example-id&client_secret=example-secret&code=abc&redirect_uri=http://localhost:8082/redirect}.
     *
     * @param code the value of the {@code code} query parameter, or {@code null} when the request
     *     carries none
     * @return the token-endpoint URL for the {@code Location} header, never {@code null}
     */
    public String redirectFlow(String code) {
        return "http://localhost:" + providerPort
                + "/token?grant_type=authorization_code&&client_id=" + clientId
                + "&client_secret=" + clientSecret
                + "&code=" + String.valueOf(code)
                + "&redirect_uri=http://localhost:" + listenerPort + "/redirect";
    }
}
