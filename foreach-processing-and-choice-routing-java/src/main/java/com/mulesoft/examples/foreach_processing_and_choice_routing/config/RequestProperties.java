package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the two outbound SOAP requests of the loan broker, bound from the
 * {@code request.*} keys of {@code application.yml}.
 *
 * <p>Each component binds the key of the same name in kebab case:
 * {@code http-request-configuration-1} holds the credit-agency request of
 * {@code HTTP_Request_Configuration_1}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:5], and
 * {@code http-request-configuration-2} holds the bank request of
 * {@code HTTP_Request_Configuration_2}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:6]. The request
 * {@code path} and {@code responseTimeout} of each come from its {@code http:request}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:73,108].
 *
 * <p>The application's configuration-properties scan registers this record as a bean.
 *
 * <p>The constructors check the bound values while the context starts and throw
 * {@link IllegalArgumentException} for the first unusable one, with a message that starts with its key
 * under {@code request.http-request-configuration-1} or {@code request.http-request-configuration-2}:
 * a group that is absent; a {@code host} or {@code path} that is absent, blank or starts with
 * {@code TODO}; a {@code base-path} that is absent or starts with {@code TODO} (an empty base path is
 * accepted); a {@code port} outside {@code 1} … {@code 65535}, including the {@code 0} an absent port
 * binds; and a {@code response-timeout} that is not a positive number of milliseconds. The checks make
 * no network request.
 *
 * @param httpRequestConfiguration1 credit-agency request settings, bound from
 *                                  {@code request.http-request-configuration-1}
 * @param httpRequestConfiguration2 bank request settings, bound from
 *                                  {@code request.http-request-configuration-2}
 */
