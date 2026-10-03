package com.mulesoft.examples.xml_only_soap_webservice.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xmlunit.builder.DiffBuilder;
import org.xmlunit.diff.Diff;

import com.mulesoft.examples.xml_only_soap_webservice.mapper.EhrMockMapper;

/**
 * Unit tests of {@link EhrMockService#ehrService(Element)}, the mock flow {@code EHRService} of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml} (lines 45-84).
 *
 * <p>The flow sets {@code operation} to the local name of the request root, {@code xpath('fn:local-name(/*)')}
 * (:48), and runs one of two branches:
 *
 * <ul>
 *   <li>{@code when operation == 'createEpisode'} (:50): DW-44 (:52-66), an {@code ns0:createEpisodeResponse}
 *       holding one {@code ns1:Episode} with {@code episodeId}, {@code startDate} and {@code endDate} set to
 *       {@code now}, the request {@code ns1:PatientId}, {@code admission} {@code Elective} and {@code care}
 *       {@code Private};</li>
 *   <li>{@code otherwise} (:69): DW-45 (:71-80), an {@code ns0:findEpisodesResponse} holding one
 *       {@code ns1:Episode} with the request {@code ns1:PatientId}, written as {@code <ns1:PatientId/>} when the
 *       request has none.</li>
 * </ul>
 *
 * <p>The service runs over a real {@link Clock#fixed(Instant, java.time.ZoneId) fixed} UTC clock, with no mock
 * and no Spring application context; the {@code test} profile annotation starts none. Each test reads the
 * returned {@link Source} once and serialises it with a JAXP identity transformer. It compares that text, with
 * XMLUnit similarity and whitespace ignored, with the {@link EhrMockMapper} output for the same request passed
 * through the same transformer, then checks the qualified names and the text of the returned elements. The class
 * and its test methods are public (D-133); the test design is recorded as D-592.
 */
@ActiveProfiles("test")
public class EhrMockServiceTest {

    /** Namespace of the {@code ns0} message elements. */
    private static final String MSG_NS = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String MODEL_NS = "http://www.mule-health.com/SOA/model/1.0";

    /** A namespace that is neither {@link #MSG_NS} nor {@link #MODEL_NS}. */
    private static final String OTHER_NS = "urn:example:other";

    /** The clock of the service under test: one fixed instant in UTC. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2015-03-01T10:15:30.123Z"), ZoneOffset.UTC);

    /** The {@code now} value the service reads from {@link #CLOCK}. */
    private static final OffsetDateTime NOW = OffsetDateTime.now(CLOCK);

    /** The text DW-44 writes for {@link #NOW}. */
    private static final String NOW_TEXT = "2015-03-01T10:15:30.123Z";

    /** Parser feature that rejects any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The service under test. */
    private final EhrMockService service = new EhrMockService(CLOCK);

