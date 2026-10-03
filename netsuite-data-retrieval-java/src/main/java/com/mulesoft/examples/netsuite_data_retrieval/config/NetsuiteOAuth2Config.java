package com.mulesoft.examples.netsuite_data_retrieval.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.RSAKey;
import io.netty.channel.ChannelOption;
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
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.NimbusJwtClientAuthenticationParametersConverter;
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
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * NetSuite OAuth 2.0 machine-to-machine client and the OAuth-filtered {@link WebClient} for the
 * NetSuite REST API. Replaces the login-authentication connector configuration
 * {@code netsuite:config-login-authentication NetSuite__Login_Authentication}, which signed in with
 * {@code nets.email}, {@code nets.password}, {@code nets.account}, {@code nets.roleId} and
 * {@code nets.applicationId} [netsuite-data-retrieval/src/main/app/netsuite-api.xml:18] (D-015,
 * D-016).
 *
 * <p>Grant and client authentication (D-016):
 * <ul>
 *   <li>grant {@code client_credentials}, registration id {@value #REGISTRATION_ID}, client id
 *       {@code netsuite.oauth.client-id}, token URI {@link NetsuiteProperties#resolvedTokenUri()}
 *       ({@code https://<account>.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token}
 *       when {@code netsuite.oauth.token-uri} is empty, D-094);</li>
 *   <li>client authentication {@code private_key_jwt}: the token request is a form {@code POST} with
 *       {@code grant_type=client_credentials},
 *       {@code client_assertion_type=urn:ietf:params:oauth:client-assertion-type:jwt-bearer} and
 *       {@code client_assertion=<JWT>}, and carries no {@code scope} parameter;</li>
 *   <li>the client assertion is signed with PS256 by the RSA key read from
 *       {@code netsuite.oauth.private-key-path}; its header carries {@code alg} {@code PS256},
 *       {@code kid} = {@code netsuite.oauth.certificate-id} and {@code typ} {@code JWT} (D-522); its
 *       claims are {@code iss} = {@code sub} = client id, {@code aud} = token URI, {@code jti},
 *       {@code iat}, {@code exp} (60 seconds after {@code iat}) and
 *       {@code scope} = {@code ["rest_webservices"]}.</li>
 * </ul>
 *
 * <p>The key file is read at the first token request, not at startup, and a loaded key is kept for
 * the life of the context. A key that cannot be loaded raises an {@link IllegalStateException}
 * naming {@code netsuite.oauth.private-key-path} at that request; a failed load is not kept, and the
 * next request reads the file again (D-525). Startup with the committed placeholder values of
 * {@code application.yml} reads no file and makes no outbound call.
 *
 * <p>Timeouts (D-095): the token request and every REST request connect within
 * {@code netsuite.connect-timeout} and wait at most {@code netsuite.response-timeout} for the
 * response. Neither the token client nor the {@link WebClient} retries; a timed-out request ends
 * after one attempt, and the single re-authentication retry after a 401 belongs to
 * {@code client/WebClientNetsuiteRestClient} (D-020).
 *
 * <p>The class declares no {@code SecurityFilterChain}, {@code @EnableWebSecurity},
 * {@code WebSecurityCustomizer} or {@code @EnableMethodSecurity}; with the four security
 * auto-configurations excluded the inbound API stays unauthenticated, as the original listener is
 * (D-097).
 *
 * <p>Usage by the REST client:
 * <pre>{@code
 * Authentication principal = UsernamePasswordAuthenticationToken.authenticated(
 *         NetsuiteOAuth2Config.PRINCIPAL_NAME, null, AuthorityUtils.NO_AUTHORITIES);
 * String body = netsuiteWebClient.get()
 *         .uri("/services/rest/record/v1/customer/{id}", id)
 *         .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction
 *                 .clientRegistrationId(NetsuiteOAuth2Config.REGISTRATION_ID)
 *                 .andThen(ServletOAuth2AuthorizedClientExchangeFilterFunction.authentication(principal)))
 *         .retrieve()
 *         .bodyToMono(String.class)
 *         .block();
 * }</pre>
 */
@Configuration(proxyBeanMethods = false)
public class NetsuiteOAuth2Config {

    /** Registration id of the NetSuite client registration and default registration of the filter (D-016). */
    public static final String REGISTRATION_ID = "netsuite";

    /**
     * Principal name under which the NetSuite authorized client is stored (D-524).
     * {@code client/WebClientNetsuiteRestClient} passes it to the filter through
     * {@link ServletOAuth2AuthorizedClientExchangeFilterFunction#authentication(
     * org.springframework.security.core.Authentication)} with an {@code Authentication} whose
     * {@code getName()} is this value, and calls
     * {@code removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME)} on the
     * {@link OAuth2AuthorizedClientService} before its single re-authentication retry (D-020).
     */
    public static final String PRINCIPAL_NAME = "netsuite-data-retrieval";

    /** Property key of the client id (D-015). */
    static final String CLIENT_ID_KEY = "netsuite.oauth.client-id";

    /** Property key of the certificate id sent as the assertion {@code kid} (D-015). */
    static final String CERTIFICATE_ID_KEY = "netsuite.oauth.certificate-id";

    /** Property key of the PKCS#8 PEM private key file (D-015). */
    static final String PRIVATE_KEY_PATH_KEY = "netsuite.oauth.private-key-path";

    /** Property key of the connect timeout (D-095). */
    static final String CONNECT_TIMEOUT_KEY = "netsuite.connect-timeout";

    /** Property key of the response timeout (D-095). */
    static final String RESPONSE_TIMEOUT_KEY = "netsuite.response-timeout";

    /** Value of the {@code scope} claim of the client assertion (D-016). */
    static final List<String> ASSERTION_SCOPE = List.of("rest_webservices");

    /** Value of the {@code typ} header of the client assertion (D-522). */
    static final String ASSERTION_TYPE = "JWT";

    /** Smallest accepted RSA modulus length of the private key, in bits (D-525). */
    static final int MIN_RSA_KEY_BITS = 2048;

    private static final String PEM_BEGIN = "-----BEGIN PRIVATE KEY-----";

    private static final String PEM_END = "-----END PRIVATE KEY-----";

    private static final Logger LOGGER = LoggerFactory.getLogger(NetsuiteOAuth2Config.class);

    /**
     * NetSuite client registration {@value #REGISTRATION_ID}: grant {@code client_credentials},
     * client authentication {@code private_key_jwt}, client id {@code netsuite.oauth.client-id},
     * token URI {@link NetsuiteProperties#resolvedTokenUri()} and no scopes (D-016, D-094).
     * Builds with the committed placeholder values of {@code application.yml} and makes no outbound
     * call.
     *
     * @param p the bound {@code netsuite.*} properties
     * @return a repository holding the single NetSuite registration
     * @throws IllegalStateException when {@code netsuite.oauth.client-id} is empty (D-527)
     */
    @Bean
    public ClientRegistrationRepository netsuiteClientRegistrationRepository(NetsuiteProperties p) {
        String clientId = oauth(p).clientId();
        if (!StringUtils.hasText(clientId)) {
            throw new IllegalStateException(CLIENT_ID_KEY + " must be set to the NetSuite integration client id");
        }
        String tokenUri = p.resolvedTokenUri();
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.PRIVATE_KEY_JWT)
                .tokenUri(tokenUri)
                .build();
        LOGGER.info("NetSuite OAuth 2.0 client registration '{}' uses token URI {}", REGISTRATION_ID, tokenUri);
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * In-memory store of the NetSuite authorized client, keyed by {@value #REGISTRATION_ID} and
     * {@value #PRINCIPAL_NAME}. {@code client/WebClientNetsuiteRestClient} removes the entry before
     * its single re-authentication retry (D-020).
     *
     * @param repo the NetSuite client registration repository
     * @return the authorized-client service
     */
    @Bean
    public OAuth2AuthorizedClientService netsuiteAuthorizedClientService(ClientRegistrationRepository repo) {
        return new InMemoryOAuth2AuthorizedClientService(repo);
    }

    /**
     * Authorized-client manager of the NetSuite registration: the {@code client_credentials}
     * provider, a token client signing a {@code private_key_jwt} client assertion and the
     * {@code netsuiteAuthorizedClientService} store (D-016). An access token is requested on the
     * first call and reused until it expires.
     *
     * <p>Token request: form {@code POST} to {@link NetsuiteProperties#resolvedTokenUri()} with
     * {@code grant_type}, {@code client_assertion_type} and {@code client_assertion}; header
     * {@code alg} {@code PS256}, {@code kid} = certificate id, {@code typ} {@code JWT} (D-522);
     * claim {@code scope} = {@code ["rest_webservices"]} next to the Spring defaults {@code iss},
     * {@code sub}, {@code aud}, {@code jti}, {@code iat} and {@code exp}. The token
     * {@link RestTemplate} connects within {@code netsuite.connect-timeout}, reads within
     * {@code netsuite.response-timeout} (D-095), maps an OAuth 2.0 error response with
     * {@link OAuth2ErrorResponseErrorHandler} and makes one attempt (D-020).
     *
     * @param repo    the NetSuite client registration repository
     * @param service the NetSuite authorized-client service
     * @param p       the bound {@code netsuite.*} properties
     * @return the authorized-client manager used by {@code netsuiteWebClient}
     * @throws IllegalStateException when a timeout is negative or exceeds {@link Integer#MAX_VALUE}
     *                               milliseconds
     */
    @Bean
    public OAuth2AuthorizedClientManager netsuiteAuthorizedClientManager(
            ClientRegistrationRepository repo, OAuth2AuthorizedClientService service, NetsuiteProperties p) {
        NetsuiteProperties.Oauth oauth = oauth(p);
        Function<ClientRegistration, JWK> jwkResolver = jwkResolver(oauth.privateKeyPath(), oauth.certificateId());

        NimbusJwtClientAuthenticationParametersConverter<OAuth2ClientCredentialsGrantRequest> jwtParams =
                new NimbusJwtClientAuthenticationParametersConverter<>(jwkResolver);
        jwtParams.setJwtClientAssertionCustomizer(context -> {
            context.getClaims().claim("scope", ASSERTION_SCOPE);
            context.getHeaders().type(ASSERTION_TYPE);
        });

        OAuth2ClientCredentialsGrantRequestEntityConverter converter =
                new OAuth2ClientCredentialsGrantRequestEntityConverter();
        converter.addParametersConverter(jwtParams);

        DefaultClientCredentialsTokenResponseClient tokenClient = new DefaultClientCredentialsTokenResponseClient();
        tokenClient.setRequestEntityConverter(converter);
        tokenClient.setRestOperations(tokenRestTemplate(p));

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(repo, service);
        manager.setAuthorizedClientProvider(OAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenClient))
                .build());
        return manager;
    }

    /**
     * {@link WebClient} for the NetSuite REST API (D-016): base URL
     * {@link NetsuiteProperties#resolvedRestBaseUrl()} (D-094), an
     * {@code Authorization: Bearer <access token>} header from {@code netsuiteAuthorizedClientManager}
     * on every request, default registration {@value #REGISTRATION_ID}, connect timeout
     * {@code netsuite.connect-timeout}, response timeout {@code netsuite.response-timeout} (D-095),
     * and whole response bodies buffered without a size limit (D-523). Built from Boot's
     * auto-configured {@link WebClient.Builder} with its Jackson codecs.
     *
     * <p>The filter has no authorization-failure handler and the client has no retry, neither a
     * Reactor retry operator nor Reactor Netty's resend after a connection reset: a 401 reaches
     * {@code client/WebClientNetsuiteRestClient}, which re-authenticates once, and a timed-out or
     * reset request ends after one attempt (D-020, D-526).
     *
     * @param builder the Boot-configured builder (prototype scope)
     * @param manager the NetSuite authorized-client manager
     * @param p       the bound {@code netsuite.*} properties
     * @return the NetSuite REST {@link WebClient}
     * @throws IllegalStateException when a timeout is negative or exceeds {@link Integer#MAX_VALUE}
     *                               milliseconds
     */
    @Bean
    public WebClient netsuiteWebClient(
            WebClient.Builder builder, OAuth2AuthorizedClientManager manager, NetsuiteProperties p) {
        int connectMillis = timeoutMillis(p.connectTimeout(), CONNECT_TIMEOUT_KEY);
        timeoutMillis(p.responseTimeout(), RESPONSE_TIMEOUT_KEY);

        ServletOAuth2AuthorizedClientExchangeFilterFunction filter =
                new ServletOAuth2AuthorizedClientExchangeFilterFunction(manager);
        filter.setDefaultClientRegistrationId(REGISTRATION_ID);

        // Reactor Netty's resend-once of a request aborted by a TCP connection reset is switched off:
        // every REST request makes exactly one attempt (D-020, D-526).
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectMillis)
                .responseTimeout(p.responseTimeout())
                .disableRetry(true);

        String baseUrl = p.resolvedRestBaseUrl();
        LOGGER.info("NetSuite REST client uses base URL {} (connect timeout {}, response timeout {})",
                baseUrl, p.connectTimeout(), p.responseTimeout());
        return builder
                .baseUrl(baseUrl)
                .apply(filter.oauth2Configuration())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(-1))
                .build();
    }


    /**
     * Token-endpoint {@link RestTemplate}: form and OAuth 2.0 access-token-response converters,
     * {@link OAuth2ErrorResponseErrorHandler}, and a {@link SimpleClientHttpRequestFactory} with the
     * {@code netsuite.connect-timeout} connect timeout and {@code netsuite.response-timeout} read
     * timeout (D-095). No retry and no interceptors (D-020).
     */
    private static RestTemplate tokenRestTemplate(NetsuiteProperties p) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis(p.connectTimeout(), CONNECT_TIMEOUT_KEY));
        requestFactory.setReadTimeout(timeoutMillis(p.responseTimeout(), RESPONSE_TIMEOUT_KEY));

        RestTemplate restTemplate = new RestTemplate(
                List.of(new FormHttpMessageConverter(), new OAuth2AccessTokenResponseHttpMessageConverter()));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(requestFactory);
        return restTemplate;
    }

    /**
     * JWK resolver of the {@code private_key_jwt} client assertion. For a registration whose client
     * authentication method is {@code private_key_jwt} it returns the RSA signing key, loaded from
     * {@code privateKeyPath} at the first call and kept afterwards; for any other method it returns
     * {@code null}. Concurrent first calls load the key once. A failed load throws and is not kept,
     * and the next call loads again (D-525).
     *
     * @throws IllegalStateException when {@code netsuite.oauth.certificate-id} is empty or the key
     *                               cannot be loaded
     */
    private static Function<ClientRegistration, JWK> jwkResolver(String privateKeyPath, String certificateId) {
        AtomicReference<RSAKey> loadedKey = new AtomicReference<>();
        Object loadLock = new Object();
        return registration -> {
            if (!ClientAuthenticationMethod.PRIVATE_KEY_JWT.equals(registration.getClientAuthenticationMethod())) {
                return null;
            }
            RSAKey key = loadedKey.get();
            if (key != null) {
                return key;
            }
            synchronized (loadLock) {
                key = loadedKey.get();
                if (key == null) {
                    if (!StringUtils.hasText(certificateId)) {
                        throw new IllegalStateException(CERTIFICATE_ID_KEY
                                + " must be set to the certificate id of the NetSuite OAuth 2.0 client credentials mapping");
                    }
                    key = loadRsaKey(privateKeyPath, certificateId);
                    loadedKey.set(key);
                    LOGGER.info("Loaded the NetSuite OAuth 2.0 signing key from {} = {} (kid {}, {} bits)",
                            PRIVATE_KEY_PATH_KEY, privateKeyPath, certificateId, key.size());
                }
                return key;
            }
        };
    }

    /**
     * Reads an unencrypted PKCS#8 PEM RSA private key ({@code -----BEGIN PRIVATE KEY-----}) from the
     * filesystem path {@code path}, derives its public key from the modulus and public exponent, and
     * returns an {@link RSAKey} with key id {@code keyId} and algorithm {@code PS256} (D-016, D-525).
     * JDK crypto only.
     *
     * @throws IllegalStateException naming {@code netsuite.oauth.private-key-path} and {@code path},
     *                               with the cause attached, when the path is empty or invalid, the
     *                               file cannot be read, holds no PKCS#8 block or invalid Base64, or
     *                               the key is not an RSA CRT key of at least
     *                               {@value #MIN_RSA_KEY_BITS} bits
     */
    private static RSAKey loadRsaKey(String path, String keyId) {
        if (!StringUtils.hasText(path)) {
            throw keyFailure(path, "no file is configured", null);
        }
        String pem;
        try {
            pem = Files.readString(Path.of(path), StandardCharsets.US_ASCII);
        } catch (IOException | InvalidPathException ex) {
            throw keyFailure(path, "the file cannot be read (" + ex + ")", ex);
        }

        int begin = pem.indexOf(PEM_BEGIN);
        if (begin < 0) {
            throw keyFailure(path, "the file holds no unencrypted PKCS#8 '" + PEM_BEGIN + "' block", null);
        }
        int bodyStart = begin + PEM_BEGIN.length();
        int end = pem.indexOf(PEM_END, bodyStart);
        if (end < 0) {
            throw keyFailure(path, "the PKCS#8 block has no '" + PEM_END + "' line", null);
        }
        String body = StringUtils.trimAllWhitespace(pem.substring(bodyStart, end));
        if (body.isEmpty()) {
            throw keyFailure(path, "the PKCS#8 block is empty", null);
        }

        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException ex) {
            throw keyFailure(path, "the PKCS#8 block is not valid Base64 (" + ex.getMessage() + ")", ex);
        }

        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
                throw keyFailure(path, "the key is not an RSA CRT private key ("
                        + privateKey.getClass().getName() + ")", null);
            }
            int bits = crtKey.getModulus().bitLength();
            if (bits < MIN_RSA_KEY_BITS) {
                throw keyFailure(path, "the RSA key has " + bits + " bits; at least " + MIN_RSA_KEY_BITS
                        + " are required for PS256", null);
            }
            RSAPublicKey publicKey = (RSAPublicKey) keyFactory.generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
            return new RSAKey.Builder(publicKey)
                    .privateKey(crtKey)
                    .keyID(keyId)
                    .algorithm(JWSAlgorithm.PS256)
                    .build();
        } catch (GeneralSecurityException ex) {
            throw keyFailure(path, "the file holds no RSA PKCS#8 private key (" + ex + ")", ex);
        }
    }

    /** Failure of {@link #loadRsaKey(String, String)}: the message names the property key and the path. */
    private static IllegalStateException keyFailure(String path, String problem, Throwable cause) {
        return new IllegalStateException("Cannot load the NetSuite OAuth 2.0 private key from "
                + PRIVATE_KEY_PATH_KEY + " = '" + path + "': " + problem, cause);
    }

    /**
     * Milliseconds of a {@code netsuite.*} timeout, for the {@code int} millisecond settings of the
     * token request factory and the Netty connect option (D-095, D-527).
     *
     * @throws IllegalStateException naming {@code key} when the timeout is unset, negative or above
     *                               {@link Integer#MAX_VALUE} milliseconds
     */
    private static int timeoutMillis(Duration timeout, String key) {
        if (timeout == null || timeout.isNegative()) {
            throw new IllegalStateException(key + " must be a non-negative duration, got " + timeout);
        }
        if (timeout.compareTo(Duration.ofMillis(Integer.MAX_VALUE)) > 0) {
            throw new IllegalStateException(key + " must not exceed " + Integer.MAX_VALUE + " ms, got " + timeout);
        }
        return (int) timeout.toMillis();
    }

    /** {@code netsuite.oauth} of {@code p}; an unbound group counts as all keys unset. */
    private static NetsuiteProperties.Oauth oauth(NetsuiteProperties p) {
        NetsuiteProperties.Oauth oauth = p.oauth();
        return oauth != null ? oauth : new NetsuiteProperties.Oauth(null, null, null, null);
    }
}

