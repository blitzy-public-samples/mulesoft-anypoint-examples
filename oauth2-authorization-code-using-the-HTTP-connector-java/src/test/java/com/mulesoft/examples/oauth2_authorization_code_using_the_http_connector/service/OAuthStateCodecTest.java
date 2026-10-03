package com.mulesoft.examples.oauth2_authorization_code_using_the_http_connector.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Unit tests of {@link OAuthStateCodec}, the {@code state} encoding of the Box authorization-code grant
 * [oauth2-authorization-code-using-the-HTTP-connector/src/main/app/http-authorization-code-web.xml:5-19].
 *
 * <p>Checks the two marker constants, the encoded form for each combination of parts, the three decoders,
 * the round trip through {@link URLEncoder} and {@link URLDecoder}, and the guard against a part appended
 * after the redirect URL. Plain JUnit 5 with no Spring context (D-042); coverage floor D-049.
 */
class OAuthStateCodecTest {

    /** On-complete redirect URL of the {@code /web} login flow. */
    private static final String URL = "http://localhost:8081/web/loginDone";

    /** Redirect URL carrying a query string with {@code ?}, {@code &} and {@code =}. */
    private static final String QUERY_URL = "http://x/done?a=1&b=2";

    /** Redirect URL carrying a port, a {@code :} inside the query and a percent-escape. */
    private static final String COLON_QUERY_URL = "https://h:8443/p?q=a:b&r=%20";

    /** Message of the {@link IllegalArgumentException} for a part appended after the redirect URL. */
    private static final String PARAMETER_AFTER_REDIRECT_MESSAGE =
            "parameter cannot be added after :onCompleteRedirectTo";

    private OAuthStateCodec codec;

    /** Creates the codec under test. */
    @BeforeEach
    void setUp() {
        codec = new OAuthStateCodec();
    }

    // ---------------------------------------------------------------------------------------------
    // Encoding
    // ---------------------------------------------------------------------------------------------

    /** Checks the values of both marker constants. */
    @Test
    @DisplayName("Marker constants hold the Mule state markers")
    void markerConstants() {
        assertThat(OAuthStateCodec.RESOURCE_OWNER_ID_MARKER).isEqualTo(":resourceOwnerId=");
        assertThat(OAuthStateCodec.ON_COMPLETE_REDIRECT_TO_MARKER).isEqualTo(":onCompleteRedirectTo=");
    }

    /** Checks the encoded form when only the redirect URL is given. */
    @Test
    @DisplayName("Redirect URL alone encodes as the redirect part")
    void encodeRedirectOnly() {
        assertThat(codec.encode(null, null, URL))
                .isEqualTo(":onCompleteRedirectTo=http://localhost:8081/web/loginDone");
    }

    /** Checks the encoded form for a resource owner and a redirect URL. */
    @Test
    @DisplayName("Resource owner precedes the redirect part")
    void encodeOwnerAndRedirect() {
        assertThat(codec.encode(null, "u1", URL))
                .isEqualTo(":resourceOwnerId=u1:onCompleteRedirectTo=http://localhost:8081/web/loginDone");
    }

    /** Checks the encoded form for an original state and a redirect URL. */
    @Test
    @DisplayName("Original state precedes the redirect part")
    void encodeStateAndRedirect() {
        assertThat(codec.encode("s", null, URL))
                .isEqualTo("s:onCompleteRedirectTo=http://localhost:8081/web/loginDone");
    }

    /** Checks the encoded form when all three parts are given. */
    @Test
    @DisplayName("All three parts encode in state, owner, redirect order")
    void encodeAllParts() {
        assertThat(codec.encode("s", "u1", URL))
                .isEqualTo("s:resourceOwnerId=u1:onCompleteRedirectTo=" + URL);
    }

    /** Checks the encoded form for an original state alone and for a resource owner alone. */
    @Test
    @DisplayName("Single state or owner part encodes without a redirect part")
    void encodeWithoutRedirect() {
        assertThat(codec.encode("s", null, null)).isEqualTo("s");
        assertThat(codec.encode(null, "u1", null)).isEqualTo(":resourceOwnerId=u1");
    }

    /** Checks that no parts encode as {@code null}, as {@code OAuthStateCodec#encode} documents. */
    @Test
    @DisplayName("No parts encode as null")
    void encodeNoParts() {
        assertThat(codec.encode(null, null, null)).isNull();
    }

    /** Checks that an empty redirect URL encodes as the bare marker and decodes as the empty string. */
    @Test
    @DisplayName("Empty redirect URL encodes as the bare marker")
    void encodeEmptyRedirect() {
        String encoded = codec.encode(null, null, "");

        assertThat(encoded).isEqualTo(":onCompleteRedirectTo=");
        assertThat(codec.decodeOnCompleteRedirectTo(encoded)).isEmpty();
        assertThat(codec.decodeOriginalState(encoded)).isNull();
    }

