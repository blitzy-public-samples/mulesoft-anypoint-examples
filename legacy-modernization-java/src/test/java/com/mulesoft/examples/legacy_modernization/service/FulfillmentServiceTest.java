package com.mulesoft.examples.legacy_modernization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.InputStream;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;

import jakarta.xml.bind.JAXBContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.ordermgmt.Address;
import org.ordermgmt.ObjectFactory;
import org.ordermgmt.OrderItem;
import org.ordermgmt.PutShippingOrder;
import org.ordermgmt.ShippingOrder;
import org.ordermgmt.ShippingOrderConfirmation;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * Mockito unit tests of {@link FulfillmentService}, the service of flow
 * {@code Fulfillment_LegacySystemModernization} [legacy-modernization/src/main/app/FufillmentWebService.xml:4-37]
 * and port of {@code org.ordermgmt.FulfillmentImpl}, with a mocked {@link ShippingOrderFileWriter} and no
 * application context.
 *
 * <p>The request is the {@code putShippingOrder} payload of the classpath resource {@code original/message.xml}
 * [legacy-modernization/src/test/resources/message.xml], unmarshalled into the generated {@link PutShippingOrder}:
 * shipping id {@code 1234}, identical billing and shipping addresses, and the order items 1234×500, 6789×1500
 * and 9998×5000.
 *
 * <p>The cases cover:
 *
 * <ul>
 *   <li>the flow method: a confirmation that echoes the request with {@code orderReceivedStatus} {@code true},
 *       passed once to {@link ShippingOrderFileWriter#write(ShippingOrderConfirmation)} (D-060);</li>
 *   <li>{@code putShippingOrder}: the same confirmation, built without a call to the writer.</li>
 * </ul>
 *
 * <p>These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
@ExtendWith(MockitoExtension.class)
class FulfillmentServiceTest {

    /** Namespace of the {@code putShippingOrder} request element. */
    private static final String ORDER_NAMESPACE_URI = "http://ordermgmt.org/";

    @Mock
    ShippingOrderFileWriter writer;

    private FulfillmentService service;

    private PutShippingOrder input;

    /**
     * Creates the service over the mocked writer and reads the request of {@code original/message.xml}.
     *
     * @throws Exception when the resource cannot be read, parsed or unmarshalled
     */
    @BeforeEach
    void setUp() throws Exception {
        service = new FulfillmentService(writer);
        input = readRequest();
    }

    /**
     * The flow method returns a confirmation that echoes the request with {@code orderReceivedStatus}
     * {@code true}, and passes that same instance exactly once to the writer (D-060).
     */
    @Test
    void confirmationEchoesRequestAndDispatchesWrite() {
        ShippingOrderConfirmation result = service.fulfillmentLegacySystemModernization(input);

        assertEchoes(result);
        verify(writer, times(1)).write(same(result));
        verifyNoMoreInteractions(writer);
    }

    /**
     * {@code putShippingOrder} returns a confirmation that echoes its four arguments with
     * {@code orderReceivedStatus} {@code true}, and does not call the writer.
     */
    @Test
    void putShippingOrderBuildsConfirmation() {
        ShippingOrderConfirmation result = service.putShippingOrder(input.getShippingId(),
                input.getBillingAddress(), input.getShippingAddress(), input.getOrder());

        assertEchoes(result);
        verifyNoInteractions(writer);
    }

    /**
     * Asserts that the confirmation carries the request's shipping id, addresses and order items, and an
     * {@code orderReceivedStatus} of {@code true}.
     *
     * @param result the confirmation under test
     */
    private void assertEchoes(ShippingOrderConfirmation result) {
        assertThat(result).isNotNull();
        ShippingOrder shippingOrder = result.getShippingOrder();
        assertThat(shippingOrder).isNotNull();
        assertThat(shippingOrder.getShippingId()).isEqualTo("1234");
        assertMulesoftAddress(shippingOrder.getBillingAddress());
        assertMulesoftAddress(shippingOrder.getShippingAddress());
        assertThat(shippingOrder.getOrder()).isNotNull();
        List<OrderItem> items = shippingOrder.getOrder().getOrderItem();
        assertThat(items).hasSize(3);
        assertThat(items).extracting(OrderItem::getMerchantSKU).containsExactly("1234", "6789", "9998");
        assertThat(items).extracting(OrderItem::getQuantity).containsExactly(500, 1500, 5000);
        assertThat(result.isOrderReceivedStatus()).isTrue();
    }

    /**
     * Asserts the seven fields of the {@code Mulesoft} address of {@code message.xml}.
     *
     * @param address the address under test
     */
    private static void assertMulesoftAddress(Address address) {
        assertThat(address).isNotNull();
        assertThat(address.getName()).isEqualTo("Mulesoft");
        assertThat(address.getLine1()).isEqualTo("77 Geary St");
        assertThat(address.getLine2()).isEqualTo("Level 4");
        assertThat(address.getCity()).isEqualTo("San Francisco");
        assertThat(address.getStateOrProvinceCode()).isEqualTo("CA");
        assertThat(address.getCountryCode()).isEqualTo("USA");
        assertThat(address.getPostalCode()).isEqualTo("94108");
    }

    /**
     * Reads {@code original/message.xml} with a namespace-aware parser and unmarshals its
     * {@code {http://ordermgmt.org/}putShippingOrder} element into a {@link PutShippingOrder}.
     *
     * @return the request payload
     * @throws Exception when the resource cannot be read, parsed or unmarshalled
     */
    private static PutShippingOrder readRequest() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document;
        try (InputStream in = FulfillmentServiceTest.class.getClassLoader()
                .getResourceAsStream("original/message.xml")) {
            assertThat(in).as("classpath resource original/message.xml").isNotNull();
            document = factory.newDocumentBuilder().parse(in);
        }
        Element element = (Element) document.getElementsByTagNameNS(ORDER_NAMESPACE_URI, "putShippingOrder").item(0);
        assertThat(element).as("putShippingOrder element").isNotNull();
        return JAXBContext.newInstance(ObjectFactory.class).createUnmarshaller()
                .unmarshal(new DOMSource(element), PutShippingOrder.class).getValue();
    }
}