@ConfigurationProperties("request")
public record RequestProperties(
        HttpRequestConfiguration1 httpRequestConfiguration1,
        HttpRequestConfiguration2 httpRequestConfiguration2) {

    /** Property path of the credit-agency request settings. */
    private static final String CONFIGURATION_1 = "request.http-request-configuration-1";

    /** Property path of the bank request settings. */
    private static final String CONFIGURATION_2 = "request.http-request-configuration-2";

    /** Text that opens an unfilled placeholder value. */
    private static final String PLACEHOLDER = "TODO";

    /** Lowest port a request may address. */
    private static final int MIN_PORT = 1;

    /** Highest port a request may address. */
    private static final int MAX_PORT = 65535;

    /**
     * Requires both request groups.
     *
     * @param httpRequestConfiguration1 credit-agency request settings, or {@code null} when
     *                                  {@code request.http-request-configuration-1} is absent
     * @param httpRequestConfiguration2 bank request settings, or {@code null} when
     *                                  {@code request.http-request-configuration-2} is absent
     * @throws IllegalArgumentException with the message
     *         {@code request.http-request-configuration-1 is required} or
     *         {@code request.http-request-configuration-2 is required} for the first absent group
     */
    public RequestProperties {
        requirePresent(CONFIGURATION_1, httpRequestConfiguration1);
        requirePresent(CONFIGURATION_2, httpRequestConfiguration2);
    }

    /**
     * Requires a bound value.
     *
     * @param key   property path of the value
     * @param value bound value, or {@code null} when the key is absent
     * @throws IllegalArgumentException with the message {@code <key> is required} when {@code value} is
     *         {@code null}
     */
    private static void requirePresent(String key, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(key + " is required");
        }
    }

    /**
     * Requires a text value that is present, not blank and not the unfilled placeholder.
     *
     * @param key   property path of the value
     * @param value bound value, or {@code null} when the key is absent
     * @throws IllegalArgumentException with the message {@code <key> is required} when {@code value} is
     *         {@code null}, and {@code <key> must not be blank or the TODO placeholder: <value>} when it
     *         is blank or starts with {@code TODO}
     */
    private static void requireText(String key, String value) {
        requirePresent(key, value);
        if (value.isBlank() || isPlaceholder(value)) {
            throw new IllegalArgumentException(
                    key + " must not be blank or the TODO placeholder: " + value);
        }
    }

    /**
     * Requires a base path that is present and not the unfilled placeholder; an empty base path passes.
     *
     * @param key   property path of the value
     * @param value bound value, or {@code null} when the key is absent
     * @throws IllegalArgumentException with the message {@code <key> is required} when {@code value} is
     *         {@code null}, and {@code <key> must not be the TODO placeholder: <value>} when it starts
     *         with {@code TODO}
     */
    private static void requireBasePath(String key, String value) {
        requirePresent(key, value);
        if (isPlaceholder(value)) {
            throw new IllegalArgumentException(key + " must not be the TODO placeholder: " + value);
        }
    }

    /**
     * Requires a port between {@code 1} and {@code 65535}.
     *
     * @param key  property path of the value
     * @param port bound port, {@code 0} when the key is absent
     * @throws IllegalArgumentException with the message {@code <key> must be between 1 and 65535: <port>}
     *         when {@code port} is outside that range
     */
    private static void requirePort(String key, int port) {
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new IllegalArgumentException(
                    key + " must be between " + MIN_PORT + " and " + MAX_PORT + ": " + port);
        }
    }

    /**
     * Requires a positive response timeout in milliseconds.
     *
     * @param key             property path of the value
     * @param responseTimeout bound timeout in milliseconds, {@code 0} when the key is absent
     * @throws IllegalArgumentException with the message
     *         {@code <key> must be a positive number of milliseconds: <responseTimeout>} when
     *         {@code responseTimeout} is {@code 0} or negative
     */
    private static void requireTimeout(String key, int responseTimeout) {
        if (responseTimeout <= 0) {
            throw new IllegalArgumentException(
                    key + " must be a positive number of milliseconds: " + responseTimeout);
        }
    }

    /**
     * Tells whether a value is the unfilled placeholder.
     *
     * @param value value to test
     * @return {@code true} when {@code value}, stripped of surrounding white space, starts with
     *         {@code TODO}
     */
    private static boolean isPlaceholder(String value) {
        return value.strip().startsWith(PLACEHOLDER);
    }

    /**
     * Target of the credit-agency request, sent as an HTTP {@code POST} to
     * {@code http://<host>:<port><basePath><path>}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:5,73].
     *
     * @param host            host of the credit agency; {@code 0.0.0.0} in {@code application.yml}
     * @param port            port of the credit agency; {@code 18080} in {@code application.yml}
     * @param basePath        base path of the credit agency, bound from {@code base-path};
     *                        {@code /mule/TheCreditAgencyService} in {@code application.yml}
     * @param path            request path appended to {@code basePath}; {@code /*} in
     *                        {@code application.yml}
     * @param responseTimeout response timeout in milliseconds, bound from
     *                        {@code response-timeout}; {@code 10000} in {@code application.yml}
     */
    public record HttpRequestConfiguration1(
            String host,
            int port,
            String basePath,
            String path,
            int responseTimeout) {

        /**
         * Checks {@code host}, {@code port}, {@code base-path}, {@code path} and
         * {@code response-timeout} in that order and throws for the first unusable one.
         *
         * @param host            host of the credit agency
         * @param port            port of the credit agency, {@code 0} when the key is absent
         * @param basePath        base path of the credit agency
         * @param path            request path appended to {@code basePath}
         * @param responseTimeout response timeout in milliseconds, {@code 0} when the key is absent
         * @throws IllegalArgumentException whose message starts with
         *         {@code request.http-request-configuration-1.<key>} when {@code host} or {@code path} is
         *         absent, blank or starts with {@code TODO}, {@code basePath} is absent or starts with
         *         {@code TODO}, {@code port} is outside {@code 1} … {@code 65535}, or
         *         {@code responseTimeout} is not positive
         */
        public HttpRequestConfiguration1 {
            requireText(CONFIGURATION_1 + ".host", host);
            requirePort(CONFIGURATION_1 + ".port", port);
            requireBasePath(CONFIGURATION_1 + ".base-path", basePath);
            requireText(CONFIGURATION_1 + ".path", path);
            requireTimeout(CONFIGURATION_1 + ".response-timeout", responseTimeout);
        }
    }

    /**
     * Settings of the bank request, sent as an HTTP {@code POST} to the host, port and path of the
     * bank URI passed with each call, followed by {@code path}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:6,108]. Host, port
     * and base path are read from that URI and have no keys here.
     *
     * @param path            request path appended to the bank URI's path; {@code /*} in
     *                        {@code application.yml}
     * @param responseTimeout response timeout in milliseconds, bound from
     *                        {@code response-timeout}; {@code 10000} in {@code application.yml}
     */
    public record HttpRequestConfiguration2(
            String path,
            int responseTimeout) {

        /**
         * Checks {@code path} and {@code response-timeout} in that order and throws for the first
         * unusable one.
         *
         * @param path            request path appended to the bank URI's path
         * @param responseTimeout response timeout in milliseconds, {@code 0} when the key is absent
         * @throws IllegalArgumentException whose message starts with
         *         {@code request.http-request-configuration-2.<key>} when {@code path} is absent, blank
         *         or starts with {@code TODO}, or {@code responseTimeout} is not positive
         */
        public HttpRequestConfiguration2 {
            requireText(CONFIGURATION_2 + ".path", path);
            requireTimeout(CONFIGURATION_2 + ".response-timeout", responseTimeout);
        }
    }
}
