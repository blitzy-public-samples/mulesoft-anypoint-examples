package com.mulesoft.examples.legacy_modernization.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.soap.SoapBody;
import org.springframework.ws.soap.SoapMessage;

/**
 * Answers every exception raised while dispatching a SOAP request to the {@code IFulfillmentService} endpoint, by
 * default at {@code /OrderFulfillment}, the counterpart of the {@code cxf:jaxws-service} of flow
 * {@code Fulfillment_LegacySystemModernization}, with a SOAP 1.1 Server fault (D-028, D-531).
 *
 * <p>It writes a SOAP 1.1 {@code soap:Server} fault whose {@code faultstring} is the exception message, or the
 * exception's {@link Exception#toString()} when it has no message, and reports the exception as resolved. The
 * message is written unchanged, including the {@code Unexpected wrapper element … found.   Expected
 * {http://ordermgmt.org/}putShippingOrder.} message of a request whose payload root no endpoint maps (D-325).
 *
 * <p>Fault shape in the project's SOAP 1.1 response envelope (D-324):
 * <pre>{@code
 * <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault>
 * <faultcode>soap:Server</faultcode><faultstring>message</faultstring></soap:Fault></soap:Body></soap:Envelope>
 * }</pre>
 * The {@code faultstring} carries no {@code xml:lang} attribute, and the fault has no {@code faultactor} and no
 * {@code detail} element. Spring WS answers a response holding this fault with HTTP status 500.
 *
 * <p>Every exception type gets the same fault shape. Examples:
 * <ul>
 *   <li>{@code new IllegalStateException("boom")} gives the fault string {@code boom};</li>
 *   <li>{@code new NullPointerException()} gives the fault string {@code java.lang.NullPointerException};</li>
 *   <li>a wrapping exception gives its own message, never the message of its cause.</li>
 * </ul>
 *
 * <p>The {@code MessageDispatcher} that {@code @EnableWs} sets up detects this bean by type and consults it first,
 * ahead of Spring WS's {@code SoapFaultAnnotationExceptionResolver} and {@code SimpleSoapExceptionResolver}. Each
 * resolved exception is logged once at ERROR with the endpoint and the class names and stack frames of the
 * exception and of each of its causes, without any exception message (D-531). The resolver holds no state and is
 * safe for concurrent requests.
 */
@Component
public class SoapFaultMappingExceptionResolver implements EndpointExceptionResolver, Ordered {

    /** Logger that records each resolved exception at ERROR. */
    private static final Logger LOG = LoggerFactory.getLogger(SoapFaultMappingExceptionResolver.class);

    /**
     * Writes a SOAP 1.1 {@code soap:Server} fault whose {@code faultstring} is the exception message, or the
     * exception's {@code toString()} when it has no message, into the response of the message context, and reports
     * the exception as resolved.
     *
     * <p>The fault string is {@code ex.getMessage()} when it is neither {@code null} nor empty, and otherwise
     * {@code ex.toString()}; the cause chain is not read. The fault replaces any content already written to the
     * response body and is added without a locale, actor or detail.
     *
     * <p>The log entry reads {@code SOAP fault for endpoint <endpoint>: } followed by the exception's class name and
     * stack frames, then, for each cause, {@code Caused by: } with the cause's class name and stack frames
     * (D-531). {@code <endpoint>} is {@code null} when no endpoint was mapped.
     *
     * @param messageContext the context of the request being dispatched; its response is a {@link SoapMessage}
     * @param endpoint       the endpoint that was executing, or {@code null} when no endpoint was mapped
     * @param ex             the exception raised while dispatching the request
     * @return {@code true} for every exception, which marks it as resolved
     */
    @Override
    public boolean resolveException(MessageContext messageContext, Object endpoint, Exception ex) {
        String message = ex.getMessage();
        String faultString = (message == null || message.isEmpty()) ? ex.toString() : message;
        // Message-free entry: no exception message and no throwable argument reach the log (D-531).
        LOG.error("SOAP fault for endpoint {}: {}", endpoint, messageFreeTrace(ex));
        SoapMessage response = (SoapMessage) messageContext.getResponse();
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
     * chain (D-531).
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
