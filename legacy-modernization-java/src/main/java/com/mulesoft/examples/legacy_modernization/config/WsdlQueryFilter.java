package com.mulesoft.examples.legacy_modernization.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Answers {@code GET /OrderFulfillment?wsdl} with the fixed contract {@value WsConfig#WSDL_LOCATION}, its
 * {@code soap:address} set to the request URL (D-028, D-540).
 *
 * <p>Counterpart of the WSDL the {@code cxf:jaxws-service} of flow {@code Fulfillment_LegacySystemModernization}
 * publishes at {@code <address>?wsdl} [legacy-modernization/src/main/app/FufillmentWebService.xml:7]. The
 * original {@code http:listener} declares {@code allowedMethods="POST"} [FufillmentWebService.xml:5]; the answer
 * of the original to this {@code GET} is pinned by the Tier 2A scenario {@code legacy-modernization_wsdl-fulfillment}
 * (D-540).
 *
 * <p>A request is answered here when all of these hold:
 * <ul>
 *   <li>its method is exactly {@code GET};</li>
 *   <li>its request URI with the context path removed is exactly {@value WsConfig#SERVICE_PATH}, the servlet path
 *       of the default listener path (D-327);</li>
 *   <li>its query string is {@code wsdl} in any letter case, for example {@code ?wsdl} or {@code ?WSDL}.</li>
 * </ul>
 * Every other request continues down the filter chain unchanged, for example {@code POST /OrderFulfillment} (the
 * SOAP endpoint), {@code GET /OrderFulfillment} without a query ({@code 405} from the
 * {@code MessageDispatcherServlet}), {@code HEAD /OrderFulfillment?wsdl}, {@code GET /OrderFulfillment/?wsdl},
 * {@code GET /OrderFulfillment?wsdl=1}, {@code GET /OrderFulfillment?xsd=1} and every other path. The schema of the
 * contract is inline, and no {@code ?xsd=} query is served.
 *
 * <p>The answer is status {@code 200}, {@code Content-Type: text/xml;charset=UTF-8}, a {@code Content-Length}
 * header and the UTF-8 bytes of the committed contract, in which the single occurrence of
 * {@code http://localhost:1080/OrderFulfillment} is replaced by {@link HttpServletRequest#getRequestURL()} (scheme,
 * host as requested, port and path, no query) with {@code &}, {@code <}, {@code >}, {@code "} and {@code '}
 * written as XML entities. Every other byte equals the committed file. For
 * {@code GET http://localhost:20020/OrderFulfillment?wsdl} the served port reads:
 * <pre>{@code
 * <wsdl:port binding="tns:IFulfillmentServiceSoapBinding" name="IFulfillmentPort">
 *   <soap:address location="http://localhost:20020/OrderFulfillment"/>
 * </wsdl:port>
 * }</pre>
 *
 * <p>The contract is read once, when the filter is constructed, and a missing or unreadable resource, a resource
 * that is not valid UTF-8, or a resource without exactly one {@code http://localhost:1080/OrderFulfillment} stops
 * startup with {@link IllegalStateException}. The committed file is never written. Instances are immutable and
 * safe for concurrent requests. Only REQUEST dispatches are filtered: the {@link OncePerRequestFilter} defaults pass
 * error and async dispatches. Spring Boot registers this {@code Filter} bean for {@code /*}.
 */
@Component
public class WsdlQueryFilter extends OncePerRequestFilter {

    /** Address of the {@code soap:address} element of port {@code IFulfillmentPort} in the committed contract. */
    private static final String COMMITTED_ADDRESS = "http://localhost:1080/OrderFulfillment";

    /** Method of the WSDL request, compared exactly. */
    private static final String GET = "GET";

    /** Query of the WSDL request, compared in any letter case. */
    private static final String WSDL_QUERY = "wsdl";

    /** {@code Content-Type} of the served contract. */
    private static final String CONTENT_TYPE = "text/xml;charset=UTF-8";

    /** Logger of the served WSDL requests, at DEBUG. */
    private static final Logger LOG = LoggerFactory.getLogger(WsdlQueryFilter.class);

    /** Text of the committed contract, decoded from UTF-8. */
    private final String wsdl;

    /** Index in {@link #wsdl} of the single occurrence of {@link #COMMITTED_ADDRESS}. */
    private final int addressIndex;

    /**
     * Creates the filter over the classpath resource {@value WsConfig#WSDL_LOCATION}, the file the
     * {@code IFulfillmentService} {@code SimpleWsdl11Definition} bean of {@link WsConfig} wraps, read directly
     * rather than through that bean's {@code Source} (D-028, D-540).
     *
     * @throws IllegalStateException when the resource is missing, cannot be read, is not valid UTF-8, or does not
     *                               contain {@code http://localhost:1080/OrderFulfillment} exactly once
     */
    public WsdlQueryFilter() {
        this(new ClassPathResource(WsConfig.WSDL_LOCATION));
    }

    /**
     * Creates the filter over the given contract resource, read once and never written.
     *
     * @param wsdlResource the WSDL contract to serve
     * @throws IllegalStateException when {@code wsdlResource} is {@code null} or missing, cannot be read, is not
     *                               valid UTF-8, or does not contain {@code http://localhost:1080/OrderFulfillment}
     *                               exactly once
     */
    WsdlQueryFilter(Resource wsdlResource) {
        this.wsdl = readContract(wsdlResource);
        this.addressIndex = singleAddressIndex(this.wsdl, wsdlResource);
    }

    /**
     * Writes the contract for a {@code GET} {@value WsConfig#SERVICE_PATH} request with the query {@code wsdl} in
     * any letter case, and passes every other request to {@code chain} unchanged.
     *
     * @param request  the incoming request; its method, request URI, context path and query string decide, and its
     *                 request URL becomes the served {@code soap:address}
     * @param response the response, written with the contract for a WSDL request and untouched otherwise
     * @param chain    the remaining filter chain, called only for a request that is not a WSDL request
     * @throws ServletException from the remaining chain
     * @throws IOException      from the remaining chain or while writing the contract
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!isWsdlRequest(request)) {
            chain.doFilter(request, response);
            return;
        }
        byte[] body = servedContract(request.getRequestURL().toString()).getBytes(StandardCharsets.UTF_8);
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(CONTENT_TYPE);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Served {} for GET {}?wsdl: {} bytes", WsConfig.WSDL_LOCATION, WsConfig.SERVICE_PATH,
                    body.length);
        }
    }

    /**
     * Returns whether {@code request} is {@code GET} {@value WsConfig#SERVICE_PATH} with the query {@code wsdl} in
     * any letter case. The method name and the path are compared exactly; the path is the request URI with the
     * context path removed.
     *
     * @param request the incoming request
     * @return {@code true} for a WSDL request
     */
    private static boolean isWsdlRequest(HttpServletRequest request) {
        if (!GET.equals(request.getMethod())) {
            return false;
        }
        String query = request.getQueryString();
        if (query == null || !WSDL_QUERY.equalsIgnoreCase(query)) {
            return false;
        }
        return WsConfig.SERVICE_PATH.equals(applicationPath(request));
    }

    /**
     * Returns the request URI with the context path removed, for example {@code /OrderFulfillment} for the request
     * URI {@code /OrderFulfillment} and the root context path {@code ""}, or for the request URI
     * {@code /ctx/OrderFulfillment} and the context path {@code /ctx}.
     *
     * @param request the incoming request
     * @return the application-relative request path; {@code null} when the request has no URI or its URI does not
     *         start with the context path
     */
    private static String applicationPath(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath == null) {
            contextPath = "";
        }
        if (requestUri == null || !requestUri.startsWith(contextPath)) {
            return null;
        }
        return requestUri.substring(contextPath.length());
    }

    /**
     * Returns the committed contract with its single {@code http://localhost:1080/OrderFulfillment} replaced by
     * {@code requestUrl}, XML-attribute-escaped; every other character is unchanged.
     *
     * @param requestUrl the request URL, without the query
     * @return the contract text to serve
     */
    private String servedContract(String requestUrl) {
        String address = escapeAttribute(requestUrl);
        int afterAddress = this.addressIndex + COMMITTED_ADDRESS.length();
        return new StringBuilder(this.wsdl.length() - COMMITTED_ADDRESS.length() + address.length())
                .append(this.wsdl, 0, this.addressIndex)
                .append(address)
                .append(this.wsdl, afterAddress, this.wsdl.length())
                .toString();
    }

    /**
     * Returns {@code value} with {@code &}, {@code <}, {@code >}, {@code "} and {@code '} replaced by
     * {@code &amp;}, {@code &lt;}, {@code &gt;}, {@code &quot;} and {@code &apos;}; every other character is kept.
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
     * Reads every byte of {@code wsdlResource} and decodes it as UTF-8, rejecting malformed and unmappable input.
     *
     * @param wsdlResource the WSDL contract
     * @return the contract text
     * @throws IllegalStateException when {@code wsdlResource} is {@code null} or missing, cannot be read or is not
     *                               valid UTF-8
     */
    private static String readContract(Resource wsdlResource) {
        if (wsdlResource == null) {
            throw new IllegalStateException("WSDL contract resource must not be null");
        }
        if (!wsdlResource.exists()) {
            throw new IllegalStateException("WSDL contract " + wsdlResource.getDescription() + " not found");
        }
        byte[] bytes;
        try (InputStream in = wsdlResource.getInputStream()) {
            bytes = StreamUtils.copyToByteArray(in);
        }
        catch (IOException ex) {
            throw new IllegalStateException("WSDL contract " + wsdlResource.getDescription() + " cannot be read", ex);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        }
        catch (CharacterCodingException ex) {
            throw new IllegalStateException("WSDL contract " + wsdlResource.getDescription() + " is not valid UTF-8",
                    ex);
        }
    }

    /**
     * Returns the index of the single occurrence of {@code http://localhost:1080/OrderFulfillment} in {@code wsdl}.
     *
     * @param wsdl         the contract text
     * @param wsdlResource the contract resource, named in the exception message
     * @return the index of the committed address
     * @throws IllegalStateException when the address occurs zero times or more than once
     */
    private static int singleAddressIndex(String wsdl, Resource wsdlResource) {
        int first = wsdl.indexOf(COMMITTED_ADDRESS);
        if (first < 0) {
            throw new IllegalStateException("WSDL contract " + wsdlResource.getDescription()
                    + " does not contain " + COMMITTED_ADDRESS);
        }
        if (wsdl.indexOf(COMMITTED_ADDRESS, first + COMMITTED_ADDRESS.length()) >= 0) {
            throw new IllegalStateException("WSDL contract " + wsdlResource.getDescription()
                    + " contains " + COMMITTED_ADDRESS + " more than once");
        }
        return first;
    }
}
