package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.ws.wsdl.WsdlDefinitionException;
import org.springframework.ws.wsdl.wsdl11.SimpleWsdl11Definition;
import org.xml.sax.InputSource;

import com.mulesoft.examples.foreach_processing_and_choice_routing.config.ListenerProperties.Listener;

/**
 * Answers {@code GET <service URL>?wsdl} on the SOAP ports with the fixed, committed contract of the service, its
 * {@code soap:address} set to the request URL (D-028).
 *
 * <p>Counterpart of the WSDL each {@code cxf:jaxws-service} of
 * {@code foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml} publishes at
 * {@code <address>?wsdl}: :140 (flow {@code TheCreditAgencyService}) and :148, :158, :169, :179, :189 (flows
 * {@code Bank1Flow} … {@code Bank5Flow}). The served contracts are the {@link SimpleWsdl11Definition} beans of
 * {@link WsConfig}:
 * <ul>
 *   <li>{@code creditAgencyWsdl}, {@code wsdl/CreditAgencyService.wsdl} (committed address
 *       {@code http://localhost:18080/mule/TheCreditAgencyService}), on the port of listener 2;</li>
 *   <li>{@code bankWsdl}, {@code wsdl/BankService.wsdl} (committed address
 *       {@code http://localhost:10080/mule/TheBank1}), on the ports of listeners 3 … 7, one file for all five
 *       banks.</li>
 * </ul>
 *
 * <p>A request is answered here when all of these hold:
 * <ul>
 *   <li>its local port is the port of one of listeners 2 … 7 ({@link ListenerProperties#additionalOnPort(int)}
 *       present); a request on any other port, such as {@code 11081} of listener 1 or a random test port of
 *       listener 1, always continues down the chain, and {@code ?wsdl} there reaches the loan broker;</li>
 *   <li>its method is exactly {@code GET};</li>
 *   <li>its raw query string is {@code wsdl} in any letter case, for example {@code ?wsdl} or {@code ?WSDL};
 *       {@code ?wsdl=1}, {@code ?wsdl&x} and an absent query do not match;</li>
 *   <li>its raw, undecoded {@link HttpServletRequest#getRequestURI()} equals the base path of the listener on that
 *       port or starts with the base path followed by {@code /}, for example {@code /mule/TheBank1} and
 *       {@code /mule/TheBank1/x} on listener 3.</li>
 * </ul>
 * Every other request continues down the filter chain unchanged. The contract is {@code creditAgencyWsdl} when the
 * listener on the port is {@link ListenerProperties#creditAgency()}, and {@code bankWsdl} for listeners 3 … 7.
 *
 * <p>The answer is status {@code 200}, {@code Content-Type: text/xml; charset=UTF-8}, a {@code Content-Length}
 * header and the committed bytes of the contract, in which only the value of the {@code location} attribute of the
 * element {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} is replaced by
 * {@link HttpServletRequest#getRequestURL()} (scheme, host as sent in the {@code Host} header, port and path, no
 * query) with {@code &}, {@code <}, {@code "} and {@code '} written as {@code &amp;}, {@code &lt;},
 * {@code &quot;} and {@code &apos;}. Every other byte equals the committed file, the quote character of the
 * attribute included. The rest of the chain is not called for an answered request. For
 * {@code GET http://localhost:30080/mule/TheBank3?WSDL} the served port reads:
 * <pre>{@code
 * <wsdl:port name="BankPort" binding="tns:BankServiceSoapBinding">
 *   <soap:address location="http://localhost:30080/mule/TheBank3"/>
 * </wsdl:port>
 * }</pre>
 *
 * <p>Each contract is read once, when the filter is constructed, from the byte stream of the
 * {@link SAXSource} that {@link SimpleWsdl11Definition#getSource()} returns, and is never parsed into a DOM or
 * re-serialised. A namespace-aware StAX pass, with DTD support and external entities disabled, finds the first
 * {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element, its prefix and its {@code location} value; the
 * start tag of that element is then located in the UTF-8 text of the bytes, outside comments, CDATA sections and
 * processing instructions, and the raw attribute value found there must read as the value the StAX pass reported.
 * Startup stops with {@link IllegalStateException} when a contract cannot be read, is not valid UTF-8 or not
 * well-formed XML, declares a document type, has no such element, or that element has no unprefixed
 * {@code location} attribute. The committed files are never written; the replaced address exists only in the
 * response.
 *
 * <p>Spring Boot registers this {@code @Component} as a servlet filter for {@code /*} at
 * {@code Ordered.HIGHEST_PRECEDENCE + 1}, directly after {@code PortPathGuardFilter}, which has already answered a
 * path the port does not own (D-011). Inherited from {@link OncePerRequestFilter}: the filter runs once per request
 * and is skipped for async and error dispatches. Instances are immutable and safe for concurrent requests. Each
 * answered request is logged at DEBUG with its local port, the served contract and the body length.
 */
