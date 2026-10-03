package com.mulesoft.examples.legacy_modernization.mapper;

import java.util.List;
import java.util.function.Function;

import org.ordermgmt.Address;
import org.ordermgmt.OrderItem;
import org.ordermgmt.ShippingOrder;
import org.ordermgmt.ShippingOrderConfirmation;
import org.springframework.stereotype.Component;

/**
 * Maps a shipping order confirmation to CSV text: a header record, then one record per order item.
 *
 * <p>Hand re-implementation (D-034) of DW-15, the {@code dw:set-payload} inside the {@code async}
 * scope of flow {@code Fulfillment_LegacySystemModernization}
 * [legacy-modernization/src/main/app/FufillmentWebService.xml:12-32]. The input types of package
 * {@code org.ordermgmt} are the JAXB classes generated from {@code wsdl/IFulfillmentService.wsdl}
 * (D-028); the original {@code orderItemList} property is {@link org.ordermgmt.Order#getOrderItem()}.
 *
 * <pre>{@code
 * %dw 1.0
 * %output application/csv header=true
 * ---
 * payload.shippingOrder.order.orderItemList map
 * {
 *     MSKU                 : $.merchantSKU,
 *     QTY                  : $.quantity,
 *     BillingAddressName   : payload.shippingOrder.billingAddress.name,
 *     BillingAddressStreet : payload.shippingOrder.billingAddress.line1 ++ ' ' ++ payload.shippingOrder.billingAddress.line2,
 *     BillingAddrCity      : payload.shippingOrder.billingAddress.city,
 *     BillingAddrState     : payload.shippingOrder.billingAddress.stateOrProvinceCode,
 *     BillingAddrCountry   : payload.shippingOrder.billingAddress.countryCode,
 *     BillingAddrZipCode   : payload.shippingOrder.billingAddress.postalCode,
 *     ShippingAddrName     : payload.shippingOrder.shippingAddress.name,
 *     ShippingAddrStreet   : payload.shippingOrder.shippingAddress.line1 ++ ' ' ++ payload.shippingOrder.shippingAddress.line2,
 *     ShippingAddrCity     : payload.shippingOrder.shippingAddress.city,
 *     ShippingAddrState    : payload.shippingOrder.shippingAddress.stateOrProvinceCode,
 *     ShippingAddrCountry  : payload.shippingOrder.shippingAddress.countryCode,
 *     ShippingAddrZipCode  : payload.shippingOrder.shippingAddress.postalCode,
 *     ShippingId           : payload.shippingOrder.shippingId
 * }
 * }</pre>
 *
 * <p>Writer rules:
 *
 * <ul>
 *   <li>fields are separated by {@code ,}; every record, the last one included, ends with
 *       {@code \n} (LF, never CR);</li>
 *   <li>a value is enclosed in double quotes only when it contains a comma, a double quote, CR or
 *       LF; inside a quoted value a double quote is written as {@code \"}, and no other character
 *       is escaped;</li>
 *   <li>empty values, values with leading or trailing spaces and values starting with {@code #}
 *       are written unquoted;</li>
 *   <li>a {@code null} value is written as an empty field.</li>
 * </ul>
 *
 * <p>The records are built with a {@link StringBuilder} by this class's own field writer;
 * commons-csv {@code CSVPrinter} is not used.
 *
 * <p>Instances hold no state; {@link #toCsv(ShippingOrderConfirmation)} has no side effects and
 * is safe for concurrent use.
 */
@Component
public class ShippingOrderCsvMapper {

