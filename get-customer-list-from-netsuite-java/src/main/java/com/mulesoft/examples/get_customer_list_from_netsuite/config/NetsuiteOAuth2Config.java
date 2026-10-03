package com.mulesoft.examples.get_customer_list_from_netsuite.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequestEntityConverter;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import com.mulesoft.examples.get_customer_list_from_netsuite.client.NetsuiteRestClient;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;

import reactor.netty.http.client.HttpClient;

/**
 * NetSuite REST Web Services access through OAuth 2.0 client credentials (M2M) with {@code private_key_jwt}
 * client authentication (D-016). Replaces the login-based global element {@code Netsuite}
 * ({@code netsuite:config account=… email=… password=… roleId=…})
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4]; the keys
 * {@code netsuite.email}, {@code netsuite.password} and {@code netsuite.roleId} have no counterpart (D-015,
 * D-016).
 *
 * <p>Beans, in declaration order:
 * <ol>
 *   <li>{@link #netsuiteClientRegistrationRepository} &mdash; the single registration {@value #REGISTRATION_ID}:
 *       grant {@code client_credentials}, client authentication {@code private_key_jwt}, client id
 *       {@code netsuite.oauth.client-id}, token URI {@link NetsuiteProperties#tokenUri()}, no scope and no
 *       client secret.</li>
 *   <li>{@link #netsuiteAuthorizedClientService} &mdash; the in-memory store of the access token.</li>
 *   <li>{@link #netsuiteTokenResponseClient} &mdash; the token request: a form {@code POST} with
 *       {@code grant_type=client_credentials},
 *       {@code client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer} and
 *       {@code client_assertion=<JWT>}, and no {@code Authorization} header, {@code scope} or
 *       {@code client_id} parameter. The JWT carries the JWS header {@code typ} {@code JWT}, {@code alg}
 *       {@code PS256} and {@code kid} = {@code netsuite.oauth.certificate-id}, and the claims {@code iss} =
 *       {@code sub} = the client id, {@code aud} = the token URI, {@code jti}, {@code iat}, {@code exp} =
 *       {@code iat} + 60 s and {@code scope} = {@code "rest_webservices"} (D-016).</li>
 *   <li>{@link #netsuiteAuthorizedClientManager} &mdash; requests a token on the first call and again after
 *       the stored one expires (60 s clock skew), and stores it under {@value #PRINCIPAL_NAME}.</li>
 *   <li>{@link #netsuiteWebClient} &mdash; the {@code WebClient} with base URL
 *       {@link NetsuiteProperties#restBaseUrl()} that adds {@code Authorization: Bearer <token>} to every
 *       request (D-016, D-020).</li>
 *   <li>{@link #netsuiteAuthorizedClientEvictor} &mdash; removes the stored token, for the single
 *       re-authenticated retry of {@link NetsuiteRestClient} after HTTP 401 (D-020).</li>
 * </ol>
 *
 * <p>The signing key is the unencrypted PKCS#8 PEM file ({@code -----BEGIN PRIVATE KEY-----}) at
 * {@code netsuite.oauth.private-key-path} (D-015, D-016). It is read at the first token request, not at startup,
 * and kept after a successful read; a failed read is not kept, and the next token request reads the file again.
 * Creating the beans performs no file I/O and sends no request; the context starts with the committed placeholder
 * values of {@code application.yml}. A key that cannot be loaded fails the token request with an
 * {@link IllegalStateException} whose message names the property, never its value or key material.
 *
 * <p>Nothing here logs the client id, the certificate id, the client assertion or the access token. Neither HTTP
 * stack has a timeout or a retry operator, and neither sends a request twice: the token request is one HTTP/1.1
 * {@code POST} through the JDK {@code HttpClient} ({@link #tokenRestTemplate()}), and each NetSuite REST request
 * makes exactly one attempt through Reactor Netty (D-020).
 *
 * <p>Example, as used by {@link NetsuiteRestClient}:
 * <pre>{@code
 * byte[] page = netsuiteWebClient.post()
 *         .uri("/services/rest/query/v1/suiteql?limit=1000&offset=0")
 *         .header("Prefer", "transient")
 *         .bodyValue(body)
 *         .retrieve()
 *         .bodyToMono(byte[].class)
 *         .block();
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class NetsuiteOAuth2Config {

    /** Registration id of the NetSuite client registration and default registration of the exchange filter. */
    public static final String REGISTRATION_ID = "netsuite";

    /**
     * Principal name under which the access token is stored: the name of the anonymous principal that
     * {@link ServletOAuth2AuthorizedClientExchangeFilterFunction} uses when the {@code SecurityContextHolder} holds
     * no authentication.
     */
    public static final String PRINCIPAL_NAME = "anonymousUser";

    /** Key of the PEM file path setting, named in every key-loading failure message. */
    private static final String PRIVATE_KEY_PATH_KEY = "netsuite.oauth.private-key-path";

    /** Message of the failure raised when {@code netsuite.oauth.private-key-path} is unset or blank. */
    static final String PRIVATE_KEY_PATH_NOT_SET = PRIVATE_KEY_PATH_KEY + " is not set";

    /** Message of the failure raised when {@code netsuite.oauth.certificate-id} is unset or blank. */
    static final String CERTIFICATE_ID_NOT_SET = "netsuite.oauth.certificate-id is not set";

    /** First line of an unencrypted PKCS#8 PEM private key. */
    private static final String PKCS8_BEGIN = "-----BEGIN PRIVATE KEY-----";

    /** Last line of an unencrypted PKCS#8 PEM private key. */
    private static final String PKCS8_END = "-----END PRIVATE KEY-----";

    /** First line of a PKCS#1 PEM RSA private key, which is refused. */
    private static final String PKCS1_BEGIN = "-----BEGIN RSA PRIVATE KEY-----";

    /** First line of an encrypted PKCS#8 PEM private key, which is refused. */
    private static final String ENCRYPTED_PKCS8_BEGIN = "-----BEGIN ENCRYPTED PRIVATE KEY-----";

    /** Statement of the accepted key format, appended to every format failure message. */
    private static final String PKCS8_REQUIRED = "; an unencrypted PKCS#8 key (" + PKCS8_BEGIN + ") is required";

    /** JCA name of the RSA key factory. */
    private static final String RSA = "RSA";

    /** JCA name of the RSASSA-PSS key factory, used for PKCS#8 keys with the RSASSA-PSS algorithm identifier. */
    private static final String RSASSA_PSS = "RSASSA-PSS";

    /** Every whitespace character, removed from the Base64 body of the PEM block. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s");

    /** JWS header {@code typ} of the client assertion (D-016). */
    private static final String ASSERTION_TYPE = "JWT";

    /** Name of the client-assertion claim that carries the NetSuite scope (D-016). */
    private static final String SCOPE_CLAIM = "scope";

    /** Value of the {@code scope} claim: the NetSuite REST Web Services scope, a single string (D-016). */
    private static final String REST_WEBSERVICES_SCOPE = "rest_webservices";

    /** Codec in-memory buffer size meaning no limit: a whole SuiteQL page is buffered in one piece (D-020). */
    private static final int UNLIMITED_IN_MEMORY_SIZE = -1;

    private static final Logger log = LoggerFactory.getLogger(NetsuiteOAuth2Config.class);

    /**
     * Builds the NetSuite client registration {@value #REGISTRATION_ID}: grant {@code client_credentials},
     * client authentication {@code private_key_jwt}, the given client id and token URI, no scope and no client
     * secret (D-016). The token request carries no {@code scope} parameter.
     *
     * <p>Example, pointing a registration at a local token endpoint:
     * <pre>{@code
     * ClientRegistration registration = clientRegistration("cid", "http://localhost:8089/token");
     * }</pre>
     *
     * @param clientId the client id of the NetSuite integration record ({@code netsuite.oauth.client-id})
     * @param tokenUri the token endpoint URI
     * @return the client registration
     * @throws IllegalArgumentException if {@code clientId} or {@code tokenUri} is {@code null} or empty
     */
    static ClientRegistration clientRegistration(String clientId, String tokenUri) {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .clientId(clientId)
                .tokenUri(tokenUri)
                .build();
    }

    /**
     * Loads the client-assertion signing key from an unencrypted PKCS#8 PEM file (D-015, D-016).
     *
     * <p>Steps:
     * <ol>
     *   <li>The file is read as US-ASCII text.</li>
     *   <li>A PKCS#1 ({@code -----BEGIN RSA PRIVATE KEY-----}) or encrypted PKCS#8
     *       ({@code -----BEGIN ENCRYPTED PRIVATE KEY-----}) key is refused.</li>
     *   <li>The text between {@code -----BEGIN PRIVATE KEY-----} and the next {@code -----END PRIVATE KEY-----}
     *       is stripped of all whitespace and decoded as Base64.</li>
     *   <li>The DER bytes are parsed as a PKCS#8 key by the {@code RSA} key factory, and by the
     *       {@code RSASSA-PSS} key factory when the {@code RSA} one rejects them.</li>
     *   <li>The key must be an {@link RSAPrivateCrtKey}; its public key is derived from the modulus and public
     *       exponent.</li>
     *   <li>The result is an {@link RSAKey} with that key pair, key id {@code keyId}, use {@code sig} and algorithm
     *       {@code PS256}. Spring's client-assertion converter signs with the algorithm of the key, and the JWT
     *       encoder copies the key id into the JWS header {@code kid} (D-016).</li>
     * </ol>
     *
     * <p>Every failure is an {@link IllegalStateException} whose message names
     * {@code netsuite.oauth.private-key-path}; no message contains the path value or key material. I/O, security
     * and Base64 failures are attached as the cause.
     *
     * @param pemFile the PEM file
     * @param keyId   the key id, the certificate id of the NetSuite OAuth 2.0 client credentials (M2M) mapping
     * @return the signing key
     * @throws IllegalStateException if the file cannot be read or holds no usable unencrypted PKCS#8 RSA key
     * @throws NullPointerException  if {@code pemFile} is {@code null}
     */
    static RSAKey loadSigningKey(Path pemFile, String keyId) {
        Objects.requireNonNull(pemFile, "pemFile");
        try {
            String pem = Files.readString(pemFile, StandardCharsets.US_ASCII);
            byte[] der = Base64.getDecoder().decode(pkcs8Body(pem));
            RSAPrivateCrtKey privateKey = rsaPrivateCrtKey(new PKCS8EncodedKeySpec(der));
            RSAPublicKey publicKey = (RSAPublicKey) KeyFactory.getInstance(RSA)
                    .generatePublic(new RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey)
                    .privateKey(privateKey)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.PS256)
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " could not be read as a PEM file", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " holds no valid RSA private key"
                    + PKCS8_REQUIRED, e);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " holds a PEM block that is not valid Base64"
                    + PKCS8_REQUIRED, e);
        }
    }

    /**
     * Returns the Base64 body of the PKCS#8 block of a PEM text, with all whitespace removed.
     *
     * @param pem the PEM text
     * @return the Base64 text between {@code -----BEGIN PRIVATE KEY-----} and the next
     *         {@code -----END PRIVATE KEY-----}
     * @throws IllegalStateException if the text holds a PKCS#1 or encrypted PKCS#8 key, or lacks either marker
     */
    private static String pkcs8Body(String pem) {
        if (pem.contains(PKCS1_BEGIN)) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " holds a PKCS#1 key (" + PKCS1_BEGIN + ")"
                    + PKCS8_REQUIRED);
        }
        if (pem.contains(ENCRYPTED_PKCS8_BEGIN)) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " holds an encrypted PKCS#8 key ("
                    + ENCRYPTED_PKCS8_BEGIN + ")" + PKCS8_REQUIRED);
        }
        int begin = pem.indexOf(PKCS8_BEGIN);
        int end = begin < 0 ? -1 : pem.indexOf(PKCS8_END, begin + PKCS8_BEGIN.length());
        if (begin < 0 || end < 0) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " holds no " + PKCS8_BEGIN + " ... " + PKCS8_END
                    + " block" + PKCS8_REQUIRED);
        }
        return WHITESPACE.matcher(pem.substring(begin + PKCS8_BEGIN.length(), end)).replaceAll("");
    }

    /**
     * Parses PKCS#8 DER bytes into an RSA private key with CRT parameters: first with the {@code RSA} key factory,
     * then, when it rejects the bytes, with the {@code RSASSA-PSS} key factory.
     *
     * @param spec the PKCS#8 key specification
     * @return the private key
     * @throws GeneralSecurityException if neither key factory accepts the bytes; the {@code RSA} rejection is
     *                                  attached as a suppressed exception
     * @throws IllegalStateException    if the key is not an {@link RSAPrivateCrtKey}
     */
    private static RSAPrivateCrtKey rsaPrivateCrtKey(PKCS8EncodedKeySpec spec) throws GeneralSecurityException {
        PrivateKey key;
        try {
            key = KeyFactory.getInstance(RSA).generatePrivate(spec);
        } catch (InvalidKeySpecException rsaRejection) {
            try {
                key = KeyFactory.getInstance(RSASSA_PSS).generatePrivate(spec);
            } catch (InvalidKeySpecException pssRejection) {
                pssRejection.addSuppressed(rsaRejection);
                throw pssRejection;
            }
        }
        if (!(key instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " holds an RSA private key without CRT parameters"
                    + PKCS8_REQUIRED);
        }
        return crtKey;
    }

    /**
     * Registers the single NetSuite client registration {@value #REGISTRATION_ID}, built by
     * {@link #clientRegistration(String, String)} from {@code netsuite.oauth.client-id} and
     * {@link NetsuiteProperties#tokenUri()} (D-016). With the committed placeholder account the token URI is
     * {@code https://todo.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token}; no I/O takes place.
     *
     * @param properties the {@code netsuite.*} settings
     * @return the in-memory repository holding the registration
     * @throws IllegalStateException    if {@code netsuite.account} is unset or blank
     * @throws IllegalArgumentException if {@code netsuite.oauth.client-id} is unset or empty
     */
    @Bean
    public ClientRegistrationRepository netsuiteClientRegistrationRepository(NetsuiteProperties properties) {
        return new InMemoryClientRegistrationRepository(
                clientRegistration(oauth(properties).clientId(), properties.tokenUri()));
    }

    /**
     * Stores the NetSuite access token in memory, keyed by registration id and principal name (D-016).
     *
     * @param repository the repository holding the {@value #REGISTRATION_ID} registration
     * @return the in-memory authorized client service
     */
    @Bean
    public OAuth2AuthorizedClientService netsuiteAuthorizedClientService(ClientRegistrationRepository repository) {
        return new InMemoryOAuth2AuthorizedClientService(repository);
    }

    /**
     * Sends the client credentials token request with a {@code private_key_jwt} client assertion (D-016).
     *
     * <p>The form body holds {@code grant_type=client_credentials},
     * {@code client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer} and
     * {@code client_assertion=<JWT>}; the request has no {@code Authorization} header and no {@code scope},
     * {@code client_id} or {@code client_secret} parameter. The JWT is signed with the key that
     * {@link SigningKeyResolver} loads, with the JWS header {@code typ} {@code JWT}, {@code alg} {@code PS256} and
     * {@code kid} = {@code netsuite.oauth.certificate-id}. Its claims are {@code iss} = {@code sub} = the client
     * id, {@code aud} = the token URI, {@code jti}, {@code iat}, {@code exp} = {@code iat} + 60 s and
     * {@code scope} = {@code "rest_webservices"} (D-016).
     *
     * <p>The token response, including an {@code expires_in} sent as a string, is read by Spring Security's
     * default converter, and an error response is handled by Spring Security's default error handler. The request
     * is sent through {@link #tokenRestTemplate()}: one HTTP/1.1 {@code POST} with no timeout, never sent twice
     * (D-020).
     *
     * @param properties the {@code netsuite.*} settings; the key path and certificate id are read at the first
     *                   token request
     * @return the token response client
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> netsuiteTokenResponseClient(
            NetsuiteProperties properties) {
        var assertion = new NimbusJwtClientAuthenticationParametersConverter<OAuth2ClientCredentialsGrantRequest>(
                new SigningKeyResolver(properties));
        assertion.setJwtClientAssertionCustomizer(context -> {
            context.getHeaders().type(ASSERTION_TYPE);
            context.getClaims().claim(SCOPE_CLAIM, REST_WEBSERVICES_SCOPE);
        });
        var entityConverter = new OAuth2ClientCredentialsGrantRequestEntityConverter();
        entityConverter.addParametersConverter(assertion);
        var client = new DefaultClientCredentialsTokenResponseClient();
        client.setRequestEntityConverter(entityConverter);
        // The token request is sent through the JDK HttpClient in place of the client's default
        // HttpURLConnection stack: each token request is sent once (D-020).
        client.setRestOperations(tokenRestTemplate());
        return client;
    }

    /**
     * Builds the {@code RestTemplate} of the token request: the message converters and error handler that
     * {@link DefaultClientCredentialsTokenResponseClient} uses by default ({@link FormHttpMessageConverter},
     * {@link OAuth2AccessTokenResponseHttpMessageConverter}, {@link OAuth2ErrorResponseErrorHandler}), over a
     * {@link JdkClientHttpRequestFactory} with no read timeout and a JDK {@code HttpClient} pinned to HTTP/1.1
     * with the JDK defaults: no connect timeout and no redirects followed.
     *
     * <p>The JDK client never writes a {@code POST} twice: a connection reset or closed once it is established
     * fails the token request after one send. Its only repeated step is one second TCP connect after a connect
     * that fails (refused, or reset before it completes), before any byte of the request is written (D-020).
     *
     * @return the {@code RestTemplate} for the token endpoint
     */
    private static RestTemplate tokenRestTemplate() {
        var restTemplate = new RestTemplate(
                List.of(new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(new JdkClientHttpRequestFactory(java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build()));
        return restTemplate;
    }

    /**
     * Obtains NetSuite access tokens with the client credentials grant through {@code tokenResponseClient} and
     * stores them in {@code service} (D-016). A token is requested at the first authorization and again once the
     * stored one is within the provider's 60 s clock skew of expiry. Spring Security's default success and
     * failure handlers apply.
     *
     * @param repository          the repository holding the {@value #REGISTRATION_ID} registration
     * @param service             the store of the access token
     * @param tokenResponseClient the token request client
     * @return the authorized client manager
     */
    @Bean
    public OAuth2AuthorizedClientManager netsuiteAuthorizedClientManager(
            ClientRegistrationRepository repository,
            OAuth2AuthorizedClientService service,
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> tokenResponseClient) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials ->
                        clientCredentials.accessTokenResponseClient(tokenResponseClient))
                .build();
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(repository, service);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * The {@code WebClient} for NetSuite REST Web Services (D-016, D-020).
     *
     * <ul>
     *   <li>Built from Boot's prototype {@code WebClient.Builder}, which carries Boot's Jackson codecs.</li>
     *   <li>Base URL {@link NetsuiteProperties#restBaseUrl()}.</li>
     *   <li>The only exchange filter is {@link ServletOAuth2AuthorizedClientExchangeFilterFunction} with default
     *       registration {@value #REGISTRATION_ID}: it obtains the token through {@code manager} and adds
     *       {@code Authorization: Bearer <token>}. It installs no failure handler; the stored token is removed on
     *       HTTP 401 by {@link NetsuiteRestClient} through {@link #netsuiteAuthorizedClientEvictor} (D-020).</li>
     *   <li>The codec in-memory buffer has no size limit: a whole 1000-row SuiteQL page is read in one piece
     *       (D-020).</li>
     *   <li>No connect or response timeout, no {@code retry()} operator, and Reactor Netty's resend of a request
     *       whose connection is reset is off: each request makes exactly one attempt (D-020).</li>
     * </ul>
     *
     * @param builder    Boot's {@code WebClient.Builder}
     * @param manager    the authorized client manager that supplies the access token
     * @param properties the {@code netsuite.*} settings
     * @return the NetSuite {@code WebClient}
     * @throws IllegalStateException if {@code netsuite.account} is unset or blank
     */
    @Bean
    public WebClient netsuiteWebClient(WebClient.Builder builder,
                                       OAuth2AuthorizedClientManager manager,
                                       NetsuiteProperties properties) {
        var oauth2 = new ServletOAuth2AuthorizedClientExchangeFilterFunction(manager);
        oauth2.setDefaultClientRegistrationId(REGISTRATION_ID);
        return builder.baseUrl(properties.restBaseUrl())
                // Reactor Netty's default resend of a request whose connection is reset before the request is
                // written is disabled: a connectivity failure surfaces after one attempt (D-020).
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().disableRetry(true)))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(UNLIMITED_IN_MEMORY_SIZE))
                .apply(oauth2.oauth2Configuration())
                .build();
    }

    /**
     * Removes the stored NetSuite access token, the authorized client of registration {@value #REGISTRATION_ID}
     * and principal {@value #PRINCIPAL_NAME}. The next request through {@link #netsuiteWebClient} requests a new
     * token (D-020).
     *
     * @param service the store of the access token
     * @return the evictor used by {@link NetsuiteRestClient} before its single re-authenticated retry
     */
    @Bean
    public NetsuiteRestClient.AuthorizedClientEvictor netsuiteAuthorizedClientEvictor(
            OAuth2AuthorizedClientService service) {
        return () -> service.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME);
    }

    /**
     * Returns the {@code netsuite.oauth} settings, or settings with three {@code null} components when the record
     * holds none.
     *
     * @param properties the {@code netsuite.*} settings
     * @return the {@code netsuite.oauth} settings, never {@code null}
     */
    private static NetsuiteProperties.Oauth oauth(NetsuiteProperties properties) {
        NetsuiteProperties.Oauth oauth = properties.oauth();
        return oauth != null ? oauth : new NetsuiteProperties.Oauth(null, null, null);
    }

    /**
     * Supplies the client-assertion signing key to Spring's client-assertion converter (D-015, D-016).
     *
     * <ul>
     *   <li>Nothing is read at construction.</li>
     *   <li>The first call loads the key with {@link #loadSigningKey(Path, String)} from
     *       {@code netsuite.oauth.private-key-path}, with key id {@code netsuite.oauth.certificate-id}; later calls
     *       return the loaded key without reading the file.</li>
     *   <li>A failed load is not kept: the next call reads the file again.</li>
     * </ul>
     *
     * <p>The instance is safe for concurrent calls; concurrent first calls all return the key stored first.
     */
    private static final class SigningKeyResolver implements Function<ClientRegistration, JWK> {

        /** The {@code netsuite.*} settings, read at each call until a key is loaded. */
        private final NetsuiteProperties properties;

        /** The loaded signing key; empty until the first successful load. */
        private final AtomicReference<JWK> cache = new AtomicReference<>();

        /**
         * Creates the resolver; nothing is read.
         *
         * @param properties the {@code netsuite.*} settings
         * @throws NullPointerException if {@code properties} is {@code null}
         */
        SigningKeyResolver(NetsuiteProperties properties) {
            this.properties = Objects.requireNonNull(properties, "properties");
        }

        /**
         * Returns the signing key, loading it on the first call and after a failed load.
         *
         * @param registration the client registration being authenticated; not read
         * @return the signing key
         * @throws IllegalStateException if {@code netsuite.oauth.private-key-path} or
         *                               {@code netsuite.oauth.certificate-id} is unset or blank, the path is not a
         *                               valid file path, or the key cannot be loaded
         */
        @Override
        public JWK apply(ClientRegistration registration) {
            JWK cached = cache.get();
            if (cached != null) {
                return cached;
            }
            NetsuiteProperties.Oauth oauth = oauth(properties);
            String privateKeyPath = oauth.privateKeyPath();
            if (privateKeyPath == null || privateKeyPath.isBlank()) {
                throw new IllegalStateException(PRIVATE_KEY_PATH_NOT_SET);
            }
            // A blank certificate id, the JWS header kid of every client assertion, fails before the key file is
            // read (D-016).
            String certificateId = oauth.certificateId();
            if (certificateId == null || certificateId.isBlank()) {
                throw new IllegalStateException(CERTIFICATE_ID_NOT_SET);
            }
            Path pemFile;
            try {
                pemFile = Path.of(privateKeyPath);
            } catch (InvalidPathException e) {
                throw new IllegalStateException(PRIVATE_KEY_PATH_KEY + " is not a valid file path", e);
            }
            JWK loaded = loadSigningKey(pemFile, certificateId);
            if (cache.compareAndSet(null, loaded)) {
                log.info("NetSuite client-assertion signing key loaded from {}", PRIVATE_KEY_PATH_KEY);
            }
            return cache.get();
        }
    }
}
