package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.client.CurrencyClient;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config.FileEndpointProperties;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.OrderMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.mapper.ReportMapper;
import com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.model.CurrencyRates;

/**
 * Runs the body of flow {@code OrderFlow}
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:7-43] that follows its file
 * source [books.xml:8], which {@code scheduler/OrderFileScheduler} polls (D-036). For one order file
 * it:
 *
 * <ol>
 *   <li>parses the file into a DOM {@link Document} without namespaces, refusing a DOCTYPE
 *       declaration and external entities (D-613);</li>
 *   <li>reads the USD conversion rates with {@link CurrencyClient#getCurrencies()}, a loopback
 *       {@code GET /api/currencies} to this application's own HTTP port: the
 *       {@code flowVars.currencies} enricher [books.xml:9-11] (D-054);</li>
 *   <li>maps the document and the rates to the orders JSON with
 *       {@link OrderMapper#toOrdersJson(Document, CurrencyRates)} (DW-23 [books.xml:14-29]);</li>
 *   <li>maps the same document, every item before any year filter, to the report CSV with
 *       {@link ReportMapper#toReportCsv(Document)} (DW-24 [books.xml:30-37]);</li>
 *   <li>writes the orders JSON to {@code file.outbound.orders-output-pattern} ({@code orders.json})
 *       [books.xml:39], then the report CSV to {@code file.outbound.report-output-pattern}
 *       ({@code report.csv}) [books.xml:41-42], both in {@code file.outbound.path}.</li>
 * </ol>
 *
 * <p>The output directory and file names are read from {@link FileEndpointProperties#outbound()} on
 * every call. The class logs nothing and handles no exception: every failure reaches the caller
 * unchanged, and no output file is written unless both mappers return.
 *
 * <p>Instances hold no mutable state; each call builds its own parser.
 *
 * <p>Example:
 *
 * <pre>{@code
 * orderService.orderFlow(Path.of("src/main/resources/input/orders.xml"));
 * // src/main/resources/output/orders.json and report.csv now hold the DW-23 and DW-24 results
 * }</pre>
 */
@Service
public class OrderService {

    /** JAXP feature that rejects any document carrying a DOCTYPE declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** SAX feature for the inclusion of external general entities. */
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";

    /** SAX feature for the inclusion of external parameter entities and the external DTD subset. */
    private static final String EXTERNAL_PARAMETER_ENTITIES =
            "http://xml.org/sax/features/external-parameter-entities";

    /** Xerces feature for loading an external DTD in a non-validating parse. */
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    /**
     * Error handler of every parser built here: a warning is ignored, and an error or fatal error is
     * rethrown as the given {@link SAXParseException}. Nothing is printed to {@code System.err} (D-613).
     */
    private static final ErrorHandler RETHROWING_ERROR_HANDLER = new ErrorHandler() {

        @Override
        public void warning(SAXParseException exception) {
            // A warning leaves the parse running and is not reported.
        }

        @Override
        public void error(SAXParseException exception) throws SAXException {
            throw exception;
        }

        @Override
        public void fatalError(SAXParseException exception) throws SAXException {
            throw exception;
        }
    };

    /** Loopback client of {@code GET /api/currencies} (D-054). */
    private final CurrencyClient currencyClient;

    /** DW-23: the orders JSON. */
    private final OrderMapper orderMapper;

    /** DW-24: the report CSV. */
    private final ReportMapper reportMapper;

    /** The {@code file.*} settings; only {@link FileEndpointProperties#outbound()} is read here. */
    private final FileEndpointProperties fileEndpointProperties;

    /**
     * Creates the service over its four collaborators. Nothing is read or called here.
     *
     * @param currencyClient         loopback client of {@code GET /api/currencies} (D-054)
     * @param orderMapper            DW-23 mapper of the orders JSON
     * @param reportMapper           DW-24 mapper of the report CSV
     * @param fileEndpointProperties the {@code file.*} settings whose {@code outbound} group names the
     *                               output directory and the two output file names
     * @throws NullPointerException if any argument is {@code null}
     */
    public OrderService(CurrencyClient currencyClient, OrderMapper orderMapper, ReportMapper reportMapper,
            FileEndpointProperties fileEndpointProperties) {
        this.currencyClient = Objects.requireNonNull(currencyClient, "currencyClient");
        this.orderMapper = Objects.requireNonNull(orderMapper, "orderMapper");
        this.reportMapper = Objects.requireNonNull(reportMapper, "reportMapper");
        this.fileEndpointProperties = Objects.requireNonNull(fileEndpointProperties, "fileEndpointProperties");
    }