@Component
public class WsdlQueryFilter extends OncePerRequestFilter implements Ordered {

    /** Order of this filter: directly after {@code PortPathGuardFilter} at {@link Ordered#HIGHEST_PRECEDENCE}. */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1;

    /** Namespace of the WSDL 1.1 SOAP binding extension elements. */
    private static final String SOAP_BINDING_NAMESPACE = "http://schemas.xmlsoap.org/wsdl/soap/";

    /** Local name of the SOAP address element. */
    private static final String ADDRESS = "address";

    /** Name of the address attribute of the SOAP address element. */
    private static final String LOCATION = "location";

    /** Method of the WSDL request, compared exactly. */
    private static final String GET = "GET";

    /** Query of the WSDL request, compared in any letter case. */
    private static final String WSDL_QUERY = "wsdl";

    /** {@code Content-Type} of the served contract. */
    private static final String CONTENT_TYPE = "text/xml; charset=UTF-8";

    /** Path separator appended to a base path to form the prefix of its child paths. */
    private static final String SLASH = "/";

    /** Bean name of the credit agency contract in {@link WsConfig}. */
    private static final String CREDIT_AGENCY_WSDL = "creditAgencyWsdl";

    /** Bean name of the bank contract in {@link WsConfig}. */
    private static final String BANK_WSDL = "bankWsdl";

    /** Listener addresses; listeners 2 … 7 are the SOAP ports this filter answers on. */
    private final ListenerProperties listeners;

    // Each contract is held as two plain fields, its committed bytes and the byte offsets of its location value;
    // the class declares no nested type.

    /** Committed bytes of the credit agency contract, served on the port of listener 2; never written. */
    private final byte[] creditAgencyBytes;

    /**
     * Byte offsets in {@link #creditAgencyBytes} of the raw {@code location} value of its first
     * {@code {http://schemas.xmlsoap.org/wsdl/soap/}address}: index {@code 0} is the first byte after the opening
     * quote, index {@code 1} the closing quote; never written.
     */
    private final int[] creditAgencyLocation;

    /** Committed bytes of the bank contract, served on the ports of listeners 3 … 7; never written. */
    private final byte[] bankBytes;

    /** Byte offsets in {@link #bankBytes} of its raw {@code location} value, as for {@link #creditAgencyLocation}. */
    private final int[] bankLocation;

