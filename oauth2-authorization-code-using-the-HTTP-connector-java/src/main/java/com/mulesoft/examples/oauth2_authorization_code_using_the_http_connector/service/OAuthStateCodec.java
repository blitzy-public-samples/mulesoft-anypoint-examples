package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service;

import org.springframework.stereotype.Component;

/**
 * Encodes and decodes the OAuth 2.0 {@code state} parameter of the Box authorization-code grant
 * [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml:5-19].
 *
 * <p>Reproduces the Mule 3.8.0 OAuth module classes
 * {@code org.mule.module.oauth2.internal.StateEncoder} (:18-21, :35-63) and
 * {@code org.mule.module.oauth2.internal.StateDecoder} (:30-87), with the encode sequence of
 * {@code AuthorizationRequestHandler} (:117-139).
 *
 * <p>An encoded state has the form
 * {@code [originalState][:resourceOwnerId=<id>][:onCompleteRedirectTo=<url>]}. Each part is optional.
 * A value ends at the next {@link #ON_COMPLETE_REDIRECT_TO_MARKER}, never at a plain {@code :}, and the
 * redirect URL runs to the end of the state.
 *
 * <p>Example: {@code encode(null, null, "http://localhost:8081/web/loginDone")} returns
 * {@code ":onCompleteRedirectTo=http://localhost:8081/web/loginDone"}; the three decoders applied to that
 * value return {@code null}, {@code null} and {@code "http://localhost:8081/web/loginDone"}.
 *
 * <p>The class holds no state; every method is side-effect free and safe for concurrent use.
 */
@Component
public class OAuthStateCodec {

    /** Marker that precedes the resource-owner id in an encoded state. */
    public static final String RESOURCE_OWNER_ID_MARKER = ":resourceOwnerId=";

    /** Marker that precedes the on-complete redirect URL in an encoded state. */
    public static final String ON_COMPLETE_REDIRECT_TO_MARKER = ":onCompleteRedirectTo=";

    /** Message of the {@link IllegalArgumentException} raised for a part appended after the redirect URL. */
    private static final String PARAMETER_AFTER_REDIRECT_MESSAGE =
            "parameter cannot be added after :onCompleteRedirectTo";

    /**
     * Builds the encoded state from its three optional parts, in the order original state, resource-owner
     * id, on-complete redirect URL.
     *
     * <ul>
     *   <li>{@code resourceOwnerId} is appended as {@code :resourceOwnerId=<id>} when it is non-null.</li>
     *   <li>{@code onCompleteRedirectTo} is appended as {@code :onCompleteRedirectTo=<url>} when it is
     *       non-null; the empty string is appended too, giving {@code :onCompleteRedirectTo=}.</li>
     * </ul>
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code encode("abc", "joe", "http://x")} returns
     *       {@code "abc:resourceOwnerId=joe:onCompleteRedirectTo=http://x"};</li>
     *   <li>{@code encode("abc", null, null)} returns {@code "abc"};</li>
     *   <li>{@code encode(null, null, "")} returns {@code ":onCompleteRedirectTo="};</li>
     *   <li>{@code encode(null, null, null)} returns {@code null}.</li>
     * </ul>
     *
     * @param originalState        the caller's own state value, or {@code null}
     * @param resourceOwnerId      the resource-owner id, or {@code null}
     * @param onCompleteRedirectTo the URL the redirect-URL handler redirects to on completion, or {@code null}
     * @return the encoded state; {@code null} when all three arguments are {@code null}
     * @throws IllegalArgumentException with message {@code parameter cannot be added after :onCompleteRedirectTo}
     *                                  when a part is appended to a state that already contains
     *                                  {@link #ON_COMPLETE_REDIRECT_TO_MARKER}
     */
    public String encode(String originalState, String resourceOwnerId, String onCompleteRedirectTo) {
        String state = originalState;
        if (resourceOwnerId != null) {
            state = appendParameter(state, RESOURCE_OWNER_ID_MARKER, resourceOwnerId);
        }
        if (onCompleteRedirectTo != null) {
            state = appendParameter(state, ON_COMPLETE_REDIRECT_TO_MARKER, onCompleteRedirectTo);
        }
        return state;
    }

