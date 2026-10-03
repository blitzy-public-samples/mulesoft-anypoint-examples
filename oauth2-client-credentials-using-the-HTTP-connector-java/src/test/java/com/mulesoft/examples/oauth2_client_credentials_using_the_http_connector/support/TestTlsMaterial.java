package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Per-run TLS material for the SSL bundle {@code spring.ssl.bundle.jks.tls-context}, the Java form of
 * {@code tls:context} {@code TLS_Context}
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:25-28].
 *
 * <p>Each {@link #create()} call generates new material, with RSA 2048-bit keys throughout:
 * <ul>
 *   <li>a certificate authority, common name {@code tls-context-test-ca};</li>
 *   <li>a server certificate signed by that authority, common name {@code localhost}, subject alternative
 *       names {@code localhost} and {@code 127.0.0.1};</li>
 *   <li>a client certificate signed by that authority, common name {@code replserver};</li>
 *   <li>three distinct random passwords of 32 hexadecimal characters each: the key-store password, the
 *       key-entry password and the trust-store password;</li>
 *   <li>a new directory {@code tls-context-test-<random>} under {@code java.io.tmpdir} holding two JKS files:
 *     <ul>
 *       <li>{@code keystore.jks}, protected by the key-store password, with the private key entry
 *           {@code replserver} (chain: client certificate, certificate authority) protected by the
 *           key-entry password;</li>
 *       <li>{@code trust-store}, protected by the trust-store password, with the trusted certificate entry
 *           {@code client} holding the certificate authority.</li>
 *     </ul>
 *   </li>
 * </ul>
 * The directory and both files are registered for deletion on normal JVM termination. Nothing is written to
 * the repository tree, and the committed {@code src/main/resources/keystore.jks} and
 * {@code src/main/resources/trust-store} are never read. Store passwords are generated per run (D-012, D-084).
 *
 * <p>{@link #serverCertificates()} configures the HTTPS upstream that stands in for {@code app.box.com:443}
 * [http-client-credentials.xml:12-13], and {@link #register(DynamicPropertyRegistry)} binds the two files
 * and the three passwords to the {@code tls-context} bundle that {@code config.TlsConfig} loads at context
 * startup. Typical use in a {@code @SpringBootTest} class:
 * <pre>{@code
 * private static final TestTlsMaterial TLS = TestTlsMaterial.create();
 * private static final MockWebServer BOX = new MockWebServer();
 *
 * static {
 *     BOX.useHttps(TLS.serverCertificates().sslSocketFactory(), false);
 *     BOX.requireClientAuth();
 *     try {
 *         BOX.start();
 *     } catch (IOException e) {
 *         throw new UncheckedIOException(e);
 *     }
 * }
 *
 * // in the class's DynamicPropertySource method:
 * TLS.register(registry);
 * }</pre>
 *
 * <p>Every failure while generating the material raises an unchecked exception whose message names the store
 * concerned: {@link UncheckedIOException} for a file that cannot be created or written,
 * {@link IllegalStateException} for a key store the JDK cannot build. {@link #create()} declares no checked
 * exception and can be called from a static field initializer. Instances are immutable and safe to share
 * between threads.
 */
public final class TestTlsMaterial {

    /** Alias of the private key entry in {@code keystore.jks}, and common name of the client certificate. */
    private static final String KEY_ALIAS = "replserver";

    /** Alias of the trusted certificate entry in {@code trust-store}. */
    private static final String TRUST_ALIAS = "client";

    /** File name of the generated key store, the original key store file name. */
    private static final String KEY_STORE_FILE = "keystore.jks";

    /** File name of the generated trust store, the original trust store file name. */
    private static final String TRUST_STORE_FILE = "trust-store";

    /** Name prefix of the generated directory under {@code java.io.tmpdir}. */
    private static final String DIRECTORY_PREFIX = "tls-context-test-";

    /** Key store type of both generated files, the type the {@code tls-context} bundle declares. */
    private static final String STORE_TYPE = "JKS";

    /** Common name of the generated certificate authority. */
    private static final String CA_COMMON_NAME = "tls-context-test-ca";

    /** Common name and DNS subject alternative name of the server certificate. */
    private static final String SERVER_HOST = "localhost";

    /** IP subject alternative name of the server certificate. */
    private static final String SERVER_ADDRESS = "127.0.0.1";

    /** Number of random bytes behind each password; each byte renders as two hexadecimal characters. */
    private static final int PASSWORD_BYTES = 16;

    /** Scheme prefix of the two store locations. */
    private static final String FILE_SCHEME = "file:";

    /** Key-store password key, the original {@code ${keystore.password}} [http-client-credentials.xml:27]. */
    private static final String KEYSTORE_PASSWORD_PROPERTY = "keystore.password";

    /** Key-entry password key, the original {@code ${keystore.key.password}} [http-client-credentials.xml:27]. */
    private static final String KEYSTORE_KEY_PASSWORD_PROPERTY = "keystore.key.password";

    /** Trust-store password key, the original {@code ${truststore.password}} [http-client-credentials.xml:26]. */
    private static final String TRUSTSTORE_PASSWORD_PROPERTY = "truststore.password";

    /** Key-store path key, the original {@code ${keystore.path}} [http-client-credentials.xml:27]. */
    private static final String KEYSTORE_PATH_PROPERTY = "keystore.path";

    /** Trust-store path key, the original {@code ${truststore.path}} [http-client-credentials.xml:26]. */
    private static final String TRUSTSTORE_PATH_PROPERTY = "truststore.path";

    /** Key-store location of the {@code tls-context} SSL bundle. */
    private static final String BUNDLE_KEYSTORE_LOCATION_PROPERTY =
            "spring.ssl.bundle.jks.tls-context.keystore.location";

    /** Trust-store location of the {@code tls-context} SSL bundle. */
    private static final String BUNDLE_TRUSTSTORE_LOCATION_PROPERTY =
            "spring.ssl.bundle.jks.tls-context.truststore.location";

    /** Server-side handshake certificates of the HTTPS upstream, built once per instance. */
    private final HandshakeCertificates serverCertificates;

    /** {@code file:} location of the generated {@code keystore.jks}, with an absolute path. */
    private final String keyStoreLocation;

    /** {@code file:} location of the generated {@code trust-store}, with an absolute path. */
    private final String trustStoreLocation;

    /** Password of the generated {@code keystore.jks}. */
    private final String storePassword;

    /** Password of the {@code replserver} key entry in the generated {@code keystore.jks}. */
    private final String keyPassword;

    /** Password of the generated {@code trust-store}. */
    private final String trustPassword;

    /**
     * Generates the certificates, the passwords and the two JKS files described on the class.
     *
     * @throws UncheckedIOException  when the directory cannot be created or a store file cannot be written
     * @throws IllegalStateException when the JDK cannot build or serialise a key store
     */
    private TestTlsMaterial() {
        HeldCertificate ca = new HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName(CA_COMMON_NAME)
                .rsa2048()
                .build();
        HeldCertificate server = new HeldCertificate.Builder()
                .commonName(SERVER_HOST)
                .addSubjectAlternativeName(SERVER_HOST)
                .addSubjectAlternativeName(SERVER_ADDRESS)
                .signedBy(ca)
                .rsa2048()
                .build();
        HeldCertificate client = new HeldCertificate.Builder()
                .commonName(KEY_ALIAS)
                .signedBy(ca)
                .rsa2048()
                .build();

        SecureRandom random = new SecureRandom();
        this.storePassword = randomPassword(random);
        this.keyPassword = randomPassword(random, storePassword);
        this.trustPassword = randomPassword(random, storePassword, keyPassword);

        Path directory = createDirectory();
        directory.toFile().deleteOnExit();
        Path keyStorePath = directory.resolve(KEY_STORE_FILE);
        Path trustStorePath = directory.resolve(TRUST_STORE_FILE);
        keyStorePath.toFile().deleteOnExit();
        trustStorePath.toFile().deleteOnExit();

        writeKeyStore(keyStorePath, client, ca, storePassword, keyPassword);
        writeTrustStore(trustStorePath, ca, trustPassword);
        this.keyStoreLocation = FILE_SCHEME + keyStorePath.toAbsolutePath();
        this.trustStoreLocation = FILE_SCHEME + trustStorePath.toAbsolutePath();

        this.serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(server, ca.certificate())
                .addTrustedCertificate(ca.certificate())
                .build();
    }

    /**
     * Generates a new, independent set of certificates, passwords and JKS files, as described on the class.
     *
     * @return the generated material
     * @throws UncheckedIOException  when the directory cannot be created or a store file cannot be written; the
     *                               message names the store
     * @throws IllegalStateException when the JDK cannot build or serialise a key store; the message names the
     *                               store
     */
    public static TestTlsMaterial create() {
        return new TestTlsMaterial();
    }

    /**
     * Returns the server-side handshake certificates of the HTTPS upstream: the server certificate
     * ({@code localhost}, {@code 127.0.0.1}) with its chain to the certificate authority as the held
     * certificate, and the certificate authority as the only trusted certificate.
     *
     * <p>Callers pass them to an okhttp {@code MockWebServer} before {@code start()}:
     * {@code mockWebServer.useHttps(serverCertificates().sslSocketFactory(), false)} together with
     * {@code mockWebServer.requireClientAuth()}. The server then completes a handshake only with a client that
     * trusts the certificate authority and presents a certificate it signed, such as the {@code replserver}
     * entry of {@code keystore.jks}, and {@code RecordedRequest.getHandshake().peerCertificates()} holds that
     * client chain.
     *
     * @return the handshake certificates built for this instance; the same object on every call
     */
    public HandshakeCertificates serverCertificates() {
        return serverCertificates;
    }

    /**
     * Adds the generated store locations and passwords to {@code registry}, for a
     * {@code @DynamicPropertySource} method. Exactly these seven properties are added:
     * <table>
     *   <caption>Registered properties</caption>
     *   <tr><th>Property</th><th>Value</th></tr>
     *   <tr><td>{@code keystore.password}</td><td>the key-store password</td></tr>
     *   <tr><td>{@code keystore.key.password}</td><td>the key-entry password of {@code replserver}</td></tr>
     *   <tr><td>{@code truststore.password}</td><td>the trust-store password</td></tr>
     *   <tr><td>{@code spring.ssl.bundle.jks.tls-context.keystore.location}</td>
     *       <td>{@code file:} plus the absolute path of the generated {@code keystore.jks}</td></tr>
     *   <tr><td>{@code spring.ssl.bundle.jks.tls-context.truststore.location}</td>
     *       <td>{@code file:} plus the absolute path of the generated {@code trust-store}</td></tr>
     *   <tr><td>{@code keystore.path}</td><td>the same key-store location</td></tr>
     *   <tr><td>{@code truststore.path}</td><td>the same trust-store location</td></tr>
     * </table>
     * The bundle's {@code keystore.password}, {@code key.password} and {@code truststore.password} resolve to
     * the first three properties through their {@code ${...}} references in {@code application.yml}; the two
     * bundle locations are set directly and replace the {@code classpath:} locations of {@code application.yml}.
     *
     * @param registry the registry of the test class's {@code @DynamicPropertySource} method
     * @throws NullPointerException when {@code registry} is {@code null}
     */
    public void register(DynamicPropertyRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        registry.add(KEYSTORE_PASSWORD_PROPERTY, () -> storePassword);
        registry.add(KEYSTORE_KEY_PASSWORD_PROPERTY, () -> keyPassword);
        registry.add(TRUSTSTORE_PASSWORD_PROPERTY, () -> trustPassword);
        registry.add(BUNDLE_KEYSTORE_LOCATION_PROPERTY, () -> keyStoreLocation);
        registry.add(BUNDLE_TRUSTSTORE_LOCATION_PROPERTY, () -> trustStoreLocation);
        registry.add(KEYSTORE_PATH_PROPERTY, () -> keyStoreLocation);
        registry.add(TRUSTSTORE_PATH_PROPERTY, () -> trustStoreLocation);
    }

    /**
     * Returns {@value #PASSWORD_BYTES} random bytes from {@code random} as lower-case hexadecimal, drawing again
     * while the value equals one of {@code taken}.
     *
     * @param random the source of the random bytes
     * @param taken  passwords the result must differ from
     * @return a new password of {@code 2 * PASSWORD_BYTES} hexadecimal characters
     */
    private static String randomPassword(SecureRandom random, String... taken) {
        byte[] bytes = new byte[PASSWORD_BYTES];
        String password;
        do {
            random.nextBytes(bytes);
            password = HexFormat.of().formatHex(bytes);
        } while (Arrays.asList(taken).contains(password));
        return password;
    }

    /**
     * Creates the directory {@code tls-context-test-<random>} under {@code java.io.tmpdir}.
     *
     * @return the new directory
     * @throws UncheckedIOException when the directory cannot be created
     */
    private static Path createDirectory() {
        try {
            return Files.createTempDirectory(DIRECTORY_PREFIX);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create the directory for key store " + KEY_STORE_FILE
                    + " and trust store " + TRUST_STORE_FILE, e);
        }
    }

    /**
     * Writes {@code keystore.jks}: a JKS store protected by {@code storePassword} with the private key entry
     * {@value #KEY_ALIAS}, protected by {@code keyPassword}, whose chain is the client certificate followed by
     * the certificate authority.
     *
     * @param path          the file to create; it must not exist
     * @param client        the client certificate and its key pair
     * @param ca            the certificate authority that signed {@code client}
     * @param storePassword the store password
     * @param keyPassword   the key-entry password
     * @throws UncheckedIOException  when the file cannot be created or written
     * @throws IllegalStateException when the JDK cannot build or serialise the store
     */
    private static void writeKeyStore(Path path, HeldCertificate client, HeldCertificate ca, String storePassword,
            String keyPassword) {
        try {
            KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
            keyStore.load(null, null);
            keyStore.setKeyEntry(KEY_ALIAS, client.keyPair().getPrivate(), keyPassword.toCharArray(),
                    new Certificate[] {client.certificate(), ca.certificate()});
            store(keyStore, path, storePassword);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write key store " + KEY_STORE_FILE + " at " + path, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build key store " + KEY_STORE_FILE + " at " + path, e);
        }
    }

    /**
     * Writes {@code trust-store}: a JKS store protected by {@code trustPassword} with the trusted certificate
     * entry {@value #TRUST_ALIAS} holding the certificate authority.
     *
     * @param path          the file to create; it must not exist
     * @param ca            the certificate authority
     * @param trustPassword the store password
     * @throws UncheckedIOException  when the file cannot be created or written
     * @throws IllegalStateException when the JDK cannot build or serialise the store
     */
    private static void writeTrustStore(Path path, HeldCertificate ca, String trustPassword) {
        try {
            KeyStore trustStore = KeyStore.getInstance(STORE_TYPE);
            trustStore.load(null, null);
            trustStore.setCertificateEntry(TRUST_ALIAS, ca.certificate());
            store(trustStore, path, trustPassword);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to write trust store " + TRUST_STORE_FILE + " at " + path, e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to build trust store " + TRUST_STORE_FILE + " at " + path, e);
        }
    }

    /**
     * Serialises {@code keyStore} to a new file at {@code path}, protected by {@code password}.
     *
     * @param keyStore the store to write
     * @param path     the file to create; it must not exist
     * @param password the store password
     * @throws IOException              when the file cannot be created or written
     * @throws GeneralSecurityException when the store cannot be serialised
     */
    private static void store(KeyStore keyStore, Path path, String password)
            throws IOException, GeneralSecurityException {
        try (OutputStream out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            keyStore.store(out, password.toCharArray());
        }
    }
}
