package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.builder.Input;
import org.xmlunit.diff.Diff;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.PatientMockMapper;

/**
 * Unit tests of {@link PatientMockService#patientService(Element)}, the mock flow {@code PatientService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 4-44).
 *
 * <p>The flow sets {@code operation} to the local name of the request root, ignoring its namespace (MEL
 * {@code xpath('fn:local-name(/*)')}, :7). Its {@code when} (:10) answers {@code upsertPatient} with DW-42
 * {@code ns0:upsertPatientResponse/ns1:PatientId} = {@code now} (:12-19), and its {@code otherwise} (:22) answers
 * every other operation with DW-43 {@code ns0:getPatientResponse/ns1:Patient} (:24-40).
 *
 * <p>The service runs over a real {@code Clock.fixed} clock; no Spring application context starts and nothing is
 * mocked. Each returned {@link Source} is read once through a JAXP identity {@link Transformer} and compared, by
 * XMLUnit similarity with whitespace ignored, with the same transform of the text that {@link PatientMockMapper}
 * writes for the expected branch; the element QNames and texts are then checked through DOM lookups. The tests
 * after the five branch tests pin the operation rules of D-430: the case-sensitive compare, the node-name fallback
 * for elements parsed without namespace awareness, and {@code now} written in the injected clock's offset (D-400,
 * D-610).
 */
@ActiveProfiles("test")
public class PatientMockServiceTest {

    /** Namespace of the {@code ns0} message elements. */
    private static final String MSG_NS = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String MODEL_NS = "http://www.mule-health.com/SOA/model/1.0";

    /** A namespace that is neither the message nor the model namespace. */
    private static final String OTHER_NS = "urn:example:other";

    /** The UTC clock of the service under test. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2015-03-01T10:15:30.123Z"), ZoneOffset.UTC);

    /** The {@code now} the service reads from {@link #CLOCK}. */
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    /** The text the mapper writes for {@link #NOW}. */
    private static final String NOW_TEXT = "2015-03-01T10:15:30.123Z";

    /** A clock at the instant of {@link #CLOCK} in the offset {@code +02:00}. */
    private static final Clock PLUS_TWO_CLOCK = Clock.fixed(CLOCK.instant(), ZoneOffset.ofHours(2));

    /** The text the mapper writes for the {@code now} of {@link #PLUS_TWO_CLOCK}. */
    private static final String NOW_PLUS_TWO_TEXT = "2015-03-01T12:15:30.123+02:00";

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The {@code ns0:upsertPatient} request in the message namespace. */
    private static final String UPSERT_PATIENT = "<ns0:upsertPatient xmlns:ns0=\"" + MSG_NS + "\" xmlns:ns1=\""
            + MODEL_NS + "\"><ns1:Subject><firstName>Nial</firstName></ns1:Subject></ns0:upsertPatient>";

    /** The {@code ns0:getPatient} request in the message namespace. */
    private static final String GET_PATIENT = "<ns0:getPatient xmlns:ns0=\"" + MSG_NS + "\" xmlns:ns1=\"" + MODEL_NS
            + "\"><ns1:PatientId>P123</ns1:PatientId></ns0:getPatient>";

    /** The service under test over {@link #CLOCK}. */
    private final PatientMockService service = new PatientMockService(CLOCK);

