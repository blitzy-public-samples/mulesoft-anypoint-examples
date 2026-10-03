package com.mulesoft.examples.http_oauth_provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.oauth2.server.servlet.OAuth2AuthorizationServerJwtAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the application that replaces the Mule application {@code http-oauth-provider}
 * ({@code config.resources=http-oauth-provider.xml}): the OAuth 2.0 authorization server on
 * {@code http.provider.port} and the protected resources on {@code http.listener.port}.
 *
 * <p>The authorization-server JWT auto-configuration ({@link OAuth2AuthorizationServerJwtAutoConfiguration})
 * is excluded; access tokens are opaque references, and the context holds no JWK source, no JWT
 * decoder and no JWK set endpoint (D-041, D-296).
 *
 * <p>{@link ConfigurationPropertiesScan} registers the {@code @ConfigurationProperties} records of
 * this package and its subpackages (D-296).
 */
@SpringBootApplication(exclude = OAuth2AuthorizationServerJwtAutoConfiguration.class)
@ConfigurationPropertiesScan
public class HttpOauthProviderApplication {

    /**
     * Starts the Spring application context with the given command-line arguments.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(HttpOauthProviderApplication.class, args);
    }
}
