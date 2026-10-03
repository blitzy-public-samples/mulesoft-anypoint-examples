package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.Objects;

import org.springframework.stereotype.Service;

import com.mulesoft.examples.service_orchestration_and_choice_routing.client.OrderSoapClient;

/**
 * Implements flow {@code orderProxy} (mule-config.xml:38-42), the AJAX channel {@code /orders/soap}:
 * forwards the SOAP envelope to the order service on the 1080 listener and answers its response text.
 *
 * <p>The flow's {@code http:request} {@code POST orders} on port 1080 (mule-config.xml:40) and its
 * {@code object-to-string-transformer} (mule-config.xml:41) are {@link OrderSoapClient#postOrderProxy(String)}:
 * the envelope text is sent as its UTF-8 bytes unchanged and the response body is answered as text.
 * The service neither reads nor changes the envelope and writes no log.
 *
 * <p>Every exception of the client propagates unchanged to the channel controller, which answers it as
 * the channel's failure reply (D-077). A status of 400 or more raises a
 * {@link org.springframework.web.client.RestClientResponseException}, for example an
 * {@link org.springframework.web.client.HttpServerErrorException} with status 500 whose response body is
 * the SOAP fault envelope.
 *
 * <p>The class holds no mutable state and is safe for concurrent use.
 *
 * <pre>{@code
 * OrderProxyService service = new OrderProxyService(orderSoapClient);
 * String reply = service.orderProxy(soapRequestText);
 * // reply is the <soap:Envelope> text answered on the 1080 listener
 * }</pre>
 */
@Service
public class OrderProxyService {

    /** Client of the order service on the 1080 listener. */
    private final OrderSoapClient orderSoapClient;

    /**
     * Creates the service over the order service client.
     *
     * @param orderSoapClient the client that posts the envelope to the order service
     * @throws NullPointerException if {@code orderSoapClient} is {@code null}
     */
    public OrderProxyService(OrderSoapClient orderSoapClient) {
        this.orderSoapClient = Objects.requireNonNull(orderSoapClient, "orderSoapClient");
    }

    /**
     * Implements flow {@code orderProxy} (mule-config.xml:38-42): posts {@code body} to the order service
     * on the 1080 listener and returns the response text.
     *
     * @param body the SOAP envelope text of the page's request, forwarded as it stands
     * @return the response body of the order service, as returned by
     *         {@link OrderSoapClient#postOrderProxy(String)}
     * @throws NullPointerException                                         if {@code body} is {@code null}
     * @throws org.springframework.web.client.HttpClientErrorException      if the order service answers 4xx
     * @throws org.springframework.web.client.HttpServerErrorException      if the order service answers 5xx,
     *                                                                      for example 500 with a SOAP fault
     * @throws org.springframework.web.client.ResourceAccessException       if the connection fails or the
     *                                                                      read timeout elapses
     */
    public String orderProxy(String body) {
        return orderSoapClient.postOrderProxy(body);
    }
}
