package com.mulesoft.examples.soap_webservice_security.endpoint;

import com.mulesoft.examples.soap_webservice_security.mapper.GreetMessageMapper;
import com.mulesoft.examples.soap_webservice_security.service.GreeterService;
import com.mulesoft.mule.example.security.Greet;
import com.mulesoft.mule.example.security.GreetResponse;
import jakarta.xml.bind.JAXBElement;
import java.net.URI;
import java.net.URISyntaxException;
import org.springframework.ws.server.endpoint.annotation.Endpoint;
import org.springframework.ws.server.endpoint.annotation.PayloadRoot;
import org.springframework.ws.server.endpoint.annotation.RequestPayload;
import org.springframework.ws.server.endpoint.annotation.ResponsePayload;
import org.springframework.ws.transport.context.TransportContext;
import org.springframework.ws.transport.context.TransportContextHolder;

/**
 * Spring WS endpoint of the {@code Greeter} SOAP service: serves the Greeter {@code greet} operation on the six
 * service paths and delegates to the {@link GreeterService} method named after the matching flow; WS-Security is
 * applied beforehand (D-030).
 *
 * <p>Implements the inbound side of the six service flows of
 * {@code soap-webservice-security/src/main/app/mule-config.xml:8-85}, each an {@code http:listener} on
 * {@code HTTP_Listener_Configuration1} (port {@code listener.http-listener-configuration1.port}, 63081, base path
 * {@code listener.http-listener-configuration1.base-path}, {@code services}) followed by
 * {@code cxf:jaxws-service serviceClass="com.mulesoft.mule.example.security.Greeter"} and
 * {@code component class="com.mulesoft.mule.example.security.GreeterService"}. The last segment of the request URI
 * path selects the flow method:
 *
 * <table>
 *   <caption>Flow method per service path</caption>
 *   <tr><th>Path</th><th>Flow</th><th>Service method</th></tr>
 *   <tr><td>{@code unsecure}</td><td>{@code UnsecureServiceFlow}</td>
 *       <td>{@link GreeterService#unsecureServiceFlow(String)}</td></tr>
 *   <tr><td>{@code username}</td><td>{@code UsernameTokenServiceFlow}</td>
 *       <td>{@link GreeterService#usernameTokenServiceFlow(String)}</td></tr>
 *   <tr><td>{@code signed}</td><td>{@code UsernameTokenSignedServiceFlow}</td>
 *       <td>{@link GreeterService#usernameTokenSignedServiceFlow(String)}</td></tr>
 *   <tr><td>{@code encrypted}</td><td>{@code UsernameTokenEncryptedServiceFlow}</td>
 *       <td>{@link GreeterService#usernameTokenEncryptedServiceFlow(String)}</td></tr>
 *   <tr><td>{@code saml}</td><td>{@code SamlTokenServiceFlow}</td>
 *       <td>{@link GreeterService#samlTokenServiceFlow(String)}</td></tr>
 *   <tr><td>{@code signedsaml}</td><td>{@code SignedSamlTokenServiceFlow}</td>
 *       <td>{@link GreeterService#signedSamlTokenServiceFlow(String)}</td></tr>
 * </table>
 *
 * <p>The paths are the literal listener paths of the original flows; the {@code <flow>.listener.path} keys of
 * {@code application.yml} are not read (D-561). The {@code Greeter} interface is not carried: the fixed contract
 * {@code wsdl/Greeter.wsdl} describes the operation and is served at {@code <service address>?wsdl} by
 * {@code config.WsdlQueryFilter} (D-028). The URI-matched {@code Wss4jSecurityInterceptor}s of
 * {@code config.WsSecurityConfig} validate each secured request before this endpoint is invoked (D-030), and
 * {@code config.PortPathGuardFilter} answers 404 for any path outside the six.
 *
 * <p>Wire format: SOAP Body payloads in namespace {@code http://security.example.mule.mulesoft.com/} with an
 * unqualified, optional {@code name} child; the namespace prefix below is illustrative.
 * <pre>{@code
 * request   <sec:greet xmlns:sec="http://security.example.mule.mulesoft.com/">
 *             <name>Mule</name>
 *           </sec:greet>
 * response  <sec:greetResponse xmlns:sec="http://security.example.mule.mulesoft.com/">
 *             <name>Hello Mule</name>
 *           </sec:greetResponse>
 * }</pre>
 *
 * <p>The endpoint binds and delegates only: {@link GreetMessageMapper} reads the name from the request and builds
 * the response element, and {@link GreeterService} builds the greeting. It neither validates, logs nor catches; an
 * exception raised here, by the mapper or by the service reaches {@code exception.SoapFaultMappingExceptionResolver},
 * which answers a {@code soap:Server} fault with the exception message as {@code faultstring}. {@link Greet} and
 * {@link GreetResponse} are the JAXB types generated from {@code Greeter.wsdl}; no JAX-WS, CXF or Mule type is used
 * (D-050).
 *
 * <p>The endpoint holds no mutable state and is safe for concurrent use; the request URI is read from the
 * {@link TransportContextHolder} of the calling thread.
 */
