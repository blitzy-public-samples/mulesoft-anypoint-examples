package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
 * defaults. The constructor of each record rejects a missing, blank or {@code TODO} value and a malformed URL
 * with an {@link IllegalArgumentException} that names the key and omits the value (D-367).
 *
 * @param siteUrl {@code Sharepoint.SiteUrl}: the SharePoint Online site URL, read under the original key
 *                name, which relaxed binding matches to {@code sharepoint.site-url}
 * @param oauth   the {@code sharepoint.oauth.*} client-credentials settings of the Graph access token
 * @param graph   the {@code sharepoint.graph.*} settings of the Graph API requests
 */
@ConfigurationProperties(prefix = "sharepoint")
public record SharePointProperties(String siteUrl, Oauth oauth, Graph graph) {

    private static final String PLACEHOLDER = "TODO";

    private static final String NOT_HTTP_URL = " must be an absolute http or https URL with a host";

    private static final String NOT_TCP_PORT = " must carry a port from 1 to 65535";

    /**
     * Validates the bound site URL and the presence of the {@code sharepoint.oauth} and
     * {@code sharepoint.graph} groups (D-367).
     *
     * <p>Rejects a {@code Sharepoint.SiteUrl} that is missing, blank, {@code TODO}, not an absolute
     * {@code http} or {@code https} URL with a host, or that gives a port outside 1 to 65535, an absent
     * {@link Oauth} group and an absent {@link Graph} group.
     *
     * @throws IllegalArgumentException listing every violation, each naming its key, separated by
     *                                  {@code "; "}
     */
    public SharePointProperties {
        List<String> violations = new ArrayList<>();
        httpUrl(violations, "Sharepoint.SiteUrl", siteUrl);
        if (oauth == null) {
            violations.add("sharepoint.oauth.tenant-id, sharepoint.oauth.client-id, "
                    + "sharepoint.oauth.client-secret, sharepoint.oauth.token-uri, "
                    + "sharepoint.oauth.scope must be set");
        }
        if (graph == null) {
            violations.add("sharepoint.graph.base-url must be set");
        }
        rejectViolations(violations);
    }

    /**
     * Adds a violation naming {@code key}, without the value, to {@code violations} when {@code value} is
     * {@code null}, blank, or {@code TODO} in any letter case once stripped.
     *
     * @param violations the violations of the record under construction
     * @param key        the configuration key of {@code value}
     * @param value      the bound value
     * @return {@code true} when {@code value} is present
     */
    private static boolean present(List<String> violations, String key, String value) {
        if (value == null || value.isBlank()) {
            violations.add(key + " must be set");
            return false;
        }
        if (PLACEHOLDER.equalsIgnoreCase(value.strip())) {
            violations.add(key + " must be set to a value other than " + PLACEHOLDER);
            return false;
        }
        return true;
    }

    /**
     * Parses a present {@code value}, unmodified, with {@link URI#URI(String)} and adds a violation naming
     * {@code key}, without the value, to {@code violations} unless it is an absolute {@code http} or
     * {@code https} URL with a host and, when it gives a port, a port from 1 to 65535. A URL without a port,
     * or with an empty port after the colon, takes the default port of its scheme and is accepted. A value
     * that is not present gets the violation of {@link #present(List, String, String)}.
     *
     * @param violations the violations of the record under construction
     * @param key        the configuration key of {@code value}
     * @param value      the bound value
     * @return the parsed URL, or empty when {@code value} is not present or not such a URL
     */
    private static Optional<URI> httpUrl(List<String> violations, String key, String value) {
        if (!present(violations, key, value)) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            violations.add(key + NOT_HTTP_URL);
            return Optional.empty();
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || host == null || host.isEmpty()) {
            violations.add(key + NOT_HTTP_URL);
            return Optional.empty();
        }
        int port = uri.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            violations.add(key + NOT_TCP_PORT);
            return Optional.empty();
        }
        return Optional.of(uri);
    }

    /**
     * Throws an {@link IllegalArgumentException} whose message is {@code violations} joined with {@code "; "}
     * when {@code violations} is not empty.
     *
     * @param violations the violations of the record under construction
     */
    private static void rejectViolations(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", violations));
        }
    }

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

        /**
         * Validates the bound client-credentials settings (D-367).
         *
         * <p>Rejects a {@code sharepoint.oauth.tenant-id}, {@code sharepoint.oauth.client-id},
         * {@code sharepoint.oauth.client-secret} or {@code sharepoint.oauth.scope} that is missing, blank
         * or {@code TODO}, and a {@code sharepoint.oauth.token-uri} that is missing, blank, {@code TODO},
         * not an absolute {@code http} or {@code https} URL with a host, or that gives a port outside 1 to
         * 65535.
         *
         * @throws IllegalArgumentException listing every violation, each naming its key, separated by
         *                                  {@code "; "}
         */
        public Oauth {
            List<String> violations = new ArrayList<>();
            present(violations, "sharepoint.oauth.tenant-id", tenantId);
            present(violations, "sharepoint.oauth.client-id", clientId);
            present(violations, "sharepoint.oauth.client-secret", clientSecret);
            httpUrl(violations, "sharepoint.oauth.token-uri", tokenUri);
            present(violations, "sharepoint.oauth.scope", scope);
            rejectViolations(violations);
        }
    }

    /**
     * Microsoft Graph API settings of the site, folder and file requests (D-032).
     *
     * @param baseUrl {@code sharepoint.graph.base-url}: the Microsoft Graph API root that every request path
     *                is appended to
     */
    public record Graph(String baseUrl) {

        /**
         * Validates the bound Graph API root (D-367).
         *
         * <p>Rejects a {@code sharepoint.graph.base-url} that is missing, blank, {@code TODO}, not an
         * absolute {@code http} or {@code https} URL with a host, that gives a port outside 1 to 65535, or
         * that carries a query or a fragment.
         *
         * @throws IllegalArgumentException listing every violation, each naming the key, separated by
         *                                  {@code "; "}
         */
        public Graph {
            List<String> violations = new ArrayList<>();
            Optional<URI> url = httpUrl(violations, "sharepoint.graph.base-url", baseUrl);
            if (url.isPresent() && (url.get().getRawQuery() != null || url.get().getRawFragment() != null)) {
                violations.add("sharepoint.graph.base-url must not carry a query or fragment");
            }
            rejectViolations(violations);
        }
    }
}