    /**
     * Creates the filter and reads both committed contracts once.
     *
     * @param listeners        the bound {@code listener.http-listener-configuration-<n>} entries
     * @param creditAgencyWsdl the {@code creditAgencyWsdl} bean of {@link WsConfig},
     *                         {@code wsdl/CreditAgencyService.wsdl}
     * @param bankWsdl         the {@code bankWsdl} bean of {@link WsConfig}, {@code wsdl/BankService.wsdl}
     * @throws NullPointerException  when an argument is {@code null}
     * @throws IllegalStateException when a contract cannot be read, is not valid UTF-8 or not well-formed XML,
     *                               declares a document type, has no
     *                               {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element, or that
     *                               element has no unprefixed {@code location} attribute
     */
    public WsdlQueryFilter(ListenerProperties listeners,
            @Qualifier(CREDIT_AGENCY_WSDL) SimpleWsdl11Definition creditAgencyWsdl,
            @Qualifier(BANK_WSDL) SimpleWsdl11Definition bankWsdl) {
        this.listeners = Objects.requireNonNull(listeners, "listeners");
        String creditAgency = describe(CREDIT_AGENCY_WSDL,
                Objects.requireNonNull(creditAgencyWsdl, CREDIT_AGENCY_WSDL));
        String bank = describe(BANK_WSDL, Objects.requireNonNull(bankWsdl, BANK_WSDL));
        this.creditAgencyBytes = committedBytes(creditAgency, creditAgencyWsdl);
        this.creditAgencyLocation = locationValue(creditAgency, this.creditAgencyBytes);
        this.bankBytes = committedBytes(bank, bankWsdl);
        this.bankLocation = locationValue(bank, this.bankBytes);
        if (logger.isDebugEnabled()) {
            logger.debug("Read " + creditAgency + " with soap:address location "
                    + rawValue(this.creditAgencyBytes, this.creditAgencyLocation) + " and " + bank
                    + " with soap:address location " + rawValue(this.bankBytes, this.bankLocation));
        }
    }

    /**
     * Returns the filter order.
     *
     * @return {@code Ordered.HIGHEST_PRECEDENCE + 1}
     */
    @Override
    public int getOrder() {
        return ORDER;
    }

    /**
     * Writes the contract of the service on the request's port for a {@code GET} with the query {@code wsdl} in any
     * letter case on a path the port's listener owns, and passes every other request to {@code chain} unchanged
     * (D-028).
     *
     * @param request  the incoming request; its local port, method, raw query and raw request URI decide, and its
     *                 request URL becomes the served {@code soap:address}
     * @param response the response, written with the contract for a WSDL request and untouched otherwise
     * @param chain    the rest of the filter chain, called only for a request that is not a WSDL request
     * @throws ServletException      from the rest of the chain
     * @throws IOException           from the rest of the chain, or while writing the contract
     * @throws IllegalStateException from {@link ListenerProperties#additionalOnPort(int)} when one of the entries
     *                               {@code http-listener-configuration-2} … {@code -7} is not bound
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Listener> owner = listeners.additionalOnPort(request.getLocalPort());
        if (owner.isEmpty() || !isWsdlRequest(request, owner.get().basePath())) {
            chain.doFilter(request, response);
            return;
        }
        boolean creditAgency = owner.get().equals(listeners.creditAgency());
        String requestUrl = request.getRequestURL().toString();
        byte[] body = creditAgency
                ? render(creditAgencyBytes, creditAgencyLocation, requestUrl)
                : render(bankBytes, bankLocation, requestUrl);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
        if (logger.isDebugEnabled()) {
            logger.debug("Served " + (creditAgency ? CREDIT_AGENCY_WSDL : BANK_WSDL) + " on local port "
                    + request.getLocalPort() + ": " + body.length + " bytes");
        }
    }

    /**
     * Tells whether {@code request} is a {@code GET} with the raw query {@code wsdl} in any letter case on a raw
     * request URI that equals {@code basePath} or starts with {@code basePath + "/"}.
     *
     * @param request  the incoming request
     * @param basePath the base path of the listener on the request's local port
     * @return {@code true} for a WSDL request
     */
    private static boolean isWsdlRequest(HttpServletRequest request, String basePath) {
        if (!GET.equals(request.getMethod())) {
            return false;
        }
        String query = request.getQueryString();
        if (query == null || !WSDL_QUERY.equalsIgnoreCase(query)) {
            return false;
        }
        String uri = request.getRequestURI();
        return uri != null && (uri.equals(basePath) || uri.startsWith(basePath + SLASH));
    }

