package com.mulesoft.examples.salesforce_data_retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sfdc.*} Salesforce connection keys of {@code application.yml}: the user name,
 * password and security token of the original global element {@code sfdc:config} named
 * {@code Salesforce} [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:3],
 * under their original key names (D-015), plus the connected-app consumer key and secret and the
 * OAuth2 login URL (D-013). See D-012, D-013, D-015.
 *
 * <p>Every value comes from configuration: the committed {@code application.yml} sets
 * {@code https://login.salesforce.com} for {@code sfdc.login-url} and a marker value, replaced by
 * the user, for each of the five credentials (D-012). The record declares no default, and its
 * accessors return the bound values unchanged, with no trimming, decoding or substitution. The
 * OAuth2 username-password grant sends {@link #key()} as {@code client_id}, {@link #secret()} as
 * {@code client_secret}, {@link #username()} as {@code username}, and {@link #password()}
 * immediately followed by {@link #securityToken()} as {@code password} (D-013).
 *
 * <p>{@link #toString()} prints the user name and the login URL; the four credential values are
 * masked (D-281):
 *
 * <pre>{@code
 * new SalesforceProperties("user", "p+w&1", "T0k", "key", "secret",
 *         "https://login.salesforce.com").toString();
 * // SalesforceProperties[username=user, password=****, securityToken=****, key=****,
 * //         secret=****, loginUrl=https://login.salesforce.com]
 * }</pre>
 *
 * @param username      Salesforce user name, bound from {@code sfdc.username}
 * @param password      Salesforce password, bound from {@code sfdc.password}
 * @param securityToken Salesforce security token, bound from {@code sfdc.securityToken}; the
 *                      relaxed forms {@code sfdc.security-token} and {@code SFDC_SECURITYTOKEN}
 *                      bind to it as well
 * @param key           connected-app consumer key, bound from {@code sfdc.key}
 * @param secret        connected-app consumer secret, bound from {@code sfdc.secret}
 * @param loginUrl      base URL of the OAuth2 token endpoint, bound from
 *                      {@code sfdc.login-url}
 */
@ConfigurationProperties("sfdc")
public record SalesforceProperties(String username, String password, String securityToken,
                                   String key, String secret, String loginUrl) {

    /**
     * Returns the user name and login URL; credential values are masked.
     *
     * <p>The text has the form
     * {@code SalesforceProperties[username=<username>, password=<m>, securityToken=<m>, key=<m>,
     * secret=<m>, loginUrl=<loginUrl>]}, where each {@code <m>} is {@code ****} for a non-null,
     * non-empty value and {@code null} otherwise. A {@code null} user name or login URL prints as
     * {@code null}.
     *
     * @return the masked text form of these properties
     */
    @Override
    public String toString() {
        // Replaces the record's implicit toString(), which prints every component in clear (D-281).
        return "SalesforceProperties[username=" + username
                + ", password=" + mask(password)
                + ", securityToken=" + mask(securityToken)
                + ", key=" + mask(key)
                + ", secret=" + mask(secret)
                + ", loginUrl=" + loginUrl
                + "]";
    }

    /**
     * Returns {@code ****} for a non-null, non-empty value and {@code null} otherwise.
     *
     * @param value the credential value to mask; may be {@code null}
     * @return {@code ****}, or the text {@code null} for a {@code null} or empty value
     */
    private static String mask(String value) {
        return value == null || value.isEmpty() ? "null" : "****";
    }
}
