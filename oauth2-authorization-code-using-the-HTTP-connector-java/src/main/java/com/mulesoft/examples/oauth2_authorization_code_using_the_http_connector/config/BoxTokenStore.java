package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.config;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * In-memory store of the Box OAuth 2.0 tokens obtained through the authorization-code grant. Replaces the token
 * manager {@code Token_Manager_Config}
 * [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml:4], which the
 * grant type references through {@code tokenManager-ref} (:6).
 *
 * <p>The store holds one {@link TokenContext} per resource-owner id: the access token, the refresh token and the
 * {@code expires_in} value of the token response, plus the {@code token_type} custom parameter its extractor reads
 * (:15). {@link #DEFAULT_RESOURCE_OWNER_ID} is the key when the OAuth {@code state} names no resource owner (D-205).
 *
 * <p>Entries live for the life of the JVM. Nothing is persisted, nothing expires and nothing is refreshed: an entry
 * changes only when {@link #put} replaces it or {@link #clear} removes it (D-205). All operations are thread-safe.
 *
 * <pre>{@code
 * tokenStore.put(BoxTokenStore.DEFAULT_RESOURCE_OWNER_ID,
 *         new BoxTokenStore.TokenContext(accessToken, refreshToken, expiresIn, tokenType));
 * String accessToken = tokenStore.get(BoxTokenStore.DEFAULT_RESOURCE_OWNER_ID)
 *         .map(BoxTokenStore.TokenContext::accessToken)
 *         .orElse(null);
 * }</pre>
 */
@Component
public class BoxTokenStore {

    /** Resource-owner id under which tokens are stored and read when the OAuth {@code state} names none. */
    public static final String DEFAULT_RESOURCE_OWNER_ID = "default";

    /** Token contexts keyed by resource-owner id. */
    private final ConcurrentHashMap<String, TokenContext> tokens = new ConcurrentHashMap<>();

    /**
     * Stores {@code context} under {@code resourceOwnerId}, replacing any context already stored under that id.
     *
     * @param resourceOwnerId the resource-owner id, for example {@link #DEFAULT_RESOURCE_OWNER_ID}
     * @param context the tokens to store
     * @throws NullPointerException if {@code resourceOwnerId} or {@code context} is {@code null}
     */
    public void put(String resourceOwnerId, TokenContext context) {
        Objects.requireNonNull(resourceOwnerId, "resourceOwnerId");
        Objects.requireNonNull(context, "context");
        tokens.put(resourceOwnerId, context);
    }

    /**
     * Returns the context stored under {@code resourceOwnerId}.
     *
     * @param resourceOwnerId the resource-owner id; {@code null} is accepted
     * @return the stored context, or {@link Optional#empty()} when none is stored under that id or the id is
     *         {@code null}
     */
    public Optional<TokenContext> get(String resourceOwnerId) {
        if (resourceOwnerId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(tokens.get(resourceOwnerId));
    }

    /** Removes every stored context. */
    public void clear() {
        tokens.clear();
    }

    /**
     * Tokens of one resource owner, as extracted from the Box token response.
     *
     * <p>{@code equals} and {@code hashCode} compare all four components. {@link #toString()} prints {@code ***} in
     * place of a non-null access or refresh token (D-205).
     *
     * @param accessToken the {@code access_token} value; never {@code null}
     * @param refreshToken the {@code refresh_token} value, or {@code null} when the response carries none
     * @param expiresIn the {@code expires_in} value as text, or {@code null} when the response carries none
     * @param tokenType the {@code token_type} custom parameter
     *        [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml:15],
     *        or {@code null} when none was extracted
     */
    public record TokenContext(String accessToken, String refreshToken, String expiresIn, String tokenType) {

        /** Text printed by {@link #toString()} in place of a non-null token. */
        private static final String MASK = "***";

        /**
         * Creates a token context.
         *
         * @throws NullPointerException if {@code accessToken} is {@code null}
         */
        public TokenContext {
            Objects.requireNonNull(accessToken, "accessToken");
        }

        /**
         * Returns the record's components in the generated record form, with {@code ***} printed for a non-null
         * {@code accessToken} and {@code refreshToken}, and {@code null} for an absent component.
         *
         * @return for example {@code TokenContext[accessToken=***, refreshToken=***, expiresIn=3600, tokenType=bearer]}
         */
        @Override
        public String toString() {
            return "TokenContext[accessToken=" + mask(accessToken)
                    + ", refreshToken=" + mask(refreshToken)
                    + ", expiresIn=" + expiresIn
                    + ", tokenType=" + tokenType
                    + "]";
        }

        private static String mask(String token) {
            return token == null ? null : MASK;
        }
    }
}
