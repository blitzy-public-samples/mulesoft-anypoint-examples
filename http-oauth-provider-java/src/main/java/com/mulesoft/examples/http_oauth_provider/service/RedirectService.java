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
 * <p>Instances are immutable and safe for concurrent use.
 */
@Service
public class RedirectService {

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
     */
    public RedirectService(
            @Value("${http.provider.port}") int providerPort,
            @Value("${http.listener.port}") int listenerPort,
            @Value("${oauth2-provider.client.id}") String clientId,
            @Value("${oauth2-provider.client.secret}") String clientSecret) {
        this.providerPort = providerPort;
        this.listenerPort = listenerPort;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
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
