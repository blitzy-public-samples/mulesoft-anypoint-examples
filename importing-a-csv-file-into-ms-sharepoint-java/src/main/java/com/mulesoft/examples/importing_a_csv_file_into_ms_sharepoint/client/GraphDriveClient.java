package com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mulesoft.examples.importing_a_csv_file_into_ms_sharepoint.config.SharePointProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriUtils;
import reactor.core.publisher.Mono;

/**
 * Calls Microsoft Graph for the SharePoint site named by {@code Sharepoint.SiteUrl}: resolves the site id,
 * creates a folder in the site's default document library when it is absent, and uploads file content with
 * replace semantics (D-032). Replaces the connector behind the global element
 * {@code sharepoint:online-connection-config} {@code Microsoft_SharePoint_2013__Online_Connection}
 * [importing-a-csv-file-into-ms-sharepoint/src/main/app/importing-a-csv-file-into-ms-sharepoint.xml:4] and its
 * operations {@code sharepoint:folder-create} (:10) and {@code sharepoint:file-add overwrite="true"} (:12).
 *
 * <p>The site's default document library {@code Shared Documents} is the Graph drive of the site, whose root
 * folder is {@code /sites/{site-id}/drive/root}. With {@code {base}} standing for {@code sharepoint.graph.base-url}
 * without trailing {@code /}, the requests are:
 * <ul>
 *   <li>{@link #siteId()}: {@code GET {base}/sites/{hostname}}, or
 *       {@code GET {base}/sites/{hostname}:/{server-relative-path}} when the site URL has a path; sent once per
 *       instance, before the first folder or content request;</li>
 *   <li>{@link #getOrCreateFolder(String)}: {@code GET {base}/sites/{site-id}/drive/root:/{name}}, and after a
 *       {@code 404} answer {@code POST {base}/sites/{site-id}/drive/root/children} with the JSON body
 *       {@code {"name":"<name>","folder":{}}};</li>
 *   <li>{@link #putContent(String, byte[])}:
 *       {@code PUT {base}/sites/{site-id}/drive/root:/{path}:/content?@microsoft.graph.conflictBehavior=replace}
 *       with the file bytes as the body.</li>
 * </ul>
 *
 * <p>Every request URI is an absolute {@link URI} assembled from percent-encoded parts and passed unchanged to
 * the {@code graphWebClient} bean, whose exchange filter adds the client-credentials bearer token (D-032); the
 * base URL of that {@code WebClient} is not applied. Each request is sent exactly once and awaited with
 * {@code block()}, with no time limit of its own and no error mapping. A non-2xx answer reaches the
 * caller as the {@link WebClientResponseException} subclass of its status, a transport failure as a
 * {@link org.springframework.web.reactive.function.client.WebClientRequestException}.
 *
 * <p>The cached site id is the only mutable state, and instances are safe for concurrent use. Concurrent first
 * calls may each send the site request; the first id stored is kept and returned to all of them.
 *
 * <p>Usage:
 * <pre>{@code
 * client.getOrCreateFolder("my_folder");                   // folder under Shared Documents
 * client.putContent("my_folder/contacts.csv", fileBytes);  // file created (201) or replaced (200)
 * }</pre>
 */
@Component
public class GraphDriveClient {

    /** Logs the method and URI of every Graph request at DEBUG. */
    private static final Logger log = LoggerFactory.getLogger(GraphDriveClient.class);

    /** Builds the JSON body of the folder creation request. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Query string of the content upload request: an existing file is replaced (xml:12). */
    private static final String REPLACE_QUERY = "?@microsoft.graph.conflictBehavior=replace";

    /** Sends every Graph request and adds the client-credentials bearer token. */
    private final WebClient graphWebClient;

    /** Supplies {@code Sharepoint.SiteUrl} and {@code sharepoint.graph.base-url}. */
    private final SharePointProperties properties;

    /** The site id resolved by the first successful {@link #siteId()} call, {@code null} before. */
    private final AtomicReference<String> siteIdCache = new AtomicReference<>();

    /**
     * Creates the client over the authenticated Graph {@code WebClient} and the bound SharePoint settings.
     * Sends no request.
     *
     * @param graphWebClient the Microsoft Graph {@code WebClient} defined by {@code config.GraphOAuth2Config}
     * @param properties     the bound settings: {@code Sharepoint.SiteUrl} and {@code sharepoint.graph.base-url}
     */
    public GraphDriveClient(@Qualifier("graphWebClient") WebClient graphWebClient, SharePointProperties properties) {
        this.graphWebClient = graphWebClient;
        this.properties = properties;
    }

