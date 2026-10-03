package com.mulesoft.examples.service_orchestration_and_choice_routing.exception;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.AbstractEndpointExceptionResolver;
import org.springframework.ws.server.endpoint.MethodEndpoint;
import org.springframework.ws.soap.SoapMessage;

/**
 * Writes a SOAP 1.1 Server fault whose faultstring is the exception message, with no detail, for exceptions
 * raised while invoking OrderServiceEndpoint or SamsungServiceEndpoint (AAP 0.3.3, D-028).
 *
 * <p>Fault shape:
 * <ul>
 *   <li>{@code faultcode} is {@code Server} in the namespace and with the prefix of the response envelope;</li>
 *   <li>{@code faultstring} is the exception message, or the exception's {@code toString()} when the message is
 *       {@code null} or empty, and carries no {@code xml:lang} attribute;</li>
 *   <li>no {@code faultactor} and no {@code detail} element.</li>
 * </ul>
 * With the SOAP 1.1 envelope prefix {@code soap}, the response body is
 * <pre>{@code
 * <soap:Fault><faultcode>soap:Server</faultcode><faultstring>message</faultstring></soap:Fault>
 * }</pre>
 * and Spring WS answers it with HTTP 500.
 *
 * <p>The resolver applies to every endpoint of the application and runs at {@link Ordered#HIGHEST_PRECEDENCE}. It
 * logs each resolved exception at ERROR as the endpoint name followed by the class names and stack frames of the
 * exception and of each of its causes; neither exception messages nor the endpoint's {@code toString()} are logged
 * (D-338). It carries no stereotype annotation: the single bean of this type is declared by {@code config.WsConfig}.
 */
public class SoapFaultMappingExceptionResolver extends AbstractEndpointExceptionResolver {

    /** Logger of the resolved exceptions. */
    private static final Logger LOG = LoggerFactory.getLogger(SoapFaultMappingExceptionResolver.class);

    /**
     * Creates the resolver ordered at {@link Ordered#HIGHEST_PRECEDENCE}, ahead of the exception resolvers Spring WS
     * registers by default.
     */
    public SoapFaultMappingExceptionResolver() {
        setOrder(Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * Logs at ERROR the endpoint name and the class names and stack frames of the exception and its causes, with no
     * exception message (D-338), and replaces the body of the SOAP response with a {@code Server} fault whose
     * faultstring is the exception message, or the exception's {@code toString()} when the message is {@code null}
     * or empty. The faultstring is written without a locale and carries no {@code xml:lang} attribute; no detail is
     * added.
     *
     * @param messageContext the message context whose response receives the fault
     * @param endpoint       the endpoint that was invoked, or {@code null} when none was mapped
     * @param ex             the exception raised while dispatching the request
     * @return {@code true} once the fault is written; {@code false} when the response is not a SOAP message
     */
    @Override
    protected boolean resolveExceptionInternal(MessageContext messageContext, Object endpoint, Exception ex) {
        String faultString = faultStringOf(ex);
        LOG.error("SOAP fault for endpoint {}: {}", endpointName(endpoint), messageFreeTrace(ex));
        if (messageContext.getResponse() instanceof SoapMessage soapMessage) {
            soapMessage.getSoapBody().addServerOrReceiverFault(faultString, null);
            return true;
        }
        return false;
    }

    /**
     * Returns the exception message, or {@code ex.toString()} when the message is {@code null} or empty.
     *
     * @param ex the exception being resolved
     * @return the faultstring for {@code ex}
     */
    private static String faultStringOf(Exception ex) {
        String message = ex.getMessage();
        return (message == null || message.isEmpty()) ? ex.toString() : message;
    }

    /**
     * Returns the logged name of the endpoint: {@code none} when there is no endpoint,
     * {@code <declaring class name>#<method name>} for a {@link MethodEndpoint}, and otherwise the endpoint's class
     * name. The endpoint's {@code toString()} is never used (D-338).
     *
     * @param endpoint the endpoint that was invoked, or {@code null} when none was mapped
     * @return the endpoint name written to the log
     */
    private static String endpointName(Object endpoint) {
        if (endpoint == null) {
            return "none";
        }
        if (endpoint instanceof MethodEndpoint methodEndpoint) {
            Method method = methodEndpoint.getMethod();
            return method.getDeclaringClass().getName() + "#" + method.getName();
        }
        return endpoint.getClass().getName();
    }

    /**
     * Renders the class name and stack frames of the throwable and of each of its causes, outermost first, each cause
     * introduced by {@code Caused by: }, without any exception message. A cause that is already rendered ends the
     * chain (D-338).
     *
     * @param throwable the outermost throwable to render
     * @return the message-free trace of {@code throwable} and its causes
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