    /**
     * Returns {@code value} with {@code &}, {@code <}, {@code "} and {@code '} replaced by {@code &amp;},
     * {@code &lt;}, {@code &quot;} and {@code &apos;}; every other character is kept.
     *
     * @param value the attribute value to escape
     * @return the escaped attribute value
     */
    static String escapeAttribute(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&apos;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * Returns a new array holding {@code committed} with the bytes between {@code location[0]} and
     * {@code location[1]} replaced by {@code requestUrl}, escaped as {@link #escapeAttribute(String)} describes and
     * encoded in UTF-8. {@code committed} is not written.
     *
     * @param committed  the committed bytes of the contract
     * @param location   byte offsets of the raw {@code location} value: first byte after the opening quote, closing
     *                   quote
     * @param requestUrl the request URL without the query
     * @return the served contract
     */
    private static byte[] render(byte[] committed, int[] location, String requestUrl) {
        byte[] address = escapeAttribute(requestUrl).getBytes(StandardCharsets.UTF_8);
        int tailLength = committed.length - location[1];
        byte[] body = new byte[location[0] + address.length + tailLength];
        System.arraycopy(committed, 0, body, 0, location[0]);
        System.arraycopy(address, 0, body, location[0], address.length);
        System.arraycopy(committed, location[1], body, location[0] + address.length, tailLength);
        return body;
    }

    /**
     * Returns the raw {@code location} value of a committed contract.
     *
     * @param committed the committed bytes of the contract
     * @param location  byte offsets of the raw {@code location} value
     * @return the UTF-8 text between the quote characters
     */
    private static String rawValue(byte[] committed, int[] location) {
        return new String(committed, location[0], location[1] - location[0], StandardCharsets.UTF_8);
    }

    /**
     * Returns the description of a contract used in log lines and exception messages.
     *
     * @param beanName   the bean name of the definition in {@link WsConfig}
     * @param definition the contract definition
     * @return {@code <beanName> (<definition>)}
     */
    private static String describe(String beanName, SimpleWsdl11Definition definition) {
        return beanName + " (" + definition + ")";
    }

    /**
     * Returns the byte offsets of the raw value of the {@code location} attribute of the first
     * {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element in the committed bytes.
     *
     * @param description bean name and resource of the contract, named in exception messages
     * @param committed   the committed bytes of the contract
     * @return the offset of the first byte after the opening quote and the offset of the closing quote
     * @throws IllegalStateException when the bytes are not valid UTF-8 or not well-formed XML, declare a document
     *                               type, hold no such element, or the element has no unprefixed {@code location}
     *                               attribute
     */
    private static int[] locationValue(String description, byte[] committed) {
        String text = decodeUtf8(description, committed);
        int[] characters = locateAddress(description, committed, text);
        return new int[] {
            text.substring(0, characters[0]).getBytes(StandardCharsets.UTF_8).length,
            text.substring(0, characters[1]).getBytes(StandardCharsets.UTF_8).length,
        };
    }

    /**
     * Reads every byte of the byte stream of the {@link SAXSource} that {@code definition} returns, and closes the
     * stream.
     *
     * @param description bean name and resource of the contract, named in exception messages
     * @param definition  the contract definition
     * @return the committed bytes of the contract
     * @throws IllegalStateException when the definition cannot open its resource, its source is not a
     *                               {@link SAXSource} with a byte stream, or the stream cannot be read
     */
    private static byte[] committedBytes(String description, SimpleWsdl11Definition definition) {
        Source source;
        try {
            source = definition.getSource();
        }
        catch (WsdlDefinitionException ex) {
            throw new IllegalStateException("WSDL contract " + description + " cannot be opened", ex);
        }
        InputSource input = (source instanceof SAXSource saxSource) ? saxSource.getInputSource() : null;
        InputStream stream = (input != null) ? input.getByteStream() : null;
        if (stream == null) {
            throw new IllegalStateException("WSDL contract " + description + " provides no byte stream");
        }
        try (InputStream in = stream) {
            return StreamUtils.copyToByteArray(in);
        }
        catch (IOException ex) {
            throw new IllegalStateException("WSDL contract " + description + " cannot be read", ex);
        }
    }

    /**
     * Decodes {@code bytes} as UTF-8, rejecting malformed and unmappable input.
     *
     * @param description bean name and resource of the contract, named in the exception message
     * @param bytes       the committed bytes
     * @return the contract text
     * @throws IllegalStateException when {@code bytes} is not valid UTF-8
     */
    private static String decodeUtf8(String description, byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        catch (CharacterCodingException ex) {
            throw new IllegalStateException("WSDL contract " + description + " is not valid UTF-8", ex);
        }
    }

    /**
     * Finds the first {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} element with a namespace-aware StAX
     * pass over {@code bytes}, with DTD support and external entities disabled, and locates the raw value of its
     * {@code location} attribute in {@code text}.
     *
     * @param description bean name and resource of the contract, named in exception messages
     * @param bytes       the committed bytes
     * @param text        the committed bytes decoded from UTF-8
     * @return the character index in {@code text} of the first character after the opening quote of the raw value,
     *         and the character index of its closing quote
     * @throws IllegalStateException when the bytes are not well-formed XML, declare a document type, hold no such
     *                               element, or the element has no unprefixed {@code location} attribute
     */
    private static int[] locateAddress(String description, byte[] bytes, String text) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.TRUE);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        try {
            XMLStreamReader reader = factory.createXMLStreamReader(new ByteArrayInputStream(bytes));
            try {
                return locateAddress(description, reader, text);
            }
            finally {
                reader.close();
            }
        }
        catch (XMLStreamException ex) {
            throw new IllegalStateException("WSDL contract " + description + " is not well-formed XML", ex);
        }
    }