    /**
     * Asserts an {@code ns0:upsertPatient} request takes the {@code when} branch and returns the DW-42
     * {@code ns0:upsertPatientResponse} whose {@code ns1:PatientId} is {@code now}.
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("upsertPatient root in the message namespace takes the upsertPatient branch (mocks.xml:7, :10, DW-42)")
    public void upsertPatientInMessageNamespaceReturnsUpsertPatientResponse() throws Exception {
        String response = toXml(service.patientService(parse(UPSERT_PATIENT)));

        assertXmlEquals(normalise(new PatientMockMapper().upsertPatientResponse(NOW)), response);
        Element root = rootOf(response);
        assertQName(MSG_NS, "upsertPatientResponse", root);
        assertEquals(NOW_TEXT, onlyText(root, MODEL_NS, "PatientId"));
    }

    /**
     * Asserts an {@code upsertPatient} root in another namespace takes the {@code when} branch: the operation is the
     * root's local name, and its namespace is not read.
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("upsertPatient root in another namespace takes the upsertPatient branch (mocks.xml:7, :10, DW-42)")
    public void upsertPatientInOtherNamespaceReturnsUpsertPatientResponse() throws Exception {
        Element request = parse("<other:upsertPatient xmlns:other=\"" + OTHER_NS + "\"/>");

        String response = toXml(service.patientService(request));

        assertXmlEquals(normalise(new PatientMockMapper().upsertPatientResponse(NOW)), response);
        assertQName(MSG_NS, "upsertPatientResponse", rootOf(response));
    }

    /**
     * Asserts an {@code ns0:getPatient} request takes the {@code otherwise} branch and returns the DW-43
     * {@code ns0:getPatientResponse} whose {@code ns1:Patient} holds {@code now} and the fixed patient fields.
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("getPatient root takes the otherwise branch and returns getPatientResponse (mocks.xml:10, :22, DW-43)")
    public void getPatientReturnsGetPatientResponse() throws Exception {
        String response = toXml(service.patientService(parse(GET_PATIENT)));

        assertXmlEquals(normalise(new PatientMockMapper().getPatientResponse(NOW)), response);
        Element root = rootOf(response);
        assertQName(MSG_NS, "getPatientResponse", root);
        NodeList patients = root.getElementsByTagNameNS(MODEL_NS, "Patient");
        assertEquals(1, patients.getLength(), "{" + MODEL_NS + "}Patient elements");
        Element patient = (Element) patients.item(0);
        assertEquals(NOW_TEXT, onlyText(patient, "*", "patientId"));
        assertEquals("1930-01-01", onlyText(patient, "*", "dateOfBirth"));
        assertEquals("Male", onlyText(patient, "*", "gender"));
        assertEquals("USA", onlyText(patient, "*", "nationality"));
        assertEquals("DisneyLand", onlyText(patient, "*", "address1"));
        assertEquals("Duck", onlyText(patient, "*", "lastName"));
        assertEquals("Donald", onlyText(patient, "*", "firstName"));
        assertEquals(NOW_TEXT, onlyText(patient, "*", "nationalId"));
    }

    /**
     * Asserts a root whose local name is neither {@code upsertPatient} nor {@code getPatient} takes the
     * {@code otherwise} branch and returns the DW-43 {@code ns0:getPatientResponse}.
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("findPatients root without a namespace takes the otherwise branch (mocks.xml:10, :22, DW-43)")
    public void otherRootReturnsGetPatientResponse() throws Exception {
        String response = toXml(service.patientService(parse("<findPatients/>")));

        assertXmlEquals(normalise(new PatientMockMapper().getPatientResponse(NOW)), response);
        assertQName(MSG_NS, "getPatientResponse", rootOf(response));
    }

    /**
     * Asserts a {@code null} request, the Body child of an empty Body, gives the empty operation, takes the
     * {@code otherwise} branch without throwing and returns the DW-43 {@code ns0:getPatientResponse} (D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("null request returns getPatientResponse (mocks.xml:7, :22, DW-43)")
    public void nullRequestReturnsGetPatientResponse() throws Exception {
        Source source = assertDoesNotThrow(() -> service.patientService(null));

        String response = toXml(source);

        assertXmlEquals(normalise(new PatientMockMapper().getPatientResponse(NOW)), response);
        assertQName(MSG_NS, "getPatientResponse", rootOf(response));
    }

    /**
     * Asserts the operation compare is case-sensitive: an {@code ns0:UpsertPatient} root takes the {@code otherwise}
     * branch and returns the DW-43 {@code ns0:getPatientResponse} (D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("UpsertPatient root takes the otherwise branch: the operation compare is case-sensitive (D-430)")
    public void upsertPatientWithOtherCaseReturnsGetPatientResponse() throws Exception {
        Element request = parse("<ns0:UpsertPatient xmlns:ns0=\"" + MSG_NS + "\"/>");

        String response = toXml(service.patientService(request));

        assertXmlEquals(normalise(new PatientMockMapper().getPatientResponse(NOW)), response);
        assertQName(MSG_NS, "getPatientResponse", rootOf(response));
    }

    /**
     * Asserts a prefixed {@code ns0:upsertPatient} parsed without namespace awareness, whose local name is
     * {@code null}, takes the {@code when} branch by the part of its node name after the {@code ':'} (D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("ns0:upsertPatient parsed without namespaces takes the upsertPatient branch by node name (D-430)")
    public void prefixedUpsertPatientWithoutNamespaceAwarenessReturnsUpsertPatientResponse() throws Exception {
        Element request = parseWithoutNamespaces(UPSERT_PATIENT);
        assertNull(request.getLocalName(), "local name of an element parsed without namespaces");
        assertEquals("ns0:upsertPatient", request.getNodeName());

        String response = toXml(service.patientService(request));

        assertXmlEquals(normalise(new PatientMockMapper().upsertPatientResponse(NOW)), response);
        assertQName(MSG_NS, "upsertPatientResponse", rootOf(response));
    }

    /**
     * Asserts an unprefixed {@code upsertPatient} parsed without namespace awareness takes the {@code when} branch
     * by its whole node name (D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("upsertPatient parsed without namespaces takes the upsertPatient branch by whole node name (D-430)")
    public void unprefixedUpsertPatientWithoutNamespaceAwarenessReturnsUpsertPatientResponse() throws Exception {
        Element request = parseWithoutNamespaces("<upsertPatient/>");
        assertNull(request.getLocalName(), "local name of an element parsed without namespaces");

        String response = toXml(service.patientService(request));

        assertXmlEquals(normalise(new PatientMockMapper().upsertPatientResponse(NOW)), response);
        assertQName(MSG_NS, "upsertPatientResponse", rootOf(response));
    }

    /**
     * Asserts a prefixed {@code ns0:getPatient} parsed without namespace awareness takes the {@code otherwise}
     * branch and returns the DW-43 {@code ns0:getPatientResponse} (D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("ns0:getPatient parsed without namespaces takes the otherwise branch (mocks.xml:22, DW-43, D-430)")
    public void prefixedGetPatientWithoutNamespaceAwarenessReturnsGetPatientResponse() throws Exception {
        Element request = parseWithoutNamespaces(GET_PATIENT);
        assertNull(request.getLocalName(), "local name of an element parsed without namespaces");

        String response = toXml(service.patientService(request));

        assertXmlEquals(normalise(new PatientMockMapper().getPatientResponse(NOW)), response);
        assertQName(MSG_NS, "getPatientResponse", rootOf(response));
    }

    /**
     * Asserts the {@code when} branch writes the {@code now} of the injected clock in that clock's offset
     * {@code +02:00} as {@code ns1:PatientId} (D-400, D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("upsertPatient branch writes now from the injected clock in its offset +02:00 (DW-42, D-430)")
    public void upsertPatientWritesNowInTheClockOffset() throws Exception {
        PatientMockService plusTwoService = new PatientMockService(PLUS_TWO_CLOCK);

        String response = toXml(plusTwoService.patientService(parse(UPSERT_PATIENT)));

        assertXmlEquals(normalise(new PatientMockMapper().upsertPatientResponse(OffsetDateTime.now(PLUS_TWO_CLOCK))),
                response);
        assertEquals(NOW_PLUS_TWO_TEXT, onlyText(rootOf(response), MODEL_NS, "PatientId"));
    }

    /**
     * Asserts the {@code otherwise} branch writes the {@code now} of the injected clock in that clock's offset
     * {@code +02:00} as both {@code patientId} and {@code nationalId} (D-400, D-430).
     *
     * @throws Exception when a document cannot be parsed or transformed
     */
    @Test
    @DisplayName("otherwise branch writes now from the injected clock in its offset +02:00 (DW-43, D-430)")
    public void getPatientWritesNowInTheClockOffset() throws Exception {
        PatientMockService plusTwoService = new PatientMockService(PLUS_TWO_CLOCK);

        String response = toXml(plusTwoService.patientService(parse(GET_PATIENT)));

        assertXmlEquals(normalise(new PatientMockMapper().getPatientResponse(OffsetDateTime.now(PLUS_TWO_CLOCK))),
                response);
        Element root = rootOf(response);
        assertEquals(NOW_PLUS_TWO_TEXT, onlyText(root, "*", "patientId"));
        assertEquals(NOW_PLUS_TWO_TEXT, onlyText(root, "*", "nationalId"));
    }

