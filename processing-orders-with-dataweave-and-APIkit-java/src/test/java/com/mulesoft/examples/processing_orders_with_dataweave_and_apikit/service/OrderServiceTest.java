package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client.CurrencyClient;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config.FileEndpointProperties;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.ReportMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.model.CurrencyRates;

/**
 * Unit tests of {@link OrderService#orderFlow(Path)}, the body of {@code OrderFlow} after its file source
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:9-42]: the currency enricher
 * (:9-11), DW-23 (:14-29), DW-24 (:30-37), the {@code orders.json} write (:39), {@code set-payload
 * #[report]} (:41) and the {@code report.csv} write (:42).
 *
 * <p>Each test constructs the service directly, with no Spring application context, over a Mockito mock
 * of the loopback {@link CurrencyClient} (D-054) and mocks of {@link OrderMapper} and {@link ReportMapper},
 * stubbed inside the test. The input file and the output directory live under a JUnit temporary
 * directory. The tests assert
 * <ul>
 *   <li>the call order client, DW-23 mapper, DW-24 mapper, and the UTF-8 bytes of both outputs;</li>
 *   <li>the one parsed input document reaching both mappers;</li>
 *   <li>an {@link IOException} from either write, with the report unwritten after a failed orders write
 *       and the orders file kept after a failed report write;</li>
 *   <li>custom output file names, a missing nested output directory and the truncation of longer
 *       existing outputs;</li>
 *   <li>the unchanged propagation of a client or mapper exception, with no output written;</li>
 *   <li>the {@link SAXParseException} for an input with a DOCTYPE declaration, before any collaborator
 *       call (D-613).</li>
 * </ul>
 * These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at least
 * 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
@ExtendWith(MockitoExtension.class)
public class OrderServiceTest {

    /** Orders JSON returned by the stubbed DW-23 mapper; holds a non-ASCII character. */
    private static final String ORDERS = "ORDERS \u20ac";

    /** Report CSV returned by the stubbed DW-24 mapper; holds a non-ASCII character. */
    private static final String REPORT = "REPORT \u00e9";

    /** Default output file name of the orders JSON. */
    private static final String ORDERS_JSON = "orders.json";

    /** Default output file name of the report CSV. */
    private static final String REPORT_CSV = "report.csv";

    /** Number of {@code item} elements in the committed {@code input/orders.xml}. */
    private static final int COMMITTED_ITEMS = 4;

    /** Mock of the loopback currency client (D-054). */
    @Mock
    private CurrencyClient client;

    /** Mock of the DW-23 mapper. */
    @Mock
    private OrderMapper orderMapper;

    /** Mock of the DW-24 mapper. */
    @Mock
    private ReportMapper reportMapper;

    /** Temporary root of the input and output directories of one test. */
    @TempDir
    Path tempDir;

    /** Input directory, created before each test. */
    private Path inDir;

    /** Default output directory, absent unless a test creates it. */
    private Path outDir;

    /**
     * Creates the input directory and sets the default output directory path.
     *
     * @throws IOException when the input directory cannot be created
     */
    @BeforeEach
    public void setUp() throws IOException {
        inDir = Files.createDirectories(tempDir.resolve("in"));
        outDir = tempDir.resolve("out");
    }

    /**
     * Asserts that {@code orderFlow} calls {@code getCurrencies()}, then {@code toOrdersJson} with the rates,
     * then {@code toReportCsv}, and nothing else, and writes {@code orders.json} and {@code report.csv}
     * holding the UTF-8 bytes of the two mapper results.
     *
     * @throws Exception when the input cannot be copied or {@code orderFlow} fails
     */
    @Test
    public void callsClientThenMappersInOrderAndWritesTheirOutputAsUtf8() throws Exception {
        CurrencyRates rates = rates();
        when(client.getCurrencies()).thenReturn(rates);
        when(orderMapper.toOrdersJson(any(Document.class), eq(rates))).thenReturn(ORDERS);
        when(reportMapper.toReportCsv(any(Document.class))).thenReturn(REPORT);

        service(outDir, ORDERS_JSON, REPORT_CSV).orderFlow(copyOrdersXml());

        InOrder inOrder = inOrder(client, orderMapper, reportMapper);
        inOrder.verify(client).getCurrencies();
        inOrder.verify(orderMapper).toOrdersJson(any(Document.class), eq(rates));
        inOrder.verify(reportMapper).toReportCsv(any(Document.class));
        inOrder.verifyNoMoreInteractions();
        assertArrayEquals(ORDERS.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outDir.resolve(ORDERS_JSON)),
                "orders.json holds the DW-23 result as UTF-8");
        assertArrayEquals(REPORT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outDir.resolve(REPORT_CSV)),
                "report.csv holds the DW-24 result as UTF-8");
    }

    /**
     * Asserts that both mappers receive the same parsed document of the committed {@code orders.xml}: a
     * document element named {@code orders} with four {@code item} elements.
     *
     * @throws Exception when the input cannot be copied or {@code orderFlow} fails
     */
    @Test
    public void passesTheParsedInputDocumentToBothMappers() throws Exception {
        CurrencyRates rates = rates();
        when(client.getCurrencies()).thenReturn(rates);
        when(orderMapper.toOrdersJson(any(Document.class), eq(rates))).thenReturn(ORDERS);
        when(reportMapper.toReportCsv(any(Document.class))).thenReturn(REPORT);

        service(outDir, ORDERS_JSON, REPORT_CSV).orderFlow(copyOrdersXml());

        ArgumentCaptor<Document> ordersDocument = ArgumentCaptor.forClass(Document.class);
        ArgumentCaptor<Document> reportDocument = ArgumentCaptor.forClass(Document.class);
        verify(orderMapper).toOrdersJson(ordersDocument.capture(), eq(rates));
        verify(reportMapper).toReportCsv(reportDocument.capture());
        assertSame(ordersDocument.getValue(), reportDocument.getValue(), "one document reaches both mappers");
        for (Document document : List.of(ordersDocument.getValue(), reportDocument.getValue())) {
            Element root = document.getDocumentElement();
            String name = root.getLocalName() != null ? root.getLocalName() : root.getNodeName();
            assertEquals("orders", name, "document element name");
            assertEquals(COMMITTED_ITEMS, document.getElementsByTagName("item").getLength(), "item count");
        }
    }

    /**
     * Asserts that a directory in place of {@code orders.json} makes {@code orderFlow} throw
     * {@link IOException} and leaves {@code report.csv} unwritten.
     *
     * @throws Exception when the fixture cannot be prepared
     */
    @Test
    public void ordersWriteFailureThrowsAndLeavesNoReportFile() throws Exception {
        Files.createDirectories(outDir.resolve(ORDERS_JSON));
        stubCollaborators();
        OrderService service = service(outDir, ORDERS_JSON, REPORT_CSV);
        Path input = copyOrdersXml();

        assertThrows(IOException.class, () -> service.orderFlow(input));

        assertTrue(Files.notExists(outDir.resolve(REPORT_CSV)), "report.csv is not written");
    }

    /**
     * Asserts that a directory in place of {@code report.csv} makes {@code orderFlow} throw
     * {@link IOException} after {@code orders.json} is written with the DW-23 result.
     *
     * @throws Exception when the fixture cannot be prepared or the orders file cannot be read
     */
    @Test
    public void reportWriteFailureThrowsAndKeepsOrdersFile() throws Exception {
        Files.createDirectories(outDir.resolve(REPORT_CSV));
        stubCollaborators();
        OrderService service = service(outDir, ORDERS_JSON, REPORT_CSV);
        Path input = copyOrdersXml();

        assertThrows(IOException.class, () -> service.orderFlow(input));

        Path orders = outDir.resolve(ORDERS_JSON);
        assertTrue(Files.isRegularFile(orders), "orders.json is a regular file");
        assertArrayEquals(ORDERS.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(orders),
                "orders.json holds the DW-23 result as UTF-8");
    }

    /**
     * Asserts that the output file names come from the outbound settings: {@code o.json} and {@code r.csv}
     * hold the mapper results, and no {@code orders.json} or {@code report.csv} is written.
     *
     * @throws Exception when the input cannot be copied or {@code orderFlow} fails
     */
    @Test
    public void writesCustomOutputFileNames() throws Exception {
        stubCollaborators();

        service(outDir, "o.json", "r.csv").orderFlow(copyOrdersXml());

        assertArrayEquals(ORDERS.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outDir.resolve("o.json")),
                "o.json holds the DW-23 result");
        assertArrayEquals(REPORT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outDir.resolve("r.csv")),
                "r.csv holds the DW-24 result");
        assertTrue(Files.notExists(outDir.resolve(ORDERS_JSON)), "orders.json is not written");
        assertTrue(Files.notExists(outDir.resolve(REPORT_CSV)), "report.csv is not written");
    }

    /**
     * Asserts that an absent nested output directory {@code out/a/b} is created and receives both files.
     *
     * @throws Exception when the input cannot be copied or {@code orderFlow} fails
     */
    @Test
    public void createsMissingNestedOutputDirectory() throws Exception {
        Path nested = tempDir.resolve("out/a/b");
        stubCollaborators();

        service(nested, ORDERS_JSON, REPORT_CSV).orderFlow(copyOrdersXml());

        assertArrayEquals(ORDERS.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(nested.resolve(ORDERS_JSON)),
                "orders.json in the created directory");
        assertArrayEquals(REPORT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(nested.resolve(REPORT_CSV)),
                "report.csv in the created directory");
    }

    /**
     * Asserts that existing output files of 1000 characters are replaced by exactly the new mapper results,
     * with no remaining tail.
     *
     * @throws Exception when the fixture cannot be prepared or {@code orderFlow} fails
     */
    @Test
    public void truncatesExistingLongerOutputFiles() throws Exception {
        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve(ORDERS_JSON), "x".repeat(1000), StandardCharsets.UTF_8);
        Files.writeString(outDir.resolve(REPORT_CSV), "x".repeat(1000), StandardCharsets.UTF_8);
        stubCollaborators();

        service(outDir, ORDERS_JSON, REPORT_CSV).orderFlow(copyOrdersXml());

        assertArrayEquals(ORDERS.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outDir.resolve(ORDERS_JSON)),
                "orders.json holds only the new DW-23 result");
        assertArrayEquals(REPORT.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(outDir.resolve(REPORT_CSV)),
                "report.csv holds only the new DW-24 result");
    }

    /**
     * Asserts that an exception of the loopback client (D-054) propagates as the same instance, that no
     * mapper is called and that no output file is written.
     *
     * @throws Exception when the input cannot be copied
     */
    @Test
    public void clientExceptionPropagatesAndNoOutputIsWritten() throws Exception {
        HttpServerErrorException failure = new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR);
        when(client.getCurrencies()).thenThrow(failure);
        OrderService service = service(outDir, ORDERS_JSON, REPORT_CSV);
        Path input = copyOrdersXml();

        assertSame(failure, assertThrows(HttpServerErrorException.class, () -> service.orderFlow(input)),
                "the client exception propagates unchanged");

        verifyNoInteractions(orderMapper, reportMapper);
        assertTrue(Files.notExists(outDir.resolve(ORDERS_JSON)), "orders.json is not written");
        assertTrue(Files.notExists(outDir.resolve(REPORT_CSV)), "report.csv is not written");
    }

    /**
     * Asserts that an exception of the DW-24 mapper propagates as the same instance and that neither output
     * file is written, the DW-23 result included.
     *
     * @throws Exception when the input cannot be copied
     */
    @Test
    public void mapperExceptionPropagatesAndNoOutputIsWritten() throws Exception {
        CurrencyRates rates = rates();
        IllegalArgumentException failure = new IllegalArgumentException("report");
        when(client.getCurrencies()).thenReturn(rates);
        when(orderMapper.toOrdersJson(any(Document.class), eq(rates))).thenReturn(ORDERS);
        when(reportMapper.toReportCsv(any(Document.class))).thenThrow(failure);
        OrderService service = service(outDir, ORDERS_JSON, REPORT_CSV);
        Path input = copyOrdersXml();

        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> service.orderFlow(input)),
                "the mapper exception propagates unchanged");

        assertTrue(Files.notExists(outDir.resolve(ORDERS_JSON)), "orders.json is not written");
        assertTrue(Files.notExists(outDir.resolve(REPORT_CSV)), "report.csv is not written");
    }

    // DOCTYPE refused by the secure parser before any collaborator call (D-613).
    /**
     * Asserts that an input with a DOCTYPE declaration and an internal entity raises
     * {@link SAXParseException}, with no call to the client or the mappers and no output file.
     *
     * @throws Exception when the input file cannot be written
     */
    @Test
    public void doctypeInputIsRefusedBeforeAnyCollaboratorCall() throws Exception {
        Path input = inDir.resolve("orders.xml");
        Files.writeString(input, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<!DOCTYPE orders [<!ENTITY x \"y\">]>\n<orders>&x;</orders>", StandardCharsets.UTF_8);
        OrderService service = service(outDir, ORDERS_JSON, REPORT_CSV);

        assertThrows(SAXParseException.class, () -> service.orderFlow(input));

        verifyNoInteractions(client, orderMapper, reportMapper);
        assertTrue(Files.notExists(outDir.resolve(ORDERS_JSON)), "orders.json is not written");
        assertTrue(Files.notExists(outDir.resolve(REPORT_CSV)), "report.csv is not written");
    }

    /**
     * Returns a service over the three mocks and outbound settings of the given directory and file names,
     * with {@link #inDir} as inbound directory.
     */
    private OrderService service(Path outPath, String ordersName, String reportName) {
        return new OrderService(client, orderMapper, reportMapper, new FileEndpointProperties(
                new FileEndpointProperties.Inbound(inDir, Duration.ofMillis(1000)),
                new FileEndpointProperties.Outbound(outPath, ordersName, reportName)));
    }

    /** Copies {@code classpath:input/orders.xml} to {@code inDir/orders.xml} and returns that path. */
    private Path copyOrdersXml() throws IOException {
        Path target = inDir.resolve("orders.xml");
        try (InputStream in = OrderServiceTest.class.getResourceAsStream("/input/orders.xml")) {
            assertTrue(in != null, "classpath resource input/orders.xml");
            Files.copy(in, target);
        }
        return target;
    }

    /** Returns the USD rates of the committed {@code currency.json}: EUR 0.92, ARS 8.76, GBP 0.66. */
    private static CurrencyRates rates() {
        return new CurrencyRates(List.of(
                new CurrencyRates.Rate("EUR", new BigDecimal("0.92")),
                new CurrencyRates.Rate("ARS", new BigDecimal("8.76")),
                new CurrencyRates.Rate("GBP", new BigDecimal("0.66"))));
    }

    /** Stubs the client with {@link #rates()} and the mappers with {@link #ORDERS} and {@link #REPORT}. */
    private void stubCollaborators() {
        CurrencyRates rates = rates();
        when(client.getCurrencies()).thenReturn(rates);
        when(orderMapper.toOrdersJson(any(Document.class), eq(rates))).thenReturn(ORDERS);
        when(reportMapper.toReportCsv(any(Document.class))).thenReturn(REPORT);
    }
}
