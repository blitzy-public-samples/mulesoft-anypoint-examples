package com.mulesoft.examples.xml_only_soap_webservice.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.EndpointExceptionResolver;
import org.springframework.ws.soap.SoapMessage;

/**
 * Answers every exception raised while dispatching a SOAP request to {@code /AdmissionService},
 * {@code /PatientService} or {@code /EHRService} with a SOAP 1.1 Server fault, and logs one ERROR entry naming the
 * class and stack frames of the exception and of each of its causes, with no exception message (D-338).
 *
 * <p>The fault's {@code faultstring} is the exception's message, or the exception's string form
 * ({@link Exception#toString()}) when the message is null or empty. The {@code faultstring} element carries no
 * {@code xml:lang} attribute. Spring WS sends a response holding a Server fault with HTTP status 500.
 *
 * <p>The resolver holds the first position in the {@code MessageDispatcher}'s endpoint exception resolver order,
 * ahead of Spring WS's {@code SoapFaultAnnotationExceptionResolver} and {@code SimpleSoapExceptionResolver}, and it
 * resolves every exception (D-028, D-167).
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code new IllegalStateException("boom")} gives the fault code {@code Server} and the fault string
 *       {@code boom};</li>
 *   <li>{@code new IllegalStateException()} gives the fault string {@code java.lang.IllegalStateException};</li>
 *   <li>a wrapping exception gives its own message, never the message of its cause.</li>
 * </ul>
 *
 * <p>The resolver holds no state and is safe for concurrent requests.
 */
@Component
public class SoapFaultMappingExceptionResolver implements EndpointExceptionResolver, Ordered {

    /** Logger that records each resolved exception at ERROR. */
    private static final Logger LOG = LoggerFactory.getLogger(SoapFaultMappingExceptionResolver.class);

    /**
     * Writes a SOAP 1.1 Server fault for the exception into the response of the message context and logs one ERROR
     * entry naming the class and stack frames of the exception and of each of its causes, with no exception message
     * (D-338).
     *
     * <p>The fault string is {@code ex.getMessage()}, or {@code ex.toString()} when the message is null or empty;
     * the cause chain is not read. The fault replaces any content already written to the response body, and the
     * fault string has no language attribute.
     *
     * <p>The log entry is {@code SOAP request failed: } followed by the exception's class name and stack frames,
     * then, for each cause, {@code Caused by: } with the cause's class name and stack frames. It carries no
     * exception message and no throwable argument (D-338).
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
        LOG.error("SOAP request failed: {}", messageFreeTrace(ex));
        ((SoapMessage) messageContext.getResponse()).getSoapBody().addServerOrReceiverFault(faultString, null);
        return true;
    }

    /**
     * Renders the class name and stack frames of the throwable and of each of its causes, outermost first, each
     * cause introduced by {@code Caused by: }, without any exception message. A cause already rendered ends the
     * chain (D-338).
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

    /**
     * Returns the resolver's position in the dispatcher's endpoint exception resolver order.
     *
     * @return {@link Ordered#HIGHEST_PRECEDENCE}, the first position
     */
    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