    /**
     * Reads {@code reader} up to the first {@code {http://schemas.xmlsoap.org/wsdl/soap/}address} start element,
     * counting the earlier start tags whose local name is {@code address} by qualified name, then locates that
     * element's start tag and raw {@code location} value in {@code text} through
     * {@link #locateValue(String, String, String, String, int)}.
     *
     * @param description bean name and resource of the contract, named in exception messages
     * @param reader      the StAX reader over the committed bytes
     * @param text        the committed bytes decoded from UTF-8
     * @return the character span of the raw {@code location} value in {@code text}
     * @throws XMLStreamException    when the bytes are not well-formed XML
     * @throws IllegalStateException when the bytes declare a document type, hold no such element, or the element
     *                               has no unprefixed {@code location} attribute
     */
    private static int[] locateAddress(String description, XMLStreamReader reader, String text)
            throws XMLStreamException {
        Map<String, Integer> earlier = new HashMap<>();
        while (reader.hasNext()) {
            int event = reader.next();
            // The contracts are read with DTD support disabled: any document type declaration stops startup.
            if (event == XMLStreamConstants.DTD) {
                throw new IllegalStateException("WSDL contract " + description + " declares a document type");
            }
            if (event != XMLStreamConstants.START_ELEMENT || !ADDRESS.equals(reader.getLocalName())) {
                continue;
            }
            String prefix = reader.getPrefix();
            String qualifiedName = (prefix == null || prefix.isEmpty()) ? ADDRESS : prefix + ':' + ADDRESS;
            if (SOAP_BINDING_NAMESPACE.equals(reader.getNamespaceURI())) {
                String location = unprefixedLocation(reader);
                if (location == null) {
                    throw new IllegalStateException("WSDL contract " + description + " has a {"
                            + SOAP_BINDING_NAMESPACE + "}address element without a location attribute");
                }
                // The start tag is matched by its ordinal among the start tags of the same qualified name, which
                // names the namespace-resolved element also where a prefix is bound to another namespace earlier.
                return locateValue(description, text, qualifiedName, location,
                        earlier.getOrDefault(qualifiedName, 0));
            }
            earlier.merge(qualifiedName, 1, Integer::sum);
        }
        throw new IllegalStateException("WSDL contract " + description + " has no {" + SOAP_BINDING_NAMESPACE
                + "}address element");
    }

    /**
     * Returns the value of the attribute {@code location} in no namespace of the current start element.
     *
     * @param reader the StAX reader positioned on a start element
     * @return the attribute value as the parser reports it, or {@code null} when the element has no such attribute
     */
    private static String unprefixedLocation(XMLStreamReader reader) {
        for (int i = 0; i < reader.getAttributeCount(); i++) {
            String namespace = reader.getAttributeNamespace(i);
            if ((namespace == null || namespace.isEmpty()) && LOCATION.equals(reader.getAttributeLocalName(i))) {
                return reader.getAttributeValue(i);
            }
        }
        return null;
    }

