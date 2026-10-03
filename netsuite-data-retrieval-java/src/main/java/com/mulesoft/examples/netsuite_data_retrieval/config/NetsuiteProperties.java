package com.mulesoft.examples.netsuite_data_retrieval.config;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;

/**
 * NetSuite connection of {@code NetSuite__Login_Authentication}
 * [netsuite-data-retrieval/src/main/app/netsuite-api.xml:18], reached through NetSuite REST Web
 * Services with OAuth 2.0 client credentials (M2M) and bound from the {@code netsuite} keys of
 * {@code application.yml} (D-015, D-016). The original login keys {@code nets.email},
 * {@code nets.password}, {@code nets.account}, {@code nets.roleId} and {@code nets.applicationId}
 * have no counterpart here.
 *
 * <p>Each component binds the key of the same name in kebab case through the canonical
 * constructor. Values are bound as given: no file is opened, no URL is validated and the
 * credential placeholders of {@code application.yml} bind without error.
 *
 * <p>{@link #resolvedRestBaseUrl()} and {@link #resolvedTokenUri()} return the two NetSuite URLs,
 * each the configured value when one is set and otherwise derived from {@code netsuite.account}
 * (D-094):
 *
 * <pre>{@code
 * netsuite.account: 1234567_SB1, netsuite.rest-base-url and netsuite.oauth.token-uri empty
 *   resolvedRestBaseUrl() -> https://1234567-sb1.suitetalk.api.netsuite.com
 *   resolvedTokenUri()    -> https://1234567-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token
 *
 * netsuite.rest-base-url: http://localhost:1234/, netsuite.oauth.token-uri empty
 *   resolvedRestBaseUrl() -> http://localhost:1234
 *   resolvedTokenUri()    -> http://localhost:1234/services/rest/auth/oauth2/v1/token
 * }</pre>
 *
 * @param account         NetSuite account ID, key {@code netsuite.account}; a placeholder in
 *                        {@code application.yml}. Lower-cased with {@code _} replaced by {@code -},
 *                        it is the host label of the derived URLs (D-015, D-094)
 * @param oauth           OAuth 2.0 M2M client values, keys {@code netsuite.oauth.*}; an
 *                        {@link Oauth} with every component {@code null} when no such key is set
 * @param timeZone        zone in which REST's UTC datetimes and full-dates are rendered, key
 *                        {@code netsuite.time-zone}, read with {@link ZoneId#of(String)}; default
 *                        {@code America/Los_Angeles} (D-016)
 * @param restBaseUrl     base URL of the REST Web Services calls, key {@code netsuite.rest-base-url};
 *                        empty in {@code application.yml}, and {@code null} or blank selects the URL
 *                        derived from {@code account} (D-094)
 * @param connectTimeout  connect timeout of each NetSuite REST and token request, key
 *                        {@code netsuite.connect-timeout}; default {@code 30s} (D-095)
 * @param responseTimeout response timeout of each NetSuite REST and token request, key
 *                        {@code netsuite.response-timeout}; default {@code 600s} (D-095)
 */
@ConfigurationProperties("netsuite")
public record NetsuiteProperties(
        String account,
        @DefaultValue Oauth oauth,
        @DefaultValue("America/Los_Angeles") ZoneId timeZone,
        String restBaseUrl,
        @DefaultValue("30s") Duration connectTimeout,
        @DefaultValue("600s") Duration responseTimeout) {

    /** Scheme of the base URL derived from {@code netsuite.account}. */
    private static final String DERIVED_BASE_URL_PREFIX = "https://";

    /** Domain that follows the account host label in the derived base URL. */
    private static final String DERIVED_BASE_URL_SUFFIX = ".suitetalk.api.netsuite.com";

    /** Path of the NetSuite OAuth 2.0 token endpoint under the REST base URL (D-016). */
    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    /**
     * Returns the base URL of the NetSuite REST Web Services calls.
     *
     * <p>A non-blank {@code netsuite.rest-base-url} is returned with every trailing {@code /}
     * removed. Otherwise the URL is {@code https://<account>.suitetalk.api.netsuite.com}, where
     * {@code <account>} is {@code netsuite.account} lower-cased with {@link Locale#ROOT} and with each
     * {@code _} replaced by {@code -}; a {@code null} account counts as the empty string (D-094).
     * The method never throws.
     *
     * <pre>{@code
     * account "1234567_SB1", rest-base-url empty -> "https://1234567-sb1.suitetalk.api.netsuite.com"
     * rest-base-url "http://localhost:1234/"   -> "http://localhost:1234"
     * }</pre>
     *
     * @return the REST base URL, never {@code null} and never ending in {@code /}
     */
    public String resolvedRestBaseUrl() {
        if (StringUtils.hasText(restBaseUrl)) {
            return withoutTrailingSlashes(restBaseUrl);
        }
        String host = account == null ? "" : account.toLowerCase(Locale.ROOT).replace('_', '-');
        return DERIVED_BASE_URL_PREFIX + host + DERIVED_BASE_URL_SUFFIX;
    }

    /**
     * Returns the URI of the NetSuite OAuth 2.0 token endpoint.
     *
     * <p>A non-blank {@code netsuite.oauth.token-uri} is returned unchanged. Otherwise the URI is
     * {@link #resolvedRestBaseUrl()} followed by {@code /services/rest/auth/oauth2/v1/token}
     * (D-016, D-094). A {@code null} {@link #oauth()} counts as an unset token URI. The method never
     * throws.
     *
     * <pre>{@code
     * account "1234567_SB1", rest-base-url and token-uri empty
     *   -> "https://1234567-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token"
     * rest-base-url "http://localhost:1234/", token-uri empty
     *   -> "http://localhost:1234/services/rest/auth/oauth2/v1/token"
     * }</pre>
     *
     * @return the token endpoint URI, never {@code null}
     */
    public String resolvedTokenUri() {
        if (oauth != null && StringUtils.hasText(oauth.tokenUri())) {
            return oauth.tokenUri();
        }
        return resolvedRestBaseUrl() + TOKEN_PATH;
    }

    /**
     * Returns {@code value} with every trailing {@code /} removed.
     *
     * @param value a non-blank URL
     * @return the URL without trailing {@code /} characters
     */
    private static String withoutTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * OAuth 2.0 client credentials (M2M) values of the NetSuite integration, bound from the
     * {@code netsuite.oauth} keys (D-015, D-016). Each component is bound as given; the private key
     * file is not opened here.
     *
     * @param clientId       client ID of the NetSuite M2M integration record, key
     *                       {@code netsuite.oauth.client-id}; a placeholder in
     *                       {@code application.yml}
     * @param certificateId  certificate ID of the OAuth 2.0 client credentials mapping, the
     *                       {@code kid} of the client assertion, key
     *                       {@code netsuite.oauth.certificate-id}; a placeholder in
     *                       {@code application.yml}
     * @param privateKeyPath filesystem path of the PKCS#8 PEM private key of that certificate, key
     *                       {@code netsuite.oauth.private-key-path}; a placeholder in
     *                       {@code application.yml}
     * @param tokenUri       URI of the OAuth 2.0 token endpoint, key {@code netsuite.oauth.token-uri};
     *                       empty in {@code application.yml}, and {@code null} or blank selects the
     *                       URI derived in {@link NetsuiteProperties#resolvedTokenUri()} (D-094)
     */
    public record Oauth(String clientId, String certificateId, String privateKeyPath, String tokenUri) {
    }
}