    /**
     * Returns the Graph id of the site named by {@code Sharepoint.SiteUrl}, for example
     * {@code contoso.sharepoint.com,1111-...,2222-...}.
     *
     * <p>The first successful call sends {@code GET {base}/sites/{hostname}} for a site URL without a path, or
     * {@code GET {base}/sites/{hostname}:/{server-relative-path}} otherwise, and caches the {@code id} member of
     * the answer; later calls return the cached id and send nothing. The path is the decoded path of the site
     * URL without trailing {@code /}, re-encoded segment by segment; the port and the query of the site URL are
     * ignored. {@code https://contoso.sharepoint.com/sites/team/} gives
     * {@code {base}/sites/contoso.sharepoint.com:/sites/team}. A failed resolution caches nothing.
     *
     * @return the cached or newly resolved site id
     * @throws IllegalStateException       when {@code Sharepoint.SiteUrl} is not set, is not a valid URI or has no
     *                                     host, when {@code sharepoint.graph.base-url} is not set, or when the
     *                                     answer carries no {@code id}
     * @throws WebClientResponseException  when Graph answers the site request with a non-2xx status
     */
    public String siteId() {
        String cached = siteIdCache.get();
        if (cached != null) {
            return cached;
        }
        String resolved = resolveSiteId();
        siteIdCache.compareAndSet(null, resolved);
        return siteIdCache.get();
    }

    /**
     * Makes sure the folder {@code name} exists in the root of the site's default document library, the
     * {@code Shared Documents} folder of {@code sharepoint:folder-create} (xml:10).
     *
     * <p>Sends {@code GET {base}/sites/{site-id}/drive/root:/{name}}. A 2xx answer ends the call. A {@code 404}
     * answer is followed by {@code POST {base}/sites/{site-id}/drive/root/children} with
     * {@code Content-Type: application/json} and the body {@code {"name":"<name>","folder":{}}}, whose
     * {@code name} is {@code name} without leading and trailing {@code /}; Graph answers {@code 201} with the
     * created folder. The site id is resolved first when it is not cached yet.
     *
     * @param name the folder name relative to the library root, for example {@code my_folder}
     * @throws IllegalArgumentException   when {@code name} is {@code null}, empty or only {@code /} characters;
     *                                    no request is sent
     * @throws IllegalStateException      when the site id or the base URL cannot be resolved
     * @throws WebClientResponseException when the folder request answers a status other than 2xx or
     *                                    {@code 404}, or the creation request answers a non-2xx status
     */
    public void getOrCreateFolder(String name) {
        String folder = encodePath(name);
        String drive = driveUri();
        try {
            execute(HttpMethod.GET, URI.create(drive + "/root:/" + folder),
                    request -> request.retrieve().toBodilessEntity());
        } catch (WebClientResponseException.NotFound absent) {
            byte[] body = folderCreationBody(stripSlashes(name));
            execute(HttpMethod.POST, URI.create(drive + "/root/children"),
                    request -> request.contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(body)
                            .retrieve()
                            .toBodilessEntity());
        }
    }