    /**
     * Locates the raw value of the {@code location} attribute of the element the StAX pass found in the contract
     * text: the start tag named {@code qualifiedName} whose ordinal among the start tags of that name is
     * {@code ordinal}, counted outside comments, CDATA sections and processing instructions.
     *
     * @param description   bean name and resource of the contract, named in exception messages
     * @param text          the contract text
     * @param qualifiedName the element name as written, {@code soap:address} in both committed contracts
     * @param location      the {@code location} value the StAX pass reported
     * @param ordinal       the number of start tags named {@code qualifiedName} before the element, {@code 0} in
     *                      both committed contracts
     * @return the character index of the first character after the opening quote of the raw value, and the
     *         character index of its closing quote
     * @throws IllegalStateException when the start tag or its {@code location} attribute is not found, or the raw
     *                               value does not read as {@code location}
     */
    private static int[] locateValue(String description, String text, String qualifiedName, String location,
            int ordinal) {
        int seen = 0;
        int index = text.indexOf('<');
        while (index >= 0) {
            int next;
            if (text.startsWith("<!--", index)) {
                next = after(text, "-->", index + 4);
            }
            else if (text.startsWith("<![CDATA[", index)) {
                next = after(text, "]]>", index + 9);
            }
            else if (text.startsWith("<?", index)) {
                next = after(text, "?>", index + 2);
            }
            else if (text.startsWith("</", index) || text.startsWith("<!", index)) {
                next = index + 2;
            }
            else {
                int nameEnd = nameEnd(text, index + 1);
                if (text.substring(index + 1, nameEnd).equals(qualifiedName)) {
                    if (seen == ordinal) {
                        return checkedValue(description, text, nameEnd, qualifiedName, location);
                    }
                    seen++;
                }
                next = nameEnd;
            }
            index = text.indexOf('<', next);
        }
        throw new IllegalStateException("WSDL contract " + description + " has no <" + qualifiedName
                + "> start tag");
    }

    /**
     * Reads the attributes of a start tag from {@code from}, just after its name, up to the {@code location}
     * attribute, and checks that its raw value reads as {@code location}.
     *
     * @param description   bean name and resource of the contract, named in exception messages
     * @param text          the contract text
     * @param from          index just after the element name of the start tag
     * @param qualifiedName the element name as written, named in exception messages
     * @param location      the {@code location} value the StAX pass reported
     * @return the character index of the first character after the opening quote of the raw {@code location}
     *         value, and the character index of its closing quote
     * @throws IllegalStateException when the start tag has no {@code location} attribute, an attribute is not
     *                               written as {@code name="value"} or {@code name='value'}, or the raw value does
     *                               not read as {@code location}
     */
    private static int[] checkedValue(String description, String text, int from, String qualifiedName,
            String location) {
        int index = skipWhitespace(text, from);
        while (index < text.length() && text.charAt(index) != '>' && text.charAt(index) != '/') {
            int nameStart = index;
            while (index < text.length() && !isWhitespace(text.charAt(index)) && text.charAt(index) != '=') {
                index++;
            }
            String name = text.substring(nameStart, index);
            index = skipWhitespace(text, index);
            if (index >= text.length() || text.charAt(index) != '=') {
                throw malformedStartTag(description, qualifiedName);
            }
            index = skipWhitespace(text, index + 1);
            char quote = (index < text.length()) ? text.charAt(index) : 0;
            int valueEnd = (quote == '"' || quote == '\'') ? text.indexOf(quote, index + 1) : -1;
            if (valueEnd < 0) {
                throw malformedStartTag(description, qualifiedName);
            }
            if (LOCATION.equals(name)) {
                String raw = text.substring(index + 1, valueEnd);
                if (!location.equals(attributeValue(raw))) {
                    throw new IllegalStateException("WSDL contract " + description + " has a location value "
                            + raw + " in its <" + qualifiedName + "> start tag that does not read as " + location);
                }
                return new int[] {index + 1, valueEnd};
            }
            index = skipWhitespace(text, valueEnd + 1);
        }
        throw new IllegalStateException("WSDL contract " + description + " has no location attribute in its <"
                + qualifiedName + "> start tag");
    }

