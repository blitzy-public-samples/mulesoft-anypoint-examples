package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config.SalesforceProperties;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import com.sforce.ws.transport.JdkHttpTransport;
import com.sforce.ws.transport.LimitingInputStream;
import com.sforce.ws.transport.LimitingOutputStream;
import com.sforce.ws.transport.Transport;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Owns the Salesforce session of this project and hands out one cached Partner API
 * {@link PartnerConnection}. Replaces the {@code sfdc:config} element of
 * {@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:4-6}.
 *
 * <p><b>Token request (D-013, D-015).</b> On the first {@link #connection()} call, and on the first
 * call after {@link #invalidate()}, the provider sends one OAuth 2.0 username-password grant:
 * <pre>
 * POST {sfdc.login-url}/services/oauth2/token
 * Content-Type: application/x-www-form-urlencoded
 *
 * grant_type=password&amp;client_id={sfdc.key}&amp;client_secret={sfdc.secret}
 *   &amp;username={sfdc.user}&amp;password={sfdc.password}{sfdc.securityToken}
 * </pre>
 * The password field is {@code sfdc.password} immediately followed by {@code sfdc.securityToken}, and
 * the whole body is form-encoded once: password {@code p+w&1} with token {@code T0k} is sent as
 * {@code password=p%2Bw%261T0k}. A {@code null} property value is sent as an empty value. The
 * {@code access_token} and {@code instance_url} members of the JSON answer are read; the answer body
 * is never logged.
 *
 * <p><b>Partner connection.</b> The connection is a {@code new PartnerConnection(config)} on a
 * {@link ConnectorConfig} with manual login, the access token as session id, and the service endpoint
 * {@code <instance_url>/services/Soap/u/65.0}. Building it sends nothing; the first SOAP request is
 * the caller's. The connection is cached until {@link #invalidate()} is called.
 *
 * <p><b>Failure classification of the token request (D-020, D-530).</b> No request is retried here.
 * <ul>
 *   <li>HTTP 400 or 401 → {@link UpstreamAuthenticationException};</li>
 *   <li>HTTP 429 → {@link UpstreamRateLimitException} carrying the {@code Retry-After} header value,
 *       or no value when the header is absent;</li>
 *   <li>a {@link ResourceAccessException} (connection refused, timeout), or a
 *       {@link RestClientException} whose direct cause is an {@link IOException} (a timeout while the
 *       status line is read) → {@link UpstreamUnavailableException};</li>
 *   <li>any other status or exception propagates unchanged.</li>
 * </ul>
 *
 * <p><b>SOAP response status (D-530).</b> Every SOAP request of the cached connection goes through a
 * {@link StatusCapturingTransport}, which records the HTTP status and {@code Retry-After} header of the
 * response on the calling thread. {@link #lastResponseStatus()} returns the value recorded for the
 * current thread and {@link #clearLastResponseStatus()} removes it.
 *
 * <p>The password, security token, consumer secret and access token are never logged.
 * {@link #connection()} and {@link #invalidate()} are {@code synchronized}; no network call happens
 * when the bean is created.
 *
 * <p>Usage:
 * <pre>{@code
 * PartnerConnection partner = sessionProvider.connection();
 * QueryResult result = partner.query("SELECT Email FROM Contact");
 * }</pre>
 */
@Component
public class SalesforceSessionProvider {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceSessionProvider.class);

    /** Path of the OAuth 2.0 token endpoint below {@code sfdc.login-url}. */
    static final String TOKEN_PATH = "/services/oauth2/token";

    /** Partner API SOAP path appended to {@code instance_url}; API version 65.0 of force-partner-api. */
    static final String SOAP_PATH = "/services/Soap/u/65.0";

    /** Name of the response header read for rate-limit answers. */
    static final String RETRY_AFTER = "Retry-After";

    private static final String ACCESS_TOKEN = "access_token";
    private static final String INSTANCE_URL = "instance_url";

    /** Parser of the token answer; its error messages carry no excerpt of the parsed body. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .build();

    private final SalesforceProperties properties;
    private final RestClient restClient;
    private final Consumer<ConnectorConfig> connectorCustomizer;

    /** Status of the latest SOAP response received on each thread (D-530). */
    private final ThreadLocal<ResponseStatus> lastStatus = new ThreadLocal<>();

    /** Cached connection; {@code null} before the first token request and after {@link #invalidate()}. */
    private PartnerConnection connection;

    /**
     * Creates the provider with a {@link JdkClientHttpRequestFactory} in its default settings (no
     * connect or read timeout) and no further {@link ConnectorConfig} settings.
     *
     * @param properties the bound {@code sfdc.*} properties
     */
    @Autowired
    public SalesforceSessionProvider(SalesforceProperties properties) {
        this(properties, new JdkClientHttpRequestFactory(), config -> { });
    }

    /**
     * Creates the provider with the given HTTP request factory for the token request and a customizer
     * applied to every {@link ConnectorConfig} after this class has set the session id, the service
     * endpoint and the transport factory (for example {@code setReadTimeout}).
     *
     * @param properties          the {@code sfdc.*} properties
     * @param requestFactory      request factory of the token {@link RestClient}
     * @param connectorCustomizer callback receiving each new {@link ConnectorConfig}
     */
    SalesforceSessionProvider(SalesforceProperties properties,
                              ClientHttpRequestFactory requestFactory,
                              Consumer<ConnectorConfig> connectorCustomizer) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.restClient = RestClient.builder()
                .requestFactory(Objects.requireNonNull(requestFactory, "requestFactory"))
                .build();
        this.connectorCustomizer = Objects.requireNonNull(connectorCustomizer, "connectorCustomizer");
    }

    /**
     * Returns the cached Partner API connection, requesting a new access token first when no connection
     * is cached.
     *
     * @return the cached {@link PartnerConnection}
     * @throws UpstreamAuthenticationException the token endpoint answered HTTP 400 or 401
     * @throws UpstreamRateLimitException      the token endpoint answered HTTP 429
     * @throws UpstreamUnavailableException    the token endpoint was unreachable or timed out
     * @throws IllegalStateException           {@code sfdc.login-url} is unset, or the token answer lacks
     *                                         {@code access_token} or {@code instance_url}
     */
    public synchronized PartnerConnection connection() {
        if (connection == null) {
            JsonNode token = requestToken();
            connection = openPartnerConnection(token.get(ACCESS_TOKEN).asText(), token.get(INSTANCE_URL).asText());
            LOG.info("Salesforce session established; Partner API endpoint {}",
                    connection.getConfig().getServiceEndpoint());
        }
        return connection;
    }

    /**
     * Drops the cached connection; the next {@link #connection()} call sends a new token request.
     */
    public synchronized void invalidate() {
        if (connection != null) {
            LOG.info("Salesforce session invalidated");
        }
        connection = null;
    }

    /**
     * Returns the HTTP status and {@code Retry-After} header of the latest SOAP response received on
     * the current thread (D-530).
     *
     * @return the recorded status, or empty when none was recorded since the last
     *         {@link #clearLastResponseStatus()}
     */
    Optional<ResponseStatus> lastResponseStatus() {
        return Optional.ofNullable(lastStatus.get());
    }

    /**
     * Removes the SOAP response status recorded for the current thread.
     */
    void clearLastResponseStatus() {
        lastStatus.remove();
    }

    /**
     * Sends the username-password token request and returns the parsed JSON answer, holding non-blank
     * {@code access_token} and {@code instance_url} text members.
     */
    private JsonNode requestToken() {
        String loginUrl = properties.loginUrl();
        if (loginUrl == null || loginUrl.isBlank()) {
            throw new IllegalStateException("sfdc.login-url must be set");
        }
        // Form fields in this order, form-encoded once by FormHttpMessageConverter (D-013).
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", nullToEmpty(properties.key()));
        form.add("client_secret", nullToEmpty(properties.secret()));
        form.add("username", nullToEmpty(properties.user()));
        form.add("password", nullToEmpty(properties.password()) + nullToEmpty(properties.securityToken()));

        byte[] body;
        try {
            body = restClient.post()
                    .uri(UriComponentsBuilder.fromUriString(loginUrl.strip()).path(TOKEN_PATH).build().toUri())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .body(byte[].class);
        } catch (HttpClientErrorException e) {
            throw classifyRejection(e);
        } catch (ResourceAccessException e) {
            LOG.warn("Salesforce token endpoint unreachable: {}", e.getClass().getSimpleName());
            throw new UpstreamUnavailableException("Salesforce token endpoint unreachable", e);
        } catch (RestClientResponseException e) {
            throw e;
        } catch (RestClientException e) {
            // A RestClientException whose direct cause is an IOException (for example a read timeout
            // on the status line) → UpstreamUnavailableException (D-020, D-530).
            if (e.getCause() instanceof IOException) {
                LOG.warn("Salesforce token endpoint unreachable: {}", e.getCause().getClass().getSimpleName());
                throw new UpstreamUnavailableException("Salesforce token endpoint unreachable", e);
            }
            throw e;
        }
        return parseTokenAnswer(body);
    }

    /**
     * Maps a 4xx answer of the token endpoint: 400 and 401 to {@link UpstreamAuthenticationException},
     * 429 to {@link UpstreamRateLimitException}; any other 4xx status is returned unchanged (D-020).
     */
    private static RuntimeException classifyRejection(HttpClientErrorException e) {
        HttpStatusCode statusCode = e.getStatusCode();
        int status = statusCode.value();
        if (status == 400 || status == 401) {
            LOG.warn("Salesforce token request rejected with HTTP {}", status);
            return new UpstreamAuthenticationException("Salesforce token request rejected with HTTP " + status, e);
        }
        if (status == 429) {
            HttpHeaders headers = e.getResponseHeaders();
            String retryAfter = headers == null ? null : headers.getFirst(RETRY_AFTER);
            LOG.warn("Salesforce token request rate-limited with HTTP 429, Retry-After {}",
                    retryAfter == null ? "absent" : retryAfter);
            String message = "Salesforce token request rate-limited with HTTP 429";
            return retryAfter == null
                    ? new UpstreamRateLimitException(message, e)
                    : new UpstreamRateLimitException(message, retryAfter, e);
        }
        return e;
    }

    /**
     * Parses the token answer and checks that {@code access_token} and {@code instance_url} are present
     * and non-blank. Parse errors carry no part of the body.
     */
    private static JsonNode parseTokenAnswer(byte[] body) {
        JsonNode root = null;
        if (body != null && body.length > 0) {
            try {
                root = JSON.readTree(body);
            } catch (IOException e) {
                throw new IllegalStateException("Salesforce token response is not valid JSON", e);
            }
        }
        requiredText(root, ACCESS_TOKEN);
        requiredText(root, INSTANCE_URL);
        return root;
    }

    /**
     * Returns the text of a required member of the token answer.
     *
     * @throws IllegalStateException the member is missing, not a JSON string, or blank
     */
    private static String requiredText(JsonNode root, String member) {
        JsonNode node = root == null ? null : root.get(member);
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalStateException("Salesforce token response lacks " + member);
        }
        return node.asText();
    }

    /**
     * Builds the {@link ConnectorConfig} and the {@link PartnerConnection} for an access token. The
     * constructor sends no request: manual login is set and the session id is already present.
     */
    private PartnerConnection openPartnerConnection(String accessToken, String instanceUrl) {
        ConnectorConfig config = new ConnectorConfig();
        config.setManualLogin(true);
        config.setSessionId(accessToken);
        config.setServiceEndpoint(stripTrailingSlash(instanceUrl) + SOAP_PATH);
        // Every SOAP request gets a fresh StatusCapturingTransport (D-530).
        config.setTransportFactory(() -> new StatusCapturingTransport(config, lastStatus));
        connectorCustomizer.accept(config);
        try {
            return new PartnerConnection(config);
        } catch (ConnectionException e) {
            if (hasTimeoutOrConnectCause(e)) {
                throw new UpstreamUnavailableException("Salesforce Partner API endpoint unreachable", e);
            }
            throw new IllegalStateException("Salesforce Partner connection could not be created: "
                    + e.getMessage(), e);
        }
    }

    /** Returns {@code value} without one trailing {@code /}. */
    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** Reports whether the cause chain holds a {@link SocketTimeoutException} or {@link ConnectException}. */
    private static boolean hasTimeoutOrConnectCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    /**
     * HTTP status and {@code Retry-After} header of one SOAP response (D-530).
     *
     * @param status     the HTTP status code
     * @param retryAfter the {@code Retry-After} header value as received, or {@code null} when absent
     */
    record ResponseStatus(int status, String retryAfter) {
    }

    /**
     * force-wsc {@link Transport} that sends each SOAP request over {@link HttpURLConnection} as
     * force-wsc 65.0.0's {@link JdkHttpTransport} does, and stores the HTTP status and
     * {@code Retry-After} header of each response in a {@link ThreadLocal} sink (D-530).
     *
     * <p>Requests: the connection is opened through {@link JdkHttpTransport#createConnection}, which
     * applies the {@link ConnectorConfig} proxy, headers, compression headers and timeouts; the method is
     * {@code POST}; chunked streaming with 4096-byte chunks is used when
     * {@link ConnectorConfig#useChunkedPost()} is set; the configured SSL context, request size limit and
     * GZIP request compression are applied. Responses: a status below 400 is successful and its input
     * stream is returned; otherwise the error stream, or an empty stream when there is none, is returned;
     * the response size limit and GZIP decoding ({@code Content-Encoding: gzip}) are applied. Message
     * handlers and trace output of {@link ConnectorConfig} are not applied.
     */
    static final class StatusCapturingTransport implements Transport {

        private static final int CHUNK_SIZE = 4096;

        private final ThreadLocal<ResponseStatus> sink;
        private ConnectorConfig config;
        private HttpURLConnection connection;
        private boolean successful;

        /**
         * Creates a transport for one request.
         *
         * @param config connector settings of the Partner connection
         * @param sink   per-thread holder receiving the status of the response
         */
        StatusCapturingTransport(ConnectorConfig config, ThreadLocal<ResponseStatus> sink) {
            this.config = Objects.requireNonNull(config, "config");
            this.sink = Objects.requireNonNull(sink, "sink");
        }

        /** Replaces the connector settings used by the next {@code connect} call. */
        @Override
        public void setConfig(ConnectorConfig config) {
            this.config = Objects.requireNonNull(config, "config");
        }

        /**
         * Opens a SOAP request with the headers {@code SOAPAction} (the quoted action, {@code ""} when
         * {@code null}), {@code Content-Type: text/xml; charset=UTF-8} and {@code Accept: text/xml}.
         */
        @Override
        public OutputStream connect(String url, String soapAction) throws IOException {
            HashMap<String, String> headers = new HashMap<>();
            headers.put("SOAPAction", "\"" + (soapAction == null ? "" : soapAction) + "\"");
            headers.put("Content-Type", "text/xml; charset=UTF-8");
            headers.put("Accept", "text/xml");
            return connect(url, headers, true);
        }

        /** Opens a request with the given headers and compression enabled. */
        @Override
        public OutputStream connect(String endpoint, HashMap<String, String> headers) throws IOException {
            return connect(endpoint, headers, true);
        }

        /** Opens a {@code POST} request with the given headers and returns its request body stream. */
        @Override
        public OutputStream connect(String endpoint, HashMap<String, String> headers, boolean enableCompression)
                throws IOException {
            HttpURLConnection http = JdkHttpTransport.createConnection(config, new URL(endpoint), headers,
                    enableCompression);
            SSLContext sslContext = config.getSslContext();
            if (sslContext != null && http instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(sslContext.getSocketFactory());
            }
            http.setRequestMethod("POST");
            http.setDoInput(true);
            http.setDoOutput(true);
            if (config.useChunkedPost()) {
                http.setChunkedStreamingMode(CHUNK_SIZE);
            }
            connection = http;
            successful = false;
            OutputStream out = http.getOutputStream();
            if (config.getMaxRequestSize() > 0) {
                out = new LimitingOutputStream(config.getMaxRequestSize(), out);
            }
            if (enableCompression && config.isCompression()) {
                out = new GZIPOutputStream(out);
            }
            return out;
        }

        /**
         * Reads the response status, stores {@code ResponseStatus(status, Retry-After)} in the sink and
         * returns the response body stream.
         *
         * @throws IOException           the status line could not be read (for example a read timeout)
         * @throws IllegalStateException no request was opened on this transport
         */
        @Override
        public InputStream getContent() throws IOException {
            HttpURLConnection http = connection;
            if (http == null) {
                throw new IllegalStateException("No request was opened on this transport");
            }
            int status = http.getResponseCode();
            sink.set(new ResponseStatus(status, http.getHeaderField(RETRY_AFTER)));
            successful = status < 400;
            InputStream in = successful ? http.getInputStream() : http.getErrorStream();
            if (in == null) {
                return new ByteArrayInputStream(new byte[0]);
            }
            if (config.getMaxResponseSize() > 0) {
                in = new LimitingInputStream(config.getMaxResponseSize(), in);
            }
            if ("gzip".equals(http.getHeaderField("Content-Encoding"))) {
                in = new GZIPInputStream(in);
            }
            return in;
        }

        /** Returns {@code true} when the status read by {@link #getContent()} was below 400. */
        @Override
        public boolean isSuccessful() {
            return successful;
        }
    }
}
