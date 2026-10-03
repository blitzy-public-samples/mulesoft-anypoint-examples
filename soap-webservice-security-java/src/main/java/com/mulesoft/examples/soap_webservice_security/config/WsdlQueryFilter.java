package com.mulesoft.examples.soap_webservice_security.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.Source;
import javax.xml.transform.sax.SAXSource;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.ws.wsdl.WsdlDefinitionException;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.xml.sax.InputSource;

/**
 * Serves {@code wsdl/Greeter.wsdl} for {@code ?wsdl} on each of the six Greeter service paths, with
 * {@code soap:address} set to the request address (D-028, D-679).
 *
 * <p>Sources: the {@code cxf:jaxws-service} of each service flow publishes its WSDL at {@code <address>?wsdl}
 * [soap-webservice-security/src/main/app/mule-config.xml:11,17,30,44,58,73], under the listener paths
 * {@code unsecure}, {@code username}, {@code signed}, {@code encrypted}, {@code saml} and {@code signedsaml}
 * [:9,16,29,43,57,72] of {@code HTTP_Listener_Configuration1}, base path {@code services} [:6]; the original client
 * reads it from, for example, {@code http://localhost:63081/services/username?wsdl}
 * [soap-webservice-security/src/main/java/com/mulesoft/mule/example/security/SecureClient.java:46-124].
 *
 * <p>A request is answered here when all three hold:
 * <ul>
 *   <li>its method is exactly {@code GET};</li>
 *   <li>its context-relative path, the undecoded {@link HttpServletRequest#getRequestURI()} without
 *       {@link HttpServletRequest#getContextPath()}, equals {@code /<base-path>/<p>} for {@code <p>} one of
 *       {@code unsecure}, {@code username}, {@code signed}, {@code encrypted}, {@code saml} and
 *       {@code signedsaml}, where {@code <base-path>} is {@code listener.http-listener-configuration1.base-path}
 *       (default {@code services}); paths are compared as exact, case-sensitive strings;</li>
 *   <li>its {@link HttpServletRequest#getQueryString()} equals {@code wsdl} in any letter case, for example
 *       {@code ?wsdl} or {@code ?WSDL}; {@code ?wsdl=1}, {@code ?wsdl&x}, {@code ?xsd=1} and an absent query do not
 *       match.</li>
 * </ul>
 * Every other request goes to the rest of the filter chain unchanged, for example the SOAP {@code POST} to
 * {@code /services/<p>}, {@code HEAD /services/<p>?wsdl} and {@code GET /services/<p>} without a query. The
 * contract carries its schema inline, and no {@code ?xsd=} query is served.
 *
 * <p>The answer is status {@code 200}, {@code Content-Type: text/xml;charset=UTF-8}, a {@code Content-Length}
 * equal to the UTF-8 byte length of the body, and the bytes of the committed contract in which only the value of
 * the {@code location} attribute of its single {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element is
 * replaced by the request address
 * {@code request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()
 * + request.getRequestURI()}, without the query. The address is written with {@code &}, {@code <}, {@code >},
 * {@code "} and {@code '} as {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;} and {@code &apos;}, and the
 * parsed attribute value equals the request address (D-679). Every other byte equals the committed file. The rest of
 * the chain does not run for an answered request. For {@code GET http://localhost:63081/services/signed?WSDL} the
 * served port reads:
 * <pre>{@code
 * <wsdl:port binding="tns:GreeterServiceSoapBinding" name="GreeterPort">
 *   <soap:address location="http://localhost:63081/services/signed"/>
 * </wsdl:port>
 * }</pre>
 *
 * <p>The contract is read once, when the filter is constructed, from the byte stream of the {@link SAXSource} that
 * {@link SimpleWsdl11Definition#getSource()} of the {@code greeterWsdlDefinition} bean of {@link WsSecurityConfig}
 * returns, and is decoded as UTF-8. A namespace-aware StAX pass, with DTD support and external entities disabled,
 * finds the single {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element and its unprefixed
 * {@code location} value; the text {@code location="<value>"} must then occur exactly once in the decoded contract,
 * and that occurrence is the one replaced per request. Construction fails with {@link IllegalStateException} when
 * the contract cannot be read, is not valid UTF-8 or not well-formed XML, has no or several such address elements,
 * the element has no unprefixed {@code location} attribute, or {@code location="<value>"} does not occur exactly
 * once. The committed file is never written; the replaced address exists only in the response.
 *
 * <p>Spring Boot registers this {@code @Component} as a servlet filter for {@code /*} and the {@code REQUEST}
 * dispatcher type with the order {@link #ORDER}, after {@link PortPathGuardFilter}, which has already answered 404
 * for a path the local port does not own (D-011). Error and async dispatches pass through unfiltered, the
 * {@link OncePerRequestFilter} defaults. Instances are immutable and safe for concurrent requests. Each answered
 * request writes one DEBUG log entry with the matched path, the query string and the body length.
 */