    /**
     * Parses {@code xml} with a namespace-aware parser that rejects document type declarations and expands no
     * entity or XInclude.
     *
     * @param xml the document text
     * @return the document element
     * @throws Exception when the parser cannot be configured or the text is not a well-formed document
     */
    private static Element parse(String xml) throws Exception {
        return parse(xml, true);
    }

    /**
     * Parses {@code xml} with a parser without namespace awareness that rejects document type declarations and
     * expands no entity or XInclude; the elements it returns have no local name.
     *
     * @param xml the document text
     * @return the document element
     * @throws Exception when the parser cannot be configured or the text is not a well-formed document
     */
    private static Element parseWithoutNamespaces(String xml) throws Exception {
        return parse(xml, false);
    }

    /**
     * Parses {@code xml} with the given namespace awareness, {@code disallow-doctype-decl} enabled, XInclude off and
     * entity expansion off.
     *
     * @param xml the document text
     * @param namespaceAware whether the parser is namespace aware
     * @return the document element
     * @throws Exception when the parser cannot be configured or the text is not a well-formed document
     */
    private static Element parse(String xml, boolean namespaceAware) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /**
     * Reads {@code source} once through a JAXP identity transformer that resolves no external DTD or stylesheet.
     *
     * @param source the source to read
     * @return the serialised document
     * @throws Exception when the transformer cannot be configured or the source is not a well-formed document
     */
    private static String toXml(Source source) throws Exception {
        assertNotNull(source, "source returned by the service");
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = factory.newTransformer();
        StringWriter xml = new StringWriter();
        transformer.transform(source, new StreamResult(xml));
        return xml.toString();
    }

