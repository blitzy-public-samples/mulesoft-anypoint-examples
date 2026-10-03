/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.service_orchestration_and_choice_routing.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mulesoft.examples.service_orchestration_and_choice_routing.model.PurchaseReceipt;
import com.mulesoft.examples.service_orchestration_and_choice_routing.model.Status;
import com.mulesoft.se.samsung.OrderResponse;

/**
 * Unit tests of {@link PurchaseReceiptMapper}: DW-31 Samsung response mapping and the SC-05/SC-06 receipt scripts
 * (D-034).
 *
 * <ul>
 *   <li>DW-31 [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:68-77], flow
 *       {@code samsungOrder}: {@link PurchaseReceiptMapper#fromSamsung(OrderResponse)} copies the Samsung
 *       {@code id}, {@code result} as {@link Status} and {@code price} as the {@code float} total price.</li>
 *   <li>SC-05 [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:80-91], the
 *       {@code samsungOrder} catch strategy: {@link PurchaseReceiptMapper#rejected()} is a {@code REJECTED} receipt
 *       with total price {@code 0} and no id.</li>
 *   <li>SC-06 [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:116-122], flow
 *       {@code inhouseOrder}: {@link PurchaseReceiptMapper#accepted(Object)} is an {@code ACCEPTED} receipt with no
 *       id and the total price {@code Float.valueOf} reads from the text of the {@code totalPrice} value.</li>
 * </ul>
 *
 * <p>Each test calls a {@link PurchaseReceiptMapper} created with {@code new}, with no Spring application context, no
 * network and no file access. The receipt {@code 1}, {@code ACCEPTED}, {@code 2550.0} is the one of the original
 * reply {@code original/reply.xml}; the response {@code 123}, {@code ACCEPTED}, {@code 450} is the design-time sample
 * {@code sample_data/OrderResponse_1.dwl}, held inline (D-037).
 */
public class PurchaseReceiptMapperTest {

    private final PurchaseReceiptMapper mapper = new PurchaseReceiptMapper();

    /**
     * Builds the JAXB {@link OrderResponse} generated from {@code wsdl/samsung.wsdl} through its setters.
     *
     * @param id     the Samsung order id
     * @param result the Samsung result text
     * @param price  the Samsung price text
     * @return a new response holding the three values
     */
    private static OrderResponse orderResponse(String id, String result, String price) {
        OrderResponse response = new OrderResponse();
        response.setId(id);
        response.setResult(result);
        response.setPrice(price);
        return response;
    }

    // DW-31: OrderResponse to PurchaseReceipt

