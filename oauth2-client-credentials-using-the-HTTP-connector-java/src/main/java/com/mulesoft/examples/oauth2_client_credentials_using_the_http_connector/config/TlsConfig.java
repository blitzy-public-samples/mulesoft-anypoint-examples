package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config;

import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslManagerBundle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Netty client TLS context built from the {@code tls-context} SSL bundle; used by the Box API client.
 * Source: {@code tls:context} {@code TLS_Context}
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:25-28].
 *
 * <p>{@code TLS_Context} is the JKS bundle {@code spring.ssl.bundle.jks.tls-context} of {@code application.yml}
 * (D-316):
 * <ul>
 *   <li>{@code tls:key-store} (:27) is {@code keystore.location} {@code classpath:${keystore.path}} with
 *       {@code keystore.password} {@code ${keystore.password}}, and {@code key.password}
 *       {@code ${keystore.key.password}} for the key entry;</li>
 *   <li>{@code tls:trust-store} (:26) is {@code truststore.location} {@code classpath:${truststore.path}} with
 *       {@code truststore.password} {@code ${truststore.password}}.</li>
 * </ul>
 * The three passwords hold placeholder values in the committed {@code application.yml}; the {@code local} profile
 * and the test profile supply their values (D-012). This class holds no password, store location or key alias: every
 * value comes from the bundle and from the two {@code @Value} keys of {@link #boxSslContext}.
 *
 * <p>{@code HTTP_Request_Configuration} (:12-13) is the only element that references {@code TLS_Context}. The
 * single consumer of {@link #boxSslContext} is {@code BoxClientCredentialsConfig#boxWebClient}, which injects it
 * by the bean name {@code boxSslContext}. The Box token request and the FIM request configuration
 * {@code HTTP_Request_Configuration1} (:29) use the JVM default trust. The context is registered with no web
 * server, no Spring MVC component and no other client.
 *
 * <pre>{@code
 * HttpClient httpClient = HttpClient.create().secure(spec -> spec.sslContext(boxSslContext));
 * }</pre>
 */
@Configuration
public class TlsConfig {

    /** Name of the SSL bundle under {@code spring.ssl.bundle.jks} that holds the {@code TLS_Context} stores. */
    public static final String BUNDLE_NAME = "tls-context";

    /**
     * Builds the client-mode Netty {@link SslContext} of {@code TLS_Context} from the SSL bundle
     * {@value #BUNDLE_NAME}.
     *
     * <p>The key managers come from the bundle's key store and key-entry password, the trust managers from its
     * trust store. Enabled protocols and cipher suites set under the bundle's {@code options} are applied; when
     * the bundle sets none, Netty's defaults apply. The context is a singleton built once, at context startup.
     *
     * <p>Each failure stops context startup:
     * <ul>
     *   <li>no bundle named {@value #BUNDLE_NAME}: Boot's {@code NoSuchSslBundleException}, unchanged;</li>
     *   <li>a key store that cannot be read or opened, or a key entry that cannot be recovered with
     *       {@code key.password}: {@link IllegalStateException} {@code Unable to load key store at <location>},
     *       with {@code <location>} the value of {@code keyStoreLocation} and Boot's exception as the cause;</li>
     *   <li>a trust store that cannot be read or opened: {@link IllegalStateException}
     *       {@code Unable to load trust store at <location>}, with {@code <location>} the value of
     *       {@code trustStoreLocation} and Boot's exception as the cause;</li>
     *   <li>managers, protocols or cipher suites that Netty rejects: {@link IllegalStateException}
     *       {@code Unable to build TLS context from key store at <location> and trust store at <location>}, with
     *       the {@link SSLException} as the cause.</li>
     * </ul>
     *
     * @param sslBundles         Boot's SSL bundle registry
     * @param keyStoreLocation   {@code spring.ssl.bundle.jks.tls-context.keystore.location}, the key store
     *                           location named in failure messages
     * @param trustStoreLocation {@code spring.ssl.bundle.jks.tls-context.truststore.location}, the trust store
     *                           location named in failure messages
     * @return the client-mode Netty SSL context of the Box API client
     * @throws IllegalStateException when the key store, the trust store or the Netty context cannot be loaded or
     *                               built, as listed above
     */
    @Bean
    public SslContext boxSslContext(SslBundles sslBundles,
            @Value("${spring.ssl.bundle.jks.tls-context.keystore.location}") String keyStoreLocation,
            @Value("${spring.ssl.bundle.jks.tls-context.truststore.location}") String trustStoreLocation) {
        SslBundle bundle = sslBundles.getBundle(BUNDLE_NAME);
        SslManagerBundle managers = bundle.getManagers();

        KeyManagerFactory keyManagerFactory;
        try {
            keyManagerFactory = managers.getKeyManagerFactory();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to load key store at " + keyStoreLocation, e);
        }

        TrustManagerFactory trustManagerFactory;
        try {
            trustManagerFactory = managers.getTrustManagerFactory();
        } catch (RuntimeException e) {
            throw new IllegalStateException("Unable to load trust store at " + trustStoreLocation, e);
        }

        SslContextBuilder builder = SslContextBuilder.forClient()
                .keyManager(keyManagerFactory)
                .trustManager(trustManagerFactory);
        String[] enabledProtocols = bundle.getOptions().getEnabledProtocols();
        if (enabledProtocols != null) {
            builder.protocols(enabledProtocols);
        }
        String[] ciphers = bundle.getOptions().getCiphers();
        if (ciphers != null) {
            builder.ciphers(Arrays.asList(ciphers));
        }

        try {
            return builder.build();
        } catch (SSLException e) {
            throw new IllegalStateException("Unable to build TLS context from key store at " + keyStoreLocation
                    + " and trust store at " + trustStoreLocation, e);
        }
    }
}
