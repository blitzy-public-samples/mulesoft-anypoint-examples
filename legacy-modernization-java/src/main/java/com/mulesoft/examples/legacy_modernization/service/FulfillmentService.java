/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.legacy_modernization.service;

import org.ordermgmt.Address;
import org.ordermgmt.Order;
import org.ordermgmt.PutShippingOrder;
import org.ordermgmt.ShippingOrder;
import org.ordermgmt.ShippingOrderConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service of flow {@code Fulfillment_LegacySystemModernization}
 * [legacy-modernization/src/main/app/FufillmentWebService.xml:4-37] and port of
 * {@code org.ordermgmt.FulfillmentImpl} [legacy-modernization/src/main/java/org/ordermgmt/FulfillmentImpl.java].
 * The copyright header is the one of {@code FulfillmentImpl.java} (D-048).
 *
 * <ul>
 *   <li>{@link #putShippingOrder(String, Address, Address, Order)} is {@code FulfillmentImpl#putShippingOrder},
 *       invoked by the flow's {@code component} [:8]: it builds the {@link ShippingOrderConfirmation}.</li>
 *   <li>{@link #fulfillmentLegacySystemModernization(PutShippingOrder)} is the flow body: the component, then
 *       the {@code async} scope [:9-36], which is {@link ShippingOrderFileWriter#write(ShippingOrderConfirmation)}
 *       on the {@code legacyFulfillmentExecutor} executor (D-060).</li>
 * </ul>
 *
 * <p>The {@link ShippingOrder} and {@link ShippingOrderConfirmation} of a call are local variables; the bean
 * holds no request state (D-669). The payload types are the JAXB classes generated from
 * {@code wsdl/IFulfillmentService.wsdl} (D-028).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * FulfillmentService service = new FulfillmentService(shippingOrderFileWriter);
 * ShippingOrderConfirmation confirmation = service.fulfillmentLegacySystemModernization(request);
 * // confirmation.isOrderReceivedStatus() is true; shippingOrderFileWriter.write(confirmation) was called once
 * }</pre>
 */
@Service
public class FulfillmentService {

    private static final Logger LOG = LoggerFactory.getLogger(FulfillmentService.class);

    private final ShippingOrderFileWriter shippingOrderFileWriter;

    /**
     * Creates the service.
     *
     * @param shippingOrderFileWriter writes a confirmation as the legacy fulfillment CSV file
     */
    public FulfillmentService(ShippingOrderFileWriter shippingOrderFileWriter) {
        this.shippingOrderFileWriter = shippingOrderFileWriter;
    }

    /**
     * Runs flow {@code Fulfillment_LegacySystemModernization} for one {@code putShippingOrder} request
     * [legacy-modernization/src/main/app/FufillmentWebService.xml:4-37].
     *
     * <ol>
     *   <li>Builds the confirmation with {@link #putShippingOrder(String, Address, Address, Order)} from the
     *       request's {@code shippingId}, {@code billingAddress}, {@code shippingAddress} and {@code order}
     *       [:8].</li>
     *   <li>Passes that confirmation, {@code null} included, once to
     *       {@link ShippingOrderFileWriter#write(ShippingOrderConfirmation)}. Called on the Spring bean, the
     *       write runs on the {@code legacyFulfillmentExecutor} executor and this method returns without
     *       waiting for the file [:9-36] (D-060).</li>
     *   <li>Returns the same confirmation, not modified after the write call.</li>
     * </ol>
     *
     * @param request the unmarshalled {@code putShippingOrder} payload
     * @return the confirmation for the SOAP reply; {@code null} when it could not be built (D-669)
     * @throws NullPointerException when {@code request} is {@code null}
     */
    public ShippingOrderConfirmation fulfillmentLegacySystemModernization(PutShippingOrder request) {
        ShippingOrderConfirmation confirmation = putShippingOrder(request.getShippingId(),
                request.getBillingAddress(), request.getShippingAddress(), request.getOrder());
        shippingOrderFileWriter.write(confirmation);
        return confirmation;
    }

    /**
     * Builds the confirmation of a shipping order, as {@code FulfillmentImpl#putShippingOrder} does
     * [legacy-modernization/src/main/java/org/ordermgmt/FulfillmentImpl.java:21-38].
     *
     * <p>Sets the four arguments on a new {@link ShippingOrder} in the order of the original constructor
     * ({@code shippingId}, {@code billingAddress}, {@code shippingAddress}, {@code order}) and wraps it in a new
     * {@link ShippingOrderConfirmation} whose {@code orderReceivedStatus} is {@code true}. The arguments are
     * stored as given: the same object references, no copy, no validation, {@code null} accepted. The CSV file
     * is not written here.
     *
     * <p>An exception while building is logged at ERROR with its stack trace, and {@code null} is returned
     * (D-669).
     *
     * @param shippingId      the shipping identifier
     * @param billingAddress  the billing address
     * @param shippingAddress the shipping address
     * @param order           the ordered items
     * @return the confirmation, or {@code null} when building it failed
     */
    public ShippingOrderConfirmation putShippingOrder(String shippingId, Address billingAddress,
            Address shippingAddress, Order order) {
        // Local variables in place of FulfillmentImpl's shippingOrder and shippingOrderConfirmation fields;
        // the result is assigned once fully built and stays null when the catch runs (D-669).
        ShippingOrderConfirmation confirmation = null;
        try {
            ShippingOrder so = new ShippingOrder();
            so.setShippingId(shippingId);
            so.setBillingAddress(billingAddress);
            so.setShippingAddress(shippingAddress);
            so.setOrder(order);
            ShippingOrderConfirmation built = new ShippingOrderConfirmation();
            built.setShippingOrder(so);
            built.setOrderReceivedStatus(true);
            confirmation = built;
        } catch (Exception e) {
            LOG.error("Failed to build the shipping order confirmation", e);
        }
        return confirmation;
    }
}
