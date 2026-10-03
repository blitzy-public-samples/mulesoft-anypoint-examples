package com.mulesoft.examples.proxying_a_rest_api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the upstream request configuration {@code HTTP_Request_Configuration}
 * [proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:5-9], bound from the
 * {@code request.http-request-configuration} keys of {@code application.yml} (D-544).
 *
 * <p>Each component binds the key of the same name in kebab case under
 * {@code request.http-request-configuration}: {@code host}, {@code port}, {@code protocol},
 * {@code connect-timeout}, {@code response-timeout}, {@code follow-redirects} and the {@code tls}
 * block. {@code application.yml} holds the original literals {@code api.box.com}, {@code 443} and
 * {@code HTTPS}, and the values of the three keys the original element does not set. The
 * application's configuration-properties scan registers this record as a bean (D-314).
 *
 * <p>The record checks no value and keeps each one exactly as bound. A missing key binds
 * {@code null}, {@code 0} or {@code false}; a missing {@code tls} block binds a {@code null}
 * {@link #tls()}. Credentials: see DECISIONS.md D-012.
 *
 * <pre>{@code
 * request:
 *   http-request-configuration:
 *     host: api.box.com
 *     port: 443
 *     protocol: HTTPS
 *     connect-timeout: 30000
 *     response-timeout: 10000
 *     follow-redirects: true
 *     tls:
 *       key-store:
 *         path: keystore.jks
 *         type: JKS
 *         password: ${keystore.key}
 *         key-password: ${keystore.password}
 * }</pre>
 *
 * @param host            upstream host; {@code api.box.com} in {@code application.yml}
 * @param port            upstream port; {@code 443} in {@code application.yml}
 * @param protocol        upstream protocol, {@code HTTP} or {@code HTTPS} in any letter case;
 *                        {@code HTTPS} in {@code application.yml}
 * @param connectTimeout  connect timeout in milliseconds; {@code 0} or a negative value means
 *                        not set
 * @param responseTimeout response timeout in milliseconds, the longest wait for the upstream
 *                        response; {@code 0} or a negative value means not set
 * @param followRedirects {@code true} when upstream redirects are followed, {@code false} when
 *                        a redirect response is returned as received
 * @param tls             key store of the {@code tls:context}, bound from
 *                        {@code request.http-request-configuration.tls}, or {@code null} when that
 *                        block is absent
 */
@ConfigurationProperties(prefix = "request.http-request-configuration")
public record HttpRequestConfigurationProperties(
        String host,
        int port,
        String protocol,
        int connectTimeout,
        int responseTimeout,
        boolean followRedirects,
        Tls tls) {

    /** Protocol value for which {@link #https()} answers {@code true}, compared ignoring case. */
    private static final String HTTPS = "HTTPS";

    /**
     * Tells whether the upstream protocol is HTTPS.
     *
     * @return {@code true} when {@code protocol} equals {@code HTTPS} ignoring case; {@code false}
     *         for any other value, {@code null} included
     */
    public boolean https() {
        return HTTPS.equalsIgnoreCase(protocol);
    }

    /**
     * The {@code tls:context} of {@code HTTP_Request_Configuration}
     * [proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:6-8]: one key store and no trust
     * store.
     *
     * @param keyStore the {@code tls:key-store}, bound from {@code tls.key-store}, or {@code null}
     *                 when that block is absent
     */
    public record Tls(KeyStore keyStore) {
    }

    /**
     * The {@code tls:key-store} of {@code HTTP_Request_Configuration}
     * [proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:7], bound from
     * {@code request.http-request-configuration.tls.key-store}.
     *
     * <p>{@code password} is the store password and binds {@code ${keystore.key}};
     * {@code keyPassword} is the key password and binds {@code ${keystore.password}}, the pairing
     * of the original attributes {@code password="${keystore.key}"} and
     * {@code keyPassword="${keystore.password}"}. Credentials: see DECISIONS.md D-012.
     *
     * <p>{@link #toString()} writes {@code ****} in place of both passwords whatever their values,
     * {@code null} included, for example
     * {@code KeyStore[path=keystore.jks, type=JKS, password=****, keyPassword=****]}. The default
     * {@code toString()} of {@link Tls} and of {@link HttpRequestConfigurationProperties} prints
     * this text for the key store.
     *
     * <p>This record shares its simple name with {@link java.security.KeyStore}; code that uses
     * both names one of them by its fully qualified name.
     *
     * @param path        location of the key store; {@code keystore.jks} in {@code application.yml}
     * @param type        key store type; {@code JKS} in {@code application.yml}
     * @param password    store password
     * @param keyPassword password of the private-key entry
     */
    public record KeyStore(String path, String type, String password, String keyPassword) {

        /** Text written in place of each password by {@link #toString()}. */
        private static final String MASK = "****";

        /**
         * Renders the path and type and masks both passwords.
         *
         * @return {@code KeyStore[path=<path>, type=<type>, password=****, keyPassword=****]}
         */
        @Override
        public String toString() {
            return "KeyStore[path=" + path
                    + ", type=" + type
                    + ", password=" + MASK
                    + ", keyPassword=" + MASK + "]";
        }
    }
}