@Component
public class WsdlQueryFilter extends OncePerRequestFilter implements Ordered {

    /** Filter order, ten after {@link PortPathGuardFilter#ORDER}: this filter runs after the port guard. */
    public static final int ORDER = PortPathGuardFilter.ORDER + 10;

    /** Name of the {@link SimpleWsdl11Definition} bean of {@link WsSecurityConfig} served by this filter. */
    private static final String WSDL_DEFINITION_BEAN = "greeterWsdlDefinition";

    /** Listener paths of the six Greeter service flows under the services listener's base path. */
    private static final List<String> SERVICE_PATH_NAMES =
            List.of("unsecure", "username", "signed", "encrypted", "saml", "signedsaml");

    /** Namespace of the WSDL 1.1 SOAP binding extension elements. */
    private static final String SOAP_BINDING_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/soap/";

    /** Local name of the SOAP address element. */
    private static final String ADDRESS_ELEMENT = "address";

    /** Name of the address attribute of the SOAP address element. */
    private static final String LOCATION_ATTRIBUTE = "location";

    /** Method of the WSDL request, compared exactly. */
    private static final String GET = "GET";

    /** Query of the WSDL request, compared in any letter case. */
    private static final String WSDL_QUERY = "wsdl";

    /** {@code Content-Type} of the served contract. */
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";

    /** Logger of the answered requests, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(WsdlQueryFilter.class);

    /** The six context-relative paths answered with the contract; immutable. */
    private final Set<String> wsdlPaths;

    /** Committed contract text up to and including {@code location="}; never written. */
    private final String contractHead;

    /** Committed contract text from the closing quote of the replaced {@code location} value; never written. */
    private final String contractTail;

    /**
     * Creates the filter and reads the committed contract once (D-028).
     *
     * @param greeterWsdlDefinition the {@code greeterWsdlDefinition} bean of {@link WsSecurityConfig}, the fixed
     *                              contract {@code wsdl/Greeter.wsdl}
     * @param basePath              the value of {@code listener.http-listener-configuration1.base-path}, default
     *                              {@code services}
     * @throws NullPointerException  if an argument is {@code null}
     * @throws IllegalStateException if the contract cannot be read, is not valid UTF-8 or not well-formed XML, does
     *                               not hold exactly one {@code {http://schemas.xmlsoap.org/wsdl/soap/}address}
     *                               element with an unprefixed {@code location} attribute, or does not hold the text
     *                               {@code location="<value>"} exactly once
     */
    public WsdlQueryFilter(
            @Qualifier(WSDL_DEFINITION_BEAN) SimpleWsdl11Definition greeterWsdlDefinition,
            @Value("${listener.http-listener-configuration1.base-path}") String basePath) {
        super();
        Objects.requireNonNull(greeterWsdlDefinition, WSDL_DEFINITION_BEAN + " must not be null");
        Objects.requireNonNull(basePath, "listener.http-listener-configuration1.base-path must not be null");
        this.wsdlPaths = servicePaths(basePath);
        byte[] bytes = contractBytes(greeterWsdlDefinition);
        String wsdl = decodeUtf8(bytes);
        String location = soapAddressLocation(bytes);
        int valueStart = singleLocationValueIndex(wsdl, location);
        this.contractHead = wsdl.substring(0, valueStart);
        this.contractTail = wsdl.substring(valueStart + location.length());
        LOG.debug("Read {} with soap:address location {}; serving it for ?wsdl on {}", WSDL_DEFINITION_BEAN,
                location, this.wsdlPaths);
    }