    /**
     * Returns the identity transform of the text {@link PatientMockMapper} writes.
     *
     * @param mapperText the mapper's document text
     * @return the serialised document, comparable with {@link #toXml(Source)} of the service's result
     * @throws Exception when the text is not a well-formed document
     */
    private static String normalise(String mapperText) throws Exception {
        return toXml(new StreamSource(new StringReader(mapperText)));
    }

    /**
     * Asserts {@code actual} is similar to {@code expected}: same namespace URIs, local names, attributes and texts
     * in the same order, with whitespace-only text ignored.
     *
     * @param expected the expected document text
     * @param actual the document text under test
     */
    private static void assertXmlEquals(String expected, String actual) {
        Diff diff = DiffBuilder.compare(Input.fromString(expected))
                .withTest(Input.fromString(actual))
                .ignoreWhitespace()
                .checkForSimilar()
                .build();
        assertFalse(diff.hasDifferences(), diff.toString());
    }

    /**
     * Returns the document element of {@code xml} parsed with {@link #parse(String)}.
     *
     * @param xml the document text
     * @return its document element
     * @throws Exception when the text is not a well-formed document
     */
    private static Element rootOf(String xml) throws Exception {
        return parse(xml);
    }

    /**
     * Asserts the namespace URI and the local name of {@code element}.
     *
     * @param namespaceUri the expected namespace URI
     * @param localName the expected local name
     * @param element the element to check
     */
    private static void assertQName(String namespaceUri, String localName, Element element) {
        assertEquals(namespaceUri, element.getNamespaceURI(), "namespace of " + element.getNodeName());
        assertEquals(localName, element.getLocalName(), "local name of " + element.getNodeName());
    }

    /**
     * Asserts {@code parent} has exactly one descendant {@code {namespaceUri}localName} and returns its text.
     *
     * @param parent the element to search
     * @param namespaceUri the namespace URI, or {@code "*"} for any namespace
     * @param localName the local name
     * @return the text content of the single match
     */
    private static String onlyText(Element parent, String namespaceUri, String localName) {
        NodeList matches = parent.getElementsByTagNameNS(namespaceUri, localName);
        assertEquals(1, matches.getLength(), "{" + namespaceUri + "}" + localName + " elements");
        return matches.item(0).getTextContent();
    }
}

