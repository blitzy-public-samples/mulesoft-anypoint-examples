package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import java.time.Duration;
import java.util.Objects;

import javax.net.ssl.SSLContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

/**
 * TLS wiring of the example. Replaces the {@code tls:context} {@code TLS_Context}
 * [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml:21-24], which
 * the request configuration {@code HTTP_Request_Configuration_HTTPS} (:5) and its
 * {@code oauth2:authorization-code-grant-type} (:6) reference through {@code tlsContext-ref}.
 *
 * <p>{@code TLS_Context} is the JKS SSL bundle {@code spring.ssl.bundle.jks.tls-context} of
 * {@code application.yml}, named by {@link #BUNDLE_NAME}:
 * <ul>
 *   <li>{@code tls:trust-store} (:22) is {@code truststore.location} {@code classpath:trust-store} with
 *       {@code truststore.password} {@code ${truststore.password}};</li>
 *   <li>{@code tls:key-store} (:23) is {@code keystore.location} {@code classpath:keystore.jks} with
 *       {@code keystore.password} {@code ${keystore.password}}, and {@code key.password}
 *       {@code ${keystore.keyPassword}} for the key entry.</li>
 * </ul>
 * The three passwords are placeholders in the committed {@code application.yml}; the {@code local} profile and the
 * {@code test} profile supply their values (D-012). This class holds no password, store location or key alias:
 * every TLS value comes from the bundle.
 *
 * <p>The class provides two singleton beans, both built from that bundle:
 * <ul>
 *   <li>{@link #listenerSslContext()}: the server {@link SSLContext} of the HTTPS listener that
 *       {@code AdditionalPortsConfig} opens on embedded Undertow for the local authorization URL and the
 *       redirection URL (:6-7; D-010, D-011);</li>
 *   <li>{@link #boxRequestFactory()}: the {@link ClientHttpRequestFactory} of the outbound Box calls, the token
 *       request of the grant type (:13) and the search request of {@code userLoginDoneFlow} (:40), with the
 *       connect and read timeouts of {@code box.request.response-timeout} (D-664).</li>
 * </ul>
 * The class is a full-mode {@code @Configuration}: a call of either method on the injected {@code TlsConfig}
 * returns the bean the context holds.
 *
 * <pre>{@code
 * RestClient boxClient = restClientBuilder.requestFactory(tlsConfig.boxRequestFactory()).build();
 * factory.addBuilderCustomizers(builder ->
 *         builder.addHttpsListener(port, host, tlsConfig.listenerSslContext()));
 * }</pre>
 */
@Configuration
public class TlsConfig {

    /** Name of the SSL bundle under {@code spring.ssl.bundle.jks} that holds the {@code TLS_Context} stores. */
    public static final String BUNDLE_NAME = "tls-context";

    /** Key of the timeout that {@link #boxRequestFactory()} applies, named in its exceptions. */
    private static final String RESPONSE_TIMEOUT_KEY = "box.request.response-timeout";

    private static final Logger LOG = LoggerFactory.getLogger(TlsConfig.class);

    /** Boot's SSL bundle registry. */
    private final SslBundles sslBundles;

    /** The bound {@code box.*} keys; supplies {@code box.request.response-timeout}. */
    private final BoxProperties box;

    /**
     * Creates the configuration.
     *
     * @param sslBundles Boot's SSL bundle registry, which holds the bundle {@value #BUNDLE_NAME}
     * @param box        the bound {@code box.*} keys
     * @throws NullPointerException if {@code sslBundles} or {@code box} is {@code null}
     */
    public TlsConfig(SslBundles sslBundles, BoxProperties box) {
        this.sslBundles = Objects.requireNonNull(sslBundles, "sslBundles");
        this.box = Objects.requireNonNull(box, "box");
    }

