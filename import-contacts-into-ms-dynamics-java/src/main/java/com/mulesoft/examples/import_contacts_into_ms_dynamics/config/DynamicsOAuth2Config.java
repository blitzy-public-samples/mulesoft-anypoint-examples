package com.mulesoft.examples.import_contacts_into_ms_dynamics.config;

import io.netty.channel.ChannelOption;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.util.Assert;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

/**
 * OAuth 2.0 client-credentials registration and {@link WebClient} for the Dataverse Web API v9.2, replacing the
 * {@code dynamicscrm:config} global element
 * [import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml:3] (D-019).
 *
 * <p>Every bean is built from {@link DynamicsProperties}; no {@code spring.security.oauth2.client.*} property is read.
 * The registration, the authorized-client store, the authorized-client manager and the exchange filter are the
 * reactive OAuth 2.0 client types, and they work without a web request or a web server: the application opens no
 * port. The access token is requested from Microsoft Entra on the first filtered request, not at startup, and the
 * context starts with the committed placeholder credential values (D-012).
 *
 * <p>The beans, in dependency order:
 * <ol>
 *   <li>{@code dynamicsHttpConnector}: Reactor Netty connector with the {@code dynamics.connect-timeout} and
 *       {@code dynamics.response-timeout} limits, shared by the Dataverse and token requests;</li>
 *   <li>{@code dynamicsClientRegistrationRepository}: the registration {@code dynamics};</li>
 *   <li>{@code dynamicsAuthorizedClientService}: the in-memory store of the obtained token;</li>
 *   <li>{@code dynamicsAuthorizedClientManager}: obtains the token through the client-credentials grant;</li>
 *   <li>{@code dynamicsWebClient}: the Dataverse {@code WebClient} that attaches the bearer token and the OData
 *       headers.</li>
 * </ol>
 *
 * <p>Usage by a Dataverse caller:
 * <pre>{@code
 * webClient.post()
 *         .uri("/api/data/v9.2/contacts")
 *         .attributes(ServerOAuth2AuthorizedClientExchangeFilterFunction.clientRegistrationId("dynamics"))
 *         .bodyValue(contact)
 *         .retrieve()
 *         .toBodilessEntity()
 *         .block();
 * }</pre>
 * With no {@code Authentication} in the Reactor context, the filter stores the token under the principal name
 * {@code anonymousUser}; removing that entry from {@code dynamicsAuthorizedClientService} makes the next request
 * obtain a new token (D-020).
 */
@Configuration(proxyBeanMethods = false)
public class DynamicsOAuth2Config {

    /** Registration id of the Microsoft Entra client-credentials registration for the Dataverse Web API (D-019). */
    private static final String REGISTRATION_ID = "dynamics";

    /** Suffix appended to the organization root URL to form the client-credentials scope (D-019). */
    private static final String DEFAULT_SCOPE_SUFFIX = "/.default";

    /** OData protocol version sent in the {@code OData-Version} and {@code OData-MaxVersion} headers (D-019). */
    private static final String ODATA_VERSION = "4.0";

