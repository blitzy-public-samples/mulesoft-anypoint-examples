package com.mulesoft.examples.legacy_modernization.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.ordermgmt.Address;
import org.ordermgmt.Order;
import org.ordermgmt.OrderItem;
import org.ordermgmt.ShippingOrder;
import org.ordermgmt.ShippingOrderConfirmation;

/**
 * Unit tests of {@link ShippingOrderCsvMapper#toCsv(ShippingOrderConfirmation)}, the DW-15
 * {@code dw:set-payload} [legacy-modernization/src/main/app/FufillmentWebService.xml:12-32], run on
 * {@code new ShippingOrderCsvMapper()} with no application context.
 *
 * <p>The input is built from the JAXB types of package {@code org.ordermgmt} generated from
 * {@code wsdl/IFulfillmentService.wsdl}, with the values of the request
 * [legacy-modernization/src/test/resources/message.xml]: shipping id {@code 1234}, identical billing
 * and shipping addresses, and the items {@code 1234}/{@code 500}, {@code 6789}/{@code 1500} and
 * {@code 9998}/{@code 5000}.
 *
 * <p>The cases cover:
 *
 * <ul>
 *   <li>the header record and one record per order item, each ending with {@code \n};</li>
 *   <li>the street column {@code line1 ++ ' ' ++ line2}, and the {@code IllegalArgumentException}
 *       of {@code ++} with a {@code null} operand;</li>
 *   <li>a {@code null} value written as an empty field;</li>
 *   <li>quoting of a value holding a comma, and {@code \"} for a double quote inside a value;</li>
 *   <li>{@code ""} for an empty item list and for a {@code null} order.</li>
 * </ul>
 *
 * <p>Every expectation is compared character for character. These tests cover the {@code mapper}
 * package under the JaCoCo LINE covered ratio rule of at least 0.80 (D-049).
 */
public class ShippingOrderCsvMapperTest {

    /** The DW-15 header record without its line feed. */
    private static final String HEADER_RECORD = "MSKU,QTY,BillingAddressName,BillingAddressStreet,"
            + "BillingAddrCity,BillingAddrState,BillingAddrCountry,BillingAddrZipCode,"
            + "ShippingAddrName,ShippingAddrStreet,ShippingAddrCity,ShippingAddrState,"
            + "ShippingAddrCountry,ShippingAddrZipCode,ShippingId";

    /** The DW-15 header record with its line feed. */
    private static final String HEADER = "MSKU,QTY,BillingAddressName,BillingAddressStreet,"
            + "BillingAddrCity,BillingAddrState,BillingAddrCountry,BillingAddrZipCode,"
            + "ShippingAddrName,ShippingAddrStreet,ShippingAddrCity,ShippingAddrState,"
            + "ShippingAddrCountry,ShippingAddrZipCode,ShippingId\n";

    /** The data record of item {@code 1234}/{@code 500} of message.xml, without its line feed. */
    private static final String ROW_1234 = "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234";

    /** The CSV text of the message.xml request: the header and three data records. */
    private static final String MESSAGE_XML_CSV = "MSKU,QTY,BillingAddressName,BillingAddressStreet,"
            + "BillingAddrCity,BillingAddrState,BillingAddrCountry,BillingAddrZipCode,"
            + "ShippingAddrName,ShippingAddrStreet,ShippingAddrCity,ShippingAddrState,"
            + "ShippingAddrCountry,ShippingAddrZipCode,ShippingId\n"
            + "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "6789,1500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "9998,5000,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n";

    private final ShippingOrderCsvMapper mapper = new ShippingOrderCsvMapper();

    /** DW-15: the message.xml order maps to the header and one record per item, in item order. */
    @Test
    public void messageXmlOrderMapsToCsv() {
        String csv = mapper.toCsv(messageXmlConfirmation());

        assertThat(csv).isEqualTo(MESSAGE_XML_CSV);
    }

    /** DW-15: a single item gives two {@code \n}-terminated records, the header and one data record. */
    @Test
    public void singleItem() {
        String csv = mapper.toCsv(confirmation(item("1234", 500)));

        assertThat(csv).isEqualTo(HEADER
                + "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
                + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n");
        String[] lines = csv.split("\n", -1);
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).isEqualTo(HEADER_RECORD);
        assertThat(lines[1]).isEqualTo(ROW_1234);
        assertThat(lines[2]).isEmpty();
    }

    /** DW-15: a {@code null} billing {@code line2} fails the {@code ++} concatenation. */
    @Test
    public void nullLine2Throws() {
        ShippingOrderConfirmation c = messageXmlConfirmation();
        c.getShippingOrder().getBillingAddress().setLine2(null);

        assertThatThrownBy(() -> mapper.toCsv(c))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Type mismatch for '++' operator");
    }