@Endpoint
public class GreeterEndpoint {

    /** Target namespace of {@code Greeter.wsdl} and of its {@code greet} and {@code greetResponse} elements. */
    private static final String NAMESPACE_URI = "http://security.example.mule.mulesoft.com/";

    /** Builds the greeting of each of the six service flows. */
    private final GreeterService greeterService;

    /** Reads the name from the {@code greet} element and wraps the greeting in the {@code greetResponse} element. */
    private final GreetMessageMapper mapper;

    /**
     * Creates the endpoint over the Greeter service and the payload mapper.
     *
     * @param greeterService the service implementing the six service flows
     * @param mapper         the mapper of the {@code greet} and {@code greetResponse} payloads
     */
    public GreeterEndpoint(GreeterService greeterService, GreetMessageMapper mapper) {
        this.greeterService = greeterService;
        this.mapper = mapper;
    }

    /**
     * Answers the {@code greet} operation: binds the name of the {@code greet} request, passes it to the
     * {@link GreeterService} flow method of the request's service path (table in the class Javadoc) and returns the
     * greeting in the {@code greetResponse} element. A request without a {@code name} child passes {@code null} to
     * the flow method.
     *
     * @param request the {@code {http://security.example.mule.mulesoft.com/}greet} request payload
     * @return the {@code {http://security.example.mule.mulesoft.com/}greetResponse} element holding the greeting
     * @throws IllegalStateException when the request has no transport connection, its URI cannot be parsed, or the
     *                               last segment of its URI path is none of the six service paths
     */
    @PayloadRoot(namespace = NAMESPACE_URI, localPart = "greet")
    @ResponsePayload
    public JAXBElement<GreetResponse> greet(@RequestPayload JAXBElement<Greet> request) {
        String path = servicePath();
        String name = mapper.nameOf(request);
        // Literal listener paths of the six flows; the D-139 <flow>.listener.path keys are not read (D-561).
        String result = switch (path) {
            case "unsecure" -> greeterService.unsecureServiceFlow(name);
            case "username" -> greeterService.usernameTokenServiceFlow(name);
            case "signed" -> greeterService.usernameTokenSignedServiceFlow(name);
            case "encrypted" -> greeterService.usernameTokenEncryptedServiceFlow(name);
            case "saml" -> greeterService.samlTokenServiceFlow(name);
            case "signedsaml" -> greeterService.signedSamlTokenServiceFlow(name);
            default -> throw new IllegalStateException("No Greeter service is mapped to path: " + path);
        };
        return mapper.toGreetResponse(result);
    }

    /**
     * Returns the service path of the current request: the last segment of its URI path, after one trailing
     * {@code /} is removed; the empty string when the URI has no path. The query string is not part of the path.
     *
     * @return the last URI path segment of the current request
     * @throws IllegalStateException when the current thread has no transport context or connection, or the
     *                               connection URI cannot be parsed
     */
    private static String servicePath() {
        TransportContext context = TransportContextHolder.getTransportContext();
        if (context == null || context.getConnection() == null) {
            throw new IllegalStateException("No transport context for the Greeter request");
        }
        URI uri;
        try {
            uri = context.getConnection().getUri();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Cannot parse the URI of the Greeter request", ex);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
