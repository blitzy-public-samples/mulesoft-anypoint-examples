package com.mulesoft.examples.document_integration_using_the_cmis_connector.client;

import com.mulesoft.examples.document_integration_using_the_cmis_connector.config.CmisProperties;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * CMIS 1.0 AtomPub client that creates a document from in-memory content in a folder addressed by its path
 * (D-031). It is the Java counterpart of the global element {@code cmis:config}
 * [document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:3] and of the
 * operation {@code cmis:create-document-by-path-from-content} [same file:13], and the only class of the project
 * that sends requests to the CMIS repository.
 *
 * <p>The connection settings come from {@link CmisProperties}: {@link CmisProperties#baseUrl()} is the URL of
 * the AtomPub service document, {@link CmisProperties#repositoryId()} selects its workspace, and
 * {@link CmisProperties#username()} and {@link CmisProperties#password()} are sent as HTTP Basic credentials,
 * UTF-8 encoded, with every request. The committed values of the URL and the credentials are placeholders
 * (D-012). The document settings are arguments of {@link #createDocumentByPath}; the class holds no defaults.
 *
 * <p>Each call of {@link #createDocumentByPath} sends exactly three requests and caches nothing between calls:
 * <ol>
 *   <li>{@code GET} of the service document ({@code Accept: application/atomsvc+xml}), which yields the
 *       workspace's {@code objectbypath} URI template;</li>
 *   <li>{@code GET} of the folder entry at the expanded template ({@code Accept: application/atom+xml;type=entry}),
 *       which yields the folder's children feed link;</li>
 *   <li>{@code POST} of an Atom entry carrying the base64 content and the {@code cmis:objectTypeId} and
 *       {@code cmis:name} properties to the children feed, with the {@code versioningState} query parameter;
 *       the answer is the created document's entry, whose {@code cmis:objectId} is returned.</li>
 * </ol>
 *
 * <p>Failures surface as unchecked exceptions and nothing is logged or retried:
 * <ul>
 *   <li>a 4xx or 5xx answer raises Spring's {@code HttpClientErrorException} or {@code HttpServerErrorException},
 *       and any other status outside 2xx, such as an unfollowed redirect, raises
 *       {@link RestClientResponseException}; all three are {@link RestClientResponseException}s carrying the
 *       status, headers and body;</li>
 *   <li>a connection or I/O failure raises Spring's {@link ResourceAccessException};</li>
 *   <li>an answer that is not well-formed XML, or that declares a DOCTYPE, raises {@link IllegalStateException}
 *       with the parser's exception as its cause;</li>
 *   <li>an answer that lacks the workspace, URI template, children link or object id the operation needs raises
 *       {@link IllegalStateException} naming the missing item.</li>
 * </ul>
 *
 * <p>The HTTP client is a {@link RestClient} on a {@link SimpleClientHttpRequestFactory}, with no connect or
 * read timeout, no interceptor and no retry, built on first use. Construction reads no property and sends no
 * request. Instances are safe for concurrent use.
 *
 * <p>Example, with the values of {@code cmis.document.*}:
 * <pre>{@code
 * String objectId = client.createDocumentByPath("/okm:root/", "pic1712345678901.jpg", "image/jpg",
 *         "cmis:document", "NONE", logoBytes);
 * // objectId is the repository's id of the new document, for OpenKM "/okm:root/pic1712345678901.jpg"
 * }</pre>
 */
@Component
public class CmisAtomPubClient {

    /** Atom Syndication Format namespace ({@code atom}). */
    private static final String ATOM = "http://www.w3.org/2005/Atom";

    /** Atom Publishing Protocol namespace ({@code app}). */
    private static final String APP = "http://www.w3.org/2007/app";

    /** CMIS core namespace ({@code cmis}). */
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";

    /** CMIS RestAtom namespace ({@code cmisra}). */
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    /** Media type of the AtomPub service document. */
    private static final String SERVICE_MEDIA_TYPE = "application/atomsvc+xml";

    /** Media type of an Atom entry: the folder entry, the posted entry and the created entry. */
    private static final String ENTRY_MEDIA_TYPE = "application/atom+xml;type=entry";

    /** Media type of an Atom feed, the {@code type} of the folder's children link. */
    private static final String FEED_MEDIA_TYPE = "application/atom+xml;type=feed";

    /** {@code cmisra:type} value of the URI template that addresses an object by its path. */
    private static final String OBJECT_BY_PATH = "objectbypath";

    /** URI template variable that receives the folder path. */
    private static final String PATH_VARIABLE = "path";

    /** A URI template variable {@code {name}}; group 1 is the name. */
    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([^}]*)\\}");

    /** Matches every whitespace character of a link {@code type} attribute. */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Name of the unqualified attribute that identifies a CMIS property. */
    private static final String PROPERTY_DEFINITION_ID = "propertyDefinitionId";

    /** {@code propertyDefinitionId} of the created object's id. */
    private static final String OBJECT_ID = "cmis:objectId";

    /** {@code propertyDefinitionId} of the posted object type id. */
    private static final String OBJECT_TYPE_ID = "cmis:objectTypeId";

    /** {@code propertyDefinitionId} of the posted document name. */
    private static final String NAME = "cmis:name";

    /** Query parameter of the children feed POST that carries the versioning state. */
    private static final String VERSIONING_STATE = "versioningState";

    /** Parser feature that rejects any document containing a DOCTYPE declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** Parser feature that resolves external general entities. */
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";

    /** Parser feature that resolves external parameter entities. */
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";

    /**
     * Parser error handler that raises every warning, recoverable error and fatal error as its
     * {@link SAXParseException}, and writes nothing to the console.
     */
    private static final ErrorHandler RAISING_ERROR_HANDLER = new ErrorHandler() {
        /**
         * Raises the warning.
         *
         * @param exception the parser's warning
         * @throws SAXException always, {@code exception} itself
         */
        @Override
        public void warning(SAXParseException exception) throws SAXException {
            throw exception;
        }

        /**
         * Raises the recoverable error.
         *
         * @param exception the parser's error
         * @throws SAXException always, {@code exception} itself
         */
        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        /**
         * Raises the fatal error.
         *
         * @param exception the parser's fatal error
         * @throws SAXException always, {@code exception} itself
         */
        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** Connection settings of the CMIS repository; read on every call, never at construction. */
    private final CmisProperties properties;

    /** HTTP client, built by {@link #restClient()} on first use. */
    private volatile RestClient restClient;

    /**
     * Creates the client. The properties are stored, not read; no HTTP client is built and no request is sent.
     *
     * @param properties the {@code cmis.*} connection settings
     */
    public CmisAtomPubClient(CmisProperties properties) {
        this.properties = properties;
    }

    /**
     * Creates a document from {@code content} in the folder at {@code folderPath} and returns its
     * {@code cmis:objectId}, as {@code cmis:create-document-by-path-from-content} does
     * [document-integration-using-the-cmis-connector/src/main/app/cmis-document-integration.xml:13].
     *
     * <p>The three requests, each with the Basic credentials of {@link CmisProperties}:
     * <ol>
     *   <li>{@code GET} {@link CmisProperties#baseUrl()}. In the {@code app:service} root, the {@code app:workspace}
     *       whose {@code cmisra:repositoryInfo/cmis:repositoryId} equals {@link CmisProperties#repositoryId()} is
     *       selected, and in it the {@code cmisra:uritemplate} whose {@code cmisra:type} is {@code objectbypath}.</li>
     *   <li>{@code GET} of the template with {@code {path}} replaced by {@code folderPath}, form-encoded in UTF-8
     *       ({@code /okm:root/} becomes {@code %2Fokm%3Aroot%2F}), and every other variable replaced by the empty
     *       string, resolved against the service document URL. In the {@code atom:entry} root, the
     *       {@code atom:link} with {@code rel="down"} and {@code type} {@code application/atom+xml;type=feed}
     *       (whitespace ignored, case-insensitive) is selected; its {@code href}, resolved against the folder
     *       entry URL, is the children feed.</li>
     *   <li>{@code POST} to the children feed with {@code versioningState} appended as a query parameter,
     *       lower-cased ({@code NONE} becomes {@code none}), of this entry:
     * <pre>{@code
     * <atom:entry xmlns:atom="http://www.w3.org/2005/Atom"
     *             xmlns:cmis="http://docs.oasis-open.org/ns/cmis/core/200908/"
     *             xmlns:cmisra="http://docs.oasis-open.org/ns/cmis/restatom/200908/">
     *   <atom:id>urn:uuid:<random UUID></atom:id>
     *   <atom:title><fileName></atom:title>
     *   <atom:updated><now, ISO-8601 instant></atom:updated>
     *   <cmisra:content>
     *     <cmisra:mediatype><mimeType></cmisra:mediatype>
     *     <cmisra:base64><content, base64></cmisra:base64>
     *   </cmisra:content>
     *   <cmisra:object>
     *     <cmis:properties>
     *       <cmis:propertyId propertyDefinitionId="cmis:objectTypeId">
     *         <cmis:value><objectTypeId></cmis:value>
     *       </cmis:propertyId>
     *       <cmis:propertyString propertyDefinitionId="cmis:name">
     *         <cmis:value><fileName></cmis:value>
     *       </cmis:propertyString>
     *     </cmis:properties>
     *   </cmisra:object>
     * </atom:entry>
     * }</pre>
     *       The value returned is the text of the {@code cmis:value} of the {@code cmis:propertyId} whose
     *       {@code propertyDefinitionId} is {@code cmis:objectId}, under
     *       {@code atom:entry/cmisra:object/cmis:properties} of the answer.</li>
     * </ol>
     *
     * @param folderPath      path of the target folder, for example {@code /okm:root/}
     * @param fileName        {@code cmis:name} and {@code atom:title} of the document, for example
     *                        {@code pic1712345678901.jpg}
     * @param mimeType        media type of the content, for example {@code image/jpg}
     * @param objectTypeId    CMIS object type of the document, for example {@code cmis:document}
     * @param versioningState CMIS versioning state, for example {@code NONE}; sent lower-cased
     * @param content         the document bytes
     * @return the created document's {@code cmis:objectId}, trimmed and not empty
     * @throws NullPointerException        if an argument is {@code null}; no request is sent
     * @throws RestClientResponseException if a request is answered with a status outside 2xx
     * @throws ResourceAccessException     if a request fails to connect or to transfer
     * @throws IllegalStateException       if an answer is not well-formed XML, declares a DOCTYPE, or lacks the
     *                                     workspace of the repository id, its {@code objectbypath} URI template,
     *                                     the folder's children feed link or the created {@code cmis:objectId}
     */
    public String createDocumentByPath(String folderPath, String fileName, String mimeType, String objectTypeId,
                                       String versioningState, byte[] content) {
        Objects.requireNonNull(folderPath, "folderPath");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(mimeType, "mimeType");
        Objects.requireNonNull(objectTypeId, "objectTypeId");
        Objects.requireNonNull(versioningState, "versioningState");
        Objects.requireNonNull(content, "content");

        URI serviceUri = URI.create(properties.baseUrl());
        String template = objectByPathTemplate(parse(execute(HttpMethod.GET, serviceUri, SERVICE_MEDIA_TYPE, null)));

        URI folderUri = serviceUri.resolve(URI.create(expand(template, folderPath)));
        URI childrenUri = folderUri.resolve(
                URI.create(childrenHref(parse(execute(HttpMethod.GET, folderUri, ENTRY_MEDIA_TYPE, null)))));

        URI createUri = UriComponentsBuilder.fromUri(childrenUri)
                .queryParam(VERSIONING_STATE, versioningState.toLowerCase(Locale.ROOT))
                .build(true)
                .toUri();
        byte[] entry = serialize(documentEntry(fileName, mimeType, objectTypeId, content));
        return createdObjectId(parse(execute(HttpMethod.POST, createUri, ENTRY_MEDIA_TYPE, entry)));
    }

    /**
     * Returns the HTTP client, building it on first use with {@link SimpleClientHttpRequestFactory}: no connect
     * or read timeout, no interceptor and no retry. Double-checked locking on the volatile field builds it once.
     *
     * @return the shared client
     */
    private RestClient restClient() {
        RestClient client = restClient;
        if (client == null) {
            synchronized (this) {
                client = restClient;
                if (client == null) {
                    client = RestClient.builder().requestFactory(new SimpleClientHttpRequestFactory()).build();
                    restClient = client;
                }
            }
        }
        return client;
    }

    /**
     * Sends one request and returns the answer body. The request carries the Basic credentials of
     * {@link CmisProperties} (D-012), UTF-8 encoded, and the given {@code Accept} header; a {@code POST} also
     * carries {@code body} with {@code Content-Type: application/atom+xml;type=entry}. The URI is sent as given,
     * with no further encoding.
     *
     * <p>A 4xx or 5xx answer raises Spring's default {@code HttpClientErrorException} or
     * {@code HttpServerErrorException}. Any other status outside 2xx, such as a redirect the request factory does
     * not follow, raises a {@link RestClientResponseException} with the status, headers and body. Every 2xx status
     * is accepted.
     *
     * @param method the HTTP method, {@code GET} or {@code POST}
     * @param uri    the absolute request URI
     * @param accept the {@code Accept} header value
     * @param body   the entry to post, or {@code null} for a request without a body
     * @return the answer body, empty when the answer has none
     * @throws RestClientResponseException if the status is outside 2xx
     * @throws ResourceAccessException     if the request fails to connect or to transfer
     */
    private byte[] execute(HttpMethod method, URI uri, String accept, byte[] body) {
        RestClient.RequestBodySpec request = restClient().method(method).uri(uri).headers(headers -> {
            headers.setBasicAuth(properties.username(), properties.password(), StandardCharsets.UTF_8);
            headers.set(HttpHeaders.ACCEPT, accept);
        });
        if (body != null) {
            request = request.contentType(MediaType.parseMediaType(ENTRY_MEDIA_TYPE)).body(body);
        }
        ResponseEntity<byte[]> response = request.retrieve().toEntity(byte[].class);
        HttpStatusCode status = response.getStatusCode();
        byte[] responseBody = response.getBody() == null ? new byte[0] : response.getBody();
        if (!status.is2xxSuccessful()) {
            throw new RestClientResponseException(
                    method.name() + " " + uri + " was answered with status " + status.value() + ", not 2xx",
                    status, "", response.getHeaders(), responseBody, null);
        }
        return responseBody;
    }

    /**
     * Creates a document builder from a new namespace-aware {@link DocumentBuilderFactory} that refuses DOCTYPE
     * declarations, resolves no external general or parameter entity, does not expand entity references and does
     * not process XInclude. The builder raises every parser warning and error and writes nothing to the console.
     *
     * @return the builder
     * @throws IllegalStateException if the factory rejects a setting; its exception is the cause
     */
    private static DocumentBuilder documentBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(RAISING_ERROR_HANDLER);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser cannot be configured: " + e.getMessage(), e);
        }
    }

    /**
     * Parses an answer body with a builder from {@link #documentBuilder()}. The parser detects the character
     * encoding from the bytes and the XML declaration.
     *
     * @param bytes the answer body
     * @return the parsed document
     * @throws IllegalStateException if the parser cannot be configured or the bytes are not well-formed XML,
     *                               including bytes that declare a DOCTYPE; the parser's exception is the cause
     * @throws UncheckedIOException  if reading the bytes fails
     */
    private static Document parse(byte[] bytes) {
        DocumentBuilder builder = documentBuilder();
        try {
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (SAXException e) {
            throw new IllegalStateException("CMIS answer is not well-formed XML: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("CMIS answer cannot be read: " + e.getMessage(), e);
        }
    }

    /**
     * Serialises a document as UTF-8 XML with an XML declaration, using {@link TransformerFactory#newInstance()}
     * with access to external DTDs and stylesheets disabled.
     *
     * @param document the document to write
     * @return the XML bytes
     * @throws IllegalStateException if the transformer cannot be configured or fails; its exception is the cause
     */
    private static byte[] serialize(Document document) {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toByteArray();
        } catch (TransformerException e) {
            throw new IllegalStateException("CMIS entry cannot be serialised: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the {@code objectbypath} URI template of the configured repository from a service document: the
     * {@code cmisra:template} text of the first {@code cmisra:uritemplate} child, with {@code cmisra:type}
     * {@code objectbypath}, of the first {@code app:workspace} child of the {@code app:service} root whose
     * {@code cmisra:repositoryInfo/cmis:repositoryId} text equals {@link CmisProperties#repositoryId()}. The
     * parser has already decoded entity references such as {@code &amp;}.
     *
     * @param serviceDocument the parsed service document
     * @return the template text, trimmed and not empty
     * @throws IllegalStateException if the root is not {@code app:service}, no workspace has the repository id,
     *                               or the workspace has no non-empty {@code objectbypath} template
     */
    private String objectByPathTemplate(Document serviceDocument) {
        Element service = root(serviceDocument, APP, "service", "service document");
        String repositoryId = properties.repositoryId();
        Element workspace = null;
        for (Element candidate : children(service, APP, "workspace")) {
            Element repositoryInfo = child(candidate, CMISRA, "repositoryInfo");
            Element id = repositoryInfo == null ? null : child(repositoryInfo, CMIS, "repositoryId");
            if (id != null && text(id).equals(repositoryId)) {
                workspace = candidate;
                break;
            }
        }
        if (workspace == null) {
            throw new IllegalStateException(
                    "CMIS service document has no workspace for repository id '" + repositoryId + "'");
        }
        for (Element uriTemplate : children(workspace, CMISRA, "uritemplate")) {
            Element type = child(uriTemplate, CMISRA, "type");
            Element template = child(uriTemplate, CMISRA, "template");
            if (type != null && OBJECT_BY_PATH.equals(text(type)) && template != null && !text(template).isEmpty()) {
                return text(template);
            }
        }
        throw new IllegalStateException("CMIS workspace of repository id '" + repositoryId
                + "' has no " + OBJECT_BY_PATH + " URI template");
    }

    /**
     * Expands a CMIS URI template: {@code {path}} becomes {@code folderPath} encoded by
     * {@link URLEncoder#encode(String, java.nio.charset.Charset)} in UTF-8, and every other variable, such as
     * {@code {filter}} or {@code {includeAllowableActions}}, becomes the empty string.
     *
     * <p>Example: {@code .../default/path?path={path}&filter={filter}} with {@code /okm:root/} becomes
     * {@code .../default/path?path=%2Fokm%3Aroot%2F&filter=}.
     *
     * @param template   the template text
     * @param folderPath the folder path
     * @return the expanded URI reference
     */
    private static String expand(String template, String folderPath) {
        Matcher matcher = TEMPLATE_VARIABLE.matcher(template);
        StringBuilder expanded = new StringBuilder(template.length() + folderPath.length() * 3);
        while (matcher.find()) {
            String value = PATH_VARIABLE.equals(matcher.group(1))
                    ? URLEncoder.encode(folderPath, StandardCharsets.UTF_8)
                    : "";
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    /**
     * Reads the children feed link of a folder entry: the {@code href} of the first {@code atom:link} child of the
     * {@code atom:entry} root whose {@code rel} is {@code down} and whose {@code type}, with all whitespace removed,
     * equals {@code application/atom+xml;type=feed} ignoring case. A {@code down} link of another type, such as
     * {@code application/cmistree+xml}, is skipped.
     *
     * @param folderEntry the parsed folder entry
     * @return the link's {@code href}, trimmed and not empty
     * @throws IllegalStateException if the root is not {@code atom:entry}, no such link exists, or its
     *                               {@code href} is empty
     */
    private static String childrenHref(Document folderEntry) {
        Element entry = root(folderEntry, ATOM, "entry", "folder entry");
        for (Element link : children(entry, ATOM, "link")) {
            String type = WHITESPACE.matcher(link.getAttribute("type")).replaceAll("");
            if ("down".equals(link.getAttribute("rel").trim()) && FEED_MEDIA_TYPE.equalsIgnoreCase(type)) {
                String href = link.getAttribute("href").trim();
                if (href.isEmpty()) {
                    throw new IllegalStateException("CMIS folder entry's children link (rel=\"down\", type=\""
                            + FEED_MEDIA_TYPE + "\") has no href");
                }
                return href;
            }
        }
        throw new IllegalStateException("CMIS folder entry has no children link (rel=\"down\", type=\""
                + FEED_MEDIA_TYPE + "\")");
    }

    /**
     * Builds the Atom entry posted to the children feed. The root {@code atom:entry} declares the prefixes
     * {@code atom}, {@code cmis} and {@code cmisra}; its children, in order, are {@code atom:id}
     * ({@code urn:uuid:} and a random UUID), {@code atom:title} ({@code fileName}), {@code atom:updated} (the
     * current instant in ISO-8601), {@code cmisra:content} ({@code cmisra:mediatype} and {@code cmisra:base64}) and
     * {@code cmisra:object}, whose {@code cmis:properties} hold {@code cmis:propertyId} {@code cmis:objectTypeId}
     * and {@code cmis:propertyString} {@code cmis:name}, each with one {@code cmis:value}.
     *
     * @param fileName     the document name and entry title
     * @param mimeType     the content media type
     * @param objectTypeId the CMIS object type id
     * @param content      the document bytes, written with the basic base64 encoder
     * @return the entry document
     */
    private static Document documentEntry(String fileName, String mimeType, String objectTypeId, byte[] content) {
        Document document = documentBuilder().newDocument();
        Element entry = document.createElementNS(ATOM, "atom:entry");
        entry.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:atom", ATOM);
        entry.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cmis", CMIS);
        entry.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:cmisra", CMISRA);
        document.appendChild(entry);

        append(entry, ATOM, "atom:id", "urn:uuid:" + UUID.randomUUID());
        append(entry, ATOM, "atom:title", fileName);
        append(entry, ATOM, "atom:updated", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));

        Element contentElement = append(entry, CMISRA, "cmisra:content", null);
        append(contentElement, CMISRA, "cmisra:mediatype", mimeType);
        append(contentElement, CMISRA, "cmisra:base64", Base64.getEncoder().encodeToString(content));

        Element object = append(entry, CMISRA, "cmisra:object", null);
        Element objectProperties = append(object, CMIS, "cmis:properties", null);
        Element typeProperty = append(objectProperties, CMIS, "cmis:propertyId", null);
        typeProperty.setAttributeNS(null, PROPERTY_DEFINITION_ID, OBJECT_TYPE_ID);
        append(typeProperty, CMIS, "cmis:value", objectTypeId);
        Element nameProperty = append(objectProperties, CMIS, "cmis:propertyString", null);
        nameProperty.setAttributeNS(null, PROPERTY_DEFINITION_ID, NAME);
        append(nameProperty, CMIS, "cmis:value", fileName);
        return document;
    }

    /**
     * Reads the object id from the created entry: the {@code cmis:value} text of the first {@code cmis:propertyId}
     * child, with {@code propertyDefinitionId} {@code cmis:objectId}, of
     * {@code atom:entry/cmisra:object/cmis:properties}.
     *
     * @param createdEntry the parsed answer of the children feed POST
     * @return the object id, trimmed and not empty
     * @throws IllegalStateException if the root is not {@code atom:entry} or the {@code cmis:objectId} value is
     *                               missing or empty
     */
    private static String createdObjectId(Document createdEntry) {
        Element entry = root(createdEntry, ATOM, "entry", "created entry");
        Element object = child(entry, CMISRA, "object");
        Element objectProperties = object == null ? null : child(object, CMIS, "properties");
        if (objectProperties != null) {
            for (Element property : children(objectProperties, CMIS, "propertyId")) {
                if (OBJECT_ID.equals(property.getAttributeNS(null, PROPERTY_DEFINITION_ID))) {
                    Element value = child(property, CMIS, "value");
                    if (value != null && !text(value).isEmpty()) {
                        return text(value);
                    }
                    break;
                }
            }
        }
        throw new IllegalStateException("CMIS created entry has no " + OBJECT_ID + " value");
    }

    /**
     * Returns the root element of a document after checking its namespace and local name.
     *
     * @param document    the parsed document
     * @param namespace   the expected namespace URI
     * @param localName   the expected local name
     * @param description the document's name in the exception message
     * @return the root element
     * @throws IllegalStateException if the root has another name
     */
    private static Element root(Document document, String namespace, String localName, String description) {
        Element root = document.getDocumentElement();
        if (!namespace.equals(root.getNamespaceURI()) || !localName.equals(root.getLocalName())) {
            throw new IllegalStateException("CMIS " + description + " root is {" + root.getNamespaceURI() + "}"
                    + root.getLocalName() + ", not {" + namespace + "}" + localName);
        }
        return root;
    }

    /**
     * Returns the direct child elements of {@code parent} with the given namespace and local name, in document
     * order. Descendants below the children are not searched.
     *
     * @param parent    the parent element
     * @param namespace the children's namespace URI
     * @param localName the children's local name
     * @return the matching children, possibly empty
     */
    private static List<Element> children(Element parent, String namespace, String localName) {
        List<Element> matches = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node.getNodeType() == Node.ELEMENT_NODE
                    && namespace.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                matches.add((Element) node);
            }
        }
        return matches;
    }

    /**
     * Returns the first direct child element of {@code parent} with the given namespace and local name.
     *
     * @param parent    the parent element
     * @param namespace the child's namespace URI
     * @param localName the child's local name
     * @return the child, or {@code null} when there is none
     */
    private static Element child(Element parent, String namespace, String localName) {
        List<Element> matches = children(parent, namespace, localName);
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Returns the trimmed text content of an element.
     *
     * @param element the element
     * @return the text, without leading and trailing whitespace
     */
    private static String text(Element element) {
        return element.getTextContent().trim();
    }

    /**
     * Appends a namespaced child element, with optional text, to {@code parent}.
     *
     * @param parent        the parent element
     * @param namespace     the child's namespace URI
     * @param qualifiedName the child's prefixed name
     * @param text          the child's text, or {@code null} for an element without text
     * @return the appended child
     */
    private static Element append(Element parent, String namespace, String qualifiedName, String text) {
        Element element = parent.getOwnerDocument().createElementNS(namespace, qualifiedName);
        if (text != null) {
            element.setTextContent(text);
        }
        parent.appendChild(element);
        return element;
    }
}