    /**
     * Returns the exception for a start tag whose attributes cannot be read.
     *
     * @param description   bean name and resource of the contract
     * @param qualifiedName the element name as written
     * @return an {@link IllegalStateException} naming the contract and the start tag
     */
    private static IllegalStateException malformedStartTag(String description, String qualifiedName) {
        return new IllegalStateException("WSDL contract " + description + " has an unreadable <" + qualifiedName
                + "> start tag");
    }

    /**
     * Returns the value an XML parser reports for the raw attribute value {@code raw} of an attribute without a
     * declared type: {@code CR LF}, {@code CR}, {@code LF} and tab each read as one space, and the references
     * {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;}, {@code &apos;}, {@code &#N;} and {@code &#xN;} read
     * as their characters.
     *
     * @param raw the attribute value as written between its quote characters
     * @return the reported value, or {@code null} when {@code raw} holds a reference that is not one of these
     */
    static String attributeValue(String raw) {
        StringBuilder value = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                value.append(' ');
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    i++;
                }
            }
            else if (c == '\n' || c == '\t') {
                value.append(' ');
            }
            else if (c == '&') {
                int semicolon = raw.indexOf(';', i + 1);
                String reference = (semicolon < 0) ? null : referencedText(raw.substring(i + 1, semicolon));
                if (reference == null) {
                    return null;
                }
                value.append(reference);
                i = semicolon;
            }
            else {
                value.append(c);
            }
        }
        return value.toString();
    }

    /**
     * Returns the text of a predefined entity or character reference.
     *
     * @param name the reference between {@code &} and {@code ;}, for example {@code amp}, {@code #38} or
     *             {@code #x26}
     * @return the referenced text, or {@code null} for any other name or an invalid code point
     */
    private static String referencedText(String name) {
        String predefined = switch (name) {
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            default -> null;
        };
        if (predefined != null) {
            return predefined;
        }
        if (name.length() < 2 || name.charAt(0) != '#') {
            return null;
        }
        boolean hex = name.charAt(1) == 'x';
        String digits = name.substring(hex ? 2 : 1);
        if (digits.isEmpty() || digits.charAt(0) == '+' || digits.charAt(0) == '-') {
            return null;
        }
        try {
            return new String(Character.toChars(Integer.parseInt(digits, hex ? 16 : 10)));
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Returns the index just after the first {@code terminator} at or after {@code from}.
     *
     * @param text       the contract text
     * @param terminator the closing text of a comment, CDATA section or processing instruction
     * @param from       index to search from
     * @return the index after the terminator, or {@code text.length()} when it does not occur
     */
    private static int after(String text, String terminator, int from) {
        int end = text.indexOf(terminator, from);
        return (end < 0) ? text.length() : end + terminator.length();
    }

    /**
     * Returns the index just after the element name that starts at {@code from}.
     *
     * @param text the contract text
     * @param from index of the first character of the element name
     * @return the index of the first whitespace, {@code /} or {@code >} at or after {@code from}, or
     *         {@code text.length()}
     */
    private static int nameEnd(String text, int from) {
        int index = from;
        while (index < text.length()) {
            char c = text.charAt(index);
            if (isWhitespace(c) || c == '/' || c == '>') {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * Returns the index of the first character at or after {@code from} that is not XML whitespace.
     *
     * @param text the contract text
     * @param from index to start at
     * @return that index, or {@code text.length()}
     */
    private static int skipWhitespace(String text, int from) {
        int index = from;
        while (index < text.length() && isWhitespace(text.charAt(index))) {
            index++;
        }
        return index;
    }

    /**
     * Tells whether {@code c} is XML whitespace: space, tab, line feed or carriage return.
     *
     * @param c the character
     * @return {@code true} for XML whitespace
     */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
