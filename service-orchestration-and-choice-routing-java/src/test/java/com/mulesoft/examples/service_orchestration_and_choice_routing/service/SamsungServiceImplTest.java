package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import javax.xml.transform.dom.DOMResult;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mulesoft.se.samsung.ObjectFactory;
import com.mulesoft.se.samsung.OrderRequest;
import com.mulesoft.se.samsung.OrderResponse;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Unit tests of {@link SamsungServiceImpl}: flow {@code samsungService}
 * ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:150-155}), whose
 * logger [fulfillment.xml:152] logs {@code aaaa} at INFO, and the purchase logic of the original
 * class {@code com.mulesoft.se.samsung.SamsungServiceImpl}, which prices 2550 per unit.
 *
 * <p>Each test uses a service constructed with {@code new SamsungServiceImpl()}, with no Spring
 * application context and no mocks (D-494). A started {@link ListAppender} is attached to the
 * {@code SamsungServiceImpl} logger, whose level is {@link Level#ALL} during each test and restored
 * afterwards. {@link OrderRequest}, {@link OrderResponse} and {@link ObjectFactory} are the JAXB
 * types generated from {@code wsdl/samsung.wsdl} into {@code com.mulesoft.se.samsung} (D-028). The
 * tests assert
 * <ul>
 *   <li>{@code purchase} answers id {@code "1"}, result {@code "ACCEPTED"} and price
 *       {@code 2550 * quantity} as decimal text of the {@code int} product, and logs nothing;</li>
 *   <li>{@code samsungService} logs exactly one event, {@code aaaa} at INFO, and answers as
 *       {@code purchase} does;</li>
 *   <li>the marshalled {@code orderResponse} element holds only {@code id}, {@code result} and
 *       {@code price}, and no part of the request name.</li>
 * </ul>
 * The four test methods are public and the lifecycle methods package-private (D-494). The tests
 * cover every line of {@link SamsungServiceImpl} (D-049).
 */
public class SamsungServiceImplTest {

    /** Target namespace of {@code samsung.wsdl} and of its generated types. */
    private static final String SAMSUNG_NAMESPACE = "http://samsung.se.mulesoft.com/";

    /** Product name of the requests, the Samsung item name of the original test message. */
    private static final String PRODUCT_NAME = "s-1";

    /** Message of the logger of flow {@code samsungService}, fulfillment.xml:152. */
    private static final String FLOW_LOG_MESSAGE = "aaaa";

    /** Id of every purchase response, original SamsungServiceImpl.java:19. */
    private static final String ORDER_ID = "1";

    /** Result of every purchase response, original SamsungServiceImpl.java:20. */
    private static final String ACCEPTED = "ACCEPTED";

    /** The unit under test, constructed directly. */
    private final SamsungServiceImpl service = new SamsungServiceImpl();

    /** Appender that records the events of the {@code SamsungServiceImpl} logger. */
    private ListAppender<ILoggingEvent> appender;

    /** The {@code SamsungServiceImpl} logger. */
    private Logger serviceLogger;

    /** Level of {@link #serviceLogger} before the test. */
    private Level previousLevel;

    /** Attaches a started list appender to the service logger and sets its level to {@link Level#ALL}. */
    @BeforeEach
    void setUp() {
        serviceLogger = (Logger) LoggerFactory.getLogger(SamsungServiceImpl.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.ALL);

        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    /** Detaches and stops the list appender and restores the logger's previous level. */
    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    /**
     * {@code purchase} of quantity 1 returns id {@code "1"}, result {@code "ACCEPTED"} and price
     * {@code "2550"}, and records no log event.
     */
    @Test
    @DisplayName("purchase of one unit returns id 1, result ACCEPTED and price 2550")
    public void purchaseReturnsAcceptedWithPriceForQuantityOne() {
        OrderResponse response = service.purchase(request(1));

        assertAccepted(response, "2550");
        assertThat(appender.list).isEmpty();
    }

    /**
     * {@code purchase} prices 2550 per unit as the decimal text of the {@code int} product, with id
     * {@code "1"} and result {@code "ACCEPTED"}: quantity 3 gives {@code "7650"}, quantity 0 gives
     * {@code "0"}, quantity 842150 gives {@code "2147482500"}, and quantity 842151, whose product
     * exceeds {@link Integer#MAX_VALUE}, gives the wrapped {@code "-2147482246"}.
     */
    @Test
    @DisplayName("purchase multiplies the unit price 2550 by the quantity")
    public void purchaseMultipliesPriceByQuantity() {
        assertAccepted(service.purchase(request(3)), "7650");
        assertAccepted(service.purchase(request(0)), "0");
        assertAccepted(service.purchase(request(842150)), "2147482500");
        assertAccepted(service.purchase(request(842151)), "-2147482246");
        assertThat(appender.list).isEmpty();
    }

    /**
     * {@code samsungService} of quantity 3 records exactly one log event, {@code aaaa} at INFO on
     * the {@code SamsungServiceImpl} logger, and returns a response equal, field by field, to the one
     * {@code purchase} returns for the same request: id {@code "1"}, result {@code "ACCEPTED"} and
     * price {@code "7650"}.
     */
    @Test
    @DisplayName("samsungService logs aaaa at INFO and answers as purchase does")
    public void samsungServiceLogsAaaaAndDelegatesToPurchase() {
        OrderRequest request = request(3);

        OrderResponse response = service.samsungService(request);

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.INFO);
        assertThat(event.getFormattedMessage()).isEqualTo(FLOW_LOG_MESSAGE);
        assertThat(event.getLoggerName()).isEqualTo(SamsungServiceImpl.class.getName());

        OrderResponse expected = service.purchase(request);
        assertThat(response).usingRecursiveComparison().isEqualTo(expected);
        assertAccepted(response, "7650");
        assertThat(appender.list).hasSize(1);
    }

    /**
     * The response of {@code purchase}, marshalled as the {@code orderResponse} element of
     * {@code samsung.wsdl}, holds exactly the unqualified children {@code id}, {@code result} and
     * {@code price}, in that order, with the texts {@code 1}, {@code ACCEPTED} and {@code 2550}; the
     * request name {@code s-1} appears nowhere in it.
     *
     * @throws JAXBException when the JAXB context cannot be created or the response cannot be marshalled
     */
    @Test
    @DisplayName("purchase response carries only id, result and price, without the request name")
    public void purchaseKeepsRequestNameOutOfResponse() throws JAXBException {
        OrderResponse response = service.purchase(request(1));

        Marshaller marshaller = JAXBContext.newInstance(ObjectFactory.class).createMarshaller();
        DOMResult result = new DOMResult();
        marshaller.marshal(new ObjectFactory().createOrderResponse(response), result);
        Element root = ((Document) result.getNode()).getDocumentElement();

        assertThat(root.getNamespaceURI()).isEqualTo(SAMSUNG_NAMESPACE);
        assertThat(root.getLocalName()).isEqualTo("orderResponse");
        assertThat(childElements(root)).containsExactly("id=1", "result=ACCEPTED", "price=2550");
        assertThat(root.getTextContent()).doesNotContain(PRODUCT_NAME);
    }

    /**
     * Creates an order request for {@link #PRODUCT_NAME} with the given quantity.
     *
     * @param quantity the ordered quantity
     * @return a new order request
     */
    private static OrderRequest request(int quantity) {
        OrderRequest request = new OrderRequest();
        request.setName(PRODUCT_NAME);
        request.setQuantity(quantity);
        return request;
    }

    /**
     * Asserts that {@code response} holds id {@code "1"}, result {@code "ACCEPTED"} and
     * {@code price}.
     *
     * @param response the purchase response
     * @param price    the expected price text
     */
    private static void assertAccepted(OrderResponse response, String price) {
        assertThat(response).isNotNull();
        assertThat(response.getId()).isEqualTo(ORDER_ID);
        assertThat(response.getResult()).isEqualTo(ACCEPTED);
        assertThat(response.getPrice()).isEqualTo(price);
    }

    /**
     * Lists the element children of {@code parent} in document order as {@code localName=text}, each
     * prefixed with {@code {namespace}} when the child is namespace-qualified.
     *
     * @param parent the parent element
     * @return the children as {@code [{namespace}]localName=text} strings
     */
    private static List<String> childElements(Element parent) {
        List<String> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                String namespace = child.getNamespaceURI() == null ? "" : "{" + child.getNamespaceURI() + "}";
                children.add(namespace + child.getLocalName() + "=" + child.getTextContent());
            }
        }
        return children;
    }
}
