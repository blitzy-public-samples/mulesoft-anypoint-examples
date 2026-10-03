package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import java.util.Objects;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.Status;
import com.mulesoft.se.samsung.OrderResponse;

/**
 * Builds {@code PurchaseReceipt} values (DW-31, SC-05, SC-06; fulfillment.xml; D-034).
 *
 * <p>One method per transform of
 * {@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml}:
 *
 * <ul>
 *   <li>{@link #fromSamsung(OrderResponse)} (DW-31, fulfillment.xml:68-77) maps the Samsung
 *       {@link OrderResponse} of flow {@code samsungOrder} to a receipt;</li>
 *   <li>{@link #rejected()} (SC-05, fulfillment.xml:80-91) is the receipt of the
 *       {@code samsungOrder} catch strategy;</li>
 *   <li>{@link #accepted(Object)} (SC-06, fulfillment.xml:116-122) is the receipt of flow
 *       {@code inhouseOrder}.</li>
 * </ul>
 *
 * <p>{@link OrderResponse} is the JAXB type generated from {@code wsdl/samsung.wsdl} into
 * {@code com.mulesoft.se.samsung}; {@link PurchaseReceipt} and {@link Status} are the ported model.
 * Null handling and the number and enum conversions are those of D-572.
 *
 * <p>The class holds no state. Each call returns a new receipt and has no side effects; the methods
 * are safe for concurrent use and need no Spring context.
 *
 * <pre>{@code
 * PurchaseReceiptMapper mapper = new PurchaseReceiptMapper();
 * OrderResponse response = new OrderResponse();
 * response.setId("1");
 * response.setResult("ACCEPTED");
 * response.setPrice("2550");
 * mapper.fromSamsung(response); // id "1", status ACCEPTED, totalPrice 2550.0f
 * mapper.rejected();            // id null, status REJECTED, totalPrice 0.0f
 * mapper.accepted(477);         // id null, status ACCEPTED, totalPrice 477.0f
 * }</pre>
 */
@Component
public class PurchaseReceiptMapper {

    /**
     * Copies id, result as {@code Status}, and price as {@code float} (DW-31,
     * fulfillment.xml:68-77).
     *
     * <p>The new receipt holds:
     * <ul>
     *   <li>{@code id}: {@code response.getId()} unchanged, {@code null} included;</li>
     *   <li>{@code status}: {@code Status.valueOf(response.getResult())}, an exact, case-sensitive
     *       match of {@code ACCEPTED} or {@code REJECTED}; a {@code null} result leaves it
     *       {@code null};</li>
     *   <li>{@code totalPrice}: {@code Float.parseFloat(response.getPrice())}, for example
     *       {@code "2550"} gives {@code 2550.0f} and {@code "450"} gives {@code 450.0f}; a
     *       {@code null} price leaves it at {@code 0.0f}.</li>
     * </ul>
     *
     * @param response the Samsung order response; must not be {@code null}
     * @return a new receipt carrying the response's id, result and price
     * @throws NullPointerException     when {@code response} is {@code null}
     * @throws IllegalArgumentException when the result is not the name of a {@link Status} constant
     * @throws NumberFormatException    when the price is not a number {@link Float#parseFloat}
     *                                  accepts
     */
    public PurchaseReceipt fromSamsung(OrderResponse response) {
        Objects.requireNonNull(response, "response");
        String result = response.getResult();
        String price = response.getPrice();

        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setId(response.getId());
        if (result != null) {
            receipt.setStatus(Status.valueOf(result));
        }
        if (price != null) {
            receipt.setTotalPrice(Float.parseFloat(price));
        }
        return receipt;
    }

    /**
     * REJECTED receipt with totalPrice 0 (SC-05, fulfillment.xml:80-91).
     *
     * <p>The new receipt has status {@link Status#REJECTED}, {@code totalPrice} {@code 0.0f} and a
     * {@code null} id.
     *
     * @return a new REJECTED receipt
     */
    public PurchaseReceipt rejected() {
        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setStatus(Status.REJECTED);
        receipt.setTotalPrice(0f);
        return receipt;
    }

    /**
     * ACCEPTED receipt with totalPrice parsed from the given value (SC-06,
     * fulfillment.xml:116-122).
     *
     * <p>The new receipt has status {@link Status#ACCEPTED}, a {@code null} id and
     * {@code totalPrice} {@code Float.valueOf(String.valueOf(totalPrice))}: the {@code Integer}
     * {@code 0} gives {@code 0.0f}, the {@code Integer} {@code 477} gives {@code 477.0f}, the
     * {@code String} {@code "2550"} gives {@code 2550.0f}, the {@code Double} {@code 5.0} gives
     * {@code 5.0f} and the {@code BigDecimal} {@code 12.50} gives {@code 12.5f}.
     *
     * @param totalPrice the item total, the flow variable {@code totalPrice} of fulfillment.xml:114
     * @return a new ACCEPTED receipt with the parsed total price
     * @throws NumberFormatException when the text of {@code totalPrice} is not a number
     *                               {@link Float#valueOf(String)} accepts, {@code null} (text
     *                               {@code "null"}) included
     */
    public PurchaseReceipt accepted(Object totalPrice) {
        PurchaseReceipt receipt = new PurchaseReceipt();
        receipt.setStatus(Status.ACCEPTED);
        receipt.setTotalPrice(Float.valueOf(String.valueOf(totalPrice)).floatValue());
        return receipt;
    }
}
