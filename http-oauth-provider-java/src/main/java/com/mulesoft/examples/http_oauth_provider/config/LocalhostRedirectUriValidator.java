package com.mulesoft.examples.http_oauth_provider.config;

import java.util.function.Consumer;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Checks the {@code redirect_uri} of an authorization-code request against the client's redirect-URI
 * pattern {@code http://localhost*} (D-041).
 *
 * <p>Source: {@code http-oauth-provider/src/main/app/http-oauth-provider.xml:30}, the
 * {@code <oauth2-provider:redirect-uri>} of client {@code myclientid}.
 *
 * <p>The accepted prefix is the pattern with one trailing {@code *} removed; a pattern without a trailing
 * {@code *} is the prefix unchanged. A request is accepted when its {@code redirect_uri} is absent or blank,
 * or starts with the prefix ({@link String#startsWith(String)}, case-sensitive). With {@code http://localhost*},
 * {@code http://localhost:8082/redirect}, {@code http://localhost/redirect} and
 * {@code http://localhost.example} are accepted, while {@code https://localhost/redirect},
 * {@code http://127.0.0.1/redirect} and {@code http://evil.example/} are rejected. The registered client's
 * redirect URIs are not consulted.
 *
 * <p>A rejected request raises {@link OAuth2AuthorizationCodeRequestAuthenticationException} with error code
 * {@code invalid_request}, description {@code OAuth 2.0 Parameter: redirect_uri} and a copy of the request
 * whose redirect URI is {@code null}; the authorization endpoint answers that exception with status 400 and
 * no {@code Location} header.
 *
 * <p>The instance is immutable and thread-safe. It is a plain object, not a Spring bean, and replaces the
 * redirect-URI check of the authorization-code request provider while the scope check stays in place:
 *
 * <pre>
 * codeRequestProvider.setAuthenticationValidator(
 *         new LocalhostRedirectUriValidator("http://localhost*")
 *                 .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR));
 * </pre>
 */
public final class LocalhostRedirectUriValidator implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    /** Trailing wildcard of a redirect-URI pattern. */
    private static final String WILDCARD = "*";

    /** Error description of a rejected request: {@code OAuth 2.0 Parameter: redirect_uri}. */
    private static final String REDIRECT_URI_ERROR_DESCRIPTION = "OAuth 2.0 Parameter: " + OAuth2ParameterNames.REDIRECT_URI;

    /** Prefix that an accepted {@code redirect_uri} starts with. */
    private final String redirectUriPrefix;

    /**
     * Creates a validator for one redirect-URI pattern (D-041).
     *
     * @param redirectUriPattern the client's redirect-URI pattern, for example {@code http://localhost*};
     *                           one trailing {@code *} is removed to form the accepted prefix
     * @throws IllegalArgumentException when {@code redirectUriPattern} is {@code null}, empty or blank
     */
    public LocalhostRedirectUriValidator(String redirectUriPattern) {
        Assert.hasText(redirectUriPattern, "redirectUriPattern cannot be empty");
        this.redirectUriPrefix = redirectUriPattern.endsWith(WILDCARD)
                ? redirectUriPattern.substring(0, redirectUriPattern.length() - WILDCARD.length())
                : redirectUriPattern;
    }

    /**
     * Accepts a missing {@code redirect_uri} or one starting with the configured prefix; otherwise rejects
     * with {@code invalid_request} and no redirect URI (D-041).
     *
     * @param context the authorization-code request context, carrying the
     *                {@link OAuth2AuthorizationCodeRequestAuthenticationToken} of the request
     * @throws OAuth2AuthorizationCodeRequestAuthenticationException when the requested {@code redirect_uri}
     *         does not start with the configured prefix; its error code is {@code invalid_request}, its
     *         description {@code OAuth 2.0 Parameter: redirect_uri}, and its request copy has a {@code null}
     *         redirect URI
     */
    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        OAuth2AuthorizationCodeRequestAuthenticationToken token = context.getAuthentication();
        String requested = token.getRedirectUri();
        if (!StringUtils.hasText(requested)) {
            return;
        }
        if (requested.startsWith(this.redirectUriPrefix)) {
            return;
        }
        OAuth2Error error = new OAuth2Error(OAuth2ErrorCodes.INVALID_REQUEST, REDIRECT_URI_ERROR_DESCRIPTION, null);
        OAuth2AuthorizationCodeRequestAuthenticationToken copy = new OAuth2AuthorizationCodeRequestAuthenticationToken(
                token.getAuthorizationUri(), token.getClientId(), (Authentication) token.getPrincipal(),
                null, token.getState(), token.getScopes(), token.getAdditionalParameters());
        throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, copy);
    }
}