    /**
     * HTTP connector with the configured connect and response timeouts.
     *
     * <p>{@code dynamics.connect-timeout} becomes Netty's {@link ChannelOption#CONNECT_TIMEOUT_MILLIS} and
     * {@code dynamics.response-timeout} the Reactor Netty response timeout, both in milliseconds. A response that
     * does not arrive in time ends the request with a Reactor Netty {@code ReadTimeoutException}. While this bean
     * exists, Spring Boot defines no other {@code ClientHttpConnector}.
     *
     * @param properties the bound {@code dynamics.*} keys
     * @return the connector used by {@link #dynamicsWebClient} and by the token request
     * @throws ArithmeticException when {@code dynamics.connect-timeout} exceeds {@link Integer#MAX_VALUE}
     */
    @Bean
    public ReactorClientHttpConnector dynamicsHttpConnector(DynamicsProperties properties) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.toIntExact(properties.connectTimeout()))
                .responseTimeout(Duration.ofMillis(properties.responseTimeout()));
        return new ReactorClientHttpConnector(httpClient);
    }

    /**
     * Client-credentials registration {@code dynamics} for Microsoft Entra (D-019).
     *
     * <p>The token request is a form {@code POST} to {@code dynamics.oauth.token-uri} carrying
     * {@code grant_type=client_credentials}, {@code client_id}, {@code client_secret} (client authentication
     * {@code client_secret_post}) and {@code scope=<dynamics.service-url>/.default}, the service URL taken without
     * its trailing {@code /}.
     *
     * @param properties the bound {@code dynamics.*} keys
     * @return a repository holding the single registration {@code dynamics}
     * @throws IllegalArgumentException when {@code dynamics.service-url}, {@code dynamics.oauth.client-id} or
     *     {@code dynamics.oauth.token-uri} is empty, or the scope holds a character that OAuth 2.0 does not allow
     *     in a scope token, such as a space
     */
    @Bean
    public ReactiveClientRegistrationRepository dynamicsClientRegistrationRepository(DynamicsProperties properties) {
        String baseUrl = properties.baseUrl();
        // An empty dynamics.service-url stops the context at startup with the key named in the message.
        Assert.hasText(baseUrl, "dynamics.service-url must not be empty");
        DynamicsProperties.Oauth oauth = properties.oauth();
        Assert.notNull(oauth, "dynamics.oauth.* must be set");
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .clientId(oauth.clientId())
                .clientSecret(oauth.clientSecret())
                .tokenUri(oauth.tokenUri())
                .scope(baseUrl + DEFAULT_SCOPE_SUFFIX)
                .build();
        return new InMemoryReactiveClientRegistrationRepository(registration);
    }

    /**
     * In-memory store of the authorized client.
     *
     * <p>It holds the token obtained for the registration {@code dynamics} under the principal name
     * {@code anonymousUser}, for the lifetime of the JVM. The Dataverse caller removes that entry after a Dataverse
     * {@code 401} (D-020).
     *
     * @param repository the repository holding the registration {@code dynamics}
     * @return the store read and written by {@link #dynamicsAuthorizedClientManager}
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientService dynamicsAuthorizedClientService(
            ReactiveClientRegistrationRepository repository) {
        return new InMemoryReactiveOAuth2AuthorizedClientService(repository);
    }

    /**
     * Authorized-client manager that obtains client-credentials tokens.
     *
     * <p>It returns the stored token while it is valid and otherwise requests a new one from the registration's
     * token URI through {@code dynamicsHttpConnector}: the token request has the same connect and response
     * timeouts as the Dataverse requests. It needs no {@code ServerWebExchange}.
     *
     * @param repository              the repository holding the registration {@code dynamics}
     * @param authorizedClientService the store of the obtained token
     * @param dynamicsHttpConnector   the connector with the configured timeouts
     * @return the manager used by the exchange filter of {@link #dynamicsWebClient}
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientManager dynamicsAuthorizedClientManager(
            ReactiveClientRegistrationRepository repository,
            ReactiveOAuth2AuthorizedClientService authorizedClientService,
            ReactorClientHttpConnector dynamicsHttpConnector) {
        WebClientReactiveClientCredentialsTokenResponseClient tokenClient =
                new WebClientReactiveClientCredentialsTokenResponseClient();
        tokenClient.setWebClient(WebClient.builder().clientConnector(dynamicsHttpConnector).build());

        ReactiveOAuth2AuthorizedClientProvider provider = ReactiveOAuth2AuthorizedClientProviderBuilder.builder()
                .clientCredentials(clientCredentials -> clientCredentials.accessTokenResponseClient(tokenClient))
                .build();

        AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(repository, authorizedClientService);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /**
     * {@code WebClient} for the Dataverse Web API with the OAuth 2.0 bearer filter and OData headers; each request
     * is sent once (D-020).
     *
     * <p>The base URL is {@code dynamics.service-url} without its trailing {@code /}; callers add the
     * {@code /api/data/v9.2/...} path (D-019). Every request carries {@code Authorization: Bearer <token>} for the
     * registration {@code dynamics}, {@code OData-Version: 4.0}, {@code OData-MaxVersion: 4.0} and
     * {@code Accept: application/json}. The builder is Spring Boot's {@code WebClient.Builder}, which carries the
     * Jackson codecs. The filter's authorization-failure handler keeps its default, which leaves the stored token in
     * place on a Dataverse {@code 401}.
     *
     * @param builder                         Spring Boot's prototype {@code WebClient.Builder}
     * @param dynamicsHttpConnector           the connector with the configured timeouts
     * @param dynamicsAuthorizedClientManager the manager that supplies the access token
     * @param properties                      the bound {@code dynamics.*} keys
     * @return the project's only {@code WebClient} bean
     */
    @Bean
    public WebClient dynamicsWebClient(
            WebClient.Builder builder,
            ReactorClientHttpConnector dynamicsHttpConnector,
            ReactiveOAuth2AuthorizedClientManager dynamicsAuthorizedClientManager,
            DynamicsProperties properties) {
        ServerOAuth2AuthorizedClientExchangeFilterFunction oauth2 =
                new ServerOAuth2AuthorizedClientExchangeFilterFunction(dynamicsAuthorizedClientManager);
        oauth2.setDefaultClientRegistrationId(REGISTRATION_ID);
        return builder
                .baseUrl(properties.baseUrl())
                .clientConnector(dynamicsHttpConnector)
                .filter(oauth2)
                .defaultHeader("OData-Version", ODATA_VERSION)
                .defaultHeader("OData-MaxVersion", ODATA_VERSION)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
