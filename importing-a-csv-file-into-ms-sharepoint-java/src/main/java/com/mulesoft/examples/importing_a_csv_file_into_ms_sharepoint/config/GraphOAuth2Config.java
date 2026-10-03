package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.reactive.function.client.ServerOAuth2AuthorizedClientExchangeFilterFunction;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Builds the Microsoft Graph {@code WebClient} authenticated with the Microsoft Entra client-credentials
 * registration {@code graph} (D-032). Replaces the authentication of the global element
 * {@code sharepoint:online-connection-config} {@code Microsoft_SharePoint_2013__Online_Connection}
 * [importing-a-csv-file-into-ms-sharepoint/src/main/app/importing-a-csv-file-into-ms-sharepoint.xml:4],
 * which signed in with {@code Sharepoint.Username} and {@code Sharepoint.Password}.
 *
 * <p>Every value comes from {@link SharePointProperties}: the client id, client secret, token URI and scope
 * of {@code sharepoint.oauth.*}, and the Graph API root of {@code sharepoint.graph.base-url}. The beans
 * request no token while the context starts. The first Graph request sent through
 * {@link #graphWebClient(WebClient.Builder, ReactiveOAuth2AuthorizedClientManager, SharePointProperties)}
 * posts the form {@code grant_type=client_credentials}, {@code scope}, {@code client_id} and
 * {@code client_secret} to the token URI and adds {@code Authorization: Bearer <access token>} to the Graph
 * request. Later requests reuse the token held by the authorized-client service until it expires; the
 * client-credentials provider then requests a new one. A failed token or Graph request reaches the caller
 * unchanged: there is no retry, no timeout of its own and no re-authorization on a {@code 401} answer
 * (D-032, D-505).
 *
 * <p>Usage: inject the {@code WebClient} bean by type or with {@code @Qualifier("graphWebClient")} and send
 * Graph requests through it, for example {@code graphWebClient.get().uri(uri).retrieve()}.
 */
@Configuration(proxyBeanMethods = false)
public class GraphOAuth2Config {

    /**
     * Registers the Microsoft Graph client {@code graph}: grant type {@code client_credentials}, client
     * authentication {@code client_secret_post}, and the client id, client secret, scope and token URI of
     * {@code sharepoint.oauth.*} (D-032, D-505).
     *
     * @param p the bound {@code sharepoint.*} settings
     * @return an in-memory repository holding the single registration {@code graph}
     */
    @Bean
    public ReactiveClientRegistrationRepository graphClientRegistrationRepository(SharePointProperties p) {
        ClientRegistration registration = ClientRegistration.withRegistrationId("graph")
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .clientId(p.oauth().clientId())
                .clientSecret(p.oauth().clientSecret())
                .scope(p.oauth().scope())
                .tokenUri(p.oauth().tokenUri())
                .build();
        return new InMemoryReactiveClientRegistrationRepository(registration);
    }

    /**
     * Holds the authorized Graph client, and with it the access token, in memory per registration and
     * principal name.
     *
     * @param repo the repository holding the registration {@code graph}
     * @return the in-memory authorized-client service
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientService graphAuthorizedClientService(ReactiveClientRegistrationRepository repo) {
        return new InMemoryReactiveOAuth2AuthorizedClientService(repo);
    }

    /**
     * Authorizes the Graph client through the client-credentials grant. The manager reads and stores the
     * authorized client in {@code service}, runs with the anonymous principal on any thread, and needs no
     * servlet request and no {@code ServerWebExchange} (D-505). The provider requests a token when none is
     * stored or the stored one has expired.
     *
     * @param repo    the repository holding the registration {@code graph}
     * @param service the store of the authorized Graph client
     * @return the client-credentials authorized-client manager
     */
    @Bean
    public ReactiveOAuth2AuthorizedClientManager graphAuthorizedClientManager(ReactiveClientRegistrationRepository repo,
            ReactiveOAuth2AuthorizedClientService service) {
        AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager manager =
                new AuthorizedClientServiceReactiveOAuth2AuthorizedClientManager(repo, service);
        manager.setAuthorizedClientProvider(
                ReactiveOAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
        return manager;
    }

    /**
     * Builds the Microsoft Graph {@code WebClient} authenticated with the client-credentials registration
     * {@code graph} (D-032). Its base URL is {@code sharepoint.graph.base-url}, and every request carries the
     * bearer token that {@code manager} supplies for {@code graph}.
     *
     * @param builder the {@code WebClient.Builder} prototype of Spring Boot's {@code WebClient}
     *                auto-configuration
     * @param manager the client-credentials authorized-client manager
     * @param p       the bound {@code sharepoint.*} settings
     * @return the authenticated Graph {@code WebClient}
     */
    @Bean
    public WebClient graphWebClient(WebClient.Builder builder, ReactiveOAuth2AuthorizedClientManager manager,
            SharePointProperties p) {
        // Reactive OAuth2 exchange filter, not the servlet variant (D-505).
        ServerOAuth2AuthorizedClientExchangeFilterFunction oauth =
                new ServerOAuth2AuthorizedClientExchangeFilterFunction(manager);
        oauth.setDefaultClientRegistrationId("graph");
        return builder.baseUrl(p.graph().baseUrl()).filter(oauth).build();
    }
}
