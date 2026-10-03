package com.mulesoft.examples.import_contacts_into_salesforce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Salesforce connection settings that replace the global element {@code sfdc:config} named
 * {@code Salesforce} [import-contacts-into-salesforce/src/main/app/contacts-to-SFDC.xml:4-6], bound
 * by constructor binding from the {@code sfdc.*} keys of {@code application.yml}.
 *
 * <p>Keys, their environment-variable forms under Spring Boot relaxed binding, and the components
 * they bind:
 * <ul>
 *   <li>{@code sfdc.user} ({@code SFDC_USER}): {@link #user()}, the element's {@code username}
 *       attribute (D-015);</li>
 *   <li>{@code sfdc.password} ({@code SFDC_PASSWORD}): {@link #password()}, the element's
 *       {@code password} attribute (D-015);</li>
 *   <li>{@code sfdc.securityToken} ({@code SFDC_SECURITYTOKEN}): {@link #securityToken()}, the
 *       element's {@code securityToken} attribute (D-015);</li>
 *   <li>{@code sfdc.key} ({@code SFDC_KEY}): {@link #key()}, the connected-app consumer key of the
 *       OAuth2 username-password grant (D-013);</li>
 *   <li>{@code sfdc.secret} ({@code SFDC_SECRET}): {@link #secret()}, the connected-app consumer
 *       secret of that grant (D-013);</li>
 *   <li>{@code sfdc.login-url} ({@code SFDC_LOGINURL}): {@link #loginUrl()}, the base URL of the
 *       token endpoint {@code <loginUrl>/services/oauth2/token} (D-013).</li>
 * </ul>
 *
 * <p>The record declares no default value and no validation: each component holds the bound value
 * as written, and an absent key binds {@code null}. The committed {@code application.yml} holds
 * placeholder values for the five credentials and {@code https://login.salesforce.com} for
 * {@code sfdc.login-url} (D-012). The element's {@code sfdc:connection-pooling-profile} has no
 * component (D-442). The record is registered by {@code @ConfigurationPropertiesScan} on the
 * application class.
 *
 * <p>{@code client/SalesforceSessionProvider} posts the token request with {@code client_id} =
 * {@link #key()}, {@code client_secret} = {@link #secret()}, {@code username} = {@link #user()} and
 * {@code password} = {@link #password()} immediately followed by {@link #securityToken()} (D-013).
 *
 * <p>{@link #toString()} writes {@code password}, {@code securityToken} and {@code secret} as
 * {@code ****} (D-443), for example:
 * <pre>{@code
 * SalesforceProperties[user=<user>, password=****, securityToken=****, key=<key>, secret=****,
 *                      loginUrl=https://login.salesforce.com]
 * }</pre>
 *
 * @param user          {@code sfdc.user}: Salesforce username, sent as {@code username} in the token
 *                      request
 * @param password      {@code sfdc.password}: Salesforce password, sent in the token request
 *                      immediately followed by {@code securityToken}
 * @param securityToken {@code sfdc.securityToken}: security token appended to the password in the
 *                      token request
 * @param key           {@code sfdc.key}: connected-app consumer key, sent as {@code client_id}
 * @param secret        {@code sfdc.secret}: connected-app consumer secret, sent as
 *                      {@code client_secret}
 * @param loginUrl      {@code sfdc.login-url}: base URL of the Salesforce login host that serves
 *                      {@code /services/oauth2/token}
 */
@ConfigurationProperties(prefix = "sfdc")
public record SalesforceProperties(String user, String password, String securityToken,
                                   String key, String secret, String loginUrl) {

    /**
     * Returns the record's text form with the values of {@code password}, {@code securityToken}
     * and {@code secret} written as {@code ****}, whatever they hold, {@code null} included
     * (D-443). {@code user}, {@code key} and {@code loginUrl} appear as
     * {@link String#valueOf(Object)} renders them, {@code null} as {@code null}.
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
