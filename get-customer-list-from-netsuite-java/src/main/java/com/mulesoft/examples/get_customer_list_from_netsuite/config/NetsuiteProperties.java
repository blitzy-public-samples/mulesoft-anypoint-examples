package com.mulesoft.examples.get_customer_list_from_netsuite.config;

import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * NetSuite connection settings of the global element {@code Netsuite}
 * [get-customer-list-from-netsuite/src/main/app/get-customer-list-from-netsuite.xml:4], bound by
 * constructor binding from the {@code netsuite.*} keys of {@code application.yml} (D-015).
 *
 * <p>{@code netsuite.account} is the key of the original {@code account} attribute. The keys under
 * {@code netsuite.oauth} configure NetSuite REST Web Services access through OAuth 2.0 client
 * credentials (M2M) and take the place of the original {@code email}, {@code password} and
 * {@code roleId} attributes, which have no component (D-016).
 *
 * <p>Bound keys and their environment-variable forms under relaxed binding:
 * <ul>
 *   <li>{@code netsuite.account} &mdash; {@code NETSUITE_ACCOUNT} &mdash; {@link #account()}</li>
 *   <li>{@code netsuite.oauth.client-id} &mdash; {@code NETSUITE_OAUTH_CLIENTID} &mdash;
 *       {@link Oauth#clientId()}</li>
 *   <li>{@code netsuite.oauth.certificate-id} &mdash; {@code NETSUITE_OAUTH_CERTIFICATEID} &mdash;
 *       {@link Oauth#certificateId()}</li>
 *   <li>{@code netsuite.oauth.private-key-path} &mdash; {@code NETSUITE_OAUTH_PRIVATEKEYPATH} &mdash;
 *       {@link Oauth#privateKeyPath()}</li>
 * </ul>
 *
 * <p>No component is validated at binding: an absent key binds {@code null}, and an absent
 * {@code netsuite.oauth} block binds an {@link Oauth} whose three components are {@code null}. The
 * accessors {@link #restBaseUrl()} and {@link #tokenUri()} derive their values from
 * {@link #account()} on each call and perform no I/O.
 *
 * <p>Example:
 * <pre>{@code
 * NetsuiteProperties properties = new NetsuiteProperties("1234567_SB1",
 *         new NetsuiteProperties.Oauth("client-id", "certificate-id", "/keys/netsuite.pem"));
 * properties.restBaseUrl(); // https://1234567-sb1.suitetalk.api.netsuite.com
 * properties.tokenUri();    // https://1234567-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token
 * }</pre>
 *
 * @param account {@code netsuite.account}: the NetSuite account ID, for example {@code 1234567} for a
 *                production account or {@code 1234567_SB1} for a sandbox account
 * @param oauth   the {@code netsuite.oauth.*} settings of the OAuth 2.0 client credentials (M2M)
 *                integration; an {@link Oauth} with {@code null} components when the block is absent
 */
@ConfigurationProperties("netsuite")
public record NetsuiteProperties(String account, @DefaultValue Oauth oauth) {

    /** Scheme prefix of the account-specific REST Web Services domain. */
    private static final String REST_SCHEME = "https://";

    /** Domain suffix of the account-specific REST Web Services host (D-016). */
    private static final String REST_HOST_SUFFIX = ".suitetalk.api.netsuite.com";

    /** Path of the OAuth 2.0 token endpoint under the REST Web Services base URL (D-016). */
    private static final String TOKEN_PATH = "/services/rest/auth/oauth2/v1/token";

    /**
     * Returns the base URL of NetSuite REST Web Services for {@link #account()}:
     * {@code https://<host>.suitetalk.api.netsuite.com}, where {@code <host>} is the account ID with
     * leading and trailing whitespace removed, lower-cased in {@link Locale#ROOT} and with every
     * {@code _} replaced by {@code -} (D-016).
     *
     * <p>Examples: {@code 1234567} gives {@code https://1234567.suitetalk.api.netsuite.com};
     * {@code 1234567_SB1} gives {@code https://1234567-sb1.suitetalk.api.netsuite.com}. Any other
     * non-blank value, the committed placeholder included, is transformed the same way.
     *
     * @return the REST Web Services base URL, without a trailing {@code /}
     * @throws IllegalStateException if {@link #account()} is {@code null} or blank
     */
    public String restBaseUrl() {
        if (account == null || account.isBlank()) {
            throw new IllegalStateException("netsuite.account is not set");
        }
        return REST_SCHEME + account.trim().toLowerCase(Locale.ROOT).replace('_', '-') + REST_HOST_SUFFIX;
    }

    /**
     * Returns the OAuth 2.0 client credentials token endpoint for {@link #account()}:
     * {@link #restBaseUrl()} followed by {@code /services/rest/auth/oauth2/v1/token} (D-016).
     *
     * <p>Example: {@code 1234567_SB1} gives
     * {@code https://1234567-sb1.suitetalk.api.netsuite.com/services/rest/auth/oauth2/v1/token}.
     *
     * @return the token endpoint URI
     * @throws IllegalStateException if {@link #account()} is {@code null} or blank
     */
    public String tokenUri() {
        return restBaseUrl() + TOKEN_PATH;
    }

    /**
     * The {@code netsuite.oauth.*} settings of the OAuth 2.0 client credentials (M2M) integration with
     * {@code private_key_jwt} client authentication (D-016). The record holds the values only; it reads
     * no file and contacts no host.
     *
     * @param clientId       {@code netsuite.oauth.client-id}: the client ID of the NetSuite integration
     *                       record, sent as the issuer and subject of the client assertion
     * @param certificateId  {@code netsuite.oauth.certificate-id}: the certificate ID of the OAuth 2.0
     *                       client credentials (M2M) mapping, sent as the {@code kid} header of the
     *                       client assertion
     * @param privateKeyPath {@code netsuite.oauth.private-key-path}: the file-system path of the
     *                       unencrypted PKCS#8 PEM private key of that certificate, read by
     *                       {@code NetsuiteOAuth2Config}
     */
    public record Oauth(String clientId, String certificateId, String privateKeyPath) {
    }
}
