package com.mulesoft.examples.web_service_consumer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;

import com.mulesoft.examples.web_service_consumer.client.TshirtServiceClient;
import com.mulesoft.examples.web_service_consumer.config.TshirtWsConfig;
import com.mulesoft.examples.web_service_consumer.mapper.TshirtMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mulesoft.tshirt_service.AuthenticationHeader;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.soap.SoapMessage;
import org.springframework.ws.soap.client.SoapFaultClientException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Unit tests of {@link TshirtOrderService} with {@link TshirtServiceClient} mocked and a real {@link TshirtMapper};
 * covers flows {@code orderTshirt} and {@code listInventory} of
 * {@code web-service-consumer/src/main/app/tshirt-service-consumer.xml} (D-049).
 *
 * <ul>
 *   <li>{@code orderTshirt}: the DW-35 payload and the DW-36 header reach one client call, and the DW-37 JSON of the
 *       client's response element is returned;</li>
 *   <li>{@code listInventory}: one client call, and the DW-38 JSON of the client's response element is returned;</li>
 *   <li>a SOAP fault and an I/O failure of the client propagate unchanged after one call; a {@code null} order body
 *       fails before any client call.</li>
 * </ul>
 *
 * <p>The stub responses hold the values of the Studio {@code sample_data} previews (D-037). The test imports no
 * JAX-WS, CXF or Mule type (D-050).
 */
@ExtendWith(MockitoExtension.class)
class TshirtOrderServiceTest {

    /** Target namespace of the t-shirt service. */
    private static final String NS = "http://mulesoft.org/tshirt-service";

    /** {@code OrderTshirtResponse} stub (D-037). */
    private static final String ORDER_STUB_XML = """
            <ns2:OrderTshirtResponse xmlns:ns2="http://mulesoft.org/tshirt-service"><orderId>1</orderId></ns2:OrderTshirtResponse>""";

    /** {@code ListInventoryResponse} stub with five items (D-037). */
    private static final String INVENTORY_STUB_XML = """
            <ns2:ListInventoryResponse xmlns:ns2="http://mulesoft.org/tshirt-service">
              <inventory><productCode>4102</productCode><size>L</size><description>Prueba</description><count>2</count></inventory>
              <inventory><productCode>1412</productCode><size>L</size><description>Foo</description><count>9</count></inventory>
              <inventory><productCode>5656</productCode><size>S</size><description>Bar</description><count>2</count></inventory>
              <inventory><productCode>5657</productCode><size>M</size><description>Prueba2</description><count>3</count></inventory>
              <inventory><productCode>1411</productCode><size>M</size><description>Awesome Tshirt</description><count>5</count></inventory>
            </ns2:ListInventoryResponse>""";

    /** DW-37 output for {@link #ORDER_STUB_XML}. */
    private static final String EXPECTED_ORDER_JSON = """
            {
              "OrderTshirtResponse": {
                "orderId": "1"
              }
            }""";

    /** DW-38 output for {@link #INVENTORY_STUB_XML}. */
    private static final String EXPECTED_INVENTORY_JSON = """
            {
              "inventory": {
                "productCode": "4102",
                "size": "L",
                "description": "Prueba",
                "count": "2"
              },
              "inventory": {
                "productCode": "1412",
                "size": "L",
                "description": "Foo",
                "count": "9"
              },
              "inventory": {
                "productCode": "5656",
                "size": "S",
                "description": "Bar",
                "count": "2"
              },
              "inventory": {
                "productCode": "5657",
                "size": "M",
                "description": "Prueba2",
                "count": "3"
              },
              "inventory": {
                "productCode": "1411",
                "size": "M",
                "description": "Awesome Tshirt",
                "count": "5"
              }
            }""";

    @Mock
    TshirtServiceClient client;

    @Captor
    ArgumentCaptor<Source> sourceCaptor;

    @Captor
    ArgumentCaptor<AuthenticationHeader> headerCaptor;

    TshirtMapper mapper;

    TshirtOrderService service;

    @BeforeEach
    void setUp() {
        mapper = new TshirtMapper();
        TshirtWsConfig.Properties properties = new TshirtWsConfig.Properties("TshirtService", "TshirtServicePort",
                "http://tshirt-service.cloudhub.io", "k", 10000, 10000);
        service = new TshirtOrderService(client, mapper, properties);
    }

