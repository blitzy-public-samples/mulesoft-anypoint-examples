package com.mulesoft.examples.import_contacts_into_ms_dynamics.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for the Dataverse Web API v9.2 and its OAuth 2.0 client-credentials registration,
 * bound from the {@code dynamics.*} keys of {@code application.yml} (D-015, D-019). Timeouts are in
 * milliseconds.
 *
 * <p>Replaces the {@code dynamicscrm:config} global element
 * [import-contacts-into-ms-dynamics/src/main/app/import-contacts-into-ms-dynamics.xml:3]. The record is
 * bound through its canonical constructor and declares no defaults: every value comes from the bound keys,
 * and an absent key binds {@code null}, or {@code 0} for a timeout. The committed credential and service
 * URL values are {@code TODO} placeholders (D-012).
 *
 * @param serviceUrl      {@code dynamics.service-url}: the Dataverse organization root URL (D-019)
 * @param connectTimeout  {@code dynamics.connect-timeout}: the connection timeout of every Dataverse and
 *                        token request, in milliseconds
 * @param responseTimeout {@code dynamics.response-timeout}: the response timeout of every Dataverse and
 *                        token request, in milliseconds
 * @param oauth           the {@code dynamics.oauth.*} client-credentials settings
 */
@ConfigurationProperties("dynamics")
public record DynamicsProperties(String serviceUrl, long connectTimeout, long responseTimeout, Oauth oauth) {

    /**
     * Returns the service URL without trailing slashes, or null when the service URL is unset.
     *
     * <p>For example {@code <root>/} and {@code <root>//} both yield {@code <root>}; a value without a
     * trailing slash is returned unchanged.
     *
     * @return {@link #serviceUrl()} with every trailing {@code /} removed, or {@code null} when
     *     {@link #serviceUrl()} is {@code null}
     */
    public String baseUrl() {
        if (serviceUrl == null) {
            return null;
        }
        String url = serviceUrl;
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Microsoft Entra client-credentials settings: tenant, client id and secret, token endpoint.
     *
     * @param tenantId     {@code dynamics.oauth.tenant-id}: the Microsoft Entra tenant ID
     * @param clientId     {@code dynamics.oauth.client-id}: the client ID of the Entra app registration
     * @param clientSecret {@code dynamics.oauth.client-secret}: the client secret of the Entra app
     *                     registration
     * @param tokenUri     {@code dynamics.oauth.token-uri}: the token endpoint of the client-credentials
     *                     grant
     */
    public record Oauth(String tenantId, String clientId, String clientSecret, String tokenUri) {
    }
}
