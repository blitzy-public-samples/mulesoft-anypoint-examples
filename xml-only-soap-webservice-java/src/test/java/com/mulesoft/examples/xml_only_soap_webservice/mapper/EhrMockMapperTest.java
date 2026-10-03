package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.StringReader;
import java.time.OffsetDateTime;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Unit tests of the two {@link EhrMockMapper} methods, the hand re-implementations (D-034) of the DataWeave 1.0
 * {@code set-payload} transforms of the {@code EHRService} mock flow of
 * {@code xml-only-soap-webservice/src/main/app/mocks.xml}:
 *
 * <ul>
 *   <li>DW-44 (:52-66): {@link EhrMockMapper#createEpisodeResponse(Element, OffsetDateTime)}, the
 *       {@code createEpisode} branch;</li>
 *   <li>DW-45 (:71-80): {@link EhrMockMapper#findEpisodesResponse(Element)}, the {@code otherwise} branch.</li>
 * </ul>
 *
 * <p>Each test calls the mapper directly, with no Spring application context, on a parsed DOM element or
 * {@code null}. It compares the returned text with a whole expected document using {@code assertEquals}, or
 * asserts the exception thrown. The expected documents carry the layout the mapper specifies (D-165, D-401): the
 * declaration line, {@code \n} between lines, two spaces of indentation per depth, no newline after the root end
 * tag, {@code xmlns:ns0} on the root and {@code xmlns:ns1} on {@code ns1:Episode} only, and {@code now} written
 * as {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX} in its own offset. The selector
 * {@code payload.ns0#<operation>.ns1#PatientId} gives {@code <ns1:PatientId/>} when nothing is selected and
 * {@code <ns1:PatientId></ns1:PatientId>} for empty text.
 *
 * <p>The class and its test methods are public (D-133). The {@code test} profile annotation starts no
 * application context.
 */
@ActiveProfiles("test")
public class EhrMockMapperTest {

    /** The {@code now} value passed to {@code createEpisodeResponse}. */
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2015-03-01T10:15:30.123Z");

    /** The text the mapper writes for {@link #NOW}. */
    private static final String NOW_TEXT = "2015-03-01T10:15:30.123Z";

    /** Namespace of the {@code ns0} message elements. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** First line of every document the mapper returns. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** A {@code now} value with a non-zero offset and digits below the millisecond. */
    private static final OffsetDateTime NOW_PLUS_TWO = OffsetDateTime.parse("2026-10-01T13:55:31.190123+02:00");

    /** The text the mapper writes for {@link #NOW_PLUS_TWO}. */
    private static final String NOW_PLUS_TWO_TEXT = "2026-10-01T13:55:31.190+02:00";

    /** The mapper under test. */
    private final EhrMockMapper mapper = new EhrMockMapper();

    /**
     * Asserts DW-44 echoes the {@code ns1:PatientId} of {@code ns0:createEpisode} and writes {@code now} as
     * {@code episodeId}, {@code startDate} and {@code endDate}, {@code Elective} and {@code Private}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse echoes ns1:PatientId and writes now, Elective, Private")
    public void createEpisodeResponseEchoesPatientIdAndWritesNow() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} when the only {@code PatientId} child is in the namespace
     * {@code urn:other}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> for a PatientId in namespace urn:other")
    public void createEpisodeResponseWritesEmptyPatientIdForWrongNamespace() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\">"
                + "<PatientId xmlns=\"urn:other\">P123</PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} when {@code ns0:createEpisode} has no {@code PatientId}
     * child.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> when createEpisode has no PatientId")
    public void createEpisodeResponseWritesEmptyPatientIdWhenMissing() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\"/>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} for the root {@code ns0:findEpisodes}, whose qualified
     * {@code ns1:PatientId} is not read.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> for the root ns0:findEpisodes")
    public void createEpisodeResponseWritesEmptyPatientIdForOtherRoot() throws Exception {
        Element input = parse("<ns0:findEpisodes xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:findEpisodes>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} for a {@code null} request.
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> for a null request")
    public void createEpisodeResponseWritesEmptyPatientIdForNullRequest() {
        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(null, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} for a root with the local name {@code createEpisode} in the
     * namespace {@code urn:other}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> for a createEpisode root in namespace urn:other")
    public void createEpisodeResponseWritesEmptyPatientIdForRootInOtherNamespace() throws Exception {
        Element input = parse("<other:createEpisode xmlns:other=\"urn:other\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></other:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} when the only {@code PatientId} child has no namespace.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> for an unqualified PatientId")
    public void createEpisodeResponseWritesEmptyPatientIdForUnqualifiedPatientId() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\">"
                + "<PatientId>P123</PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code <ns1:PatientId/>} for a qualified request parsed without namespace awareness,
     * whose elements carry no namespace URI or local name.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId/> for a DOM built without namespaces")
    public void createEpisodeResponseWritesEmptyPatientIdForDomWithoutNamespaces() throws Exception {
        Element input = parseWithoutNamespaces("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1
                + "\"><ns1:PatientId>P123</ns1:PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes a selected {@code ns1:PatientId} with empty text as
     * {@code <ns1:PatientId></ns1:PatientId>}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes <ns1:PatientId></ns1:PatientId> for an empty PatientId")
    public void createEpisodeResponseWritesStartAndEndTagForEmptyPatientId() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId/></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId></ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code &}, {@code <} and {@code >} of the {@code PatientId} text as {@code &amp;},
     * {@code &lt;} and {@code &gt;} and writes quotes and apostrophes unchanged.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse escapes ampersand and angle brackets in PatientId")
    public void createEpisodeResponseEscapesMarkupCharactersInPatientId() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P&amp;1 &lt;2&gt; &quot;3&apos;</ns1:PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId>P&amp;1 &lt;2&gt; \"3'</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 reads the first direct {@code ns1:PatientId} child of {@code ns0:createEpisode} and writes
     * its text untrimmed. A comment, text, a {@code PatientId} in another namespace, another {@code ns1} element,
     * a nested {@code ns1:PatientId} and a later {@code ns1:PatientId} are not selected.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes the untrimmed text of the first direct ns1:PatientId child")
    public void createEpisodeResponseSelectsFirstDirectQualifiedPatientIdUntrimmed() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\""
                + " xmlns:other=\"urn:other\">\n"
                + "  <!-- comment -->text"
                + "<other:PatientId>X1</other:PatientId>"
                + "<ns1:Wrapper><ns1:PatientId>N1</ns1:PatientId></ns1:Wrapper>"
                + "<ns1:EpisodeId>E1</ns1:EpisodeId>"
                + "<ns1:PatientId> P123 </ns1:PatientId>"
                + "<ns1:PatientId>P456</ns1:PatientId>"
                + "</ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId> P123 </ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW));
    }

    /**
     * Asserts DW-44 writes {@code now} as {@code episodeId}, {@code startDate} and {@code endDate} in its own
     * offset {@code +02:00} and drops the digits below the millisecond.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse writes now in its own offset with milliseconds")
    public void createEpisodeResponseWritesNowInItsOwnOffset() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>" + NOW_PLUS_TWO_TEXT + "</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>" + NOW_PLUS_TWO_TEXT + "</startDate>\n"
                + "    <endDate>" + NOW_PLUS_TWO_TEXT + "</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:createEpisodeResponse>", mapper.createEpisodeResponse(input, NOW_PLUS_TWO));
    }

    /**
     * Asserts DW-44 throws {@link NullPointerException} with the message {@code now} for a {@code null}
     * {@code now}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-44 createEpisodeResponse rejects a null now with NullPointerException")
    public void createEpisodeResponseRejectsNullNow() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:createEpisode>");

        NullPointerException thrown = assertThrows(NullPointerException.class,
                () -> mapper.createEpisodeResponse(input, null));

        assertEquals("now", thrown.getMessage());
    }

    /**
     * Asserts DW-45 echoes the {@code ns1:PatientId} of {@code ns0:findEpisodes} as the only child of
     * {@code ns1:Episode}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-45 findEpisodesResponse echoes ns0:findEpisodes/ns1:PatientId")
    public void findEpisodesResponseEchoesPatientId() throws Exception {
        Element input = parse("<ns0:findEpisodes xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:findEpisodes>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>", mapper.findEpisodesResponse(input));
    }

    /**
     * Asserts DW-45 writes {@code <ns1:PatientId/>} when the only {@code PatientId} child is in the namespace
     * {@code urn:other}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-45 findEpisodesResponse writes <ns1:PatientId/> for a PatientId in namespace urn:other")
    public void findEpisodesResponseWritesEmptyPatientIdForWrongNamespace() throws Exception {
        Element input = parse("<ns0:findEpisodes xmlns:ns0=\"" + NS0 + "\">"
                + "<PatientId xmlns=\"urn:other\">P123</PatientId></ns0:findEpisodes>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <ns1:PatientId/>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>", mapper.findEpisodesResponse(input));
    }

    /**
     * Asserts DW-45 writes {@code <ns1:PatientId/>} when {@code ns0:findEpisodes} has no {@code PatientId}
     * child.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-45 findEpisodesResponse writes <ns1:PatientId/> when findEpisodes has no PatientId")
    public void findEpisodesResponseWritesEmptyPatientIdWhenMissing() throws Exception {
        Element input = parse("<ns0:findEpisodes xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\"/>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <ns1:PatientId/>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>", mapper.findEpisodesResponse(input));
    }

    /**
     * Asserts DW-45 writes {@code <ns1:PatientId/>} for the root {@code ns0:createEpisode}, whose qualified
     * {@code ns1:PatientId} is not read.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-45 findEpisodesResponse writes <ns1:PatientId/> for the root ns0:createEpisode")
    public void findEpisodesResponseWritesEmptyPatientIdForOtherRoot() throws Exception {
        Element input = parse("<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:createEpisode>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <ns1:PatientId/>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>", mapper.findEpisodesResponse(input));
    }

    /**
     * Asserts DW-45 writes {@code <ns1:PatientId/>} for a {@code null} request.
     */
    @Test
    @DisplayName("DW-45 findEpisodesResponse writes <ns1:PatientId/> for a null request")
    public void findEpisodesResponseWritesEmptyPatientIdForNullRequest() {
        assertEquals(DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <ns1:PatientId/>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>", mapper.findEpisodesResponse(null));
    }

    /**
     * Asserts DW-45 writes a selected {@code ns1:PatientId} with empty text as
     * {@code <ns1:PatientId></ns1:PatientId>}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-45 findEpisodesResponse writes <ns1:PatientId></ns1:PatientId> for an empty PatientId")
    public void findEpisodesResponseWritesStartAndEndTagForEmptyPatientId() throws Exception {
        Element input = parse("<ns0:findEpisodes xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId></ns1:PatientId></ns0:findEpisodes>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <ns1:PatientId></ns1:PatientId>\n"
                + "  </ns1:Episode>\n"
                + "</ns0:findEpisodesResponse>", mapper.findEpisodesResponse(input));
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
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /**
     * Parses {@code xml} with a parser without namespace awareness that rejects document type declarations.
     *
     * @param xml the document text
     * @return the document element, whose nodes have no namespace URI and no local name
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the text is not well-formed or declares a document type
     * @throws IOException when the text cannot be read
     */
    private static Element parseWithoutNamespaces(String xml)
            throws ParserConfigurationException, SAXException, IOException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }
}