    @Test
    void orderTshirtSendsMappedPayloadWithApiKeyHeaderAndReturnsOrderJson() throws Exception {
        Element orderStub = element(ORDER_STUB_XML);
        when(client.orderTshirt(any(Source.class), any(AuthenticationHeader.class))).thenReturn(orderStub);

        String json = readResource("/original/message.json");
        String result = service.orderTshirt(json);

        verify(client, times(1)).orderTshirt(sourceCaptor.capture(), headerCaptor.capture());
        verifyNoMoreInteractions(client);

        Element root = toElement(sourceCaptor.getValue());
        assertThat(root.getNamespaceURI()).isEqualTo(NS);
        assertThat(root.getLocalName()).isEqualTo("OrderTshirt");
        List<Element> children = childElements(root);
        assertThat(children).hasSize(9);
        assertThat(children).extracting(Element::getLocalName).containsExactly(
                "email", "address1", "address2", "city", "country", "name", "postalCode", "size", "stateOrProvince");
        assertThat(children).extracting(Element::getNamespaceURI).containsOnlyNulls();
        assertThat(children).extracting(Element::getTextContent).containsExactly(
                "customerID@gmail.com", "Corrientes 316", "EP", "Buenos Aires", "Argentina", "MuleSoft Argentina",
                "C1043AAQ", "L", "CABA");

        assertThat(headerCaptor.getValue().getApiKey()).isEqualTo("k");

        assertThat(result).isEqualTo(mapper.toOrderJson(orderStub)).isEqualTo(EXPECTED_ORDER_JSON);
    }

    @Test
    void listInventoryReturnsInventoryJson() throws Exception {
        when(client.listInventory()).thenReturn(element(INVENTORY_STUB_XML));

        assertThat(service.listInventory()).isEqualTo(EXPECTED_INVENTORY_JSON);

        verify(client, times(1)).listInventory();
        verifyNoMoreInteractions(client);
    }

    @Test
    void orderTshirtPropagatesSoapFaultAfterOneCall() throws Exception {
        SoapMessage faultMessage = mock(SoapMessage.class);
        lenient().when(faultMessage.getFaultReason()).thenReturn("Invalid API key");
        lenient().when(faultMessage.getSoapBody()).thenReturn(null);
        SoapFaultClientException ex = new SoapFaultClientException(faultMessage);
        when(client.orderTshirt(any(), any())).thenThrow(ex);

        String json = readResource("/original/message.json");
        assertThatThrownBy(() -> service.orderTshirt(json)).isSameAs(ex);

        verify(client, times(1)).orderTshirt(any(), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void orderTshirtPropagatesWebServiceIoExceptionAfterOneCall() throws Exception {
        WebServiceIOException ex = new WebServiceIOException("connection refused");
        when(client.orderTshirt(any(), any())).thenThrow(ex);

        String json = readResource("/original/message.json");
        assertThatThrownBy(() -> service.orderTshirt(json)).isSameAs(ex);

        verify(client, times(1)).orderTshirt(any(), any());
        verifyNoMoreInteractions(client);
    }

    @Test
    void listInventoryPropagatesWebServiceIoExceptionAfterOneCall() throws Exception {
        WebServiceIOException ex = new WebServiceIOException("connection refused");
        when(client.listInventory()).thenThrow(ex);

        assertThatThrownBy(() -> service.listInventory()).isSameAs(ex);

        verify(client, times(1)).listInventory();
        verifyNoMoreInteractions(client);
    }

    @Test
    void orderTshirtWithNullInputThrowsWithoutCallingClient() throws Exception {
        assertThatThrownBy(() -> service.orderTshirt(null)).isInstanceOf(Exception.class);

        verifyNoInteractions(client);
    }

    /** Parses {@code xml}, with the whitespace between tags removed, into a namespace-aware DOM element. */
    private static Element element(String xml) throws Exception {
        String compact = xml.replaceAll(">\\s+<", "><");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        Document document = factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(compact.getBytes(StandardCharsets.UTF_8)));
        return document.getDocumentElement();
    }

    /** Reads a classpath resource as UTF-8 text. */
    private static String readResource(String path) throws Exception {
        try (InputStream in = Objects.requireNonNull(
                TshirtOrderServiceTest.class.getResourceAsStream(path), path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Copies {@code source} into a new DOM tree with an identity transform and returns its root element. */
    private static Element toElement(Source source) throws Exception {
        DOMResult domResult = new DOMResult();
        TransformerFactory.newInstance().newTransformer().transform(source, domResult);
        Node node = domResult.getNode();
        if (node instanceof Element element) {
            return element;
        }
        return ((Document) node).getDocumentElement();
    }

    /** Returns the direct child elements of {@code parent}, in document order. */
    private static List<Element> childElements(Element parent) {
        List<Element> elements = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE) {
                elements.add((Element) node);
            }
        }
        return elements;
    }
}