    /**
     * Returns the server TLS context of the HTTPS listener: {@link SslBundle#createSslContext()} of the bundle
     * {@value #BUNDLE_NAME}, initialised with the key managers of {@code keystore.jks} (key entry password
     * {@code key.password}) and the trust managers of {@code trust-store}, for the bundle's protocol
     * ({@code TLS} unless the bundle sets one). It is created once, at context startup.
     *
     * <p>A missing bundle raises Boot's {@code NoSuchSslBundleException}; a store that cannot be loaded or a key
     * entry that cannot be recovered raises Boot's exception unchanged. Either failure stops context startup.
     *
     * @return the initialised server {@link SSLContext} of {@code TLS_Context}
     */
    @Bean
    public SSLContext listenerSslContext() {
        return sslBundles.getBundle(BUNDLE_NAME).createSslContext();
    }

    /**
     * Returns the request factory of the outbound Box calls: a {@link SimpleClientHttpRequestFactory} over the
     * JDK {@code HttpURLConnection}, built by
     * {@link ClientHttpRequestFactories#get(Class, ClientHttpRequestFactorySettings)} from
     * {@link ClientHttpRequestFactorySettings#DEFAULTS} with these three settings and no other (D-664):
     * <ul>
     *   <li>SSL bundle {@value #BUNDLE_NAME}: an {@code https} connection uses the socket factory of the bundle's
     *       TLS context. The server certificate is checked against the entries of {@code trust-store} alone, the
     *       JVM's default trust store is not consulted, and {@code keystore.jks} supplies the client key. An
     *       {@code http} connection, such as {@code box.request.protocol: http} in a test, carries no TLS;</li>
     *   <li>connect timeout and read timeout, each {@code box.request.response-timeout} milliseconds (10000 in
     *       the committed {@code application.yml}); {@code 0} sets no limit.</li>
     * </ul>
     * The bundle sets no {@code options} (cipher suites or protocols), which Boot's JDK factory rejects. The
     * factory adds no retry and no interceptor, and keeps {@code HttpURLConnection}'s redirect default. It is
     * created once, at context startup.
     *
     * @return the request factory of the Box token and search calls
     * @throws IllegalStateException naming {@code box.request.response-timeout} when the {@code box.request}
     *                               group is absent or the timeout lies outside 0 to {@link Integer#MAX_VALUE}
     *                               milliseconds; Boot's {@code NoSuchSslBundleException} when the bundle is
     *                               missing. Each failure stops context startup
     */
    @Bean
    public ClientHttpRequestFactory boxRequestFactory() {
        Duration timeout = Duration.ofMillis(responseTimeoutMillis());
        SslBundle bundle = sslBundles.getBundle(BUNDLE_NAME);
        // Connect and read timeouts both take box.request.response-timeout (D-664).
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withSslBundle(bundle)
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);
        // The JDK HttpURLConnection factory, named explicitly, never the classpath-detected one (D-664).
        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(SimpleClientHttpRequestFactory.class,
                settings);
        LOG.debug("Box request factory: SSL bundle {}, connect and read timeout {} ms", BUNDLE_NAME,
                timeout.toMillis());
        return factory;
    }

    /**
     * Returns {@code box.request.response-timeout}.
     *
     * @return the timeout in milliseconds, between 0 and {@link Integer#MAX_VALUE}
     * @throws IllegalStateException naming the key when the {@code box.request} group is absent or the value lies
     *                               outside that range
     */
    private long responseTimeoutMillis() {
        BoxProperties.Request request = box.request();
        if (request == null) {
            throw new IllegalStateException(RESPONSE_TIMEOUT_KEY + " is not set: the box.request group is absent");
        }
        long millis = request.responseTimeout();
        if (millis < 0 || millis > Integer.MAX_VALUE) {
            throw new IllegalStateException(RESPONSE_TIMEOUT_KEY + " must be between 0 and " + Integer.MAX_VALUE
                    + " milliseconds, was " + millis);
        }
        return millis;
    }
}