    /**
     * Returns the original state that precedes the first encoded part.
     *
     * <p>The prefix ends at {@link #RESOURCE_OWNER_ID_MARKER} when the state contains it, otherwise at
     * {@link #ON_COMPLETE_REDIRECT_TO_MARKER}. An empty prefix is returned as {@code null}, so
     * {@code decodeOriginalState(":onCompleteRedirectTo=http://x")} returns {@code null}, not {@code ""}.
     *
     * @param state the encoded state, or {@code null}
     * @return the original state; {@code null} for a {@code null} state or an empty prefix; the state
     *         unchanged when it contains neither marker
     */
    public String decodeOriginalState(String state) {
        if (state == null) {
            return null;
        }
        int markerIndex = state.indexOf(RESOURCE_OWNER_ID_MARKER);
        if (markerIndex == -1) {
            markerIndex = state.indexOf(ON_COMPLETE_REDIRECT_TO_MARKER);
        }
        if (markerIndex == -1) {
            return state;
        }
        String originalState = state.substring(0, markerIndex);
        return originalState.isEmpty() ? null : originalState;
    }

    /**
     * Returns the resource-owner id encoded in the state.
     *
     * <p>The value starts after the first {@link #RESOURCE_OWNER_ID_MARKER} and ends at the next
     * {@link #ON_COMPLETE_REDIRECT_TO_MARKER}, or at the end of the state when no such marker follows.
     * For {@code "abc:resourceOwnerId=joe:onCompleteRedirectTo=http://x?a=1"} it returns {@code "joe"}.
     *
     * @param state the encoded state, or {@code null}
     * @return the resource-owner id, possibly empty; {@code null} for a {@code null} state or a state without
     *         {@link #RESOURCE_OWNER_ID_MARKER}
     */
    public String decodeResourceOwnerId(String state) {
        if (state == null) {
            return null;
        }
        int markerIndex = state.indexOf(RESOURCE_OWNER_ID_MARKER);
        if (markerIndex == -1) {
            return null;
        }
        String remainder = state.substring(markerIndex + RESOURCE_OWNER_ID_MARKER.length());
        int endIndex = remainder.indexOf(ON_COMPLETE_REDIRECT_TO_MARKER);
        return endIndex == -1 ? remainder : remainder.substring(0, endIndex);
    }

    /**
     * Returns the on-complete redirect URL encoded in the state: everything after the first
     * {@link #ON_COMPLETE_REDIRECT_TO_MARKER} up to the end of the state.
     *
     * <p>For {@code ":onCompleteRedirectTo=http://localhost:8081/web/loginDone"} it returns
     * {@code "http://localhost:8081/web/loginDone"}; for {@code ":onCompleteRedirectTo="} it returns
     * {@code ""}.
     *
     * @param state the encoded state, or {@code null}
     * @return the redirect URL, possibly empty; {@code null} for a {@code null} state or a state without
     *         {@link #ON_COMPLETE_REDIRECT_TO_MARKER}
     */
    public String decodeOnCompleteRedirectTo(String state) {
        if (state == null) {
            return null;
        }
        int markerIndex = state.indexOf(ON_COMPLETE_REDIRECT_TO_MARKER);
        if (markerIndex == -1) {
            return null;
        }
        return state.substring(markerIndex + ON_COMPLETE_REDIRECT_TO_MARKER.length());
    }

    /**
     * Appends {@code marker + value} to {@code state}, reproducing {@code StateEncoder.encodeParameter}
     * (:51-63).
     *
     * @param state  the state built so far, or {@code null}
     * @param marker {@link #RESOURCE_OWNER_ID_MARKER} or {@link #ON_COMPLETE_REDIRECT_TO_MARKER}
     * @param value  the non-null value to append
     * @return {@code marker + value} when {@code state} is {@code null}, otherwise {@code state + marker + value}
     * @throws IllegalArgumentException when {@code state} already contains {@link #ON_COMPLETE_REDIRECT_TO_MARKER}
     */
    private static String appendParameter(String state, String marker, String value) {
        if (state == null) {
            return marker + value;
        }
        if (state.contains(ON_COMPLETE_REDIRECT_TO_MARKER)) {
            throw new IllegalArgumentException(PARAMETER_AFTER_REDIRECT_MESSAGE);
        }
        return state + marker + value;
    }
}