    /** Checks the exception for a part appended to an original state that already holds the redirect marker. */
    @Test
    @DisplayName("Part after an embedded redirect marker is rejected")
    void encodeRejectsPartAfterRedirect() {
        String stateWithRedirect = "a:onCompleteRedirectTo=x";

        assertThatThrownBy(() -> codec.encode(stateWithRedirect, "u1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PARAMETER_AFTER_REDIRECT_MESSAGE);
        assertThatThrownBy(() -> codec.encode(stateWithRedirect, null, URL))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PARAMETER_AFTER_REDIRECT_MESSAGE);
    }

    // ---------------------------------------------------------------------------------------------
    // Decoding
    // ---------------------------------------------------------------------------------------------

    /**
     * Supplies every combination of original state ({@code null}, {@code "s"}, {@code "abc-123"}), resource
     * owner ({@code null}, {@code "u1"}) and redirect URL ({@code null}, {@link #URL}, {@link #QUERY_URL},
     * {@link #COLON_QUERY_URL}): 24 cases.
     *
     * @return one {@link Arguments} of {@code (originalState, resourceOwnerId, onCompleteRedirectTo)} per case
     */
    private static Stream<Arguments> stateCombinations() {
        String[] originalStates = {null, "s", "abc-123"};
        String[] resourceOwnerIds = {null, "u1"};
        String[] redirectUrls = {null, URL, QUERY_URL, COLON_QUERY_URL};
        List<Arguments> combinations = new ArrayList<>();
        for (String originalState : originalStates) {
            for (String resourceOwnerId : resourceOwnerIds) {
                for (String redirectUrl : redirectUrls) {
                    combinations.add(Arguments.of(originalState, resourceOwnerId, redirectUrl));
                }
            }
        }
        return combinations.stream();
    }

    /**
     * Checks that each decoder returns the part given to {@link OAuthStateCodec#encode}, and {@code null} for
     * an absent part.
     *
     * @param originalState        original state passed to encode, or {@code null}
     * @param resourceOwnerId      resource owner passed to encode, or {@code null}
     * @param onCompleteRedirectTo redirect URL passed to encode, or {@code null}
     */
    @ParameterizedTest(name = "state={0}, owner={1}, url={2}")
    @DisplayName("Encoded parts decode to the values given")
    @MethodSource("stateCombinations")
    void roundTrip(String originalState, String resourceOwnerId, String onCompleteRedirectTo) {
        String encoded = codec.encode(originalState, resourceOwnerId, onCompleteRedirectTo);

        assertThat(codec.decodeResourceOwnerId(encoded)).isEqualTo(resourceOwnerId);
        assertThat(codec.decodeOnCompleteRedirectTo(encoded)).isEqualTo(onCompleteRedirectTo);
        assertThat(codec.decodeOriginalState(encoded)).isEqualTo(originalState);
    }

    /** Checks that a decoder returns {@code null} for a part the state does not hold. */
    @Test
    @DisplayName("Absent part decodes as null")
    void absentPartDecodesAsNull() {
        assertThat(codec.decodeResourceOwnerId(":onCompleteRedirectTo=" + URL)).isNull();
        assertThat(codec.decodeOnCompleteRedirectTo(":resourceOwnerId=u1")).isNull();
        assertThat(codec.decodeOnCompleteRedirectTo("plain")).isNull();
        assertThat(codec.decodeResourceOwnerId("plain")).isNull();
    }

    /** Checks that a state without markers decodes as itself. */
    @Test
    @DisplayName("Plain state decodes as itself")
    void plainStateDecodesAsItself() {
        assertThat(codec.decodeOriginalState("plain")).isEqualTo("plain");
    }

    /** Checks that all three decoders return {@code null} for a {@code null} state. */
    @Test
    @DisplayName("Null state decodes as null")
    void nullStateDecodesAsNull() {
        assertThat(codec.decodeOriginalState(null)).isNull();
        assertThat(codec.decodeResourceOwnerId(null)).isNull();
        assertThat(codec.decodeOnCompleteRedirectTo(null)).isNull();
    }

    /**
     * Checks that a state starting with a marker decodes to a {@code null} original state, as the
     * {@link OAuthStateCodec#decodeOriginalState} Javadoc documents.
     */
    @Test
    @DisplayName("Marker-led state has no original state")
    void markerLedStateHasNoOriginalState() {
        assertThat(codec.decodeOriginalState(":onCompleteRedirectTo=" + URL)).isNull();
        assertThat(codec.decodeOriginalState(":resourceOwnerId=u1:onCompleteRedirectTo=" + URL)).isNull();
    }

    /** Checks that the resource-owner value ends at the redirect marker, not at a {@code :} or {@code =}. */
    @Test
    @DisplayName("Resource owner ends at the redirect marker")
    void resourceOwnerEndsAtRedirectMarker() {
        String state = ":resourceOwnerId=u1:onCompleteRedirectTo=http://x/done?a=1&b=2";

        assertThat(codec.decodeResourceOwnerId(state)).isEqualTo("u1");
        assertThat(codec.decodeOnCompleteRedirectTo(state)).isEqualTo("http://x/done?a=1&b=2");
    }

    /** Checks the form-encoded wire value of an encoded state and its decoding after {@link URLDecoder}. */
    @Test
    @DisplayName("Encoded state survives URL encoding")
    void urlEncodingRoundTrip() {
        String encoded = codec.encode(null, "u1", QUERY_URL);
        String wire = URLEncoder.encode(encoded, StandardCharsets.UTF_8);

        assertThat(wire).doesNotContain(":", "?", "&", "=");

        String decoded = URLDecoder.decode(wire, StandardCharsets.UTF_8);

        assertThat(decoded).isEqualTo(encoded);
        assertThat(codec.decodeResourceOwnerId(decoded)).isEqualTo("u1");
        assertThat(codec.decodeOnCompleteRedirectTo(decoded)).isEqualTo(QUERY_URL);
    }
}
