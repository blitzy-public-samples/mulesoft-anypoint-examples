package com.mulesoft.examples.proxying_a_rest_api.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import javax.net.ssl.SSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundleKey;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.util.ResourceUtils;

/**
 * Builds the client {@link SSLContext} of the {@code tls:context} of {@code HTTP_Request_Configuration}
 * [proxying-a-rest-api/src/main/app/proxying-a-rest-api.xml:6-8]: one key store, no trust store, and therefore the
 * JVM default trust managers (D-544).
 *
 * <p>The key store settings are the {@code request.http-request-configuration.tls.key-store} keys of
 * {@code application.yml}, bound to {@link HttpRequestConfigurationProperties.KeyStore}:
 * <ul>
 *   <li>{@code path}: location of the key store, {@code keystore.jks} in {@code application.yml};</li>
 *   <li>{@code type}: key store type, {@code JKS} in {@code application.yml}; {@link KeyStore#getDefaultType()}
 *       when unset or blank;</li>
 *   <li>{@code password}: store password, bound from {@code ${keystore.key}};</li>
 *   <li>{@code key-password}: password of the private-key entries, bound from {@code ${keystore.password}}; the
 *       store password applies when it is unset.</li>
 * </ul>
 * Both passwords are TODO placeholders in the committed {@code application.yml} (D-012). No exception message of
 * this class contains either password, and the class writes no log entry.
 *
 * <p>The bean exists when {@code request.http-request-configuration.protocol} is {@code HTTPS} in any letter case,
 * and when the key is unset. With any other value, {@code HTTP} in the test profile among them, the context holds
 * no {@link SSLContext} bean and no key store is read. The context serves the outbound client only: the class
 * registers no {@code SslBundles} entry and sets no {@code server.ssl} key.
 *
 * <p>Usage by the upstream client configuration:
 * <pre>{@code
 * HttpClient.Builder builder = HttpClient.newBuilder();
 * SSLContext context = sslContextProvider.getIfAvailable();
 * if (context != null) {
 *     builder.sslContext(context);
 * }
 * }</pre>
 *
 * <p>The class holds no state; the bean is created once, at context startup.
 */
@Configuration(proxyBeanMethods = false)
public class TlsConfig {

    /** Message of every key-store failure, followed by the configured {@code path}. */
    private static final String FAILURE_MESSAGE = "Unable to load key store ";

    /**
     * Creates the {@code upstreamSslContext} bean: an {@link SSLContext} of protocol {@code TLS} with the key
     * managers of the configured key store and the JVM default trust managers.
     *
     * <p>The key store is located from {@code path}, in this order:
     * <ol>
     *   <li>a path starting with {@code classpath:} or {@code file:} is resolved by {@code resourceLoader};</li>
     *   <li>otherwise, a path naming an existing regular file, absolute or relative to the working directory, is
     *       read from the file system;</li>
     *   <li>otherwise, the path is read as a classpath resource: {@code keystore.jks} finds
     *       {@code src/main/resources/keystore.jks}, where the original README places the file
     *       [proxying-a-rest-api/README.md:34-38].</li>
     * </ol>
     * The store is loaded with {@code type} and {@code password} ({@code null} when unset), and the context is built
     * by {@link SslBundle#createSslContext()} on
     * {@code SslBundle.of(SslStoreBundle.of(store, password, null), SslBundleKey.of(keyPassword))}.
     *
     * <p>Every failure stops context startup with {@link IllegalStateException}
     * {@code Unable to load key store <path>}, where {@code <path>} is the configured value, or {@code null} when it
     * is absent:
     * <ul>
     *   <li>no {@code tls} block, no {@code key-store} block, or a {@code null} or blank {@code path}: no cause;</li>
     *   <li>no resource at the located path: no cause;</li>
     *   <li>a {@code path} that is not a valid file-system path, an unreadable resource, an unknown {@code type}, a
     *       wrong {@code password} or {@code key-password}, or content the JDK cannot parse as a key store of that
     *       type: the exception raised as the cause.</li>
     * </ul>
     *
     * @param properties     the bound {@code request.http-request-configuration} settings
     * @param resourceLoader the context's resource loader, which resolves {@code classpath:} and {@code file:}
     *                       locations
     * @return the client SSL context
     * @throws IllegalStateException when the key store cannot be located or loaded, or the context cannot be built
     */
    // Declared public: a public member under javap -p, with a TRACEABILITY.md backward row (D-075).
    @Bean
    @ConditionalOnProperty(prefix = "request.http-request-configuration", name = "protocol",
            havingValue = "HTTPS", matchIfMissing = true)
    public SSLContext upstreamSslContext(HttpRequestConfigurationProperties properties,
            ResourceLoader resourceLoader) {
        HttpRequestConfigurationProperties.KeyStore settings = keyStoreSettings(properties);
        String path = (settings != null) ? settings.path() : null;
        if (settings == null || path == null || path.isBlank()) {
            throw failure(path, null);
        }
        Resource resource = locate(path, resourceLoader);
        try {
            KeyStore keyStore = load(resource, settings.type(), settings.password());
            SslBundle bundle = SslBundle.of(SslStoreBundle.of(keyStore, settings.password(), null),
                    SslBundleKey.of(settings.keyPassword()));
            return bundle.createSslContext();
        } catch (IOException | GeneralSecurityException | RuntimeException ex) {
            throw failure(path, ex);
        }
    }

