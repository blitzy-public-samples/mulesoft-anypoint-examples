package com.mulesoft.examples.http_oauth_provider.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests of {@link RedirectService#redirectFlow(String)}, the {@code Location} value of flow
 * {@code redirectFlow} (http-oauth-provider.xml:56); counts toward the service coverage floor
 * (D-049); client secret is a neutral test value (D-012).
 *
 * <p>Each test constructs the service directly, with no Spring application context, and compares
 * the whole returned URL with the expected string.
 */
public class RedirectServiceTest {

    /** Client id written into the {@code client_id} parameter. */
    private static final String CLIENT_ID = "myclientid";

    /** Client secret written into the {@code client_secret} parameter (D-012). */
    private static final String CLIENT_SECRET = "s3cret";

    /**
     * Creates the service under test with the given ports and the test client id and secret.
     *
     * @param providerPort the token-endpoint port, the value of {@code http.provider.port}
     * @param listenerPort the {@code /redirect} listener port, the value of {@code http.listener.port}
     * @return a new {@link RedirectService}
     */
    private static RedirectService service(int providerPort, int listenerPort) {
        return new RedirectService(providerPort, listenerPort, CLIENT_ID, CLIENT_SECRET);
    }

    /**
     * With the original ports 8081 and 8082 and code {@code abc}, the result is the token URL whose
     * separator after {@code grant_type=authorization_code} is the literal {@code &&}.
     */
    @Test
    @DisplayName("redirectFlow builds the token URL with the literal double ampersand")
    public void redirectFlowBuildsTokenLocation() {
        assertEquals(
                "http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid&client_secret=s3cret&code=abc&redirect_uri=http://localhost:8082/redirect",
                service(8081, 8082).redirectFlow("abc"));
    }

    /**
     * The token-endpoint port and the {@code redirect_uri} port are the provider and listener ports
     * passed to the constructor.
     *
     * @param providerPort the token-endpoint port
     * @param listenerPort the {@code /redirect} listener port
     */
    @ParameterizedTest(name = "provider port {0}, listener port {1}")
    @CsvSource({"8081, 8082", "18081, 28082"})
    @DisplayName("redirectFlow takes the provider and listener ports from its constructor")
    public void redirectFlowUsesConfiguredPorts(int providerPort, int listenerPort) {
        assertEquals(
                "http://localhost:" + providerPort
                        + "/token?grant_type=authorization_code&&client_id=myclientid&client_secret=s3cret&code=abc&redirect_uri=http://localhost:"
                        + listenerPort + "/redirect",
                service(providerPort, listenerPort).redirectFlow("abc"));
    }

    /** A code holding {@code &} and {@code %20} is written exactly as received, with no URL encoding. */
    @Test
    @DisplayName("redirectFlow inserts the code without URL encoding")
    public void redirectFlowInsertsCodeUnencoded() {
        assertEquals(
                "http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid&client_secret=s3cret&code=a&b%20c&redirect_uri=http://localhost:8082/redirect",
                service(8081, 8082).redirectFlow("a&b%20c"));
    }

    /** A {@code null} code, the value of a missing {@code code} query parameter, is written as {@code code=null}. */
    @Test
    @DisplayName("redirectFlow renders a missing code as null")
    public void redirectFlowRendersNullCodeAsNull() {
        assertEquals(
                "http://localhost:8081/token?grant_type=authorization_code&&client_id=myclientid&client_secret=s3cret&code=null&redirect_uri=http://localhost:8082/redirect",
                service(8081, 8082).redirectFlow(null));
    }
}
