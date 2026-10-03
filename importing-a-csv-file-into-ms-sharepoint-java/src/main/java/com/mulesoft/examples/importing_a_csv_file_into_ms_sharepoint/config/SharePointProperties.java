package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Microsoft Graph connection settings of the SharePoint Online site, bound from the {@code sharepoint}
 * prefix of {@code application.yml}. Replaces the global element {@code sharepoint:online-connection-config}
 * {@code Microsoft_SharePoint_2013__Online_Connection}
 * [importing-a-csv-file-into-ms-sharepoint/src/main/app/importing-a-csv-file-into-ms-sharepoint.xml:4]
 * (D-032).
 *
 * <p>The original {@code Sharepoint.Username} and {@code Sharepoint.Password} keys have no component; the
 * Microsoft Entra application credentials of {@link Oauth} take their place (D-032). The records declare no
 * defaults: every value comes from the bound keys, and an absent key binds {@code null}.
 *
 * @param siteUrl {@code Sharepoint.SiteUrl}: the SharePoint Online site URL, read under the original key
 *                name, which relaxed binding matches to {@code sharepoint.site-url}
 * @param oauth   the {@code sharepoint.oauth.*} client-credentials settings of the Graph access token
 * @param graph   the {@code sharepoint.graph.*} settings of the Graph API requests
 */
@ConfigurationProperties(prefix = "sharepoint")
public record SharePointProperties(String siteUrl, Oauth oauth, Graph graph) {

    /**
     * Microsoft Entra client-credentials settings of the Graph access token request (D-032).
     *
     * @param tenantId     {@code sharepoint.oauth.tenant-id}: the Microsoft Entra tenant id
     * @param clientId     {@code sharepoint.oauth.client-id}: the client id of the Entra app registration
     * @param clientSecret {@code sharepoint.oauth.client-secret}: the client secret of the Entra app
     *                     registration
     * @param tokenUri     {@code sharepoint.oauth.token-uri}: the token endpoint of the Microsoft Entra
     *                     tenant
     * @param scope        {@code sharepoint.oauth.scope}: the scope requested with the client-credentials
     *                     grant
     */
    public record Oauth(String tenantId, String clientId, String clientSecret, String tokenUri, String scope) {
    }

    /**
     * Microsoft Graph API settings of the site, folder and file requests (D-032).
     *
     * @param baseUrl {@code sharepoint.graph.base-url}: the Microsoft Graph API root that every request path
     *                is appended to
     */
    public record Graph(String baseUrl) {
    }
}
