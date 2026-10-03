/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.OrderItem;
import com.mulesoft.se.samsung.OrderRequest;

/**
 * Maps an order item to the Samsung {@link OrderRequest} (DW-30,
 * service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:53-59, flow
 * {@code samsungOrder}; D-034). Replaces the Java transformer
 * {@code com.mulesoft.se.samsung.OrderItemToOrderRequest}, which is not carried (D-037).
 *
 * <p>{@link OrderItem} is the ported model; {@link OrderRequest} is the JAXB type generated from
 * {@code wsdl/samsung.wsdl} into {@code com.mulesoft.se.samsung}.
 *
 * <p>Instances hold no state; {@link #toOrderRequest(OrderItem)} returns a new request on every call,
 * has no side effects, is safe for concurrent use and needs no Spring context.
 *
 * <pre>{@code
 * OrderItem item = new OrderItem();
 * item.setManufacturer("Samsung");
 * item.setName("Galaxy");
 * item.setProductId("i1900");
 * item.setQuantity(1);
 * OrderRequest request = new OrderItemToOrderRequestMapper().toOrderRequest(item);
 * // request.getName() is "Galaxy", request.getQuantity() is 1
 * }</pre>
 */
@Component
public class OrderItemToOrderRequestMapper {

    /**
     * Copies {@code name} and {@code quantity}.
     *
     * <p>The new request holds {@code item.getName()} unchanged, {@code null} included, and
     * {@code item.getQuantity()}. No other value of the item is read; {@code manufacturer},
     * {@code productId} and {@code purchaseReceipt} are not carried.
     *
     * @param item the order item routed to the Samsung branch; must not be {@code null}
     * @return a new Samsung order request with the item's name and quantity
     * @throws NullPointerException when {@code item} is {@code null}
     */
    public OrderRequest toOrderRequest(OrderItem item) {
        OrderRequest request = new OrderRequest();
        request.setName(item.getName());
        request.setQuantity(item.getQuantity());
        return request;
    }
}
