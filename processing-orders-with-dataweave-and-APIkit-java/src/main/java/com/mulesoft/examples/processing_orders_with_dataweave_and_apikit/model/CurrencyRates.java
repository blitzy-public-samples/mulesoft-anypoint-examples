package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.model;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * USD conversion rates returned by {@code GET /api/currencies}: the body that {@code OrderFlow}
 * stores in {@code flowVars.currencies}
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:9-11], read over loopback
 * HTTP (D-054).
 *
 * <p>The JSON form is the object of {@code currency.json}: one {@code USD} array whose entries
 * bind, in document order, to {@link #usd()}.
 *
 * <pre>{@code
 * {
 *   "USD": [
 *     {"currency": "EUR", "ratio":0.92},
 *     {"currency": "ARS", "ratio":8.76},
 *     {"currency": "GBP", "ratio":0.66}
 *   ]
 * }
 * }</pre>
 *
 * <p>Read with decimal ratios kept as written:
 *
 * <pre>{@code
 * ObjectMapper mapper = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
 * CurrencyRates rates = mapper.readValue(body, CurrencyRates.class);
 * rates.usd().get(0).ratio(); // 0.92, scale 2
 * }</pre>
 *
 * <p>Serialisation writes the component under the key {@code USD}.
 *
 * @param usd the entries of the {@code USD} array, in document order
 */
public record CurrencyRates(@JsonProperty("USD") List<Rate> usd) {

    /**
     * One currency code and its ratio to USD; DW-23 multiplies a USD item price by {@code ratio}
     * for the price in {@code currency}
     * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:21-24].
     *
     * <p>{@code currency} holds the code text of the served data, while
     * {@code api/currency-schema.json} declares the property a number (D-043). {@code ratio} holds
     * the decimal with its written scale when read with
     * {@code DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS}: {@code 0.92} reads as
     * {@code new BigDecimal("0.92")}.
     *
     * @param currency the currency code, for example {@code EUR}
     * @param ratio the factor applied to a USD price for the price in {@code currency}
     */
    public record Rate(String currency, BigDecimal ratio) {
    }
}
