package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.service;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client.WorkdayRevenueClient;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerRequestMapper;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.mapper.PutCustomerResponseMapper;
import com.workday.bsvc.PutCustomerRequestType;
import com.workday.bsvc.PutCustomerResponseType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * The flow {@code add-customer-flow} [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:7-54]
 * as one synchronous method, {@link #addCustomerFlow(byte[])}, run in the caller's thread.
 *
 * <table>
 *   <caption>Flow steps and their Java counterparts</caption>
 *   <tr><th>Source line</th><th>Element</th><th>Java step</th></tr>
 *   <tr><td>:9</td><td>{@code mulexml:xml-to-dom-transformer}</td>
 *       <td>{@link #parse(byte[])}, a hardened JAXP DOM parse (D-580)</td></tr>
 *   <tr><td>:12-39</td><td>DW-01 {@code ns0#Put_Customer_Request}</td>
 *       <td>{@link PutCustomerRequestMapper#toPutCustomerRequest(Document)}</td></tr>
 *   <tr><td>:42</td><td>{@code wd-connector:invoke type="Revenue_Management||Put_Customer"}</td>
 *       <td>{@link WorkdayRevenueClient#putCustomer(PutCustomerRequestType)}</td></tr>
 *   <tr><td>:44-48</td><td>DW-02 {@code Customer_Reference.@Descriptor}</td>
 *       <td>{@link PutCustomerResponseMapper#toDescriptor(PutCustomerResponseType)}</td></tr>
 *   <tr><td>:52</td><td>{@code set-payload value="Added customer: #[payload]"}</td>
 *       <td>the return value; a {@code null} descriptor is written {@code {NullPayload}} (D-578); no log
 *       line is written (D-579)</td></tr>
 * </table>
 *
 * <p>This class catches no exception thrown by the mappers or the client: the {@code Upstream*Exception}
 * types of D-020, SOAP faults and parse failures reach the caller unchanged, and the HTTP layer answers
 * them (D-020, D-413). The flow declares no exception strategy. The class holds no HTTP type.
 *
 * <p>Usage:
 * <pre>{@code
 * AddCustomerService service = new AddCustomerService(
 *         new PutCustomerRequestMapper(), workdayRevenueClient, new PutCustomerResponseMapper());
 * String reply = service.addCustomerFlow(
 *         "<root><Account><CustomerName>John Doe</CustomerName></Account></root>".getBytes(UTF_8));
 * // reply is "Added customer: John Doe" when Workday answers with that descriptor,
 * // "Added customer: {NullPayload}" when the answer carries no descriptor
 * }</pre>
 *
 * <p>Instances are thread-safe (D-580).
 */
@Service
public class AddCustomerService {

    /** Literal text of the {@code :52} reply before {@code #[payload]}. */
    static final String REPLY_PREFIX = "Added customer: ";

    /** Text written for a {@code null} DW-02 descriptor in the {@code :52} reply (D-578). */
    static final String NULL_PAYLOAD = "{NullPayload}";

    /** Message of the {@link IllegalArgumentException} thrown for a {@code null} or empty body. */
    static final String EMPTY_BODY_MESSAGE = "Request body is empty";

    /** Xerces feature that rejects any DOCTYPE declaration, set to {@code true} (D-580). */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** SAX feature for external general entities, set to {@code false} (D-580). */
    private static final String EXTERNAL_GENERAL_ENTITIES = "http://xml.org/sax/features/external-general-entities";

    /** SAX feature for external parameter entities, set to {@code false} (D-580). */
    private static final String EXTERNAL_PARAMETER_ENTITIES = "http://xml.org/sax/features/external-parameter-entities";

    /** Xerces feature for loading an external DTD without validation, set to {@code false} (D-580). */
    private static final String LOAD_EXTERNAL_DTD = "http://apache.org/xml/features/nonvalidating/load-external-dtd";

    /** DW-01 [add_a_new_customer.xml:12-39]. */
    private final PutCustomerRequestMapper requestMapper;

    /** {@code Put_Customer} [add_a_new_customer.xml:42]. */
    private final WorkdayRevenueClient workdayRevenueClient;

    /** DW-02 [add_a_new_customer.xml:44-48]. */
    private final PutCustomerResponseMapper responseMapper;

    /** Hardened factory of {@link #parse(byte[])}, built once per instance (D-580). */
    private final DocumentBuilderFactory documentBuilderFactory;

    /**
     * Creates the service over its three collaborators and builds the hardened parser factory of
     * {@link #parse(byte[])} (D-580).
     *
     * @param requestMapper        DW-01, the {@code Put_Customer_Request} builder
     * @param workdayRevenueClient the {@code Put_Customer} operation of Workday Revenue Management
     * @param responseMapper       DW-02, the {@code Customer_Reference} descriptor reader
     * @throws IllegalStateException if the JAXP implementation rejects a hardening feature
     */
    public AddCustomerService(PutCustomerRequestMapper requestMapper,
                              WorkdayRevenueClient workdayRevenueClient,
                              PutCustomerResponseMapper responseMapper) {
        this.requestMapper = requestMapper;
        this.workdayRevenueClient = workdayRevenueClient;
        this.responseMapper = responseMapper;
        this.documentBuilderFactory = newHardenedFactory();
    }

    /**
     * Runs {@code add-customer-flow} [add_a_new_customer.xml:7-54] on one request body and returns the
     * reply text of {@code :52}.
     *
     * <ol>
     *   <li>{@code :9} parses the body into a DOM document ({@link #parse(byte[])}, D-580);</li>
     *   <li>{@code :12-39} builds the {@code Put_Customer_Request} (DW-01);</li>
     *   <li>{@code :42} sends it to Workday through {@code Put_Customer}, once;</li>
     *   <li>{@code :44-48} reads {@code Customer_Reference/@Descriptor} from the answer (DW-02);</li>
     *   <li>{@code :52} returns {@code "Added customer: "} followed by the descriptor, or by
     *       {@code {NullPayload}} when the descriptor is {@code null} (D-578). No log line is written
     *       (D-579).</li>
     * </ol>
     *
     * <p>Example: a {@code Put_Customer_Response} whose {@code Customer_Reference} has the descriptor
     * {@code John Doe} returns {@code Added customer: John Doe}.
     *
     * @param requestBody the raw HTTP request body, the XML {@code root/Account} document of
     *                    {@code root.xsd}
     * @return the reply text, never {@code null}
     * @throws IllegalArgumentException if the body is {@code null}, empty, not well-formed XML or carries
     *                                  a DOCTYPE declaration; the Workday client is not called
     * @throws UncheckedIOException     if the parser fails to read the body, for example an unsupported
     *                                  declared encoding; the Workday client is not called
     * @throws RuntimeException         any exception of the client, such as the {@code Upstream*Exception}
     *                                  types of D-020 or a SOAP fault, unchanged
     */
    public String addCustomerFlow(byte[] requestBody) {
        Document document = parse(requestBody);
        PutCustomerRequestType request = requestMapper.toPutCustomerRequest(document);
        PutCustomerResponseType response = workdayRevenueClient.putCustomer(request);
        String descriptor = responseMapper.toDescriptor(response);
        return REPLY_PREFIX + (descriptor == null ? NULL_PAYLOAD : descriptor);
    }

    /**
     * Parses the request body into a namespace-aware DOM document; DOCTYPE declarations are rejected,
     * external entities, external DTDs and XInclude are off and entity references are not expanded
     * (D-580). Parser warnings and errors are not written to {@code System.err}; a fatal error throws.
     *
     * @param body the request body bytes
     * @return the parsed document, never {@code null}
     * @throws IllegalArgumentException with the message {@code Request body is empty} for a {@code null}
     *                                  or zero-length body, or with the parser's message and the
     *                                  {@link SAXException} as cause for a body that is not well-formed
     *                                  XML or carries a DOCTYPE declaration
     * @throws UncheckedIOException     if the parser fails to read the body, for example a body whose XML
     *                                  declaration names an unsupported encoding, with the
     *                                  {@link IOException} as cause
     * @throws IllegalStateException    if no {@link DocumentBuilder} can be created, with the
     *                                  {@link ParserConfigurationException} as cause
     */
    Document parse(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException(EMPTY_BODY_MESSAGE);
        }
        DocumentBuilder builder = newDocumentBuilder();
        // Diagnostics go to the handler, not to System.err; fatal errors throw (D-580).
        builder.setErrorHandler(new DefaultHandler());
        try {
            return builder.parse(new ByteArrayInputStream(body));
        } catch (SAXException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }

    /**
     * Returns a new {@link DocumentBuilder} of the hardened factory. The factory is used by one thread
     * at a time; each call gets its own builder (D-580).
     *
     * @return a new builder
     * @throws IllegalStateException if the factory cannot create a builder
     */
    private DocumentBuilder newDocumentBuilder() {
        synchronized (documentBuilderFactory) {
            try {
                return documentBuilderFactory.newDocumentBuilder();
            } catch (ParserConfigurationException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        }
    }

    /**
     * Builds the namespace-aware JAXP factory of {@link #parse(byte[])} with secure processing on,
     * DOCTYPE declarations disallowed, external general and parameter entities off, external DTD
     * loading off, XInclude off and entity-reference expansion off (D-580).
     *
     * @return the configured factory
     * @throws IllegalStateException if the JAXP implementation rejects one of the features
     */
    private static DocumentBuilderFactory newHardenedFactory() {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
            factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false);
            factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false);
            factory.setFeature(LOAD_EXTERNAL_DTD, false);
        } catch (ParserConfigurationException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }
}
