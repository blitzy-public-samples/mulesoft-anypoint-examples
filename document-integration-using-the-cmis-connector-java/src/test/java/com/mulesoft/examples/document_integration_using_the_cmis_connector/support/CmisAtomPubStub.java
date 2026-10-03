package com.mulesoft.examples.document_integration_using_the_cmis_connector.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * In-JVM CMIS 1.0 AtomPub repository served through an okhttp {@code MockWebServer} (D-039). It answers the
 * three requests of the create-document-by-path operation that {@code client.CmisAtomPubClient} sends
 * (D-031):
 * <ol>
 *   <li>{@code GET /cmis/atom}: the service document, with the workspaces {@code other} and {@code default},
 *       each carrying an {@code objectbypath} URI template rooted at the base URL;</li>
 *   <li>{@code GET /cmis/atom/default/path?path=%2Fokm%3Aroot%2F&...}: the folder entry of {@code /okm:root},
 *       with a {@code down} link of type {@code application/cmistree+xml} to the descendants and a {@code down}
 *       link of type {@code application/atom+xml;type=feed} to the children feed;</li>
 *   <li>{@code POST /cmis/atom/default/children?id=%2Fokm%3Aroot&versioningState=none}: 201 with the created
 *       entry, whose {@code cmis:objectId} is {@code /okm:root/} followed by the posted {@code cmis:name}.</li>
 * </ol>
 *
 * <p>Requests are matched on the method and the raw request target only, with no URL decoding; every other
 * request answers 404 with an empty body. {@link Mode} selects the failure answers. Every dispatched request is
 * recorded in arrival order and available through {@link #requests()}. The stub does not check
 * {@code Authorization} or {@code Content-Type}.
 *
 * <p>Usage:
 * <pre>{@code
 * MockWebServer server = new MockWebServer();
 * server.start();
 * String root = server.url("/").toString();
 * root = root.substring(0, root.length() - 1);   // "http://localhost:54321"
 * CmisAtomPubStub stub = new CmisAtomPubStub(root);
 * server.setDispatcher(stub);
 * // cmis.base-url = root + "/cmis/atom"
 * }</pre>
 */
public final class CmisAtomPubStub extends Dispatcher {

    /** Atom Publishing Protocol namespace ({@code app}). */
    private static final String APP = "http://www.w3.org/2007/app";

    /** Atom Syndication Format namespace ({@code atom}). */
    private static final String ATOM = "http://www.w3.org/2005/Atom";

    /** CMIS RestAtom namespace ({@code cmisra}). */
    private static final String CMISRA = "http://docs.oasis-open.org/ns/cmis/restatom/200908/";

    /** CMIS core namespace ({@code cmis}). */
    private static final String CMIS = "http://docs.oasis-open.org/ns/cmis/core/200908/";

    /** XML declaration that starts every response document. */
    private static final String XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>";

    /** Repository id of the workspace that holds {@code /okm:root}. */
    private static final String REPOSITORY_ID = "default";

    /** Repository id of the second workspace, listed first in the service document. */
    private static final String OTHER_REPOSITORY_ID = "other";

    /** Raw request target of the service document. */
    private static final String SERVICE_PATH = "/cmis/atom";

    /** Path part of the {@code objectbypath} lookup in the {@code default} repository. */
    private static final String FOLDER_BY_PATH_PATH = "/cmis/atom/default/path";

    /** Path part of the children feed of {@code /okm:root}. */
    private static final String CHILDREN_PATH = "/cmis/atom/default/children";

    /** Raw {@code path} query value that selects {@code /okm:root/}. */
    private static final String FOLDER_PATH_PARAMETER = "%2Fokm%3Aroot%2F";

    /** Raw {@code id} query value of the children feed of {@code /okm:root}. */
    private static final String FOLDER_ID_PARAMETER = "%2Fokm%3Aroot";

    /** Raw {@code versioningState} query value accepted by the children feed. */
    private static final String VERSIONING_STATE_PARAMETER = "none";

    /** Object id prefix of every created document. */
    private static final String FOLDER_OBJECT_ID_PREFIX = "/okm:root/";

    /** {@code atom:updated} value of every entry. */
    private static final String UPDATED = "2014-01-01T00:00:00Z";

    /** Media type of the service document. */
    private static final String SERVICE_MEDIA_TYPE = "application/atomsvc+xml;charset=UTF-8";

    /** Media type of folder and document entries. */
    private static final String ENTRY_MEDIA_TYPE = "application/atom+xml;type=entry";

    /** Media type of the plain-text failure bodies. */
    private static final String TEXT_MEDIA_TYPE = "text/plain;charset=UTF-8";

    /** Name of the non-namespaced attribute that identifies a CMIS property. */
    private static final String PROPERTY_DEFINITION_ID = "propertyDefinitionId";

    /** Logger for requests answered 404 after a parsing or routing failure. */
    private static final Logger LOG = Logger.getLogger(CmisAtomPubStub.class.getName());

    /** Answers the stub gives, selected with {@link #setMode(Mode)}. */
    public enum Mode {
        /** Serves the service document, the folder entry and the created entry. */
        OK,
        /** Answers a matched children POST with 500 {@code Internal Server Error}. */
        CHILDREN_POST_500,
        /** Answers the service document request with 401 and a {@code Basic} challenge. */
        SERVICE_DOCUMENT_401,
        /**
         * Closes connections without an HTTP response. {@link #peek()} answers with
         * {@link SocketPolicy#DISCONNECT_AT_START}: MockWebServer closes each new connection before reading a
         * request. Every request answers with {@link SocketPolicy#DISCONNECT_AFTER_REQUEST}: a request received on
         * a connection opened before the switch is read, and the connection is then closed with no response.
         */
        DISCONNECT
    }

    /** Server root, such as {@code http://localhost:54321}, with no trailing slash. */
    private final String baseUrl;

    /** Current answer mode. */
    private volatile Mode mode = Mode.OK;

    /** Every dispatched request, in arrival order. */
    private final List<RecordedRequest> log = new CopyOnWriteArrayList<>();

    /**
     * Creates a stub whose absolute URLs start with {@code baseUrl}.
     *
     * @param baseUrl the server root, for example {@code http://localhost:54321}, with no trailing slash
     * @throws IllegalArgumentException when {@code baseUrl} is null, blank or ends with {@code /}
     */
    public CmisAtomPubStub(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be null or blank");
        }
        if (baseUrl.endsWith("/")) {
            throw new IllegalArgumentException("baseUrl must not end with '/': " + baseUrl);
        }
        this.baseUrl = baseUrl;
    }

    /**
     * Selects the answers of subsequent requests.
     *
     * @param mode the new mode
     * @throws NullPointerException when {@code mode} is null
     */
    public void setMode(Mode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    /**
     * Returns every request dispatched since construction or the last {@link #reset()}, in arrival order. The
     * list is an unmodifiable live view: it reflects requests recorded after the call.
     *
     * @return the recorded requests
     */
    public List<RecordedRequest> requests() {
        return Collections.unmodifiableList(log);
    }

    /** Restores {@link Mode#OK} and clears the recorded requests. */
    public void reset() {
        mode = Mode.OK;
        log.clear();
    }

    /**
     * Records the request, then answers it by the current mode and the routes described on the class.
     * Requests that match no route, including those whose body fails to parse, answer 404 with an empty
     * body. In {@link Mode#DISCONNECT} every request answers with
     * {@link SocketPolicy#DISCONNECT_AFTER_REQUEST}: MockWebServer closes the connection after reading the
     * request and sends no response.
     *
     * @param request the request MockWebServer received; for a connection closed at its start, the
     *                bookkeeping request whose method and path are null
     * @return the response to send
     */
    @Override
    public MockResponse dispatch(RecordedRequest request) {
        if (request != null) {
            log.add(request);
        }
        Mode current = mode;
        if (current == Mode.DISCONNECT) {
            return disconnectAfterRequest();
        }
        try {
            return route(request, current);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, e, () -> "CMIS stub answers 404 to " + describe(request));
            return notFound();
        }
    }

    /**
     * Returns the socket policy MockWebServer applies to each new connection: in {@link Mode#DISCONNECT} a
     * response with {@link SocketPolicy#DISCONNECT_AT_START}, which closes the connection before a request is
     * read, otherwise the inherited default.
     *
     * @return the response whose socket policy governs the next connection
     */
    @Override
    public MockResponse peek() {
        if (mode == Mode.DISCONNECT) {
            return disconnectAtStart();
        }
        return super.peek();
    }

    /**
     * Returns the {@code cmis:name} value of a recorded children POST: the text of the first {@code cmis:value}
     * child of the first element whose {@code propertyDefinitionId} is {@code cmis:name}.
     *
     * @param request a recorded children POST; its body is read from a copy and stays readable
     * @return the posted name, or null when the property or its value is absent
     * @throws IllegalArgumentException when the body is not well-formed XML
     * @throws NullPointerException when {@code request} is null
     */
    public static String postedName(RecordedRequest request) {
        return propertyValue(parse(body(request)), "cmis:name");
    }

    /**
     * Returns the {@code cmis:objectTypeId} value of a recorded children POST: the text of the first
     * {@code cmis:value} child of the first element whose {@code propertyDefinitionId} is
     * {@code cmis:objectTypeId}.
     *
     * @param request a recorded children POST; its body is read from a copy and stays readable
     * @return the posted object type id, or null when the property or its value is absent
     * @throws IllegalArgumentException when the body is not well-formed XML
     * @throws NullPointerException when {@code request} is null
     */
    public static String postedObjectTypeId(RecordedRequest request) {
        return propertyValue(parse(body(request)), "cmis:objectTypeId");
    }

    /**
     * Returns the text of {@code cmisra:content/cmisra:mediatype} in a recorded children POST.
     *
     * @param request a recorded children POST; its body is read from a copy and stays readable
     * @return the posted media type, or null when {@code cmisra:content} or its {@code cmisra:mediatype} child
     *         is absent
     * @throws IllegalArgumentException when the body is not well-formed XML
     * @throws NullPointerException when {@code request} is null
     */
    public static String postedMediaType(RecordedRequest request) {
        return contentChildText(parse(body(request)), "mediatype");
    }

    /**
     * Returns the bytes of {@code cmisra:content/cmisra:base64} in a recorded children POST, decoded with
     * {@link Base64#getMimeDecoder()}, which skips line breaks and other characters outside the base64
     * alphabet.
     *
     * @param request a recorded children POST; its body is read from a copy and stays readable
     * @return the posted content, or null when {@code cmisra:content} or its {@code cmisra:base64} child is
     *         absent
     * @throws IllegalArgumentException when the body is not well-formed XML or the text is not valid base64
     * @throws NullPointerException when {@code request} is null
     */
    public static byte[] postedContent(RecordedRequest request) {
        String text = contentChildText(parse(body(request)), "base64");
        return text == null ? null : Base64.getMimeDecoder().decode(text);
    }

    /**
     * Answers a request in mode {@code current}, which is not {@link Mode#DISCONNECT}.
     *
     * @param request the recorded request
     * @param current the mode read once for this request
     * @return the matched route's response, or 404
     */
    private MockResponse route(RecordedRequest request, Mode current) {
        if (request == null || request.getMethod() == null || request.getPath() == null) {
            return notFound();
        }
        String method = request.getMethod();
        String target = request.getPath();
        int queryStart = target.indexOf('?');
        String path = queryStart < 0 ? target : target.substring(0, queryStart);
        String rawQuery = queryStart < 0 ? null : target.substring(queryStart + 1);
        Map<String, String> query = parseQuery(rawQuery);
        if (query == null) {
            return notFound();
        }

        if ("GET".equals(method) && SERVICE_PATH.equals(target)) {
            return current == Mode.SERVICE_DOCUMENT_401 ? unauthorized() : serviceDocumentResponse();
        }
        if ("GET".equals(method) && FOLDER_BY_PATH_PATH.equals(path)) {
            return FOLDER_PATH_PARAMETER.equals(query.get("path")) ? folderEntryResponse() : notFound();
        }
        if ("POST".equals(method)
                && CHILDREN_PATH.equals(path)
                && FOLDER_ID_PARAMETER.equals(query.get("id"))
                && VERSIONING_STATE_PARAMETER.equals(query.get("versioningState"))) {
            if (current == Mode.CHILDREN_POST_500) {
                return serverError();
            }
            String name = postedName(request);
            if (name == null) {
                return notFound();
            }
            return createdResponse(name);
        }
        return notFound();
    }

    /**
     * Splits a raw query on {@code &}, then each token on its first {@code =}, keeping values undecoded. A
     * token without {@code =} has the value {@code ""}.
     *
     * @param rawQuery the text after the first {@code ?}, or null when the target has none
     * @return the parameters by name; an empty map for a null query; null when any name occurs more than once
     */
    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> parameters = new HashMap<>();
        if (rawQuery == null) {
            return parameters;
        }
        for (String token : rawQuery.split("&", -1)) {
            int separator = token.indexOf('=');
            String name = separator < 0 ? token : token.substring(0, separator);
            String value = separator < 0 ? "" : token.substring(separator + 1);
            if (parameters.containsKey(name)) {
                return null;
            }
            parameters.put(name, value);
        }
        return parameters;
    }


    /**
     * Builds the 200 service document response.
     *
     * @return the response with media type {@code application/atomsvc+xml;charset=UTF-8}
     */
    private MockResponse serviceDocumentResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", SERVICE_MEDIA_TYPE)
                .setBody(serviceDocument());
    }

    /**
     * Builds the 200 folder entry response of {@code /okm:root}.
     *
     * @return the response with media type {@code application/atom+xml;type=entry}
     */
    private static MockResponse folderEntryResponse() {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", ENTRY_MEDIA_TYPE)
                .setBody(folderEntry());
    }

    /**
     * Builds the 201 response for a document named {@code name} created in {@code /okm:root}.
     *
     * @param name the posted {@code cmis:name}
     * @return the response with media type {@code application/atom+xml;type=entry} and a {@code Location} of
     *         {@code <baseUrl>/cmis/atom/default/entry?id=<URL-encoded object id>}
     */
    private MockResponse createdResponse(String name) {
        String objectId = FOLDER_OBJECT_ID_PREFIX + name;
        return new MockResponse()
                .setResponseCode(201)
                .setHeader("Content-Type", ENTRY_MEDIA_TYPE)
                .setHeader("Location", baseUrl + "/cmis/atom/" + REPOSITORY_ID + "/entry?id="
                        + URLEncoder.encode(objectId, StandardCharsets.UTF_8))
                .setBody(createdEntry(objectId, name));
    }

    /**
     * Builds the 401 answer to the service document request in {@link Mode#SERVICE_DOCUMENT_401}.
     *
     * @return the response with {@code WWW-Authenticate: Basic realm="cmis"} and body {@code Unauthorized}
     */
    private static MockResponse unauthorized() {
        return new MockResponse()
                .setResponseCode(401)
                .setHeader("WWW-Authenticate", "Basic realm=\"cmis\"")
                .setHeader("Content-Type", TEXT_MEDIA_TYPE)
                .setBody("Unauthorized");
    }

    /**
     * Builds the 500 answer to a matched children POST in {@link Mode#CHILDREN_POST_500}.
     *
     * @return the response with body {@code Internal Server Error}
     */
    private static MockResponse serverError() {
        return new MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", TEXT_MEDIA_TYPE)
                .setBody("Internal Server Error");
    }

    /**
     * Builds the answer to an unmatched request.
     *
     * @return a 404 response with an empty body
     */
    private static MockResponse notFound() {
        return new MockResponse().setResponseCode(404);
    }

    /**
     * Builds the {@link #peek()} answer that closes a new connection before a request is read.
     *
     * @return a response with {@link SocketPolicy#DISCONNECT_AT_START}
     */
    private static MockResponse disconnectAtStart() {
        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START);
    }

    /**
     * Builds the {@link #dispatch(RecordedRequest)} answer that closes the connection after the request is
     * read, with no response.
     *
     * @return a response with {@link SocketPolicy#DISCONNECT_AFTER_REQUEST}
     */
    private static MockResponse disconnectAfterRequest() {
        return new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST);
    }

    /**
     * Builds the service document: an {@code app:service} with the workspaces {@code other} and
     * {@code default}, in this order.
     *
     * @return the XML text
     */
    private String serviceDocument() {
        return XML_DECLARATION
                + "<app:service xmlns:app=\"" + APP + "\" xmlns:atom=\"" + ATOM + "\" xmlns:cmisra=\"" + CMISRA
                + "\" xmlns:cmis=\"" + CMIS + "\">"
                + workspace(OTHER_REPOSITORY_ID)
                + workspace(REPOSITORY_ID)
                + "</app:service>";
    }

    /**
     * Builds one {@code app:workspace} with its title, repository info and {@code objectbypath} URI template.
     *
     * @param repositoryId the workspace's repository id
     * @return the XML fragment
     */
    private String workspace(String repositoryId) {
        String repository = xml(repositoryId);
        return "<app:workspace>"
                + "<atom:title>" + repository + "</atom:title>"
                + "<cmisra:repositoryInfo><cmis:repositoryId>" + repository + "</cmis:repositoryId>"
                + "</cmisra:repositoryInfo>"
                + "<cmisra:uritemplate>"
                + "<cmisra:template>" + xml(baseUrl) + "/cmis/atom/" + repository
                + "/path?path={path}&amp;filter={filter}&amp;includeAllowableActions={includeAllowableActions}"
                + "</cmisra:template>"
                + "<cmisra:type>objectbypath</cmisra:type>"
                + "<cmisra:mediatype>" + ENTRY_MEDIA_TYPE + "</cmisra:mediatype>"
                + "</cmisra:uritemplate>"
                + "</app:workspace>";
    }

    /**
     * Builds the folder entry of {@code /okm:root}: id, title, updated, the {@code cmistree} descendants link,
     * the children feed link (both relative to the server root) and the folder's properties.
     *
     * @return the XML text
     */
    private static String folderEntry() {
        return XML_DECLARATION
                + "<atom:entry xmlns:atom=\"" + ATOM + "\" xmlns:cmisra=\"" + CMISRA + "\" xmlns:cmis=\"" + CMIS
                + "\">"
                + "<atom:id>urn:cmis-stub:okm-root</atom:id>"
                + "<atom:title>okm:root</atom:title>"
                + "<atom:updated>" + UPDATED + "</atom:updated>"
                + "<atom:link rel=\"down\" type=\"application/cmistree+xml\""
                + " href=\"/cmis/atom/default/descendants?id=%2Fokm%3Aroot\"/>"
                + "<atom:link rel=\"down\" type=\"application/atom+xml;type=feed\""
                + " href=\"/cmis/atom/default/children?id=%2Fokm%3Aroot\"/>"
                + "<cmisra:object><cmis:properties>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:objectId\"><cmis:value>/okm:root</cmis:value>"
                + "</cmis:propertyId>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:baseTypeId\"><cmis:value>cmis:folder</cmis:value>"
                + "</cmis:propertyId>"
                + "</cmis:properties></cmisra:object>"
                + "</atom:entry>";
    }

    /**
     * Builds the entry of a created document.
     *
     * @param objectId the document's object id, {@code /okm:root/<name>}
     * @param name     the document's {@code cmis:name}
     * @return the XML text, with {@code objectId} and {@code name} XML-escaped
     */
    private static String createdEntry(String objectId, String name) {
        String id = xml(objectId);
        String title = xml(name);
        return XML_DECLARATION
                + "<atom:entry xmlns:atom=\"" + ATOM + "\" xmlns:cmisra=\"" + CMISRA + "\" xmlns:cmis=\"" + CMIS
                + "\">"
                + "<atom:id>urn:cmis-stub:" + id + "</atom:id>"
                + "<atom:title>" + title + "</atom:title>"
                + "<atom:updated>" + UPDATED + "</atom:updated>"
                + "<cmisra:object><cmis:properties>"
                + "<cmis:propertyId propertyDefinitionId=\"cmis:objectId\"><cmis:value>" + id + "</cmis:value>"
                + "</cmis:propertyId>"
                + "<cmis:propertyString propertyDefinitionId=\"cmis:name\"><cmis:value>" + title + "</cmis:value>"
                + "</cmis:propertyString>"
                + "</cmis:properties></cmisra:object>"
                + "</atom:entry>";
    }

    /**
     * Escapes {@code &}, {@code <}, {@code >}, {@code "} and {@code '} for XML text and attribute values.
     *
     * @param value the raw value
     * @return the escaped value
     */
    private static String xml(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Reads a recorded request body from a copy of its buffer, leaving the recorded buffer unread.
     *
     * @param request the recorded request
     * @return the body bytes
     * @throws NullPointerException when {@code request} is null
     */
    private static byte[] body(RecordedRequest request) {
        Objects.requireNonNull(request, "request");
        return request.getBody().clone().readByteArray();
    }

    /**
     * Creates a namespace-aware {@link DocumentBuilderFactory} with secure processing on, DOCTYPE declarations
     * refused, external entities off, entity references unexpanded and XInclude off.
     *
     * @return a new factory
     * @throws ParserConfigurationException when the parser rejects a feature
     */
    private static DocumentBuilderFactory documentBuilderFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        return factory;
    }

    /**
     * Parses XML bytes with a parser from {@link #documentBuilderFactory()}. Fatal parse errors are raised,
     * not printed.
     *
     * @param bytes the XML document
     * @return the parsed document
     * @throws IllegalArgumentException when the parser cannot be configured or the bytes are not well-formed
     *                                  XML
     */
    private static Document parse(byte[] bytes) {
        DocumentBuilder builder;
        try {
            builder = documentBuilderFactory().newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new IllegalArgumentException("XML parser configuration rejected: " + e.getMessage(), e);
        }
        builder.setErrorHandler(new DefaultHandler());
        try {
            return builder.parse(new ByteArrayInputStream(bytes));
        } catch (SAXException | IOException e) {
            throw new IllegalArgumentException("request body is not well-formed XML: " + e.getMessage(), e);
        }
    }

    /**
     * Finds the first element of {@code document}, of any name, whose non-namespaced
     * {@code propertyDefinitionId} attribute equals {@code propertyDefinitionId}, and returns the text of its
     * first {@code cmis:value} child element.
     *
     * @param document             the parsed body
     * @param propertyDefinitionId the wanted property id
     * @return the value text, or null when no element carries the id or the first one has no {@code cmis:value}
     */
    private static String propertyValue(Document document, String propertyDefinitionId) {
        NodeList elements = document.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            Attr attribute = element.getAttributeNodeNS(null, PROPERTY_DEFINITION_ID);
            if (attribute != null && propertyDefinitionId.equals(attribute.getValue())) {
                Element value = firstChild(element, CMIS, "value");
                return value == null ? null : value.getTextContent();
            }
        }
        return null;
    }

    /**
     * Returns the text of the {@code cmisra:<localName>} child of the first {@code cmisra:content} element.
     *
     * @param document  the parsed body
     * @param localName {@code mediatype} or {@code base64}
     * @return the child's text, or null when {@code cmisra:content} or the child is absent
     */
    private static String contentChildText(Document document, String localName) {
        Node content = document.getElementsByTagNameNS(CMISRA, "content").item(0);
        if (!(content instanceof Element)) {
            return null;
        }
        Element child = firstChild((Element) content, CMISRA, localName);
        return child == null ? null : child.getTextContent();
    }

    /**
     * Returns the first direct child element of {@code parent} with the given namespace and local name.
     *
     * @param parent    the parent element
     * @param namespace the child's namespace URI
     * @param localName the child's local name
     * @return the child, or null when there is none
     */
    private static Element firstChild(Element parent, String namespace, String localName) {
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element
                    && namespace.equals(node.getNamespaceURI())
                    && localName.equals(node.getLocalName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /**
     * Describes a request for the log: its method and raw target.
     *
     * @param request the recorded request, possibly null
     * @return the description
     */
    private static String describe(RecordedRequest request) {
        return request == null ? "a null request" : request.getMethod() + " " + request.getPath();
    }
}