    /** The accepted Samsung response of the original reply becomes receipt {@code 1}, ACCEPTED, 2550.0. */
    @Test
    @DisplayName("DW-31 maps an ACCEPTED Samsung response with price 2550 to receipt 1, ACCEPTED, 2550.0")
    public void dw31AcceptedResponse() {
        PurchaseReceipt receipt = mapper.fromSamsung(orderResponse("1", "ACCEPTED", "2550"));

        assertThat(receipt.getId()).isEqualTo("1");
        assertThat(receipt.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(2550.0f);
    }

    /** The design-time sample response becomes receipt {@code 123}, ACCEPTED, 450.0 (D-037). */
    @Test
    @DisplayName("DW-31 maps the sample Samsung response 123, ACCEPTED, 450 to receipt 123, ACCEPTED, 450.0")
    public void dw31SampleResponse() {
        PurchaseReceipt receipt = mapper.fromSamsung(orderResponse("123", "ACCEPTED", "450"));

        assertThat(receipt.getId()).isEqualTo("123");
        assertThat(receipt.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(450.0f);
    }

    /** A decimal price text keeps its fraction in the total price. */
    @Test
    @DisplayName("DW-31 maps the decimal price text 2550.5 to the total price 2550.5")
    public void dw31DecimalPrice() {
        PurchaseReceipt receipt = mapper.fromSamsung(orderResponse("7", "ACCEPTED", "2550.5"));

        assertThat(receipt.getId()).isEqualTo("7");
        assertThat(receipt.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(2550.5f);
    }

    /** The result text {@code REJECTED} becomes {@link Status#REJECTED}, with the id and the price copied. */
    @Test
    @DisplayName("DW-31 maps a REJECTED Samsung response with price 0 to receipt 9, REJECTED, 0.0")
    public void dw31RejectedResult() {
        PurchaseReceipt receipt = mapper.fromSamsung(orderResponse("9", "REJECTED", "0"));

        assertThat(receipt.getId()).isEqualTo("9");
        assertThat(receipt.getStatus()).isEqualTo(Status.REJECTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(0.0f);
    }

    /** Each call builds a new receipt; two calls with one response give equal, distinct receipts. */
    @Test
    @DisplayName("DW-31 returns a new, equal receipt on every call with the same Samsung response")
    public void dw31NewInstancePerCall() {
        OrderResponse response = orderResponse("1", "ACCEPTED", "2550");

        PurchaseReceipt first = mapper.fromSamsung(response);
        PurchaseReceipt second = mapper.fromSamsung(response);

        assertThat(first).isNotSameAs(second);
        assertThat(first).usingRecursiveComparison().isEqualTo(second);
    }

    // SC-05: REJECTED receipt of the samsungOrder catch strategy

    /** The rejected receipt has status REJECTED, total price 0.0 and no id. */
    @Test
    @DisplayName("SC-05 builds a REJECTED receipt with total price 0.0 and no id")
    public void sc05Rejected() {
        PurchaseReceipt receipt = mapper.rejected();

        assertThat(receipt.getStatus()).isEqualTo(Status.REJECTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(0.0f);
        assertThat(receipt.getId()).isNull();
    }

    /** Each call builds a new receipt; a change to one receipt leaves later receipts at total price 0.0. */
    @Test
    @DisplayName("SC-05 returns a new REJECTED receipt on every call, unaffected by changes to an earlier one")
    public void sc05NewInstancePerCall() {
        PurchaseReceipt first = mapper.rejected();
        PurchaseReceipt second = mapper.rejected();

        assertThat(first).isNotSameAs(second);

        first.setTotalPrice(5f);
        PurchaseReceipt third = mapper.rejected();

        assertThat(third).isNotSameAs(first);
        assertThat(third.getStatus()).isEqualTo(Status.REJECTED);
        assertThat(third.getTotalPrice()).isEqualTo(0.0f);
        assertThat(second.getTotalPrice()).isEqualTo(0.0f);
    }

    // SC-06: ACCEPTED receipt of flow inhouseOrder

    /** The {@code Integer} 2550, the product {@code price * quantity}, becomes the total price 2550.0. */
    @Test
    @DisplayName("SC-06 builds an ACCEPTED receipt with no id from the Integer total price 2550")
    public void sc06AcceptedFromInteger() {
        PurchaseReceipt receipt = mapper.accepted(Integer.valueOf(2550));

        assertThat(receipt.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(2550.0f);
        assertThat(receipt.getId()).isNull();
    }

    /** The {@code String} {@code "159"} becomes the total price 159.0. */
    @Test
    @DisplayName("SC-06 builds an ACCEPTED receipt with no id from the String total price 159")
    public void sc06AcceptedFromString() {
        PurchaseReceipt receipt = mapper.accepted("159");

        assertThat(receipt.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(159.0f);
        assertThat(receipt.getId()).isNull();
    }

    /** The {@code Double} 318.5, whose text is {@code "318.5"}, becomes the total price 318.5. */
    @Test
    @DisplayName("SC-06 builds an ACCEPTED receipt with no id from the Double total price 318.5")
    public void sc06AcceptedFromDecimal() {
        PurchaseReceipt receipt = mapper.accepted(Double.valueOf(318.5));

        assertThat(receipt.getStatus()).isEqualTo(Status.ACCEPTED);
        assertThat(receipt.getTotalPrice()).isEqualTo(318.5f);
        assertThat(receipt.getId()).isNull();
    }

    /** A total price text that {@code Float.valueOf} rejects raises {@link NumberFormatException}. */
    @Test
    @DisplayName("SC-06 throws NumberFormatException for the non-numeric total price abc")
    public void sc06NonNumericThrows() {
        assertThatThrownBy(() -> mapper.accepted("abc")).isInstanceOf(NumberFormatException.class);
    }
}