    /**
     * Writes the contract with {@code soap:address} set to the request address for a {@code GET} on one of the six
     * service paths with the query {@code wsdl} in any letter case, and passes every other request to
     * {@code filterChain} unchanged (D-028).
     *
     * <p>The steps for a matching request, in order: {@code setStatus(200)},
     * {@code setContentType("text/xml;charset=UTF-8")}, {@code setContentLength} with the UTF-8 byte length of the
     * body, then the body bytes on {@link HttpServletResponse#getOutputStream()}, flushed. {@code filterChain} is
     * not called for it.
     *
     * @param request     the incoming request; its method, request URI, context path and query string decide, and
     *                    its scheme, server name, server port and request URI form the served address
     * @param response    the response, written only for a matching request
     * @param filterChain the rest of the filter chain, called only for a request that does not match
     * @throws ServletException if a later filter or the servlet fails
     * @throws IOException      if a later filter or the servlet fails to read or write, or the body cannot be
     *                          written
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String path = matchedPath(request);
        if (path == null) {
            filterChain.doFilter(request, response);
            return;
        }
        String address = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort()
                + request.getRequestURI();
        byte[] body = servedContract(address).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
        LOG.debug("Served {} for GET {}?{}: {} bytes", WSDL_DEFINITION_BEAN, path, request.getQueryString(),
                body.length);
    }

    /**
     * Returns the filter order, {@link #ORDER}.
     *
     * @return {@code PortPathGuardFilter.ORDER + 10}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Returns the context-relative path of a WSDL request: method exactly {@code GET}, query string {@code wsdl} in
     * any letter case and context-relative path one of the six service paths.
     *
     * @param request the incoming request
     * @return the matched context-relative path, or {@code null} when the request is not a WSDL request
     */
    private String matchedPath(HttpServletRequest request) {
        if (!GET.equals(request.getMethod()) || !WSDL_QUERY.equalsIgnoreCase(request.getQueryString())) {
            return null;
        }
        String path = contextRelativePath(request);
        if (path == null || !wsdlPaths.contains(path)) {
            return null;
        }
        return path;
    }

    /**
     * Returns the committed contract with its single {@code location} value replaced by {@code address},
     * XML-attribute-escaped; every other character is unchanged.
     *
     * @param address the request address, without the query
     * @return the contract text to serve
     */
    private String servedContract(String address) {
        String escaped = escapeAttribute(address);
        return new StringBuilder(contractHead.length() + escaped.length() + contractTail.length())
                .append(contractHead)
                .append(escaped)
                .append(contractTail)
                .toString();
    }

    /**
     * Returns the six context-relative service paths {@code "/" + basePath + "/" + name}.
     *
     * @param basePath the services listener base path
     * @return the immutable set of service paths
     */
    private static Set<String> servicePaths(String basePath) {
        Set<String> paths = new LinkedHashSet<>();
        for (String name : SERVICE_PATH_NAMES) {
            paths.add("/" + basePath + "/" + name);
        }
        return Set.copyOf(paths);
    }

