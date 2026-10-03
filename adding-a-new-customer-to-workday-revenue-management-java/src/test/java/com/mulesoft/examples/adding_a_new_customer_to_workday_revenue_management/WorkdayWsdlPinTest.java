package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Asserts the committed Workday WSDL equals the pinned v35.0 contract (D-018, D-078).
 *
 * <p>The contract is the test-classpath resource {@value #RESOURCE}, built from
 * {@code src/main/resources/wsdl/Revenue_Management_v35.0.wsdl}. On every {@code mvn test} this class
 * <ul>
 *   <li>recomputes the SHA-256 of the committed bytes and compares its lower-case hex form with
 *       {@value #PINNED_SHA256}, and compares the byte count with {@value #PINNED_LENGTH};</li>
 *   <li>parses the bytes as namespace-aware DOM and asserts the document root is {@code wsdl:definitions} and
 *       that the document holds no {@code wsdl:import}, {@code xsd:import} or {@code xsd:include} element;</li>
 *   <li>asserts {@code wsdl:operation} elements named {@code Put_Customer} and {@code Get_Customers} exist.</li>
 * </ul>
 * A missing resource fails each test with a message naming {@value #RESOURCE} and D-078. Every failure message
 * names D-078. The class starts no Spring application context and reads no generated {@code com.workday.bsvc}
 * class.
 *
 * <p>The class and its test methods are public: each one is a backward row of {@code TRACEABILITY.md} mapped to
 * D-078 (D-133).
 */
public class WorkdayWsdlPinTest {

    /** Receives the WARN entry written when the XML parser lacks {@value #DISALLOW_DOCTYPE_DECL}. */
    private static final Logger LOG = LoggerFactory.getLogger(WorkdayWsdlPinTest.class);

    /** Test-classpath location of the committed Workday Revenue Management v35.0 WSDL. */
    private static final String RESOURCE = "wsdl/Revenue_Management_v35.0.wsdl";

    /** Pinned SHA-256 of the committed WSDL, lower-case hex (D-018, D-078). */
    private static final String PINNED_SHA256 = "48ea015c176382ae622d5f08d9a87cd12c50a45caa659b83a9b1b66b64050c5f";

    /** Pinned length of the committed WSDL in bytes (D-018, D-078). */
    private static final long PINNED_LENGTH = 2_998_693L;

    /** Namespace of WSDL 1.1 elements. */
    private static final String WSDL_NS = "http://schemas.xmlsoap.org/wsdl/";

    /** Namespace of XML Schema elements. */
    private static final String XSD_NS = XMLConstants.W3C_XML_SCHEMA_NS_URI;

    /** Xerces feature that rejects any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** Operation of the original {@code add-customer-flow}, looked up among the {@code wsdl:operation} elements. */
    private static final String PUT_CUSTOMER = "Put_Customer";

    /** Operation of the live IT assertion, looked up among the {@code wsdl:operation} elements. */
    private static final String GET_CUSTOMERS = "Get_Customers";

    /**
     * Asserts the SHA-256 of the committed WSDL equals {@value #PINNED_SHA256} and its length equals
     * {@value #PINNED_LENGTH} bytes (D-078). Both comparisons are reported when both fail.
     *
     * @throws IOException when the resource cannot be read
     * @throws NoSuchAlgorithmException when the JDK provides no SHA-256 digest
     */
    @Test
    @DisplayName("Committed Workday WSDL matches the pinned SHA-256 and length")
    public void committedWsdlMatchesPinnedSha256AndLength() throws IOException, NoSuchAlgorithmException {
        byte[] bytes = loadCommittedWsdl();
        String actualSha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        long actualLength = bytes.length;

        assertAll(
                "Committed Workday WSDL " + RESOURCE + " against the pinned v35.0 contract (D-078)",
                () -> assertEquals(PINNED_SHA256, actualSha256,
                        () -> "Committed Workday WSDL " + RESOURCE + " SHA-256 is " + actualSha256
                                + ", expected pinned " + PINNED_SHA256 + " (D-078)"),
                () -> assertEquals(PINNED_LENGTH, actualLength,
                        () -> "Committed Workday WSDL " + RESOURCE + " length is " + actualLength
                                + " bytes, expected pinned " + PINNED_LENGTH + " bytes (D-078)"));
    }

    /**
     * Asserts the committed WSDL is a {@code wsdl:definitions} document holding no {@code wsdl:import},
     * {@code xsd:import} or {@code xsd:include} element (D-078). The three counts are reported together.
     *
     * @throws IOException when the resource cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the resource is not well-formed XML
     */
    @Test
    @DisplayName("Committed Workday WSDL imports and includes no other document")
    public void committedWsdlImportsAndIncludesNothing()
            throws IOException, ParserConfigurationException, SAXException {
        Document doc = parseCommittedWsdl();
        Element root = doc.getDocumentElement();
        assertTrue(WSDL_NS.equals(root.getNamespaceURI()) && "definitions".equals(root.getLocalName()),
                () -> "Committed Workday WSDL " + RESOURCE + " root is {" + root.getNamespaceURI() + "}"
                        + root.getLocalName() + ", expected {" + WSDL_NS + "}definitions (D-078)");

        int wsdlImports = doc.getElementsByTagNameNS(WSDL_NS, "import").getLength();
        int xsdImports = doc.getElementsByTagNameNS(XSD_NS, "import").getLength();
        int xsdIncludes = doc.getElementsByTagNameNS(XSD_NS, "include").getLength();

        assertAll(
                "Committed Workday WSDL " + RESOURCE + " external references (D-078)",
                () -> assertEquals(0, wsdlImports,
                        () -> "Committed Workday WSDL " + RESOURCE + " holds " + wsdlImports
                                + " wsdl:import element(s), expected 0 (D-078)"),
                () -> assertEquals(0, xsdImports,
                        () -> "Committed Workday WSDL " + RESOURCE + " holds " + xsdImports
                                + " xsd:import element(s), expected 0 (D-078)"),
                () -> assertEquals(0, xsdIncludes,
                        () -> "Committed Workday WSDL " + RESOURCE + " holds " + xsdIncludes
                                + " xsd:include element(s), expected 0 (D-078)"));
    }

    /**
     * Asserts the committed WSDL declares {@code wsdl:operation} elements named {@code Put_Customer} and
     * {@code Get_Customers}. This is a sanity check alongside
     * {@link #committedWsdlMatchesPinnedSha256AndLength()}, which remains the pin check; operation names alone
     * release no contract (D-078).
     *
     * @throws IOException when the resource cannot be read
     * @throws ParserConfigurationException when the JDK parser rejects a mandatory feature
     * @throws SAXException when the resource is not well-formed XML
     */
    @Test
    @DisplayName("Committed Workday WSDL declares the Put_Customer and Get_Customers operations")
    public void committedWsdlDeclaresPutCustomerAndGetCustomers()
            throws IOException, ParserConfigurationException, SAXException {
        Set<String> operationNames = operationNames(parseCommittedWsdl());

        assertAll(
                "Committed Workday WSDL " + RESOURCE + " operations (D-078)",
                () -> assertOperationDeclared(operationNames, PUT_CUSTOMER),
                () -> assertOperationDeclared(operationNames, GET_CUSTOMERS));
    }

    /**
     * Asserts {@code operationNames} holds {@code expected}, with a failure message naming the resource, the
     * operation and D-078.
     *
     * @param operationNames the distinct {@code wsdl:operation} names of the committed WSDL
     * @param expected the operation name to look up
     */
    private static void assertOperationDeclared(Set<String> operationNames, String expected) {
        assertTrue(operationNames.contains(expected),
                () -> "Committed Workday WSDL " + RESOURCE + " declares no wsdl:operation named " + expected
                        + " among its " + operationNames.size() + " distinct operation names (D-078)");
    }

    /**
     * Reads every byte of {@value #RESOURCE} through the class loader of this class.
     *
     * @return the committed WSDL bytes
     * @throws IOException when the resource cannot be read
     */
    private static byte[] loadCommittedWsdl() throws IOException {
        try (InputStream in = WorkdayWsdlPinTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return fail("Committed Workday WSDL " + RESOURCE + " is missing (D-078)");
            }
            return in.readAllBytes();
        }
    }

    /**
     * Parses the committed WSDL bytes into a namespace-aware DOM with secure processing on, entity reference
     * expansion and XInclude off, and document type declarations rejected where the parser supports
     * {@value #DISALLOW_DOCTYPE_DECL}. A parser without that feature logs a WARN entry and parses with the
     * remaining settings.
     *
     * @return the parsed document
     * @throws IOException when the resource cannot be read
     * @throws ParserConfigurationException when the parser rejects secure processing
     * @throws SAXException when the bytes are not well-formed XML or declare a document type
     */
    private static Document parseCommittedWsdl() throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setExpandEntityReferences(false);
        factory.setXIncludeAware(false);
        try {
            factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        } catch (ParserConfigurationException unsupported) {
            LOG.warn("XML parser {} does not support feature {}; parsing {} without it: {}",
                    factory.getClass().getName(), DISALLOW_DOCTYPE_DECL, RESOURCE, unsupported.getMessage());
        }
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(loadCommittedWsdl()));
    }

    /**
     * Collects the {@code name} attribute of every {@code wsdl:operation} element, in document order without
     * duplicates.
     *
     * @param doc the parsed WSDL
     * @return the distinct operation names
     */
    private static Set<String> operationNames(Document doc) {
        NodeList operations = doc.getElementsByTagNameNS(WSDL_NS, "operation");
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < operations.getLength(); i++) {
            names.add(((Element) operations.item(i)).getAttribute("name"));
        }
        return names;
    }
}
