package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Unit tests of {@link PatientMockService#patientService(Element)}, the mock flow {@code PatientService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 4-44, D-430).
 *
 * <p>The service runs its own {@code PatientMockMapper} over a {@link Clock}: a {@code Clock.fixed} value, or a
 * Mockito mock where a test counts the clock reads. No Spring application context starts. The requests are the
 * {@code upsertPatient} and {@code getPatient} elements of {@code PatientService.wsdl} and
 * {@code SOA-Message-1.0.xsd}, other local names, a {@code null} element for an empty Body, and elements parsed
 * without namespace awareness.
 *
 * <p>The tests assert the operation branch each request selects, the whole returned text of DW-42
 * {@code upsertPatientResponse} or DW-43 {@code getPatientResponse}, the names of the returned elements, and the
 * single clock read per call.
 */
@ExtendWith(MockitoExtension.class)
class PatientMockServiceTest {

    /** Namespace of the {@code ns0} message elements. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** First line of every document the service returns. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The instant of every clock in these tests. */
    private static final Instant NOW = Instant.parse("2015-03-01T10:15:30.123Z");

    /** The text written for {@link #NOW} in UTC. */
    private static final String NOW_TEXT = "2015-03-01T10:15:30.123Z";

    /** The offset of the mocked clock. */
    private static final ZoneOffset PLUS_TWO = ZoneOffset.ofHours(2);

    /** The text written for {@link #NOW} in {@link #PLUS_TWO}. */
    private static final String NOW_PLUS_TWO_TEXT = "2015-03-01T12:15:30.123+02:00";

    /** The {@code ns0:upsertPatient} request, in the form {@code AdmissionService} posts it. */
    private static final String UPSERT_PATIENT = "<ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">\n"
            + "  <ns1:Subject xmlns:ns1=\"" + NS1 + "\">\n"
            + "    <nationalId>1234</nationalId>\n"
            + "    <firstName>Nial</firstName>\n"
            + "    <lastName>Darbey</lastName>\n"
            + "  </ns1:Subject>\n"
            + "</ns0:upsertPatient>";

    /** The {@code ns0:getPatient} request of {@code PatientService.wsdl}. */
    private static final String GET_PATIENT = "<ns0:getPatient xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
            + "<ns1:PatientId>1234</ns1:PatientId></ns0:getPatient>";

    /** The clock of the tests that count clock reads. */
    @Mock
    private Clock clock;

    /** The service under test over a fixed UTC clock. */
    private final PatientMockService service = new PatientMockService(Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * Asserts an {@code ns0:upsertPatient} request selects the {@code when} branch and returns DW-42
     * {@code ns0:upsertPatientResponse} holding one {@code ns1:PatientId} whose text is {@code now}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void upsertPatientReturnsUpsertPatientResponseWithNowAsPatientId() throws Exception {
        String response = textOf(service.patientService(parse(UPSERT_PATIENT)));

        assertEquals(upsertPatientResponse(NOW_TEXT), response);
        Element root = parse(response);
        assertQualifiedName(NS0, "upsertPatientResponse", root);
        List<Element> children = elementChildren(root);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "PatientId", children.get(0));
        assertEquals(NOW_TEXT, children.get(0).getTextContent());
    }

    /**
     * Asserts an {@code ns0:getPatient} request selects the {@code otherwise} branch and returns DW-43
     * {@code ns0:getPatientResponse} holding one {@code ns1:Patient} with the fixed fields and {@code now}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void getPatientReturnsGetPatientResponseWithFixedPatientAndNow() throws Exception {
        String response = textOf(service.patientService(parse(GET_PATIENT)));

        assertEquals(getPatientResponse(NOW_TEXT), response);
        Element root = parse(response);
        assertQualifiedName(NS0, "getPatientResponse", root);
        List<Element> children = elementChildren(root);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "Patient", children.get(0));
        List<String> fields = new ArrayList<>();
        for (Element field : elementChildren(children.get(0))) {
            assertNull(field.getNamespaceURI(), "namespace of " + field.getLocalName());
            fields.add(field.getLocalName() + "=" + field.getTextContent());
        }
        assertEquals(List.of("patientId=" + NOW_TEXT, "dateOfBirth=1930-01-01", "gender=Male", "nationality=USA",
                "address1=DisneyLand", "lastName=Duck", "firstName=Donald", "nationalId=" + NOW_TEXT), fields);
    }

    /**
     * Asserts a {@code null} request, the Body child of an empty Body, selects the {@code otherwise} branch and
     * returns DW-43 {@code ns0:getPatientResponse}.
     *
     * @throws Exception when the response cannot be read
     */
    @Test
    void nullRequestReturnsGetPatientResponse() throws Exception {
        assertEquals(getPatientResponse(NOW_TEXT), textOf(service.patientService(null)));
    }

    /**
     * Asserts a request whose local name is neither {@code upsertPatient} nor {@code getPatient} selects the
     * {@code otherwise} branch and returns DW-43 {@code ns0:getPatientResponse}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void unknownOperationReturnsGetPatientResponse() throws Exception {
        Element request = parse("<ns0:deletePatient xmlns:ns0=\"" + NS0 + "\"/>");

        assertEquals(getPatientResponse(NOW_TEXT), textOf(service.patientService(request)));
    }

    /**
     * Asserts the operation comparison is case-sensitive: {@code ns0:UpsertPatient} selects the {@code otherwise}
     * branch and returns DW-43 {@code ns0:getPatientResponse}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void upsertPatientWithOtherCaseReturnsGetPatientResponse() throws Exception {
        Element request = parse("<ns0:UpsertPatient xmlns:ns0=\"" + NS0 + "\"/>");

        assertEquals(getPatientResponse(NOW_TEXT), textOf(service.patientService(request)));
    }

    /**
     * Asserts the namespace of the request is not read: {@code upsertPatient} in namespace {@code urn:other}
     * selects the {@code when} branch and returns DW-42 {@code ns0:upsertPatientResponse}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void upsertPatientInOtherNamespaceReturnsUpsertPatientResponse() throws Exception {
        Element request = parse("<other:upsertPatient xmlns:other=\"urn:other\"/>");

        assertEquals(upsertPatientResponse(NOW_TEXT), textOf(service.patientService(request)));
    }

    /**
     * Asserts a prefixed {@code ns0:upsertPatient} parsed without namespace awareness, whose local name is
     * {@code null}, selects the {@code when} branch by the node name after its {@code ':'}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void prefixedUpsertPatientWithoutNamespaceAwarenessReturnsUpsertPatientResponse() throws Exception {
        Element request = parseWithoutNamespaces(UPSERT_PATIENT);
        assertNull(request.getLocalName());
        assertEquals("ns0:upsertPatient", request.getNodeName());

        assertEquals(upsertPatientResponse(NOW_TEXT), textOf(service.patientService(request)));
    }

    /**
     * Asserts an unprefixed {@code upsertPatient} parsed without namespace awareness selects the {@code when}
     * branch by its whole node name.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void unprefixedUpsertPatientWithoutNamespaceAwarenessReturnsUpsertPatientResponse() throws Exception {
        Element request = parseWithoutNamespaces("<upsertPatient/>");
        assertNull(request.getLocalName());

        assertEquals(upsertPatientResponse(NOW_TEXT), textOf(service.patientService(request)));
    }

    /**
     * Asserts a prefixed {@code ns0:getPatient} parsed without namespace awareness selects the {@code otherwise}
     * branch and returns DW-43 {@code ns0:getPatientResponse}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void prefixedGetPatientWithoutNamespaceAwarenessReturnsGetPatientResponse() throws Exception {
        Element request = parseWithoutNamespaces(GET_PATIENT);
        assertNull(request.getLocalName());

        assertEquals(getPatientResponse(NOW_TEXT), textOf(service.patientService(request)));
    }

    /**
     * Asserts the {@code upsertPatient} branch reads the clock once and writes {@code now} in the clock's offset
     * {@code +02:00}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void upsertPatientReadsTheClockOnceAndWritesNowInItsOffset() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(PLUS_TWO);

        String response = textOf(new PatientMockService(clock).patientService(parse(UPSERT_PATIENT)));

        assertEquals(upsertPatientResponse(NOW_PLUS_TWO_TEXT), response);
        verify(clock).instant();
        verify(clock).getZone();
        verifyNoMoreInteractions(clock);
    }

    /**
     * Asserts the {@code otherwise} branch reads the clock once and writes {@code now} in the clock's offset
     * {@code +02:00} as {@code patientId} and {@code nationalId}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void getPatientReadsTheClockOnceAndWritesNowInItsOffset() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(PLUS_TWO);

        String response = textOf(new PatientMockService(clock).patientService(parse(GET_PATIENT)));

        assertEquals(getPatientResponse(NOW_PLUS_TWO_TEXT), response);
        verify(clock).instant();
        verify(clock).getZone();
        verifyNoMoreInteractions(clock);
    }

    /**
     * Returns the DW-42 {@code ns0:upsertPatientResponse} text for the given {@code now} text.
     *
     * @param now the {@code ns1:PatientId} text
     * @return the expected document text
     */
    private static String upsertPatientResponse(String now) {
        return DECLARATION + "\n"
                + "<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:PatientId xmlns:ns1=\"" + NS1 + "\">" + now + "</ns1:PatientId>\n"
                + "</ns0:upsertPatientResponse>";
    }

    /**
     * Returns the DW-43 {@code ns0:getPatientResponse} text for the given {@code now} text.
     *
     * @param now the {@code patientId} and {@code nationalId} text
     * @return the expected document text
     */
    private static String getPatientResponse(String now) {
        return DECLARATION + "\n"
                + "<ns0:getPatientResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Patient xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <patientId>" + now + "</patientId>\n"
                + "    <dateOfBirth>1930-01-01</dateOfBirth>\n"
                + "    <gender>Male</gender>\n"
                + "    <nationality>USA</nationality>\n"
                + "    <address1>DisneyLand</address1>\n"
                + "    <lastName>Duck</lastName>\n"
                + "    <firstName>Donald</firstName>\n"
                + "    <nationalId>" + now + "</nationalId>\n"
                + "  </ns1:Patient>\n"
                + "</ns0:getPatientResponse>";
    }

    /**
     * Asserts the namespace URI and the local name of {@code element}.
     *
     * @param namespaceUri the expected namespace URI
     * @param localName the expected local name
     * @param element the element to check
     */
    private static void assertQualifiedName(String namespaceUri, String localName, Element element) {
        assertNotNull(element, "{" + namespaceUri + "}" + localName);
        assertEquals(namespaceUri, element.getNamespaceURI(), "namespace of " + element.getNodeName());
        assertEquals(localName, element.getLocalName(), "local name of " + element.getNodeName());
    }

    /**
     * Returns the text of the {@link StreamSource} reader that {@code source} is asserted to be.
     *
     * @param source the source returned by the service
     * @return the full text of its reader
     * @throws IOException when the reader fails
     */
    private static String textOf(Source source) throws IOException {
        StreamSource stream = assertInstanceOf(StreamSource.class, source);
        Reader reader = stream.getReader();
        assertNotNull(reader, "reader of the returned StreamSource");
        StringWriter text = new StringWriter();
        reader.transferTo(text);
        return text.toString();
    }

    /**
     * Parses {@code xml} with a namespace-aware parser that rejects document type declarations.
     *
     * @param xml the document text
     * @return the document element
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        return parse(xml, true);
    }

    /**
     * Parses {@code xml} with a parser without namespace awareness that rejects document type declarations.
     *
     * @param xml the document text
     * @return the document element, whose nodes have no local name
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parseWithoutNamespaces(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        return parse(xml, false);
    }

    /**
     * Parses {@code xml} with the given namespace awareness and {@code disallow-doctype-decl} enabled.
     *
     * @param xml the document text
     * @param namespaceAware whether the parser is namespace aware
     * @return the document element
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parse(String xml, boolean namespaceAware)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /**
     * Returns the element children of {@code parent} in document order.
     *
     * @param parent the parent element
     * @return its child elements
     */
    private static List<Element> elementChildren(Element parent) {
        List<Element> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                children.add((Element) child);
            }
        }
        return children;
    }
}
