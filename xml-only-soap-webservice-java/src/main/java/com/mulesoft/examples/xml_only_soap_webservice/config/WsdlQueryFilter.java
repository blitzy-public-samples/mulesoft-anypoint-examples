package com.mulesoft.examples.xml_only_soap_webservice.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Serves the WSDL and XSD documents of the three SOAP services at {@code <address>?wsdl} and
 * {@code <address>?xsd=<key>}, the URLs the {@code cxf:proxy-service} elements published
 * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:21;
 * xml-only-soap-webservice/src/main/app/mocks.xml:6; xml-only-soap-webservice/src/main/app/mocks.xml:47]
 * (D-028, D-069, D-548).
 *
 * <p>Service paths and their WSDL classpath resources:
 * <ul>
 *   <li>{@code /AdmissionService} → {@code service/AdmissionService.wsdl};</li>
 *   <li>{@code /PatientService} → {@code service/PatientService.wsdl};</li>
 *   <li>{@code /EHRService} → {@code service/EHRService.wsdl}.</li>
 * </ul>
 * The paths are the literal root paths the Spring WS {@code UriEndpointMapping} maps (D-087); the
 * {@code listener.http-listener-configuration.*-path} keys are not read (D-140, D-548).
 *
 * <p>{@code ?xsd=} keys, the same two for every service path, and their classpath resources:
 * <ul>
 *   <li>{@code xsd/SOA-Message-1.0.xsd} → {@code service/xsd/SOA-Message-1.0.xsd};</li>
 *   <li>{@code SOA-Model-1.0.xsd} → {@code service/xsd/SOA-Model-1.0.xsd}.</li>
 * </ul>
 *
 * <p>The served text is the committed file with plain text replacements only, where {@code <base>} is
 * {@code http://<host><context path><service path>} for the request (D-069, D-548):
 * <ul>
 *   <li>WSDL: {@code location="http://www.mule-health.com"} becomes {@code location="<base>"}; the import
 *       {@code schemaLocation="service/xsd/SOA-Message-1.0.xsd"} (AdmissionService) and
 *       {@code schemaLocation="xsd/SOA-Message-1.0.xsd"} (PatientService, EHRService) become
 *       {@code schemaLocation="<base>?xsd=xsd/SOA-Message-1.0.xsd"};</li>
 *   <li>{@code SOA-Message-1.0.xsd}: {@code schemaLocation="SOA-Model-1.0.xsd"} becomes
 *       {@code schemaLocation="<base>?xsd=SOA-Model-1.0.xsd"};</li>
 *   <li>{@code SOA-Model-1.0.xsd}: served unchanged.</li>
 * </ul>
 * {@code <host>} is the {@code Host} request header or, when that header is absent or blank,
 * {@code <server name>:<server port>}, the server name being the local address when it is blank, and an IPv6
 * address in brackets. The characters {@code &}, {@code <},
 * {@code >}, {@code "} and {@code '} of {@code <base>} are written as XML character references (D-548). The
 * classpath files themselves are never written.
 *
 * <p>Requests and answers:
 * <ul>
 *   <li>{@code GET <service path>?wsdl}, the query in any letter case: 200 with the rewritten WSDL;</li>
 *   <li>{@code GET <service path>?xsd=<key>}, the key URL-decoded as UTF-8: 200 with the schema of a known key;
 *       404 with an empty body for any other key, a malformed percent-encoding included;</li>
 *   <li>every other method, path or query, no query included: the rest of the filter chain, where the
 *       {@code MessageDispatcherServlet} handles the request (a {@code GET} answers 405).</li>
 * </ul>
 * A 200 answer carries {@code Content-Type: text/xml; charset=UTF-8} and a {@code Content-Length} of the UTF-8
 * bytes of the document.
 *
 * <p>Example, for {@code GET /AdmissionService?wsdl} with {@code Host: localhost:8081}, served lines 5 and 34 read
 * <pre>{@code
 * <xsd:import namespace="http://www.mule-health.com/SOA/message/1.0" schemaLocation="http://localhost:8081/AdmissionService?xsd=xsd/SOA-Message-1.0.xsd" />
 * <soap:address location="http://localhost:8081/AdmissionService" />
 * }</pre>
 * and every other byte equals {@code service/AdmissionService.wsdl}.
 *
 * <p>Spring Boot registers the filter bean for every request path, ahead of the servlets. The five documents are
 * read once, at construction, and held unmodifiable; the filter keeps no per-request state and is safe for
 * concurrent requests.
 */
@Component
public final class WsdlQueryFilter extends OncePerRequestFilter {

    /** Logger for the served documents and the unknown {@code ?xsd=} keys. */
    private static final Logger LOG = LoggerFactory.getLogger(WsdlQueryFilter.class);

    /** The only request method this filter answers. */
    private static final String GET = "GET";

    /** Query that selects a service's WSDL, compared in any letter case. */
    private static final String WSDL_QUERY = "wsdl";

    /** Query prefix that selects a schema by the key that follows it. */
    private static final String XSD_QUERY_PREFIX = "xsd=";

    /** Name of the request header that names the host of the served addresses. */
    private static final String HOST_HEADER = "Host";

    /** Media type of every served document. */
    private static final String CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** {@code ?xsd=} key of the message schema. */
    private static final String MESSAGE_XSD_KEY = "xsd/SOA-Message-1.0.xsd";

    /** {@code ?xsd=} key of the model schema. */
    private static final String MODEL_XSD_KEY = "SOA-Model-1.0.xsd";

    /** Service path, relative to the context path, to the classpath resource of its WSDL. */
    private static final Map<String, String> WSDL_RESOURCES = Map.of(
            "/AdmissionService", "service/AdmissionService.wsdl",
            "/PatientService", "service/PatientService.wsdl",
            "/EHRService", "service/EHRService.wsdl");

    /** {@code ?xsd=} key to the classpath resource of its schema, the same for every service path. */
    private static final Map<String, String> XSD_RESOURCES = Map.of(
            MESSAGE_XSD_KEY, "service/xsd/SOA-Message-1.0.xsd",
            MODEL_XSD_KEY, "service/xsd/SOA-Model-1.0.xsd");

    /** The {@code soap:address} attribute of each committed WSDL ({@code AdmissionService.wsdl:34}, others :53). */
    private static final String COMMITTED_ADDRESS = "location=\"http://www.mule-health.com\"";

    /** The classpath-root message schema import of {@code AdmissionService.wsdl:5} (D-069). */
    private static final String ADMISSION_MESSAGE_IMPORT = "schemaLocation=\"service/xsd/SOA-Message-1.0.xsd\"";

    /** The file-relative message schema import of {@code PatientService.wsdl:5} and {@code EHRService.wsdl:5}. */
    private static final String MOCK_MESSAGE_IMPORT = "schemaLocation=\"xsd/SOA-Message-1.0.xsd\"";

    /** The model schema import of {@code SOA-Message-1.0.xsd:16-17}. */
    private static final String MODEL_IMPORT = "schemaLocation=\"SOA-Model-1.0.xsd\"";

    /** Classpath resource path to the committed document text, decoded as UTF-8; unmodifiable. */
    private final Map<String, String> documents;

    /**
     * Reads the three WSDLs and the two XSDs from the classpath and decodes each one as UTF-8.
     *
     * @throws UncheckedIOException  when a resource is missing or cannot be read
     * @throws IllegalStateException when a resource is not valid UTF-8
     */
    public WsdlQueryFilter() {
        Map<String, String> loaded = new HashMap<>();
        for (String resource : WSDL_RESOURCES.values()) {
            loaded.put(resource, load(resource));
        }
        for (String resource : XSD_RESOURCES.values()) {
            loaded.put(resource, load(resource));
        }
        this.documents = Map.copyOf(loaded);
    }

    /**
     * Answers {@code GET <service path>?wsdl} and {@code GET <service path>?xsd=<key>} from the committed documents,
     * and hands every other request to the rest of the filter chain (D-028, D-069, D-548).
     *
     * @param request  the incoming request
     * @param response the response; written only for the {@code ?wsdl} and {@code ?xsd=} answers
     * @param chain    the rest of the filter chain, called for every request this filter does not answer
     * @throws ServletException when the rest of the chain fails
     * @throws IOException      when the response or the rest of the chain fails to write
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String contextPath = request.getContextPath();
        String path = request.getRequestURI().substring(contextPath.length());
        String wsdlResource = WSDL_RESOURCES.get(path);
        if (!GET.equals(request.getMethod()) || wsdlResource == null) {
            chain.doFilter(request, response);
            return;
        }
        String query = request.getQueryString();
        if (WSDL_QUERY.equalsIgnoreCase(query)) {
            LOG.debug("Serving {} for GET {}?wsdl", wsdlResource, path);
            serve(response, rewriteWsdl(documents.get(wsdlResource), baseAddress(request, contextPath + path)));
            return;
        }
        if (query != null && query.startsWith(XSD_QUERY_PREFIX)) {
            serveSchema(request, response, contextPath + path, decodeKey(query.substring(XSD_QUERY_PREFIX.length())));
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Answers a {@code ?xsd=} request: 200 with the schema of a known key, the message schema with its model import
     * rewritten; 404 with an empty body, through {@link HttpServletResponse#setStatus(int)}, for any other key.
     *
     * @param request     the incoming request, read for the host of the served address
     * @param response    the response to write
     * @param servicePath the context path plus the service path of the request
     * @param key         the decoded {@code ?xsd=} key, or {@code null} when it could not be decoded
     * @throws IOException when the response fails to write
     */
    private void serveSchema(HttpServletRequest request, HttpServletResponse response, String servicePath,
            String key) throws IOException {
        String xsdResource = key == null ? null : XSD_RESOURCES.get(key);
        if (xsdResource == null) {
            LOG.debug("Unknown ?xsd= key for GET {}: 404", servicePath);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        LOG.debug("Serving {} for GET {}?xsd=", xsdResource, servicePath);
        String schema = documents.get(xsdResource);
        if (MESSAGE_XSD_KEY.equals(key)) {
            schema = rewriteMessageSchema(schema, baseAddress(request, servicePath));
        }
        serve(response, schema);
    }

    /**
     * Rewrites the {@code soap:address} location and the message schema import of a committed WSDL.
     *
     * @param wsdl the committed WSDL text
     * @param base the escaped base address of the service
     * @return the WSDL text with the address and import locations on {@code base}
     */
    private static String rewriteWsdl(String wsdl, String base) {
        String messageImport = "schemaLocation=\"" + base + "?" + XSD_QUERY_PREFIX + MESSAGE_XSD_KEY + "\"";
        return wsdl.replace(COMMITTED_ADDRESS, "location=\"" + base + "\"")
                .replace(ADMISSION_MESSAGE_IMPORT, messageImport)
                .replace(MOCK_MESSAGE_IMPORT, messageImport);
    }

    /**
     * Rewrites the model schema import of the committed message schema.
     *
     * @param schema the committed {@code SOA-Message-1.0.xsd} text
     * @param base   the escaped base address of the service
     * @return the schema text with the model import location on {@code base}
     */
    private static String rewriteMessageSchema(String schema, String base) {
        return schema.replace(MODEL_IMPORT,
                "schemaLocation=\"" + base + "?" + XSD_QUERY_PREFIX + MODEL_XSD_KEY + "\"");
    }

    /**
     * Builds {@code http://<host><servicePath>} for the request, with the XML attribute characters escaped.
     *
     * <p>The host is the {@code Host} header; when that header is absent or blank it is
     * {@code <server name>:<server port>}, the server name being the local address when it is blank, and an IPv6
     * address in brackets.
     *
     * @param request     the incoming request
     * @param servicePath the context path plus the service path of the request
     * @return the base address, ready for a double-quoted XML attribute value
     */
    private static String baseAddress(HttpServletRequest request, String servicePath) {
        String host = request.getHeader(HOST_HEADER);
        if (host == null || host.isBlank()) {
            String serverName = request.getServerName();
            if (serverName == null || serverName.isBlank()) {
                serverName = request.getLocalAddr();
            }
            if (serverName.indexOf(':') >= 0 && !serverName.startsWith("[")) {
                serverName = "[" + serverName + "]";
            }
            host = serverName + ":" + request.getServerPort();
        }
        return escapeAttribute("http://" + host + servicePath);
    }

    /**
     * Writes {@code &}, {@code <}, {@code >}, {@code "} and {@code '} as XML character references.
     *
     * @param value the attribute value to escape
     * @return the escaped value; equal to {@code value} when it holds none of the five characters
     */
    private static String escapeAttribute(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * Decodes a {@code ?xsd=} key with {@link URLDecoder} as UTF-8.
     *
     * @param encoded the query text after {@code xsd=}
     * @return the decoded key, or {@code null} when the text holds a malformed percent-encoding
     */
    private static String decodeKey(String encoded) {
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Writes a document as a 200 {@code text/xml; charset=UTF-8} answer with its {@code Content-Length}, then
     * flushes the response.
     *
     * @param response the response to write
     * @param document the document text
     * @throws IOException when the response fails to write
     */
    private static void serve(HttpServletResponse response, String document) throws IOException {
        byte[] body = document.getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }

    /**
     * Reads a classpath resource with {@link ClassPathResource} and decodes it as strict UTF-8, malformed or
     * unmappable input rejected.
     *
     * @param resourcePath the classpath-relative resource path
     * @return the decoded text
     * @throws UncheckedIOException  when the resource is missing or cannot be read
     * @throws IllegalStateException when the resource is not valid UTF-8
     */
    private static String load(String resourcePath) {
        ClassPathResource resource = new ClassPathResource(resourcePath, WsdlQueryFilter.class.getClassLoader());
        try (InputStream in = resource.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalStateException("Classpath resource " + resourcePath + " is not valid UTF-8", ex);
        } catch (IOException ex) {
            throw new UncheckedIOException("Cannot read classpath resource " + resourcePath, ex);
        }
    }
}
