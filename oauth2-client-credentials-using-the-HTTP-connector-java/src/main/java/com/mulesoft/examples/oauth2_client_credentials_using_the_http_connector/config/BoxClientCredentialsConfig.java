package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.config;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.netty.http.client.HttpClient;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.DefaultClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2ClientCredentialsGrantRequest;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServletOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Box OAuth2 client-credentials client: the client registration, the in-memory authorized-client store, the token
 * client, the authorized-client manager, the exchange filter function and the Box API {@link WebClient}.
 * Source: {@code http:request-config} {@code HTTP_Request_Configuration} with its
 * {@code oauth2:client-credentials-grant-type}
 * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:12-23] and
 * {@code oauth2:token-manager-config} {@code Token_Manager_Config} (:10).
 *
 * <p>Source elements and their counterparts here:
 * <ul>
 *   <li>{@code Token_Manager_Config} (:10): {@link #oAuth2AuthorizedClientService}, an
 *       {@link InMemoryOAuth2AuthorizedClientService} keyed by {@link #REGISTRATION_ID} and
 *       {@link #PRINCIPAL_NAME};</li>
 *   <li>{@code oauth2:client-credentials-grant-type} {@code clientId="${oauth.client.id}"}
 *       {@code clientSecret="${oauth.client.secret}"} (:14): {@link #clientRegistrationRepository}, bound to the
 *       keys {@code oauth.client.id} and {@code oauth.client.secret}, whose committed values are TODO placeholders
 *       (D-012);</li>
 *   <li>{@code oauth2:token-request} (:16): {@link #boxAccessTokenResponseClient}, which posts to the key
 *       {@code box.token-url};</li>
 *   <li>{@code oauth2:custom-parameter-extractor paramName="token_type"} (:18): {@link #TOKEN_TYPE_PARAMETER}
 *       and {@link #tokenType(Map)} over the token response's additional parameters;</li>
 *   <li>{@code HTTP_Request_Configuration} with {@code tlsContext-ref="TLS_Context"} (:12-13):
 *       {@link #boxWebClient}, on Reactor Netty with {@code TlsConfig#boxSslContext}, redirects followed and
 *       the response timeout {@code box.response-timeout} (default 10000 ms);</li>
 *   <li>the grant applied to each Box request: {@link #boxAuthorizedClientManager} and
 *       {@link #boxOAuth2FilterFunction}, which add {@code Authorization: Bearer <access token>}.</li>
 * </ul>
 *
 * <p>Token lifecycle:
 * <ul>
 *   <li>No token is requested at context startup. The first Box request sent through {@link #boxWebClient} with
 *       the attribute
 *       {@code ServletOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId(REGISTRATION_ID)}
 *       requests the token; later requests reuse the stored one.</li>
 *   <li>The stored token is never refreshed for expiry: its lifetime is {@value #ACCESS_TOKEN_LIFETIME_SECONDS}
 *       seconds. A new token is requested only after {@code client/BoxClient} calls
 *       {@code removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME)} on a Box 401 or 403.</li>
 *   <li>The Box {@link WebClient} and the token client send each request once. Token-endpoint failures surface
 *       as Spring Security's {@code ClientAuthorizationException}; Box transport failures surface as
 *       {@code WebClient} exceptions. This class catches neither.</li>
 * </ul>
 *
 * <p>The application has no Spring Security filter chain; this class registers none. The client id, client
 * secret, Basic header and access token are never logged.
 *
 * <p>Example:
 *
 * <pre>{@code
 * ResponseEntity<byte[]> response = boxWebClient.get()
 *         .uri(authorizeUri)
 *         .attributes(ServletOAuth2AuthorizedClientExchangeFilterFunction
 *                 .clientRegistrationId(BoxClientCredentialsConfig.REGISTRATION_ID))
 *         .retrieve()
 *         .toEntity(byte[].class)
 *         .block();
 * }</pre>
 */
@Configuration
public class BoxClientCredentialsConfig {

    /** Registration id of the Box client registration and of the authorized clients stored for it. */
    public static final String REGISTRATION_ID = "box";

    /**
     * Principal name under which the Box authorized client is stored: the name of the anonymous authentication
     * that {@link ServletOAuth2AuthorizedClientExchangeFilterFunction} uses when no security-context
     * authentication exists. {@code client/BoxClient} calls
     * {@code oAuth2AuthorizedClientService.removeAuthorizedClient(REGISTRATION_ID, PRINCIPAL_NAME)} when Box
     * answers 401 or 403.
     */
    public static final String PRINCIPAL_NAME = "anonymousUser";

    /**
     * Token response member read by {@link #tokenType(Map)}. Source: {@code oauth2:custom-parameter-extractor}
     * {@code paramName="token_type"} {@code value="#[json:token_type]"}
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:18].
     */
    public static final String TOKEN_TYPE_PARAMETER = "token_type";

    /** Lifetime, in seconds, given to every Box access token: 3,153,600,000 seconds (about 100 years). */
    static final long ACCESS_TOKEN_LIFETIME_SECONDS = 3_153_600_000L;

    /** {@code Content-Type} of the token request: {@code application/x-www-form-urlencoded;charset=UTF-8}. */
    static final MediaType TOKEN_REQUEST_CONTENT_TYPE =
            MediaType.valueOf("application/x-www-form-urlencoded;charset=UTF-8");

    /** Scheme prefix of the token request's {@code Authorization} header value. */
    private static final String BASIC_PREFIX = "Basic ";

    private static final Logger LOGGER = LoggerFactory.getLogger(BoxClientCredentialsConfig.class);

    /**
     * Returns the {@code token_type} member of a Box token response. Source: the custom parameter extractor
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:18].
     *
     * <pre>{@code
     * String type = BoxClientCredentialsConfig.tokenType(tokenResponse.getAdditionalParameters());
     * // "bearer" for {"access_token":"T1","token_type":"bearer"}; null when token_type is absent
     * }</pre>
     *
     * @param additionalParameters additional parameters of an {@link OAuth2AccessTokenResponse}, which hold every
     *                             token response member except {@code access_token}; may be {@code null}
     * @return {@code String.valueOf} of the {@value #TOKEN_TYPE_PARAMETER} member; {@code null} when the map is
     *         {@code null}, or the member is absent or JSON {@code null}
     */
    public static String tokenType(Map<String, Object> additionalParameters) {
        if (additionalParameters == null) {
            return null;
        }
        Object value = additionalParameters.get(TOKEN_TYPE_PARAMETER);
        return value == null ? null : String.valueOf(value);
    }

    /**
     * Converts the members of a Box token response body into an {@link OAuth2AccessTokenResponse}:
     * <ul>
     *   <li>token value: the {@code access_token} member as a string;</li>
     *   <li>token type: {@link OAuth2AccessToken.TokenType#BEARER}, whatever the {@code token_type} member
     *       holds;</li>
     *   <li>expiry: {@value #ACCESS_TOKEN_LIFETIME_SECONDS} seconds after the conversion;</li>
     *   <li>scopes and refresh token: none;</li>
     *   <li>additional parameters: a {@link LinkedHashMap} copy of every member except {@code access_token},
     *       in body order, including the raw {@code token_type}, {@code expires_in} and {@code refresh_token}.</li>
     * </ul>
     * The {@code token_type} value, never the token, is logged at DEBUG.
     *
     * @param parameters members of the token response JSON object
     * @return the access token response
     * @throws NullPointerException     if {@code parameters} is {@code null}
     * @throws IllegalArgumentException if {@code access_token} is absent, JSON {@code null} or empty; the token
     *                                  client reports it as {@code invalid_token_response}
     */
    static OAuth2AccessTokenResponse toAccessTokenResponse(Map<String, Object> parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Object accessToken = parameters.get(OAuth2ParameterNames.ACCESS_TOKEN);
        Map<String, Object> additionalParameters = new LinkedHashMap<>(parameters);
        additionalParameters.remove(OAuth2ParameterNames.ACCESS_TOKEN);
        LOGGER.debug("Box token response received: token_type={}", tokenType(additionalParameters));
        return OAuth2AccessTokenResponse.withToken(accessToken == null ? null : accessToken.toString())
                .tokenType(OAuth2AccessToken.TokenType.BEARER)
                .expiresIn(ACCESS_TOKEN_LIFETIME_SECONDS)
                .additionalParameters(additionalParameters)
                .build();
    }

    /**
     * Builds the client-credentials token request: {@code POST} to the registration's token URI
     * ({@code box.token-url}), body {@code grant_type=client_credentials}, HTTP Basic with the raw client id and
     * secret (source http-client-credentials.xml:14-16). The request carries:
     * <ul>
     *   <li>{@code Content-Type: application/x-www-form-urlencoded;charset=UTF-8};</li>
     *   <li>{@code Authorization: Basic <Base64(clientId + ":" + clientSecret)>}, over the UTF-8 bytes of the id
     *       and secret as configured, with no URL-encoding;</li>
     *   <li>a form body holding the single parameter {@code grant_type=client_credentials}: no {@code scope},
     *       {@code client_id} or {@code client_secret}.</li>
     * </ul>
     * No {@code Accept} header is set here; the token client's {@link RestTemplate} adds one from its message
     * converters.
     *
     * @param grantRequest the grant request of the Box registration
     * @return the token request entity
     * @throws IllegalArgumentException if the registration's token URI is not a legal URI
     */
    static RequestEntity<MultiValueMap<String, String>> tokenRequestEntity(
            OAuth2ClientCredentialsGrantRequest grantRequest) {
        ClientRegistration registration = grantRequest.getClientRegistration();
        URI tokenUri = URI.create(registration.getProviderDetails().getTokenUri());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add(OAuth2ParameterNames.GRANT_TYPE, AuthorizationGrantType.CLIENT_CREDENTIALS.getValue());
        LOGGER.debug("Requesting Box client-credentials token from {}", tokenUri);
        return RequestEntity.post(tokenUri)
                .contentType(TOKEN_REQUEST_CONTENT_TYPE)
                .header(HttpHeaders.AUTHORIZATION,
                        basicAuthorization(registration.getClientId(), registration.getClientSecret()))
                .body(form);
    }

    /**
     * Returns {@code Basic } followed by the Base64 encoding of the UTF-8 bytes of {@code clientId + ":" +
     * clientSecret}, with neither value URL-encoded.
     */
    private static String basicAuthorization(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return BASIC_PREFIX + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns {@code responseTimeout} when it lies between 1 and {@link Integer#MAX_VALUE} milliseconds.
     *
     * @throws IllegalArgumentException otherwise, naming the key {@code box.response-timeout}
     */
    private static long requireValidTimeout(long responseTimeout) {
        if (responseTimeout <= 0 || responseTimeout > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("box.response-timeout must be between 1 and " + Integer.MAX_VALUE
                    + " milliseconds: " + responseTimeout);
        }
        return responseTimeout;
    }

    /**
     * Box client registration {@value #REGISTRATION_ID}: grant type {@code client_credentials}, client
     * authentication {@code client_secret_basic}, no scope. Source: {@code oauth2:client-credentials-grant-type}
     * (:14) and {@code oauth2:token-request} (:16) of
     * oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml.
     *
     * @param clientId     value of {@code oauth.client.id}; a TODO placeholder in the committed
     *                     {@code application.yml} (D-012)
     * @param clientSecret value of {@code oauth.client.secret}; a TODO placeholder in the committed
     *                     {@code application.yml} (D-012)
     * @param tokenUri     value of {@code box.token-url}
     * @return an in-memory repository holding the single Box registration
     * @throws IllegalArgumentException if {@code clientId} or {@code tokenUri} is empty
     */
    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${oauth.client.id}") String clientId,
            @Value("${oauth.client.secret}") String clientSecret,
            @Value("${box.token-url}") String tokenUri) {
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .tokenUri(tokenUri)
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * In-memory store of Box authorized clients, keyed by registration id and principal name. Source:
     * {@code oauth2:token-manager-config} {@code Token_Manager_Config}
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:10].
     *
     * @param clientRegistrationRepository the Box registration repository
     * @return the authorized-client store; thread-safe
     */
    @Bean
    public OAuth2AuthorizedClientService oAuth2AuthorizedClientService(
            ClientRegistrationRepository clientRegistrationRepository) {
        return new InMemoryOAuth2AuthorizedClientService(clientRegistrationRepository);
    }

    /**
     * Client-credentials token client. Source: {@code oauth2:token-request} and its token response (:16-21) of
     * oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml.
     * <ul>
     *   <li>Request: {@link #tokenRequestEntity}, a {@code POST} to {@code box.token-url} with body
     *       {@code grant_type=client_credentials} and HTTP Basic over the raw client id and secret.</li>
     *   <li>Response: read by an {@link OAuth2AccessTokenResponseHttpMessageConverter} that applies
     *       {@link #toAccessTokenResponse}; error statuses are handled by
     *       {@link OAuth2ErrorResponseErrorHandler}.</li>
     *   <li>Transport: a {@link RestTemplate} on a {@link SimpleClientHttpRequestFactory} with the JVM default
     *       trust (D-542) and a read timeout of {@code box.response-timeout} milliseconds; each token request is
     *       sent once.</li>
     * </ul>
     *
     * @param responseTimeout value of {@code box.response-timeout} (default 10000), in milliseconds
     * @return the token client
     * @throws IllegalArgumentException if {@code responseTimeout} is not between 1 and {@link Integer#MAX_VALUE}
     */
    @Bean
    public OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> boxAccessTokenResponseClient(
            @Value("${box.response-timeout:10000}") long responseTimeout) {
        OAuth2AccessTokenResponseHttpMessageConverter tokenConverter =
                new OAuth2AccessTokenResponseHttpMessageConverter();
        tokenConverter.setAccessTokenResponseConverter(BoxClientCredentialsConfig::toAccessTokenResponse);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setReadTimeout(Duration.ofMillis(requireValidTimeout(responseTimeout)));

        RestTemplate restTemplate = new RestTemplate(List.of(new FormHttpMessageConverter(), tokenConverter));
        restTemplate.setErrorHandler(new OAuth2ErrorResponseErrorHandler());
        restTemplate.setRequestFactory(requestFactory);

        DefaultClientCredentialsTokenResponseClient tokenResponseClient =
                new DefaultClientCredentialsTokenResponseClient();
        tokenResponseClient.setRequestEntityConverter(BoxClientCredentialsConfig::tokenRequestEntity);
        tokenResponseClient.setRestOperations(restTemplate);
        return tokenResponseClient;
    }

    /**
     * Authorized-client manager of the Box registration: a {@link ClientCredentialsOAuth2AuthorizedClientProvider}
     * on {@link #boxAccessTokenResponseClient} over the {@link #oAuth2AuthorizedClientService} store. It returns
     * the stored client while one exists and its token is unexpired, and otherwise requests a token and stores
     * the new client under the request's principal name. Source: the grant of {@code HTTP_Request_Configuration}
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:12-23].
     *
     * @param clientRegistrationRepository the Box registration repository
     * @param oAuth2AuthorizedClientService the authorized-client store
     * @param boxAccessTokenResponseClient  the Box token client
     * @return the authorized-client manager
     */
    @Bean
    public OAuth2AuthorizedClientManager boxAuthorizedClientManager(
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService oAuth2AuthorizedClientService,
            OAuth2AccessTokenResponseClient<OAuth2ClientCredentialsGrantRequest> boxAccessTokenResponseClient) {
        ClientCredentialsOAuth2AuthorizedClientProvider provider =
                new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(boxAccessTokenResponseClient);

        AuthorizedClientServiceOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceOAuth2AuthorizedClientManager(clientRegistrationRepository,
                        oAuth2AuthorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * Exchange filter function that adds {@code Authorization: Bearer <access token>} to each request carrying the
     * {@code clientRegistrationId} attribute, obtaining the authorized client from
     * {@link #boxAuthorizedClientManager}. It has no default registration id, no default authorized client and no
     * authorization-failure handler. Source: the grant of {@code HTTP_Request_Configuration}
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:12-23].
     *
     * @param boxAuthorizedClientManager the Box authorized-client manager
     * @return the OAuth2 exchange filter function
     */
    @Bean
    public ServletOAuth2AuthorizedClientExchangeFilterFunction boxOAuth2FilterFunction(
            OAuth2AuthorizedClientManager boxAuthorizedClientManager) {
        return new ServletOAuth2AuthorizedClientExchangeFilterFunction(boxAuthorizedClientManager);
    }

    /**
     * {@link WebClient} for the Box API, injected as {@code @Qualifier("boxWebClient")}. Source:
     * {@code http:request-config} {@code HTTP_Request_Configuration} with {@code tlsContext-ref="TLS_Context"}
     * [oauth2-client-credentials-using-the-HTTP-connector/src/main/app/http-client-credentials.xml:12-23].
     * <ul>
     *   <li>Connector: Reactor Netty with the {@code boxSslContext} TLS context, redirects followed, and a
     *       response timeout of {@code box.response-timeout} milliseconds.</li>
     *   <li>Filter: {@link #boxOAuth2FilterFunction} through its {@code oauth2Configuration()}, the client's only
     *       filter.</li>
     *   <li>No base URL and no default header; each request is sent once.</li>
     * </ul>
     *
     * @param boxSslContext           the {@code TLS_Context} Netty context from {@code TlsConfig#boxSslContext}
     * @param boxOAuth2FilterFunction the Box OAuth2 exchange filter function
     * @param responseTimeout         value of {@code box.response-timeout} (default 10000), in milliseconds
     * @return the Box API client
     * @throws IllegalArgumentException if {@code responseTimeout} is not between 1 and {@link Integer#MAX_VALUE}
     */
    @Bean
    public WebClient boxWebClient(@Qualifier("boxSslContext") SslContext boxSslContext,
            ServletOAuth2AuthorizedClientExchangeFilterFunction boxOAuth2FilterFunction,
            @Value("${box.response-timeout:10000}") long responseTimeout) {
        HttpClient httpClient = HttpClient.create()
                .secure(spec -> spec.sslContext(boxSslContext))
                .followRedirect(true)
                .responseTimeout(Duration.ofMillis(requireValidTimeout(responseTimeout)));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .apply(boxOAuth2FilterFunction.oauth2Configuration())
                .build();
    }
}

