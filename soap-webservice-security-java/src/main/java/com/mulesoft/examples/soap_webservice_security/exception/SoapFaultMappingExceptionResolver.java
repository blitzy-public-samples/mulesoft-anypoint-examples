package com.mulesoft.examples.soap_webservice_security.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapMessage;

/**
 * Writes a SOAP 1.1 Server fault whose faultstring is the exception message for any exception raised by a Spring WS
 * endpoint of this application, and answers HTTP 500 (D-028).
 *
 * <p>It serves the six {@code Greeter} services of the {@code MessageDispatcherServlet} on {@code /services/*}, the
 * counterparts of the {@code cxf:jaxws-service} elements of the flows {@code UnsecureServiceFlow},
 * {@code UsernameTokenServiceFlow}, {@code UsernameTokenSignedServiceFlow}, {@code UsernameTokenEncryptedServiceFlow},
 * {@code SamlTokenServiceFlow} and {@code SignedSamlTokenServiceFlow}, none of which declares an exception strategy.
 *
 * <p>Fault shape in the SOAP 1.1 envelope of the project's {@code messageFactory}, whose envelope and prefix it
 * leaves unchanged:
 * <pre>{@code
 * <SOAP-ENV:Envelope xmlns:SOAP-ENV="http://schemas.xmlsoap.org/soap/envelope/"><SOAP-ENV:Header/><SOAP-ENV:Body>
 * <SOAP-ENV:Fault><faultcode>SOAP-ENV:Server</faultcode><faultstring>message</faultstring></SOAP-ENV:Fault>
 * </SOAP-ENV:Body></SOAP-ENV:Envelope>
 * }</pre>
 * The fault code is the QName {@code {http://schemas.xmlsoap.org/soap/envelope/}Server}. The {@code faultstring}
 * carries no {@code xml:lang} attribute, and the fault has no {@code faultactor} and no {@code detail} element.
 *
 * <p>Every exception type gets the same fault shape. Examples:
 * <ul>
 *   <li>{@code new IllegalStateException("boom")} gives the fault string {@code boom};</li>
 *   <li>{@code new NullPointerException()} gives the fault string {@code java.lang.NullPointerException};</li>
 *   <li>a wrapping exception gives its own message, never the message of its cause.</li>
 * </ul>
 *
 * <p>The {@code MessageDispatcher} detects this bean by type and consults it first, ahead of the
 * {@code SoapFaultAnnotationExceptionResolver} and {@code SimpleSoapExceptionResolver} that {@code @EnableWs}
 * registers. WS-Security validation failures are answered by the URI-matched interceptors of
 * {@code config.WsSecurityConfig} and do not reach the dispatcher's resolvers; a {@code NoEndpointFoundException}
 * is rethrown by the dispatcher before any resolver runs. Each resolved exception is logged once at ERROR with the
 * endpoint and the class names and stack frames of the exception and of each of its causes, without any exception
 * message (D-689). The resolver holds no state and is safe for concurrent requests.
 */
@Component
public class SoapFaultMappingExceptionResolver implements EndpointExceptionResolver, Ordered {

    /** Logger that records each resolved exception at ERROR. */
    private static final Logger LOG = LoggerFactory.getLogger(SoapFaultMappingExceptionResolver.class);

    /**
     * Writes a SOAP 1.1 Server fault for the exception into the response of the message context and reports the
     * exception as resolved.
     *
     * <p>The fault string is {@code ex.getMessage()} when it is neither {@code null} nor empty, and otherwise
     * {@code ex.toString()}; the cause chain is not read. The fault replaces any content already written to the
     * response body and is added without a locale, actor or detail.
     *
     * <p>The log entry reads {@code SOAP endpoint <endpoint> raised an exception: } followed by the exception's class
     * name and stack frames, then, for each cause, {@code Caused by: } with the cause's class name and stack frames
     * (D-689). {@code <endpoint>} is {@code null} when no endpoint was mapped.
     *
     * @param messageContext the context of the request being dispatched
     * @param endpoint       the endpoint that was executing, or {@code null} when no endpoint was mapped
     * @param ex             the exception raised while dispatching the request
     * @return {@code true} when the fault was written; {@code false}, with nothing written or logged, when the
     *         response of the message context is not a {@link SoapMessage}
     */
    @Override
    public boolean resolveException(MessageContext messageContext, Object endpoint, Exception ex) {
        if (!(messageContext.getResponse() instanceof SoapMessage response)) {
            return false;
        }
        // Message-free entry: neither the exception message nor the throwable reaches the log (D-689, D-338).
        LOG.error("SOAP endpoint {} raised an exception: {}", endpoint, messageFreeTrace(ex));
        String message = ex.getMessage();
        String faultString = StringUtils.hasLength(message) ? message : ex.toString();
        SoapBody body = response.getSoapBody();
        body.addServerOrReceiverFault(faultString, null);
        return true;
    }

    /**
     * Returns the resolver's position in the dispatcher's endpoint exception resolver order.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}, the first position
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * Renders the class name and stack frames of the throwable and of each of its causes, outermost first, each
     * cause introduced by {@code Caused by: }, without any exception message. A cause already rendered ends the
     * chain (D-689).
     *
     * @param throwable the exception to render
     * @return the class names and stack frames, one per line
     */
    private static String messageFreeTrace(Throwable throwable) {
        StringBuilder trace = new StringBuilder();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = throwable; current != null && seen.add(current); current = current.getCause()) {
            if (current != throwable) {
                trace.append(System.lineSeparator()).append("Caused by: ");
            }
            trace.append(current.getClass().getName());
            for (StackTraceElement frame : current.getStackTrace()) {
                trace.append(System.lineSeparator()).append("\tat ").append(frame);
            }
        }
        return trace.toString();
    }
}
