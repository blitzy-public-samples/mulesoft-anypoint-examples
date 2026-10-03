package com.mulesoft.examples.foreach_processing_and_choice_routing.exception;

import org.springframework.ws.soap.server.endpoint.AbstractSoapFaultDefinitionExceptionResolver;
import org.springframework.ws.soap.server.endpoint.SoapFaultDefinition;

/**
 * Maps every endpoint exception to a SOAP Server fault whose string is the exception message (D-028).
 *
 * <p>It answers the exceptions raised while the {@code SoapMessageDispatcher} built by {@code config.WsConfig}
 * dispatches a request to {@code endpoint.CreditAgencyEndpoint} or {@code endpoint.BankEndpoint}, including those
 * raised by an endpoint mapping, which reach it with a {@code null} endpoint, and by an endpoint interceptor.
 *
 * <p>Fault shape in a SOAP 1.1 response:
 * <ul>
 *   <li>{@code faultcode} is {@code Server} in the envelope namespace
 *       {@code http://schemas.xmlsoap.org/soap/envelope/}, written with the envelope prefix, for example
 *       {@code SOAP-ENV:Server};</li>
 *   <li>{@code faultstring} is {@link Exception#getMessage()}, or {@link Exception#toString()} when the message is
 *       {@code null} or empty, and carries no {@code xml:lang} attribute (D-223);</li>
 *   <li>no {@code faultactor} and no {@code detail} element.</li>
 * </ul>
 * Spring WS answers a response that holds this fault with HTTP status 500.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code new IllegalStateException("boom")} yields fault code {@code Server} and fault string
 *       {@code boom};</li>
 *   <li>{@code new IllegalStateException()} yields fault string {@code java.lang.IllegalStateException}.</li>
 * </ul>
 *
 * <p>The resolver keeps the defaults of {@link AbstractSoapFaultDefinitionExceptionResolver}: no mapped endpoints,
 * which makes it apply to every endpoint and to a {@code null} endpoint, no default fault and no explicit order. It
 * carries no stereotype annotation; {@code config.WsConfig} creates the instance and registers it on the
 * dispatcher. It holds no state and is safe for concurrent requests.
 */
public class SoapFaultMappingExceptionResolver extends AbstractSoapFaultDefinitionExceptionResolver {

    /**
     * Returns a new Server fault definition for {@code ex}, whatever the endpoint.
     *
     * <p>The definition carries the fault code {@link SoapFaultDefinition#SERVER}, the fault string
     * {@code ex.getMessage()}, or {@code ex.toString()} when the message is {@code null}, and a {@code null} locale.
     * The parent class writes {@code ex.toString()} for an empty message.
     *
     * @param endpoint the endpoint that was executing, or {@code null} when no endpoint was mapped; not read
     * @param ex       the exception raised while dispatching the request
     * @return a new definition on every call, never {@code null}
     */
    @Override
    protected SoapFaultDefinition getFaultDefinition(Object endpoint, Exception ex) {
        SoapFaultDefinition definition = new SoapFaultDefinition();
        definition.setFaultCode(SoapFaultDefinition.SERVER);
        definition.setFaultStringOrReason(ex.getMessage() != null ? ex.getMessage() : ex.toString());
        // Null locale: faultstring is written without an xml:lang attribute (D-223).
        definition.setLocale(null);
        return definition;
    }
}