    /**
     * Writes the DW-15 CSV text for the given confirmation.
     *
     * <p>The result is {@code ""} (no header record, no line feed) when {@code payload}, its
     * shipping order or that order's {@code order} is {@code null}, or when the item list is
     * {@code null} or empty. Otherwise it is the header record followed by one record per element
     * of {@code shippingOrder.getOrder().getOrderItem()}, in list order, with these columns:
     *
     * <ol>
     *   <li>{@code MSKU}: the item's {@code merchantSKU};</li>
     *   <li>{@code QTY}: the item's {@code quantity} in decimal;</li>
     *   <li>{@code BillingAddressName}: billing address {@code name};</li>
     *   <li>{@code BillingAddressStreet}: billing address {@code line1}, a space, {@code line2};</li>
     *   <li>{@code BillingAddrCity}: billing address {@code city};</li>
     *   <li>{@code BillingAddrState}: billing address {@code stateOrProvinceCode};</li>
     *   <li>{@code BillingAddrCountry}: billing address {@code countryCode};</li>
     *   <li>{@code BillingAddrZipCode}: billing address {@code postalCode};</li>
     *   <li>{@code ShippingAddrName}: shipping address {@code name};</li>
     *   <li>{@code ShippingAddrStreet}: shipping address {@code line1}, a space, {@code line2};</li>
     *   <li>{@code ShippingAddrCity}: shipping address {@code city};</li>
     *   <li>{@code ShippingAddrState}: shipping address {@code stateOrProvinceCode};</li>
     *   <li>{@code ShippingAddrCountry}: shipping address {@code countryCode};</li>
     *   <li>{@code ShippingAddrZipCode}: shipping address {@code postalCode};</li>
     *   <li>{@code ShippingId}: the shipping order's {@code shippingId}.</li>
     * </ol>
     *
     * <p>A {@code null} value, a {@code null} address in a non-street column and a {@code null}
     * item in the {@code MSKU} and {@code QTY} columns are written as empty fields. A street column
     * whose {@code line1} or {@code line2} is {@code null}, a {@code null} address included, raises
     * {@link IllegalArgumentException}.
     *
     * <p>For the request {@code original/message.xml} the result is the header record and the
     * records
     * {@code 1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234},
     * then the same for items {@code 6789}/{@code 1500} and {@code 9998}/{@code 5000}, each ending
     * with {@code \n}.
     *
     * @param payload the confirmation the fulfillment service returns; may be {@code null}
     * @return the CSV text, or {@code ""} when there is no item to write
     * @throws IllegalArgumentException with a message starting {@code Type mismatch for '++'
     *     operator} when a billing or shipping {@code line1} or {@code line2} is {@code null} and
     *     the item list is not empty
     */
    public String toCsv(ShippingOrderConfirmation payload) {
        ShippingOrder shippingOrder = payload == null ? null : payload.getShippingOrder();
        if (shippingOrder == null || shippingOrder.getOrder() == null) {
            return "";
        }
        List<OrderItem> items = shippingOrder.getOrder().getOrderItem();
        if (items == null || items.isEmpty()) {
            return "";
        }
        Address billing = shippingOrder.getBillingAddress();
        Address shipping = shippingOrder.getShippingAddress();

        StringBuilder csv = new StringBuilder();
        appendRecord(csv,
                "MSKU",
                "QTY",
                "BillingAddressName",
                "BillingAddressStreet",
                "BillingAddrCity",
                "BillingAddrState",
                "BillingAddrCountry",
                "BillingAddrZipCode",
                "ShippingAddrName",
                "ShippingAddrStreet",
                "ShippingAddrCity",
                "ShippingAddrState",
                "ShippingAddrCountry",
                "ShippingAddrZipCode",
                "ShippingId");
        for (OrderItem item : items) {
            appendRecord(csv,
                    item == null ? null : item.getMerchantSKU(),
                    item == null ? null : Integer.toString(item.getQuantity()),
                    field(billing, Address::getName),
                    concat(field(billing, Address::getLine1), field(billing, Address::getLine2)),
                    field(billing, Address::getCity),
                    field(billing, Address::getStateOrProvinceCode),
                    field(billing, Address::getCountryCode),
                    field(billing, Address::getPostalCode),
                    field(shipping, Address::getName),
                    concat(field(shipping, Address::getLine1), field(shipping, Address::getLine2)),
                    field(shipping, Address::getCity),
                    field(shipping, Address::getStateOrProvinceCode),
                    field(shipping, Address::getCountryCode),
                    field(shipping, Address::getPostalCode),
                    shippingOrder.getShippingId());
        }
        return csv.toString();
    }

    /**
     * Appends one record: the escaped values separated by {@code ,}, then {@code \n}.
     *
     * @param csv the text being built
     * @param values the field values in column order; {@code null} elements are empty fields
     */
    private static void appendRecord(StringBuilder csv, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(escape(values[i]));
        }
        csv.append('\n');
    }

    /**
     * Returns one field as written to the CSV text.
     *
     * <p>{@code null} gives {@code ""}. A value containing {@code ,}, {@code "}, CR or LF is
     * enclosed in double quotes, with each embedded {@code "} written as {@code \"}. Any other
     * value, the empty string included, is returned unchanged.
     *
     * @param value the field value; may be {@code null}
     * @return the field text
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        boolean quoted = false;
        for (int i = 0; i < value.length() && !quoted; i++) {
            char c = value.charAt(i);
            quoted = c == ',' || c == '"' || c == '\r' || c == '\n';
        }
        if (!quoted) {
            return value;
        }
        StringBuilder field = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                field.append('\\');
            }
            field.append(c);
        }
        return field.append('"').toString();
    }

    /**
     * Returns {@code line1 + " " + line2}, the DW 1.0 {@code line1 ++ ' ' ++ line2}.
     *
     * @param line1 the first street line
     * @param line2 the second street line
     * @return the joined street text
     * @throws IllegalArgumentException with a message starting {@code Type mismatch for '++'
     *     operator} when {@code line1} or {@code line2} is {@code null}
     */
    private static String concat(String line1, String line2) {
        if (line1 == null) {
            throw new IllegalArgumentException(
                    "Type mismatch for '++' operator: found (:null, :string), required (:string, :string)");
        }
        if (line2 == null) {
            throw new IllegalArgumentException(
                    "Type mismatch for '++' operator: found (:string, :null), required (:string, :string)");
        }
        return line1 + " " + line2;
    }

    /**
     * Returns the value {@code accessor} reads from {@code address}, or {@code null} when
     * {@code address} is {@code null}.
     *
     * @param address the billing or shipping address; may be {@code null}
     * @param accessor the {@link Address} getter of the column
     * @return the address field, or {@code null}
     */
    private static String field(Address address, Function<Address, String> accessor) {
        return address == null ? null : accessor.apply(address);
    }
}
