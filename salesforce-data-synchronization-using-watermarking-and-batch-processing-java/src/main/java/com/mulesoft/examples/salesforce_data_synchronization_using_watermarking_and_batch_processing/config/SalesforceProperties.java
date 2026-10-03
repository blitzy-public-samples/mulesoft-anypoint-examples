package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Salesforce connection settings of the global element {@code sfdc:config name="Salesforce"}
 * [salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml:3],
 * bound from the {@code sfdc.*} keys of {@code application.yml} and used to obtain a Salesforce
 * session through the OAuth2 username-password grant (D-013).
 *
 * <p>{@code user}, {@code password} and {@code securityToken} bind the original keys
 * {@code sfdc.user}, {@code sfdc.password} and {@code sfdc.securityToken}. {@code key},
 * {@code secret} and {@code loginUrl} bind {@code sfdc.key}, {@code sfdc.secret} and
 * {@code sfdc.login-url}, the keys added for the password grant (D-015, D-013). Relaxed binding also
 * accepts {@code sfdc.security-token} and the environment variables {@code SFDC_USER},
 * {@code SFDC_PASSWORD}, {@code SFDC_SECURITYTOKEN}, {@code SFDC_KEY}, {@code SFDC_SECRET} and
 * {@code SFDC_LOGINURL}.
 *
 * <p>No component has a default value in code; a key that is absent binds {@code null} (D-012).
 * {@link #toString()} masks {@code password}, {@code securityToken} and {@code secret}.
 *
 * <p>Example: the token request of the password grant is sent to the login URL and carries the
 * password immediately followed by the security token (D-013).
 * <pre>{@code
 * String tokenUri = properties.loginUrl() + "/services/oauth2/token";
 * String grantPassword = properties.password() + properties.securityToken();
 * }</pre>
 *
 * @param user          Salesforce username, key {@code sfdc.user}
 * @param password      Salesforce password, key {@code sfdc.password}
 * @param securityToken Salesforce security token, appended to the password in the token request,
 *                      key {@code sfdc.securityToken}
 * @param key           consumer key of the connected app, key {@code sfdc.key}
 * @param secret        consumer secret of the connected app, key {@code sfdc.secret}
 * @param loginUrl      base URL of the Salesforce login host that serves
 *                      {@code /services/oauth2/token}, key {@code sfdc.login-url}
 */
@ConfigurationProperties(prefix = "sfdc")
public record SalesforceProperties(
        String user,
        String password,
        String securityToken,
        String key,
        String secret,
        String loginUrl) {

    /**
     * Returns the components with {@code password}, {@code securityToken} and {@code secret} masked,
     * in the form
     * <pre>{@code
     * SalesforceProperties[user=<user>, password=****, securityToken=****, key=<key>, secret=****, loginUrl=<loginUrl>]
     * }</pre>
     *
     * <p>The three masked components always render as {@code ****}, whether they are set or
     * {@code null}. {@code user}, {@code key} and {@code loginUrl} render as
     * {@link String#valueOf(Object)} renders them, so a {@code null} value renders as {@code null}.
     *
     * @return the masked text form of these properties
     */
    @Override
    public String toString() {
        return "SalesforceProperties[user=" + user
                + ", password=****, securityToken=****, key=" + key
                + ", secret=****, loginUrl=" + loginUrl + "]";
    }
}
