/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import com.mulesoft.se.samsung.OrderRequest;
import com.mulesoft.se.samsung.OrderResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Implements flow {@code samsungService} (fulfillment.xml:150-155) and the purchase logic of the
 * original class {@code com.mulesoft.se.samsung.SamsungServiceImpl}.
 *
 * <p>The SOAP endpoint {@code endpoint.SamsungServiceEndpoint} binds the {@code purchase} request
 * of {@code wsdl/samsung.wsdl} on {@code POST /samsung/orders} and calls
 * {@link #samsungService(OrderRequest)}. The class implements no service endpoint interface and
 * carries no JAX-WS annotation; {@code samsung.wsdl} is the service contract (D-028).
 * {@link OrderRequest} and {@link OrderResponse} are the JAXB types generated from
 * {@code samsung.wsdl} into {@code com.mulesoft.se.samsung}. The file keeps the original MuleSoft
 * copyright header (D-048).
 *
 * <p>The class holds no mutable state and is safe for concurrent use.
 *
 * <pre>{@code
 * SamsungServiceImpl service = new SamsungServiceImpl();
 * OrderRequest request = new OrderRequest();
 * request.setName("s-1");
 * request.setQuantity(3);
 * OrderResponse response = service.samsungService(request); // logs "aaaa" at INFO
 * response.getId();     // "1"
 * response.getResult(); // "ACCEPTED"
 * response.getPrice();  // "7650"
 * }</pre>
 */
@Service
public class SamsungServiceImpl {

    private static final Logger LOGGER = LoggerFactory.getLogger(SamsungServiceImpl.class);

    /**
     * Implements flow {@code samsungService} (fulfillment.xml:150-155): logs {@code aaaa} at INFO,
     * the message of the flow's logger (fulfillment.xml:152), and answers the purchase with
     * {@link #purchase(OrderRequest)}.
     *
     * <p>The message is logged before the request is read, for every call, including one with a
     * {@code null} request.
     *
     * @param request the {@code orderRequest} child of the {@code purchase} request; must not be
     *                {@code null}
     * @return the order response that {@link #purchase(OrderRequest)} returns for {@code request}
     * @throws NullPointerException when {@code request} is {@code null}, raised by
     *                              {@link #purchase(OrderRequest)} after the message is logged
     */
    public OrderResponse samsungService(OrderRequest request) {
        LOGGER.info("aaaa");
        return purchase(request);
    }

    /**
     * Answers a Samsung purchase as {@code com.mulesoft.se.samsung.SamsungServiceImpl#purchase}
     * does: id {@code "1"}, result {@code "ACCEPTED"} and price {@code 2550 * quantity} as decimal
     * text, for example {@code "2550"} for quantity 1 and {@code "7650"} for quantity 3.
     *
     * <p>The price is an {@code int} product: a quantity above 842150, or below -842150, wraps
     * around as Java {@code int} multiplication does. The request name is not read and the response
     * carries no name. Each call returns a new response object.
     *
     * @param request the order request with the ordered {@code quantity}; must not be {@code null}
     * @return a new order response with id {@code "1"}, result {@code "ACCEPTED"} and the price text
     * @throws NullPointerException when {@code request} is {@code null}
     */
    public OrderResponse purchase(OrderRequest request) {
        OrderResponse orderResponse = new OrderResponse();
        orderResponse.setId("1");
        orderResponse.setResult("ACCEPTED");
        Integer price = Integer.valueOf(2550 * request.getQuantity());
        orderResponse.setPrice(price.toString());
        return orderResponse;
    }
}