    /**
     * Asserts an {@code ns0:createEpisode} request takes the {@code when} branch (mocks.xml:50) and returns the
     * DW-44 {@code ns0:createEpisodeResponse}: the request {@code ns1:PatientId} {@code P123}, {@code now} as
     * {@code episodeId}, {@code startDate} and {@code endDate}, {@code admission} {@code Elective} and
     * {@code care} {@code Private}.
     *
     * @throws Exception when a document cannot be parsed or serialised
     */
    @Test
    @DisplayName("createEpisode root returns createEpisodeResponse with the request PatientId (mocks.xml:50, DW-44)")
    public void createEpisodeReturnsCreateEpisodeResponseWithRequestPatientId() throws Exception {
        Element request = parse("<ns0:createEpisode xmlns:ns0=\"" + MSG_NS + "\" xmlns:ns1=\"" + MODEL_NS + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:createEpisode>");

        String response = toXml(service.ehrService(request));

        assertXmlEquals(normalise(new EhrMockMapper().createEpisodeResponse(request, NOW)), response);
        Element root = rootOf(response);
        assertQualifiedName(MSG_NS, "createEpisodeResponse", root);
        Element episode = qualifiedChild(root, MODEL_NS, "Episode");
        assertEquals("P123", qualifiedChild(episode, MODEL_NS, "PatientId").getTextContent());
        assertEquals(NOW_TEXT, childByLocalName(episode, "episodeId").getTextContent());
        assertEquals("Elective", childByLocalName(episode, "admission").getTextContent());
        assertEquals(NOW_TEXT, childByLocalName(episode, "startDate").getTextContent());
        assertEquals(NOW_TEXT, childByLocalName(episode, "endDate").getTextContent());
        assertEquals("Private", childByLocalName(episode, "care").getTextContent());
    }

    /**
     * Asserts the branch test reads only the local name of the request root (mocks.xml:48): {@code createEpisode}
     * in the namespace {@code urn:example:other} takes the {@code when} branch (mocks.xml:50) and returns the
     * DW-44 {@code ns0:createEpisodeResponse} the mapper writes for the same request.
     *
     * @throws Exception when a document cannot be parsed or serialised
     */
    @Test
    @DisplayName("createEpisode root in another namespace takes the createEpisode branch (mocks.xml:48, DW-44)")
    public void createEpisodeInOtherNamespaceTakesCreateEpisodeBranch() throws Exception {
        Element request = parse("<other:createEpisode xmlns:other=\"" + OTHER_NS + "\" xmlns:ns1=\"" + MODEL_NS
                + "\"><ns1:PatientId>P123</ns1:PatientId></other:createEpisode>");
        assertQualifiedName(OTHER_NS, "createEpisode", request);

        String response = toXml(service.ehrService(request));

        assertXmlEquals(normalise(new EhrMockMapper().createEpisodeResponse(request, NOW)), response);
        assertQualifiedName(MSG_NS, "createEpisodeResponse", rootOf(response));
    }

    /**
     * Asserts an {@code ns0:findEpisodes} request takes the {@code otherwise} branch (mocks.xml:69) and returns the
     * DW-45 {@code ns0:findEpisodesResponse} holding the request {@code ns1:PatientId} {@code P456}.
     *
     * @throws Exception when a document cannot be parsed or serialised
     */
    @Test
    @DisplayName("findEpisodes root returns findEpisodesResponse with the request PatientId (mocks.xml:69, DW-45)")
    public void findEpisodesReturnsFindEpisodesResponseWithRequestPatientId() throws Exception {
        Element request = parse("<ns0:findEpisodes xmlns:ns0=\"" + MSG_NS + "\" xmlns:ns1=\"" + MODEL_NS + "\">"
                + "<ns1:PatientId>P456</ns1:PatientId></ns0:findEpisodes>");

        String response = toXml(service.ehrService(request));

        assertXmlEquals(normalise(new EhrMockMapper().findEpisodesResponse(request)), response);
        Element root = rootOf(response);
        assertQualifiedName(MSG_NS, "findEpisodesResponse", root);
        Element episode = qualifiedChild(root, MODEL_NS, "Episode");
        assertEquals("P456", qualifiedChild(episode, MODEL_NS, "PatientId").getTextContent());
    }

    /**
     * Asserts a request root that is not {@code createEpisode}, here {@code ns0:getEpisode}, takes the
     * {@code otherwise} branch (mocks.xml:69) and returns the DW-45 {@code ns0:findEpisodesResponse} whose
     * {@code ns1:PatientId} element exists with empty text.
     *
     * @throws Exception when a document cannot be parsed or serialised
     */
    @Test
    @DisplayName("other root returns findEpisodesResponse with an empty PatientId (mocks.xml:69, DW-45)")
    public void otherRootReturnsFindEpisodesResponse() throws Exception {
        Element request = parse("<ns0:getEpisode xmlns:ns0=\"" + MSG_NS + "\"/>");

        String response = toXml(service.ehrService(request));

        assertXmlEquals(normalise(new EhrMockMapper().findEpisodesResponse(request)), response);
        Element root = rootOf(response);
        assertQualifiedName(MSG_NS, "findEpisodesResponse", root);
        Element episode = qualifiedChild(root, MODEL_NS, "Episode");
        assertEquals("", qualifiedChild(episode, MODEL_NS, "PatientId").getTextContent());
    }

    /**
     * Asserts a {@code null} request, the Body child of an empty SOAP Body, is answered without an exception by the
     * {@code otherwise} branch: the DW-45 {@code ns0:findEpisodesResponse} holding one {@code ns1:Episode} whose
     * {@code ns1:PatientId} is in the model namespace and has no child node once whitespace is ignored.
     *
     * @throws Exception when a document cannot be parsed or serialised
     */
    @Test
    @DisplayName("null request returns findEpisodesResponse with an empty PatientId (DW-45)")
    public void nullRequestReturnsEmptyPatientId() throws Exception {
        Source source = assertDoesNotThrow(() -> service.ehrService(null));

        String response = toXml(source);

        assertXmlEquals(normalise(new EhrMockMapper().findEpisodesResponse(null)), response);
        Element root = rootOf(response);
        assertQualifiedName(MSG_NS, "findEpisodesResponse", root);
        Element episode = qualifiedChild(root, MODEL_NS, "Episode");
        Element patientId = qualifiedChild(episode, MODEL_NS, "PatientId");
        assertQualifiedName(MODEL_NS, "PatientId", patientId);
        assertEquals(List.of(), significantChildren(patientId), "child nodes of ns1:PatientId");
    }

    /**
     * Parses {@code xml} with a namespace-aware parser that rejects document type declarations, does not process
     * XInclude and does not expand entity references.
     *
     * @param xml the document text
     * @return the document element
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parse(String xml) throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /**
     * Serialises {@code source} with a JAXP identity transformer whose external DTD and stylesheet access is empty.
     * A {@link StreamSource} is consumed by this call.
     *
     * @param source the source to serialise
     * @return the serialised document text
     * @throws TransformerException when the source cannot be read or serialised
     */
    private static String toXml(Source source) throws TransformerException {
        assertNotNull(source, "source");
        TransformerFactory factory = TransformerFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        StringWriter xml = new StringWriter();
        factory.newTransformer().transform(source, new StreamResult(xml));
        return xml.toString();
    }

    /**
     * Passes {@code text} through the identity transformer of {@link #toXml(Source)}.
     *
     * @param text the document text, such as a mapper result
     * @return the serialised document text
     * @throws TransformerException when the text cannot be read or serialised
     */
    private static String normalise(String text) throws TransformerException {
        return toXml(new StreamSource(new StringReader(text)));
    }

    /**
     * Asserts that {@code actual} has no difference from {@code expected} beyond whitespace and the differences
     * XMLUnit classifies as similar.
     *
     * @param expected the expected document text
     * @param actual the document text under test
     */
    private static void assertXmlEquals(String expected, String actual) {
        Diff diff = DiffBuilder.compare(expected).withTest(actual).ignoreWhitespace().checkForSimilar().build();
        assertFalse(diff.hasDifferences(), diff.toString());
    }

    /**
     * Parses response text with {@link #parse(String)} and returns its document element.
     *
     * @param xml the response text
     * @return the root element of the response
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element rootOf(String xml) throws ParserConfigurationException, SAXException, IOException {
        return parse(xml);
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
        assertEquals(namespaceUri, element.getNamespaceURI(), "namespace URI of " + element.getNodeName());
        assertEquals(localName, element.getLocalName(), "local name of " + element.getNodeName());
    }

    /**
     * Returns the one child element of {@code parent} with the given namespace URI and local name, asserting that
     * exactly one exists.
     *
     * @param parent the parent element
     * @param namespaceUri the namespace URI of the child
     * @param localName the local name of the child
     * @return the matching child element
     */
    private static Element qualifiedChild(Element parent, String namespaceUri, String localName) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && namespaceUri.equals(element.getNamespaceURI())
                    && localName.equals(element.getLocalName())) {
                matches.add(element);
            }
        }
        assertEquals(1, matches.size(), "children {" + namespaceUri + "}" + localName + " of " + parent.getNodeName());
        return matches.get(0);
    }

    /**
     * Returns the one child element of {@code parent} with the given local name in any namespace, asserting that
     * exactly one exists.
     *
     * @param parent the parent element
     * @param localName the local name of the child
     * @return the matching child element
     */
    private static Element childByLocalName(Element parent, String localName) {
        List<Element> matches = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && localName.equals(element.getLocalName())) {
                matches.add(element);
            }
        }
        assertEquals(1, matches.size(), "children " + localName + " of " + parent.getNodeName());
        return matches.get(0);
    }

    /**
     * Returns the child nodes of {@code parent} other than text nodes that hold only whitespace.
     *
     * @param parent the parent node
     * @return its remaining child nodes in document order
     */
    private static List<Node> significantChildren(Node parent) {
        List<Node> children = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() != Node.TEXT_NODE || !child.getNodeValue().isBlank()) {
                children.add(child);
            }
        }
        return children;
    }
}

