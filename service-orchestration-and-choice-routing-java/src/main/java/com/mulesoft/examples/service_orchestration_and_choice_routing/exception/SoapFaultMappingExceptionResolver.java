package com.mulesoft.examples.service_orchestration_and_choice_routing.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.ws.context.MessageContext;
import org.springframework.ws.server.endpoint.AbstractEndpointExceptionResolver;
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
 * <p>The resolver applies to every endpoint of the application, runs at {@link Ordered#HIGHEST_PRECEDENCE} and logs
 * each resolved exception at ERROR with its stack trace. It carries no stereotype annotation: the single bean of
 * this type is declared by {@code config.WsConfig}.
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
     * Logs the exception at ERROR and replaces the body of the SOAP response with a {@code Server} fault whose
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
        LOG.error("SOAP fault for endpoint {}: {}", endpoint, faultString, ex);
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
}
