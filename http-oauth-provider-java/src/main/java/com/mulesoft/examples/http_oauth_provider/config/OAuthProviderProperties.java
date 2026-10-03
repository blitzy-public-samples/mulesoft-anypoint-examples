package com.mulesoft.examples.http_oauth_provider.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code oauth2-provider.*} keys of {@code application.yml}: the provider settings and the
 * registered client of {@code oauth2-provider:config} {@code oauth2Provider}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:26-41] and the resource owner of
 * {@code ss:user-service} {@code resourceOwnerUserService}
 * [http-oauth-provider/src/main/app/http-oauth-provider.xml:15-17] (D-041, D-012).
 *
 * <p>The record is bound through its canonical constructor and declares no defaults: every value comes
 * from the bound keys. An absent {@code String} key binds {@code null}, an absent
 * {@code oauth2-provider.token-ttl-seconds} binds {@code 0}, and {@link #client()} or
 * {@link #resourceOwner()} is {@code null} when none of its keys is set. The committed
 * {@code application.yml} holds placeholder values for {@code oauth2-provider.client.id},
 * {@code oauth2-provider.client.secret}, {@code oauth2-provider.resource-owner.username} and
 * {@code oauth2-provider.resource-owner.password} (D-012).
 *
 * <p>The ports {@code http.provider.port} and {@code http.listener.port} and the listener host are
 * not bound here.
 *
 * <p>The generated {@code toString()} of this record and of its nested records prints every
 * component, {@code oauth2-provider.client.secret} and {@code oauth2-provider.resource-owner.password}
 * included (D-533).
 *
 * @param providerName    {@code oauth2-provider.provider-name}: the provider name
 *                        [http-oauth-provider.xml:26 {@code providerName}]
 * @param scopes          {@code oauth2-provider.scopes}: the provider scopes, separated by whitespace
 *                        [http-oauth-provider.xml:26 {@code scopes}]
 * @param tokenTtlSeconds {@code oauth2-provider.token-ttl-seconds}: the access-token lifetime in
 *                        seconds (D-101)
 * @param client          the {@code oauth2-provider.client.*} registered client
 *                        [http-oauth-provider.xml:28-39]
 * @param resourceOwner   the {@code oauth2-provider.resource-owner.*} user
 *                        [http-oauth-provider.xml:16]
 */
@ConfigurationProperties(prefix = "oauth2-provider")
public record OAuthProviderProperties(
        String providerName,
        String scopes,
        long tokenTtlSeconds,
        Client client,
        ResourceOwner resourceOwner) {

    /**
     * Returns the provider scopes {@link #scopes()} as a list (D-041).
     *
     * <p>Example: {@code "READ_RESOURCE POST_RESOURCE"} gives {@code [READ_RESOURCE, POST_RESOURCE]}.
     *
     * @return the whitespace-separated tokens of {@link #scopes()} in their order, as an immutable
     *         list; an empty list when {@link #scopes()} is {@code null}, empty or blank
     */
    public List<String> scopeList() {
        return toScopeList(scopes);
    }

    /**
     * Binds the {@code oauth2-provider.client.*} keys: the client {@code oauth2-provider:client} of
     * {@code oauth2Provider} [http-oauth-provider/src/main/app/http-oauth-provider.xml:28-39]
     * (D-041, D-012).
     *
     * @param id          {@code oauth2-provider.client.id}: the client id
     *                    [http-oauth-provider.xml:28 {@code clientId}]
     * @param name        {@code oauth2-provider.client.name}: the client name
     *                    [http-oauth-provider.xml:28 {@code clientName}]
     * @param description {@code oauth2-provider.client.description}: the client description
     *                    [http-oauth-provider.xml:28 {@code description}]
     * @param secret      {@code oauth2-provider.client.secret}: the client secret
     *                    [http-oauth-provider.xml:28 {@code secret}]
     * @param redirectUri {@code oauth2-provider.client.redirect-uri}: the redirect-URI pattern
     *                    [http-oauth-provider.xml:30 {@code oauth2-provider:redirect-uri}]
     * @param scopes      {@code oauth2-provider.client.scopes}: the client scopes, separated by
     *                    whitespace [http-oauth-provider.xml:36-37 {@code oauth2-provider:scope}]
     */
    public record Client(
            String id,
            String name,
            String description,
            String secret,
            String redirectUri,
            String scopes) {

        /**
         * Returns the client scopes {@link #scopes()} as a list (D-041).
         *
         * <p>Example: {@code " A   B "} gives {@code [A, B]}.
         *
         * @return the whitespace-separated tokens of {@link #scopes()} in their order, as an immutable
         *         list; an empty list when {@link #scopes()} is {@code null}, empty or blank
         */
        public List<String> scopeList() {
            return toScopeList(scopes);
        }
    }

    /**
     * Binds the {@code oauth2-provider.resource-owner.*} keys: the user {@code ss:user} of
     * {@code resourceOwnerUserService} [http-oauth-provider/src/main/app/http-oauth-provider.xml:16]
     * (D-041, D-012).
     *
     * @param username  {@code oauth2-provider.resource-owner.username}: the user name
     *                  [http-oauth-provider.xml:16 {@code name}]
     * @param password  {@code oauth2-provider.resource-owner.password}: the password
     *                  [http-oauth-provider.xml:16 {@code password}]
     * @param authority {@code oauth2-provider.resource-owner.authority}: the granted authority
     *                  [http-oauth-provider.xml:16 {@code authorities}]
     */
    public record ResourceOwner(String username, String password, String authority) {
    }

    /**
     * Splits a whitespace-separated scope string into its tokens.
     *
     * @param value the scope string, possibly {@code null}
     * @return {@code List.of()} when {@code value} is {@code null}, empty or blank; otherwise the
     *         immutable list of the tokens of {@code value.trim()} split on {@code \s+}, in their order
     */
    private static List<String> toScopeList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.trim().split("\\s+"));
    }
}