    /**
     * Writes {@code content} to the file {@code path} of the site's default document library, replacing an
     * existing file, as {@code sharepoint:file-add overwrite="true"} (xml:12) does.
     *
     * <p>Sends
     * {@code PUT {base}/sites/{site-id}/drive/root:/{path}:/content?@microsoft.graph.conflictBehavior=replace}
     * with {@code Content-Type: application/octet-stream} and exactly the bytes of {@code content} as the body.
     * Graph answers {@code 201} for a created file and {@code 200} for a replaced one; both end the call. The
     * site id is resolved first when it is not cached yet.
     *
     * @param path    the file path relative to the library root, for example {@code my_folder/contacts.csv}
     * @param content the file bytes, uploaded unmodified
     * @throws IllegalArgumentException   when {@code content} is {@code null}, or {@code path} is {@code null},
     *                                    empty or only {@code /} characters; no request is sent
     * @throws IllegalStateException      when the site id or the base URL cannot be resolved
     * @throws WebClientResponseException when Graph answers the upload with a non-2xx status
     */
    public void putContent(String path, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("content must not be null");
        }
        String item = encodePath(path);
        URI uri = URI.create(driveUri() + "/root:/" + item + ":/content" + REPLACE_QUERY);
        execute(HttpMethod.PUT, uri,
                request -> request.contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .bodyValue(content)
                        .retrieve()
                        .toBodilessEntity());
    }

    /**
     * Sends the site request of {@link #siteId()} and returns the {@code id} member of the answer.
     *
     * @return the site id, never empty
     * @throws IllegalStateException when the answer is empty, or its {@code id} is absent, {@code null} or blank
     */
    private String resolveSiteId() {
        URI uri = siteRequestUri();
        JsonNode site = execute(HttpMethod.GET, uri, request -> request.retrieve().bodyToMono(JsonNode.class));
        JsonNode id = site == null ? null : site.path("id");
        if (id == null || id.isMissingNode() || id.isNull() || id.asText().isBlank()) {
            throw new IllegalStateException("Microsoft Graph returned no site id for " + uri);
        }
        return id.asText();
    }

    /**
     * Builds the site request URI from {@code Sharepoint.SiteUrl}: {@code {base}/sites/{hostname}} when its
     * decoded path, without trailing {@code /}, is empty, otherwise
     * {@code {base}/sites/{hostname}:/{encoded path}}.
     *
     * @return the absolute site request URI
     * @throws IllegalStateException when {@code Sharepoint.SiteUrl} is not set, is not a valid URI or has no host
     */
    private URI siteRequestUri() {
        String siteUrl = properties.siteUrl();
        if (siteUrl == null || siteUrl.isBlank()) {
            throw new IllegalStateException("Sharepoint.SiteUrl is not set");
        }
        URI site;
        try {
            site = URI.create(siteUrl);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("Sharepoint.SiteUrl is not a valid URI", invalid);
        }
        String host = site.getHost();
        if (host == null) {
            throw new IllegalStateException("Sharepoint.SiteUrl must be an absolute URL with a host");
        }
        String path = stripTrailingSlashes(site.getPath() == null ? "" : site.getPath());
        String sites = baseUrl() + "/sites/" + host;
        return URI.create(path.isEmpty() ? sites : sites + ":/" + encodePath(path));
    }

    /**
     * Returns {@code {base}/sites/{site-id}/drive}, the default document library of the site, with the site id
     * encoded as one path segment. Resolves the site id when it is not cached yet.
     *
     * @return the drive URI prefix, without trailing {@code /}
     */
    private String driveUri() {
        String site = UriUtils.encodePathSegment(siteId(), StandardCharsets.UTF_8);
        return baseUrl() + "/sites/" + site + "/drive";
    }

    /**
     * Returns {@code sharepoint.graph.base-url} without trailing {@code /} characters.
     *
     * @return the Graph API root, for example {@code https://graph.microsoft.com/v1.0}
     * @throws IllegalStateException when the {@code sharepoint.graph} group or its base URL is absent or blank
     */
    private String baseUrl() {
        SharePointProperties.Graph graph = properties.graph();
        String baseUrl = graph == null ? null : graph.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("sharepoint.graph.base-url is not set");
        }
        return stripTrailingSlashes(baseUrl);
    }

    /**
     * Percent-encodes a drive path segment by segment: leading and trailing {@code /} are removed, every
     * segment between {@code /} separators is encoded with
     * {@link UriUtils#encodePathSegment(String, java.nio.charset.Charset)} in UTF-8, and the segments are joined
     * with {@code /}. A space becomes {@code %20}; {@code :}, {@code ,} and {@code @} stay literal;
     * {@code #}, {@code ?} and {@code %} are percent-encoded.
     *
     * @param path the path, for example {@code my folder/contacts.csv}
     * @return the encoded path, for example {@code my%20folder/contacts.csv}
     * @throws IllegalArgumentException when {@code path} is {@code null}, empty or only {@code /} characters
     */
    private static String encodePath(String path) {
        String stripped = stripSlashes(path);
        if (stripped.isEmpty()) {
            throw new IllegalArgumentException("Drive path must not be null, empty or only '/' characters");
        }
        return Arrays.stream(stripped.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));
    }

    /**
     * Removes leading and trailing {@code /} characters.
     *
     * @param value the value, or {@code null}
     * @return {@code value} without leading and trailing {@code /}, or the empty string for {@code null}
     */
    private static String stripSlashes(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        while (start < value.length() && value.charAt(start) == '/') {
            start++;
        }
        return stripTrailingSlashes(value.substring(start));
    }

    /**
     * Removes trailing {@code /} characters.
     *
     * @param value the value
     * @return {@code value} without trailing {@code /}
     */
    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    /**
     * Serialises the folder creation body {@code {"name":"<name>","folder":{}}}: members in that order, no
     * whitespace, {@code name} JSON-escaped.
     *
     * @param name the folder name
     * @return the UTF-8 JSON bytes
     * @throws IllegalStateException when Jackson cannot serialise the body
     */
    private static byte[] folderCreationBody(String name) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("name", name);
        body.putObject("folder");
        try {
            return MAPPER.writeValueAsBytes(body);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialise the folder creation body", failure);
        }
    }

    /**
     * Sends one Graph request through {@code graphWebClient} and waits for its result. Logs the method and the
     * URI at DEBUG, never a header or a body. Errors propagate unchanged.
     *
     * @param method   the HTTP method
     * @param uri      the absolute request URI, sent as given
     * @param exchange completes the request specification and returns the response publisher
     * @param <T>      the result type
     * @return the result, or {@code null} for an empty response publisher
     */
    private <T> T execute(HttpMethod method, URI uri, Function<WebClient.RequestBodySpec, Mono<T>> exchange) {
        log.debug("Microsoft Graph request {} {}", method, uri);
        return exchange.apply(graphWebClient.method(method).uri(uri)).block();
    }
}