    /**
     * Returns the request URI without the context path.
     *
     * @param request the incoming request
     * @return the undecoded request URI without the context path; {@code null} when the request has no URI or the
     *         URI does not start with a non-empty context path
     */
    private static String contextRelativePath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        String contextPath = request.getContextPath();
        if (contextPath == null || contextPath.isEmpty()) {
            return uri;
        }
        return uri.startsWith(contextPath) ? uri.substring(contextPath.length()) : null;
    }

    /**
     * Returns {@code value} with {@code &}, {@code <}, {@code >}, {@code "} and {@code '} replaced by
     * {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;} and {@code &apos;}; every other character is kept
     * (D-679).
     *
     * @param value the attribute value to escape
     * @return the escaped attribute value
     */
    private static String escapeAttribute(String value) {
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
     * Reads every byte of the byte stream of the {@link SAXSource} that {@code definition} returns.
     *
     * @param definition the WSDL definition bean
     * @return the committed contract bytes
     * @throws IllegalStateException when the source cannot be created, is not a {@link SAXSource} with a byte
     *                               stream, or cannot be read
     */
    private static byte[] contractBytes(SimpleWsdl11Definition definition) {
        Source source;
        try {
            source = definition.getSource();
        } catch (WsdlDefinitionException ex) {
            throw new IllegalStateException("Cannot open the WSDL contract of " + WSDL_DEFINITION_BEAN, ex);
        }
        InputSource inputSource = source instanceof SAXSource saxSource ? saxSource.getInputSource() : null;
        InputStream byteStream = inputSource == null ? null : inputSource.getByteStream();
        if (byteStream == null) {
            throw new IllegalStateException("The WSDL contract of " + WSDL_DEFINITION_BEAN
                    + " is not a SAXSource with a byte stream: " + source);
        }
        try (InputStream in = byteStream) {
            return StreamUtils.copyToByteArray(in);
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot read the WSDL contract of " + WSDL_DEFINITION_BEAN, ex);
        }
    }

    /**
     * Decodes {@code bytes} as UTF-8, rejecting malformed and unmappable input.
     *
     * @param bytes the contract bytes
     * @return the contract text
     * @throws IllegalStateException when {@code bytes} are not valid UTF-8
     */
    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalStateException("The WSDL contract of " + WSDL_DEFINITION_BEAN + " is not valid UTF-8",
                    ex);
        }
    }

    /**
     * Returns the unprefixed {@code location} value of the single
     * {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element of the contract, read by a namespace-aware
     * StAX pass with DTD support and external entities disabled.
     *
     * @param bytes the contract bytes
     * @return the committed {@code location} value
     * @throws IllegalStateException when the contract is not well-formed XML, holds no or several such address
     *                               elements, or the element has no unprefixed {@code location} attribute
     */
    private static String soapAddressLocation(byte[] bytes) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        int addressCount = 0;
        String location = null;
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && SOAP_BINDING_NAMESPACE.equals(reader.getNamespaceURI())
                        && ADDRESS_ELEMENT.equals(reader.getLocalName())) {
                    addressCount++;
                    location = unprefixedAttribute(reader, LOCATION_ATTRIBUTE);
                }
            }
        } catch (XMLStreamException ex) {
            throw new IllegalStateException("The WSDL contract of " + WSDL_DEFINITION_BEAN
                    + " is not well-formed XML", ex);
        } finally {
            closeQuietly(reader);
        }
        if (addressCount != 1) {
            throw new IllegalStateException("The WSDL contract of " + WSDL_DEFINITION_BEAN + " holds " + addressCount
                    + " {" + SOAP_BINDING_NAMESPACE + "}" + ADDRESS_ELEMENT + " elements, expected exactly 1");
        }
        if (location == null) {
            throw new IllegalStateException("The {" + SOAP_BINDING_NAMESPACE + "}" + ADDRESS_ELEMENT
                    + " element of the WSDL contract of " + WSDL_DEFINITION_BEAN + " has no " + LOCATION_ATTRIBUTE
                    + " attribute");
        }
        return location;
    }

    /**
     * Returns the value of the attribute {@code localName} in no namespace of the current start element.
     *
     * @param reader    the reader positioned on a start element
     * @param localName the attribute's local name
     * @return the attribute value, or {@code null} when the element has no such attribute
     */
    private static String unprefixedAttribute(XMLStreamReader reader, String localName) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String namespace = reader.getAttributeNamespace(i);
            if ((namespace == null || namespace.isEmpty()) && localName.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /**
     * Closes {@code reader}, logging a close failure at DEBUG.
     *
     * @param reader the reader to close; may be {@code null}
     */
    private static void closeQuietly(XMLStreamReader reader) {
        if (reader == null) {
            return;
        }
        try {
            reader.close();
        } catch (XMLStreamException ex) {
            LOG.debug("Could not close the StAX reader of {}", WSDL_DEFINITION_BEAN, ex);
        }
    }

    /**
     * Returns the index in {@code wsdl} of the first character of {@code location} inside the single occurrence of
     * {@code location="<location>"}.
     *
     * @param wsdl     the contract text
     * @param location the committed {@code location} value
     * @return the index of the value's first character
     * @throws IllegalStateException when {@code location="<location>"} occurs zero times or more than once
     */
    private static int singleLocationValueIndex(String wsdl, String location) {
        String attribute = LOCATION_ATTRIBUTE + "=\"" + location + "\"";
        int first = wsdl.indexOf(attribute);
        if (first < 0) {
            throw new IllegalStateException("The WSDL contract of " + WSDL_DEFINITION_BEAN + " does not contain "
                    + attribute);
        }
        if (wsdl.indexOf(attribute, first + 1) >= 0) {
            throw new IllegalStateException("The WSDL contract of " + WSDL_DEFINITION_BEAN + " contains "
                    + attribute + " more than once");
        }
        return first + LOCATION_ATTRIBUTE.length() + 2;
    }
}

