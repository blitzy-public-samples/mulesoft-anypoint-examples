package com.mulesoft.examples.authenticating_salesforce_using_oauth2.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code sfdc.*} keys of {@code application.yml}: the Salesforce connected-app credentials
 * and the OAuth web-server-flow endpoints and callback listener of {@code Salesforce__OAuth_}
 * [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:3-5,12] (D-012, D-014).
 *
 * <p>The record is bound through its canonical constructor and declares no defaults: every value comes
 * from the bound keys. An absent {@code String} key binds {@code null}, an absent
 * {@code sfdc.oauth.callback-port} binds {@code 0}, and {@link #oauth()} is {@code null} when no
 * {@code sfdc.oauth.*} key is set. The committed {@code application.yml} holds placeholder values for
 * {@code sfdc.key} and {@code sfdc.secret} (D-012).
 *
 * @param key    {@code sfdc.key}: the connected-app consumer key
 *               [salesforce-oauth.xml:3 {@code consumerKey}]
 * @param secret {@code sfdc.secret}: the connected-app consumer secret
 *               [salesforce-oauth.xml:3 {@code consumerSecret}]
 * @param oauth  the {@code sfdc.oauth.*} callback listener address and Salesforce authorize and token
 *               URLs
 */
@ConfigurationProperties("sfdc")
public record SalesforceOAuthProperties(String key, String secret, OAuth oauth) {

    /**
     * Callback listener address and Salesforce authorize/token URLs (D-011). The redirect URI is
     * {@code http://<callbackDomain>:<callbackPort>/<callbackPath>}, which is
     * {@code http://localhost:8081/oauth2callback} with the committed {@code application.yml}.
     *
     * @param callbackPort     {@code sfdc.oauth.callback-port}: the port of the extra callback listener
     *                         [salesforce-oauth.xml:4 {@code localPort}]
     * @param callbackPath     {@code sfdc.oauth.callback-path}: the callback path, without a leading
     *                         slash [salesforce-oauth.xml:4 {@code path}]
     * @param callbackDomain   {@code sfdc.oauth.callback-domain}: the host of the callback listener and
     *                         of the redirect URI [salesforce-oauth.xml:4 {@code domain}]
     * @param authorizationUrl {@code sfdc.oauth.authorization-url}: the Salesforce authorize endpoint
     *                         [salesforce-oauth.xml:12 {@code authorizationUrl}]
     * @param accessTokenUrl   {@code sfdc.oauth.access-token-url}: the Salesforce token endpoint
     *                         [salesforce-oauth.xml:12 {@code accessTokenUrl}]
     */
    public record OAuth(
            int callbackPort,
            String callbackPath,
            String callbackDomain,
            String authorizationUrl,
            String accessTokenUrl) {
    }
}
