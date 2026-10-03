package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * Unit tests of {@link EhrMockService#ehrService(Element)}, the mock flow {@code EHRService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 45-84).
 *
 * <p>The service runs its own {@code EhrMockMapper} over a {@link Clock}: a {@code Clock.fixed} value, or a
 * Mockito mock where a test counts the clock reads. No Spring application context starts. The requests are the
 * {@code createEpisode} and {@code findEpisodes} elements of {@code EHRService.wsdl} and
 * {@code SOA-Message-1.0.xsd}, other local names, a {@code null} element for an empty Body, and elements parsed
 * without namespace awareness.
 *
 * <p>The tests assert the operation branch each request selects, the whole returned text of DW-44
 * {@code createEpisodeResponse} or DW-45 {@code findEpisodesResponse}, the names of the returned elements, one
 * clock read in the {@code createEpisode} branch and none in the {@code otherwise} branch.
 */
@ExtendWith(MockitoExtension.class)
class EhrMockServiceTest {

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

    /** The {@code ns1:PatientId} text of the requests. */
    private static final String PATIENT_ID = "2015-03-01T10:15:29.987Z";

    /** The {@code ns1:PatientId} line DW-44 and DW-45 write for {@link #PATIENT_ID}. */
    private static final String PATIENT_ID_LINE = "<ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId>";

    /** The {@code ns1:PatientId} line DW-44 and DW-45 write when no qualified {@code PatientId} is selected. */
    private static final String EMPTY_PATIENT_ID_LINE = "<ns1:PatientId/>";

    /** The clock of the tests that count clock reads. */
    @Mock
    private Clock clock;

    /** The service under test over a fixed UTC clock. */
    private final EhrMockService service = new EhrMockService(Clock.fixed(NOW, ZoneOffset.UTC));

    /**
     * Asserts an {@code ns0:createEpisode} request selects the {@code when} branch and returns DW-44
     * {@code ns0:createEpisodeResponse} echoing its {@code ns1:PatientId}, with {@code now} as {@code episodeId},
     * {@code startDate} and {@code endDate}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void createEpisodeReturnsCreateEpisodeResponseWithPatientIdAndNow() throws Exception {
        String response = textOf(service.ehrService(parse(request("ns0", NS0, "createEpisode"))));

        assertEquals(createEpisodeResponse(NOW_TEXT, PATIENT_ID_LINE), response);
        Element root = parse(response);
        assertQualifiedName(NS0, "createEpisodeResponse", root);
        List<Element> children = elementChildren(root);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "Episode", children.get(0));
        List<String> fields = new ArrayList<>();
        for (Element field : elementChildren(children.get(0))) {
            fields.add(field.getNodeName() + "=" + field.getTextContent());
        }
        assertEquals(List.of("episodeId=" + NOW_TEXT, "ns1:PatientId=" + PATIENT_ID, "admission=Elective",
                "startDate=" + NOW_TEXT, "endDate=" + NOW_TEXT, "care=Private"), fields);
    }

    /**
     * Asserts an {@code ns0:findEpisodes} request selects the {@code otherwise} branch, returns DW-45
     * {@code ns0:findEpisodesResponse} echoing its {@code ns1:PatientId}, and does not read the clock.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void findEpisodesReturnsFindEpisodesResponseWithPatientIdWithoutReadingTheClock() throws Exception {
        String response = textOf(new EhrMockService(clock).ehrService(parse(request("ns0", NS0, "findEpisodes"))));

        assertEquals(findEpisodesResponse(PATIENT_ID_LINE), response);
        verifyNoInteractions(clock);
        Element root = parse(response);
        assertQualifiedName(NS0, "findEpisodesResponse", root);
        List<Element> children = elementChildren(root);
        assertEquals(1, children.size());
        assertQualifiedName(NS1, "Episode", children.get(0));
        List<Element> fields = elementChildren(children.get(0));
        assertEquals(1, fields.size());
        assertQualifiedName(NS1, "PatientId", fields.get(0));
        assertEquals(PATIENT_ID, fields.get(0).getTextContent());
    }

    /**
     * Asserts a {@code null} request, the Body child of an empty Body, selects the {@code otherwise} branch,
     * returns DW-45 {@code ns0:findEpisodesResponse} with {@code <ns1:PatientId/>}, and does not read the clock.
     *
     * @throws Exception when the response cannot be read
     */
    @Test
    void nullRequestReturnsFindEpisodesResponseWithEmptyPatientId() throws Exception {
        assertEquals(findEpisodesResponse(EMPTY_PATIENT_ID_LINE), textOf(new EhrMockService(clock).ehrService(null)));
        verifyNoInteractions(clock);
    }

    /**
     * Asserts a request whose local name is neither {@code createEpisode} nor {@code findEpisodes} selects the
     * {@code otherwise} branch and returns DW-45 {@code ns0:findEpisodesResponse} with {@code <ns1:PatientId/>}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void unknownOperationReturnsFindEpisodesResponseWithEmptyPatientId() throws Exception {
        Element request = parse(request("ns0", NS0, "getEpisode"));

        assertEquals(findEpisodesResponse(EMPTY_PATIENT_ID_LINE),
                textOf(new EhrMockService(clock).ehrService(request)));
        verifyNoInteractions(clock);
    }

    /**
     * Asserts the operation comparison is case-sensitive: {@code ns0:CreateEpisode} selects the {@code otherwise}
     * branch and returns DW-45 {@code ns0:findEpisodesResponse} without reading the clock.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void createEpisodeWithOtherCaseReturnsFindEpisodesResponse() throws Exception {
        Element request = parse(request("ns0", NS0, "CreateEpisode"));

        assertEquals(findEpisodesResponse(EMPTY_PATIENT_ID_LINE),
                textOf(new EhrMockService(clock).ehrService(request)));
        verifyNoInteractions(clock);
    }

    /**
     * Asserts the namespace of the request is not read for the branch: {@code createEpisode} in namespace
     * {@code urn:other} selects the {@code when} branch and returns DW-44 with {@code <ns1:PatientId/>}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void createEpisodeInOtherNamespaceReturnsCreateEpisodeResponseWithEmptyPatientId() throws Exception {
        Element request = parse(request("other", "urn:other", "createEpisode"));

        assertEquals(createEpisodeResponse(NOW_TEXT, EMPTY_PATIENT_ID_LINE), textOf(service.ehrService(request)));
    }

    /**
     * Asserts a prefixed {@code ns0:createEpisode} parsed without namespace awareness, whose local name is
     * {@code null}, selects the {@code when} branch by the node name after its {@code ':'} and returns DW-44 with
     * {@code <ns1:PatientId/>}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void prefixedCreateEpisodeWithoutNamespaceAwarenessReturnsCreateEpisodeResponse() throws Exception {
        Element request = parseWithoutNamespaces(request("ns0", NS0, "createEpisode"));
        assertNull(request.getLocalName());
        assertEquals("ns0:createEpisode", request.getNodeName());

        assertEquals(createEpisodeResponse(NOW_TEXT, EMPTY_PATIENT_ID_LINE), textOf(service.ehrService(request)));
    }

    /**
     * Asserts an unprefixed {@code createEpisode} parsed without namespace awareness selects the {@code when}
     * branch by its whole node name and returns DW-44 with {@code <ns1:PatientId/>}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void unprefixedCreateEpisodeWithoutNamespaceAwarenessReturnsCreateEpisodeResponse() throws Exception {
        Element request = parseWithoutNamespaces("<createEpisode/>");
        assertNull(request.getLocalName());

        assertEquals(createEpisodeResponse(NOW_TEXT, EMPTY_PATIENT_ID_LINE), textOf(service.ehrService(request)));
    }

    /**
     * Asserts a prefixed {@code ns0:findEpisodes} parsed without namespace awareness selects the {@code otherwise}
     * branch and returns DW-45 with {@code <ns1:PatientId/>}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void prefixedFindEpisodesWithoutNamespaceAwarenessReturnsFindEpisodesResponse() throws Exception {
        Element request = parseWithoutNamespaces(request("ns0", NS0, "findEpisodes"));
        assertNull(request.getLocalName());

        assertEquals(findEpisodesResponse(EMPTY_PATIENT_ID_LINE), textOf(service.ehrService(request)));
    }

    /**
     * Asserts the {@code createEpisode} branch reads the clock once and writes {@code now} in the clock's offset
     * {@code +02:00} as {@code episodeId}, {@code startDate} and {@code endDate}.
     *
     * @throws Exception when a document cannot be read or parsed
     */
    @Test
    void createEpisodeReadsTheClockOnceAndWritesNowInItsOffset() throws Exception {
        when(clock.instant()).thenReturn(NOW);
        when(clock.getZone()).thenReturn(PLUS_TWO);

        String response = textOf(new EhrMockService(clock).ehrService(parse(request("ns0", NS0, "createEpisode"))));

        assertEquals(createEpisodeResponse(NOW_PLUS_TWO_TEXT, PATIENT_ID_LINE), response);
        verify(clock).instant();
        verify(clock).getZone();
        verifyNoMoreInteractions(clock);
    }

    /**
     * Builds a request element holding one {@code ns1:PatientId} with {@link #PATIENT_ID}.
     *
     * @param prefix the prefix of the root element
     * @param namespaceUri the namespace bound to {@code prefix}
     * @param localName the local name of the root element
     * @return the request text
     */
    private static String request(String prefix, String namespaceUri, String localName) {
        return "<" + prefix + ":" + localName + " xmlns:" + prefix + "=\"" + namespaceUri + "\" xmlns:ns1=\"" + NS1
                + "\"><ns1:PatientId>" + PATIENT_ID + "</ns1:PatientId></" + prefix + ":" + localName + ">";
    }

    /**
     * Returns the DW-44 {@code ns0:createEpisodeResponse} text.
     *
     * @param now the {@code episodeId}, {@code startDate} and {@code endDate} text
     * @param patientIdLine the {@code ns1:PatientId} line
     * @return the expected document text
     */
    private static String createEpisodeResponse(String now, String patientIdLine) {
        return DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + now + "</episodeId>\n"
                + "    " + patientIdLine + "\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + now + "</startDate>\n"
                + "    <endDate>" + now + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>";
    }

    /**
     * Returns the DW-45 {@code ns0:findEpisodesResponse} text.
     *
     * @param patientIdLine the {@code ns1:PatientId} line
     * @return the expected document text
     */
    private static String findEpisodesResponse(String patientIdLine) {
        return DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    " + patientIdLine + "\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>";
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
