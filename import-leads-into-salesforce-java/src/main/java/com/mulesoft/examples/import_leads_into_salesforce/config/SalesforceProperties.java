package com.mulesoft.examples.import_leads_into_salesforce.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings of the Salesforce global element {@code sfdc:config} named {@code Salesforce}
 * [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:4-6], bound by
 * constructor binding from the {@code sfdc.*} keys of {@code application.yml}.
 *
 * <p>{@code user}, {@code password} and {@code securityToken} bind the original keys
 * {@code sfdc.user}, {@code sfdc.password} and {@code sfdc.securityToken} (D-015). {@code key},
 * {@code secret} and {@code loginUrl} bind the connected-app client credentials and the login host
 * of the OAuth2 username-password grant (D-013). The five credential keys hold placeholder values
 * in the committed {@code application.yml} (D-012), and {@code sfdc.login-url} defaults to
 * {@code https://login.salesforce.com} there.
 *
 * <p>The record declares no defaults and no validation: every value comes from the bound keys, and
 * a key that is absent binds {@code null}. Environment variables {@code SFDC_USER},
 * {@code SFDC_PASSWORD}, {@code SFDC_SECURITYTOKEN}, {@code SFDC_KEY}, {@code SFDC_SECRET} and
 * {@code SFDC_LOGINURL} override the keys through relaxed binding.
 *
 * @param user          {@code sfdc.user}: the Salesforce username
 * @param password      {@code sfdc.password}: the Salesforce password
 * @param securityToken {@code sfdc.securityToken}: the Salesforce security token
 * @param key           {@code sfdc.key}: the connected-app consumer key (D-013)
 * @param secret        {@code sfdc.secret}: the connected-app consumer secret (D-013)
 * @param loginUrl      {@code sfdc.login-url}: base URL of the Salesforce login host (D-013)
 */
@ConfigurationProperties(prefix = "sfdc")
public record SalesforceProperties(String user, String password, String securityToken,
                                   String key, String secret, String loginUrl) {
}