    /** DW-15: a {@code null} billing name is written as an empty field. */
    @Test
    public void nullNameWritesEmptyField() {
        ShippingOrderConfirmation c = messageXmlConfirmation();
        c.getShippingOrder().getBillingAddress().setName(null);

        String row = firstDataRow(mapper.toCsv(c));

        assertThat(row).startsWith("1234,500,,77 Geary St Level 4,");
        assertThat(row).isEqualTo("1234,500,,77 Geary St Level 4,San Francisco,CA,USA,94108,"
                + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234");
    }

    /** DW-15: only the field holding a comma is enclosed in double quotes. */
    @Test
    public void commaValueQuoted() {
        ShippingOrderConfirmation c = messageXmlConfirmation();
        c.getShippingOrder().getBillingAddress().setCity("San Francisco, CA");

        String row = firstDataRow(mapper.toCsv(c));

        assertThat(row).isEqualTo("1234,500,Mulesoft,77 Geary St Level 4,\"San Francisco, CA\",CA,USA,94108,"
                + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234");
    }

    /** DW-15: a double quote inside a value is written {@code \"} within a quoted field. */
    @Test
    public void quoteValueEscaped() {
        ShippingOrderConfirmation c = messageXmlConfirmation();
        c.getShippingOrder().getBillingAddress().setName("Mule \"Soft\"");

        String row = firstDataRow(mapper.toCsv(c));

        String quotedName = "\"Mule \\\"Soft\\\"\"";
        assertThat(row).isEqualTo("1234,500," + quotedName + ",77 Geary St Level 4,San Francisco,CA,USA,94108,"
                + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234");
    }

    /** DW-15: an empty item list gives {@code ""}, with no header record. */
    @Test
    public void emptyItemListWritesNothing() {
        ShippingOrderConfirmation c = confirmation();

        assertThat(c.getShippingOrder().getOrder().getOrderItem()).isEmpty();
        assertThat(mapper.toCsv(c)).isEqualTo("");
    }

    /** DW-15: a {@code null} order gives {@code ""}, with no header record. */
    @Test
    public void nullOrderWritesNothing() {
        ShippingOrderConfirmation c = messageXmlConfirmation();
        c.getShippingOrder().setOrder(null);

        assertThat(mapper.toCsv(c)).isEqualTo("");
    }

    /**
     * Returns the first data record of {@code csv}, after asserting that the first record is the
     * header and that the text holds the three message.xml data records and a final line feed.
     */
    private static String firstDataRow(String csv) {
        String[] lines = csv.split("\n", -1);
        assertThat(lines).hasSize(5);
        assertThat(lines[0]).isEqualTo(HEADER_RECORD);
        assertThat(lines[4]).isEmpty();
        return lines[1];
    }

    /** Returns a new message.xml address: {@code Mulesoft}, {@code 77 Geary St}, {@code Level 4}, San Francisco. */
    private static Address address() {
        Address address = new Address();
        address.setName("Mulesoft");
        address.setLine1("77 Geary St");
        address.setLine2("Level 4");
        address.setCity("San Francisco");
        address.setStateOrProvinceCode("CA");
        address.setCountryCode("USA");
        address.setPostalCode("94108");
        return address;
    }

    /** Returns an order item with the given merchant SKU and quantity. */
    private static OrderItem item(String sku, int qty) {
        OrderItem item = new OrderItem();
        item.setMerchantSKU(sku);
        item.setQuantity(qty);
        return item;
    }

    /**
     * Returns a received confirmation of shipping order {@code 1234} with separate billing and
     * shipping {@link #address()} instances and an order holding {@code items} in the given order.
     */
    private static ShippingOrderConfirmation confirmation(OrderItem... items) {
        Order order = new Order();
        for (OrderItem item : items) {
            order.getOrderItem().add(item);
        }
        ShippingOrder shippingOrder = new ShippingOrder();
        shippingOrder.setShippingId("1234");
        shippingOrder.setBillingAddress(address());
        shippingOrder.setShippingAddress(address());
        shippingOrder.setOrder(order);
        ShippingOrderConfirmation confirmation = new ShippingOrderConfirmation();
        confirmation.setShippingOrder(shippingOrder);
        confirmation.setOrderReceivedStatus(true);
        return confirmation;
    }

    /** Returns the confirmation of the message.xml request: items 1234/500, 6789/1500, 9998/5000. */
    private static ShippingOrderConfirmation messageXmlConfirmation() {
        return confirmation(item("1234", 500), item("6789", 1500), item("9998", 5000));
    }
}
