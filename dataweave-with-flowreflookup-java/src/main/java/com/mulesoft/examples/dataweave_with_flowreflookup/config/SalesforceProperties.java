package com.mulesoft.examples.dataweave_with_flowreflookup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Salesforce connection settings of the global element {@code Salesforce__Basic_authentication}
 * [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:3], bound from the
 * {@code sfdc} keys of {@code application.yml} (D-012, D-013, D-015).
 *
 * <p>Keys, in Spring Boot relaxed binding, and the components they bind:
 * <ul>
 *   <li>{@code sfdc.user} ({@code SFDC_USER}): {@link #user()}, the {@code username} attribute of
 *       the original element;</li>
 *   <li>{@code sfdc.password} ({@code SFDC_PASSWORD}): {@link #password()}, the {@code password}
 *       attribute of the original element;</li>
 *   <li>{@code sfdc.securityToken} ({@code SFDC_SECURITYTOKEN}): {@link #securityToken()}, the
 *       {@code securityToken} attribute of the original element;</li>
 *   <li>{@code sfdc.key} ({@code SFDC_KEY}): {@link #key()}, the connected-app consumer key
 *       (D-013);</li>
 *   <li>{@code sfdc.secret} ({@code SFDC_SECRET}): {@link #secret()}, the connected-app consumer
 *       secret (D-013);</li>
 *   <li>{@code sfdc.login-url} ({@code SFDC_LOGINURL}): {@link #loginUrl()}, the base URL of the
 *       OAuth2 token endpoint.</li>
 * </ul>
 * Each value binds as written, an empty value included, and an absent key binds as {@code null}.
 * The record declares no default value; {@code application.yml} holds the defaults.
 *
 * <p>{@code client/SalesforceSessionProvider} posts the OAuth2 username-password grant to
 * {@code <loginUrl>/services/oauth2/token} with {@code client_id} = {@code key},
 * {@code client_secret} = {@code secret}, {@code username} = {@code user} and {@code password} =
 * {@code password} immediately followed by {@code securityToken} (D-013).
 *
 * <p>{@link #toString()} replaces the values of {@code password}, {@code securityToken} and
 * {@code secret} with {@code ****} (D-282). {@code equals} and {@code hashCode} are the record
 * defaults over all six components.
 *
 * <pre>{@code
 * SalesforceProperties sfdc =
 *         new SalesforceProperties("u", "p", "t", "k", "s", "https://login.salesforce.com");
 * sfdc.loginUrl(); // https://login.salesforce.com
 * sfdc.toString();
 * // SalesforceProperties[user=u, password=****, securityToken=****, key=k, secret=****,
 * //                      loginUrl=https://login.salesforce.com]
 * }</pre>
 *
 * @param user          Salesforce username, key {@code sfdc.user}
 * @param password      Salesforce password, key {@code sfdc.password}
 * @param securityToken Salesforce security token, key {@code sfdc.securityToken}
 * @param key           connected-app consumer key, key {@code sfdc.key}
 * @param secret        connected-app consumer secret, key {@code sfdc.secret}
 * @param loginUrl      base URL of the OAuth2 token endpoint, key {@code sfdc.login-url};
 *                      {@code https://login.salesforce.com} in {@code application.yml}
 */
@ConfigurationProperties(prefix = "sfdc")
public record SalesforceProperties(String user, String password, String securityToken,
                                   String key, String secret, String loginUrl) {

    /**
     * Returns the default record text form with the values of {@code password},
     * {@code securityToken} and {@code secret} replaced by {@code ****}, whatever those values are,
     * {@code null} included (D-282). {@code user}, {@code key} and {@code loginUrl} appear as
     * {@link String#valueOf(Object)} renders them.
     *
     * @return {@code SalesforceProperties[user=<user>, password=****, securityToken=****,
     *         key=<key>, secret=****, loginUrl=<loginUrl>]}
     */
    @Override
    public String toString() {
        return "SalesforceProperties[user=" + user
                + ", password=****"
                + ", securityToken=****"
                + ", key=" + key
                + ", secret=****"
                + ", loginUrl=" + loginUrl
                + "]";
    }
}