    /**
     * Reads the key-store block of the bound settings.
     *
     * @param properties the bound settings, or {@code null}
     * @return the {@code tls.key-store} settings, or {@code null} when the settings, the {@code tls} block or the
     *         {@code key-store} block is absent
     */
    private static HttpRequestConfigurationProperties.KeyStore keyStoreSettings(
            HttpRequestConfigurationProperties properties) {
        if (properties == null || properties.tls() == null) {
            return null;
        }
        return properties.tls().keyStore();
    }

    /**
     * Locates the key store resource for {@code path} in the order documented on {@link #upstreamSslContext}.
     *
     * @param path           the configured path, neither {@code null} nor blank
     * @param resourceLoader the resource loader for {@code classpath:} and {@code file:} locations
     * @return an existing resource
     * @throws IllegalStateException {@code Unable to load key store <path>}, with no cause when no resource exists
     *                               at the located path, and with the exception raised when the path cannot be
     *                               resolved
     */
    private static Resource locate(String path, ResourceLoader resourceLoader) {
        Resource resource;
        try {
            if (path.startsWith(ResourceUtils.CLASSPATH_URL_PREFIX) || path.startsWith(ResourceUtils.FILE_URL_PREFIX)) {
                resource = resourceLoader.getResource(path);
            } else {
                Path file = Path.of(path);
                resource = Files.isRegularFile(file)
                        ? new FileSystemResource(file)
                        : resourceLoader.getResource(ResourceUtils.CLASSPATH_URL_PREFIX + path);
            }
        } catch (RuntimeException ex) {
            throw failure(path, ex);
        }
        if (!resource.exists()) {
            // No cause: the failure message is the root cause of the startup failure.
            throw failure(path, null);
        }
        return resource;
    }

    /**
     * Loads a key store from {@code resource}; the stream is closed whether loading succeeds or fails.
     *
     * @param resource an existing key store resource
     * @param type     the key store type; {@link KeyStore#getDefaultType()} when {@code null} or blank
     * @param password the store password, or {@code null} to load without an integrity check
     * @return the loaded key store
     * @throws IOException              when the resource cannot be read, the password is wrong or the content is
     *                                  not a key store of that type
     * @throws GeneralSecurityException when the type is unknown, the integrity algorithm is unavailable or a
     *                                  certificate cannot be loaded
     */
    private static KeyStore load(Resource resource, String type, String password)
            throws IOException, GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(
                (type == null || type.isBlank()) ? KeyStore.getDefaultType() : type);
        try (InputStream in = resource.getInputStream()) {
            keyStore.load(in, (password != null) ? password.toCharArray() : null);
        }
        return keyStore;
    }

    /**
     * Creates the key-store failure for {@code path}.
     *
     * @param path  the configured path, or {@code null}
     * @param cause the exception raised, or {@code null}
     * @return {@link IllegalStateException} {@code Unable to load key store <path>} with {@code cause}
     */
    private static IllegalStateException failure(String path, Throwable cause) {
        return new IllegalStateException(FAILURE_MESSAGE + path, cause);
    }
}