    /**
     * Processes one order file as {@code OrderFlow} does after its file source [books.xml:9-42]:
     *
     * <ol>
     *   <li>parses {@code inputFile} (D-613);</li>
     *   <li>calls {@link CurrencyClient#getCurrencies()} (D-054);</li>
     *   <li>calls {@link OrderMapper#toOrdersJson(Document, CurrencyRates)} with the parsed document and
     *       the rates (DW-23);</li>
     *   <li>calls {@link ReportMapper#toReportCsv(Document)} with the same document (DW-24);</li>
     *   <li>creates the output directory {@code file.outbound.path} when absent and writes the orders
     *       JSON to {@code file.outbound.orders-output-pattern} [books.xml:39];</li>
     *   <li>creates the output directory when absent and writes the report CSV to
     *       {@code file.outbound.report-output-pattern} [books.xml:41-42] (D-034).</li>
     * </ol>
     *
     * <p>Each output is written as UTF-8 with no byte-order mark and nothing appended, and replaces any
     * existing file of that name, truncating a longer one. Nothing is written unless both mappers
     * return; a failure in steps 1 to 4 leaves the output directory untouched, and a failed orders
     * write leaves the report unwritten. Every exception propagates unchanged: none is caught, wrapped
     * or logged here.
     *
     * @param inputFile the order file to process; it is read and neither moved nor deleted
     * @throws IOException  if {@code inputFile} cannot be read, or the output directory or an output file
     *                      cannot be created or written
     * @throws SAXException if {@code inputFile} is not well-formed XML or carries a DOCTYPE declaration;
     *                      the parser raises it as a {@link SAXParseException} (D-613)
     * @throws NullPointerException if {@code inputFile} is {@code null}, or a mapper raises it (for
     *                      example {@code OrderMapper} for a response without a body)
     * @throws org.springframework.web.client.RestClientException as raised by
     *                      {@link CurrencyClient#getCurrencies()} for a non-2xx status, a connection
     *                      or timeout failure, or an unreadable body
     * @throws IllegalStateException if {@link CurrencyClient#getCurrencies()} finds no running web server
     *                      port, or the JDK parser rejects the parser configuration
     * @throws IllegalArgumentException as raised by {@link ReportMapper#toReportCsv(Document)} when no
     *                      price is read
     */
    public void orderFlow(Path inputFile) throws IOException, SAXException {
        Document document = newDocumentBuilder().parse(inputFile.toFile());
        CurrencyRates rates = currencyClient.getCurrencies();
        String ordersJson = orderMapper.toOrdersJson(document, rates);
        String reportCsv = reportMapper.toReportCsv(document);
        FileEndpointProperties.Outbound outbound = fileEndpointProperties.outbound();
        write(outbound.path(), outbound.ordersOutputPattern(), ordersJson);
        // set-payload #[report] then the report.csv outbound endpoint [books.xml:41-42] (D-034).
        write(outbound.path(), outbound.reportOutputPattern(), reportCsv);
    }

    /**
     * Parses without namespaces; refuses a DOCTYPE declaration and external entities (D-613). Returns a
     * new builder from a new {@link DocumentBuilderFactory#newInstance()} with
     * {@link XMLConstants#FEATURE_SECURE_PROCESSING} on, DOCTYPE declarations disallowed, external
     * general and parameter entities and external DTD loading off, no external DTD or schema access,
     * XInclude off, entity references unexpanded and no validation, and with
     * {@link #RETHROWING_ERROR_HANDLER} as its error handler.
     *
     * @return a builder for one parse
     * @throws IllegalStateException wrapping the {@link ParserConfigurationException} raised when the
     *                               JDK parser does not support a feature set here
     */
    private static DocumentBuilder newDocumentBuilder() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            factory.setNamespaceAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setValidating(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(RETHROWING_ERROR_HANDLER);
            return builder;
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException("XML parser configuration rejected: " + e.getMessage(), e);
        }
    }

    /**
     * Creates {@code directory} and its missing parents, then writes {@code content} to the file
     * {@code fileName} in it as UTF-8, creating the file or truncating an existing one; no byte-order
     * mark and no line feed are added.
     *
     * @param directory the output directory, relative to the working directory unless absolute
     * @param fileName  the output file name
     * @param content   the text to write
     * @throws IOException if the directory cannot be created, or the file cannot be opened or written,
     *                     for example when a directory of that name exists
     */
    private static void write(Path directory, String fileName, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(fileName), content, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }
}
