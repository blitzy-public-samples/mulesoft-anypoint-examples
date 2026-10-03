package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the Salesforce connection bound from the {@code sfdc.*} keys (D-012, D-013, D-015).
 *
 * <p>Source: the global element {@code sfdc:config} named {@code Salesforce}
 * [salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:4-6], whose
 * {@code username}, {@code password} and {@code securityToken} attributes read {@code ${sfdc.user}},
 * {@code ${sfdc.password}} and {@code ${sfdc.securityToken}}. Its {@code connection-pooling-profile}
 * has no component here.
 *
 * <p>The record is bound through its canonical constructor and declares no default value: each
 * component holds the value of its key in {@code application.yml} or an active profile file, and
 * {@code null} when the key is absent. The accessors return the bound values unchanged.
 *
 * <p>{@link #toString()} prints {@code user}, {@code key} and {@code loginUrl} as bound and
 * {@code ****} in place of {@code password}, {@code securityToken} and {@code secret} (D-292):
 *
 * <pre>{@code
 * new SalesforceProperties("u", "p", "t", "k", "s", "https://login.salesforce.com").toString();
 * // SalesforceProperties[user=u, password=****, securityToken=****, key=k, secret=****,
 * //         loginUrl=https://login.salesforce.com]
 * }</pre>
 *
 * @param user          Salesforce user name, bound from {@code sfdc.user}
 * @param password      Salesforce password, bound from {@code sfdc.password}
 * @param securityToken Salesforce security token, bound from {@code sfdc.securityToken}; the relaxed
 *                      forms {@code sfdc.security-token} and {@code SFDC_SECURITYTOKEN} bind to it as
 *                      well
 * @param key           connected-app consumer key, bound from {@code sfdc.key}
 * @param secret        connected-app consumer secret, bound from {@code sfdc.secret}
 * @param loginUrl      base URL of the OAuth2 token endpoint, bound from {@code sfdc.login-url}
 *                      ({@code SFDC_LOGINURL} as an environment variable)
 */
@ConfigurationProperties(prefix = "sfdc")
public record SalesforceProperties(
        String user,
        String password,
        String securityToken,
        String key,
        String secret,
        String loginUrl) {

    /** Text printed in place of {@code password}, {@code securityToken} and {@code secret}. */
    private static final String MASK = "****";

    /**
     * Returns the components in record form, with {@code ****} in place of the password, the security
     * token and the consumer secret, whatever their values, {@code null} included (D-292).
     *
     * <p>The text has the form
     * {@code SalesforceProperties[user=<user>, password=****, securityToken=****, key=<key>,
     * secret=****, loginUrl=<loginUrl>]}; a {@code null} user, key or login URL prints as {@code null}.
     *
     * @return the masked text form of these settings
     */
    @Override
    public String toString() {
        return "SalesforceProperties[user=" + user
                + ", password=" + MASK
                + ", securityToken=" + MASK
                + ", key=" + key
                + ", secret=" + MASK
                + ", loginUrl=" + loginUrl
                + "]";
    }
}
