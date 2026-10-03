package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Unit tests of the three {@link AdmissionMapper} methods, the hand re-implementations (D-034) of the
 * DataWeave 1.0 {@code set-payload} transforms of
 * {@code xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml}:
 *
 * <ul>
 *   <li>DW-39 (:26-46): {@link AdmissionMapper#toAdmitSubjectResponse(Element)}, {@code createEpisodeResponse}
 *       to {@code admitSubjectResponse} with {@code startDate}, {@code endDate} + {@code P5D} and the
 *       {@code Bill } element with its trailing space and {@code initialStateEstimate} (D-029, D-043);</li>
 *   <li>DW-40 (:53-61): {@link AdmissionMapper#toUpsertPatient(Element)}, the {@code admitSubject}
 *       {@code Subject} to {@code upsertPatient};</li>
 *   <li>DW-41 (:73-80): {@link AdmissionMapper#toCreateEpisode(Element)}, {@code upsertPatientResponse}
 *       {@code ns1:PatientId} to {@code createEpisode}.</li>
 * </ul>
 *
 * <p>Each test calls the mapper directly, with no Spring application context, on a parsed DOM element or
 * {@code null}. It compares the returned text with a whole expected document using {@code assertEquals}, or
 * asserts the parsed output or the exception thrown. The expected documents carry the layout the mapper
 * specifies (D-156): the declaration line, {@code \n} between lines, two spaces of indentation per depth, no
 * newline after the root end tag, {@code xmlns:ns0} on the root and {@code xmlns:ns1} on each {@code ns1} child
 * of the root, and the {@code Bill } start tag
 * {@code <ns1:Bill  xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">} with the end tag
 * {@code </ns1:Bill >} (D-393). The tests run every public method and every conditional branch of the mapper
 * (D-049).
 *
 * <p>The class and its test methods are public (D-133). The {@code test} profile annotation starts no
 * application context.
 */
@ActiveProfiles("test")
public class AdmissionMapperTest {

    /** Namespace of the {@code ns0} message elements. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace of the {@code ns1} model elements. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Namespace of the SOAP 1.1 envelope of {@code original/message.xml}. */
    private static final String SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/";

    /** First line of every document the mapper returns. */
    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** Feature that makes the parser reject any document type declaration. */
    private static final String DISALLOW_DOCTYPE_DECL = "http://apache.org/xml/features/disallow-doctype-decl";

    /** The mapper under test. */
    private final AdmissionMapper mapper = new AdmissionMapper();

    /**
     * Asserts DW-39 on an Episode with both dates in the EHR mock date-time form: {@code startDate} is the
     * calendar date of its text, {@code endDate} that date plus five days, and the {@code Bill } element holds
     * the four literals, written with the start tag {@code <ns1:Bill  xmlns:ns1="…">} and the end tag
     * {@code </ns1:Bill >} (D-029, D-043, D-393).
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse writes Episode with startDate, endDate + P5D and ns1:Bill (D-029, D-043)")
    public void toAdmitSubjectResponseWritesEpisodeWithBothDatesAndBill() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(
                parse(episodeInput("2015-03-01T10:15:30.123Z", "2015-03-01T10:15:30.123Z")));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>2015-03-01</startDate>\n"
                + "    <endDate>2015-03-06</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
        assertTrue(actual.contains("<ns1:Bill "), "start tag prefix <ns1:Bill  (D-393)");
        assertTrue(actual.contains("</ns1:Bill >"), "end tag </ns1:Bill >");
    }

    /**
     * Asserts DW-39 on an Episode without {@code startDate} and {@code endDate} writes neither element and keeps
     * every other line.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse omits startDate and endDate when both are absent")
    public void toAdmitSubjectResponseOmitsBothDatesWhenAbsent() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(parse(episodeInput(null, null)));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
    }

    /**
     * Asserts DW-39 on an Episode with {@code startDate} and without {@code endDate} writes only
     * {@code <startDate>2015-03-01</startDate>}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse writes only startDate when endDate is absent")
    public void toAdmitSubjectResponseWritesOnlyStartDateWhenEndDateAbsent() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(parse(episodeInput("2015-03-01T10:15:30.123Z", null)));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>2015-03-01</startDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
    }

    /**
     * Asserts DW-39 on an Episode with {@code endDate} and without {@code startDate} writes only
     * {@code <endDate>2015-03-06</endDate>}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse writes only endDate + P5D when startDate is absent")
    public void toAdmitSubjectResponseWritesOnlyEndDateWhenStartDateAbsent() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(parse(episodeInput(null, "2015-03-01T10:15:30.123Z")));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <endDate>2015-03-06</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
    }

    /**
     * Asserts DW-39 adds the five days of {@code P5D} across the end of February 2015: an {@code endDate} on
     * 2015-02-27 is written as {@code 2015-03-04}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse adds P5D to endDate across the end of February")
    public void toAdmitSubjectResponseAddsFiveDaysAcrossMonthEnd() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(
                parse(episodeInput("2015-02-27T10:15:30.123Z", "2015-02-27T10:15:30.123Z")));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>2015-02-27</startDate>\n"
                + "    <endDate>2015-03-04</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
    }

    /**
     * Asserts the DW-39 output parses as a namespace-aware document whose root is {@code admitSubjectResponse}
     * in {@code NS0}, whose second element child is {@code Bill} in {@code NS1}, and whose Bill holds the
     * unqualified {@code costPerNight} {@code 100}, {@code initialStateEstimate} {@code 5}, {@code runningTotal}
     * {@code 500} and {@code status} {@code ADMITTED}, in that order (D-029, D-043).
     *
     * @throws Exception when the input or the output cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse output parses with Bill in the model namespace (D-029, D-043)")
    public void toAdmitSubjectResponseBillParsesAsBillInModelNamespace() throws Exception {
        Element root = parse(mapper.toAdmitSubjectResponse(
                parse(episodeInput("2015-03-01T10:15:30.123Z", "2015-03-01T10:15:30.123Z"))));

        assertEquals(NS0, root.getNamespaceURI());
        assertEquals("admitSubjectResponse", root.getLocalName());
        List<Element> children = elementChildren(root);
        assertEquals(2, children.size());
        assertEquals(NS1, children.get(0).getNamespaceURI());
        assertEquals("Episode", children.get(0).getLocalName());
        Element bill = children.get(1);
        assertEquals("Bill", bill.getLocalName());
        assertEquals(NS1, bill.getNamespaceURI());
        assertEquals("ns1:Bill", bill.getTagName());
        List<String> fields = new ArrayList<>();
        for (Element field : elementChildren(bill)) {
            assertNull(field.getNamespaceURI(), "Bill child " + field.getLocalName() + " is unqualified");
            fields.add(field.getLocalName() + "=" + field.getTextContent());
        }
        assertEquals(List.of("costPerNight=100", "initialStateEstimate=5", "runningTotal=500", "status=ADMITTED"),
                fields);
    }

    /**
     * Asserts DW-39 reads plain {@code yyyy-MM-dd} date texts as well: {@code 2015-03-01} gives
     * {@code <startDate>2015-03-01</startDate>} and {@code <endDate>2015-03-06</endDate>}.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse reads plain yyyy-MM-dd startDate and endDate texts")
    public void toAdmitSubjectResponseAcceptsPlainDates() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(parse(episodeInput("2015-03-01", "2015-03-01")));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>2015-03-01</startDate>\n"
                + "    <endDate>2015-03-06</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
    }

    /**
     * Asserts DW-39 writes an Episode of self-closing {@code episodeId}, {@code ns1:PatientId}, {@code admission}
     * and {@code care}, no dates, and the unchanged Bill for a {@code null} input, a root whose local name is
     * not {@code createEpisodeResponse} and a {@code createEpisodeResponse} without {@code Episode} (D-156).
     *
     * @throws Exception when an input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse writes self-closing Episode values when no Episode is selected")
    public void toAdmitSubjectResponseWritesEmptyEpisodeWhenEpisodeMissing() throws Exception {
        String expected = DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId/>\n"
                + "    <ns1:PatientId/>\n"
                + "    <admission/>\n"
                + "    <care/>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>";
        Element otherRoot = parse("<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Episode><episodeId>E1</episodeId><startDate>2015-03-01</startDate></ns1:Episode>"
                + "</ns0:findEpisodesResponse>");
        Element withoutEpisode = parse("<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">"
                + "<episodeId>E1</episodeId></ns0:createEpisodeResponse>");

        assertEquals(expected, mapper.toAdmitSubjectResponse(null));
        assertEquals(expected, mapper.toAdmitSubjectResponse(otherRoot));
        assertEquals(expected, mapper.toAdmitSubjectResponse(withoutEpisode));
    }

    /**
     * Asserts DW-39 writes an Episode child that is present with empty text as a start and an end tag
     * ({@code <episodeId></episodeId>}) and each absent child as a self-closing element (D-156).
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse writes empty values as tag pairs and absent values self-closing")
    public void toAdmitSubjectResponseWritesEmptyAndAbsentEpisodeValues() throws Exception {
        Element input = parse("<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Episode><episodeId></episodeId><ns1:PatientId></ns1:PatientId></ns1:Episode>"
                + "</ns0:createEpisodeResponse>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId></episodeId>\n"
                + "    <ns1:PatientId></ns1:PatientId>\n"
                + "    <admission/>\n"
                + "    <care/>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", mapper.toAdmitSubjectResponse(input));
    }

    /**
     * Asserts DW-39 throws {@link DateTimeParseException} for a present {@code startDate} or {@code endDate}
     * whose text does not begin with an ISO {@code yyyy-MM-dd} date: a day-first date, a date without leading
     * zeros and the empty text.
     *
     * @throws Exception when an input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse rejects startDate and endDate texts without an ISO date")
    public void toAdmitSubjectResponseRejectsDateTextWithoutIsoDate() throws Exception {
        Element dayFirstStart = parse(episodeInput("01/03/2015T10:15:30.123Z", null));
        Element shortEnd = parse(episodeInput(null, "2015-3-1"));
        Element emptyEnd = parse(episodeInput(null, ""));

        assertThrows(DateTimeParseException.class, () -> mapper.toAdmitSubjectResponse(dayFirstStart));
        assertThrows(DateTimeParseException.class, () -> mapper.toAdmitSubjectResponse(shortEnd));
        assertThrows(DateTimeParseException.class, () -> mapper.toAdmitSubjectResponse(emptyEnd));
    }

    /**
     * Asserts DW-39 writes {@code &}, {@code <} and {@code >} of a value as {@code &amp;}, {@code &lt;} and
     * {@code &gt;} and writes quotes and apostrophes unchanged.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse escapes ampersand and angle brackets in values")
    public void toAdmitSubjectResponseEscapesMarkupCharacters() throws Exception {
        Element input = parse("<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Episode><episodeId>E&quot;1&apos;</episodeId><ns1:PatientId>P123</ns1:PatientId>"
                + "<admission>Elective</admission><care>Private &amp; &lt;Ward&gt;</care></ns1:Episode>"
                + "</ns0:createEpisodeResponse>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E\"1'</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <care>Private &amp; &lt;Ward&gt;</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", mapper.toAdmitSubjectResponse(input));
    }

    /**
     * Asserts DW-39 selects elements by their node name without prefix in a DOM built without namespace
     * awareness, giving the document of the namespace-aware input.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-39 toAdmitSubjectResponse selects by unprefixed node name in a DOM without namespaces")
    public void toAdmitSubjectResponseSelectsByNodeNameWithoutNamespaces() throws Exception {
        String actual = mapper.toAdmitSubjectResponse(
                parseWithoutNamespaces(episodeInput("2015-03-01T10:15:30.123Z", "2015-03-01T10:15:30.123Z")));

        assertEquals(DECLARATION + "\n"
                + "<ns0:admitSubjectResponse xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Episode xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <episodeId>E1</episodeId>\n"
                + "    <ns1:PatientId>P123</ns1:PatientId>\n"
                + "    <admission>Elective</admission>\n"
                + "    <startDate>2015-03-01</startDate>\n"
                + "    <endDate>2015-03-06</endDate>\n"
                + "    <care>Private</care>\n"
                + "  </ns1:Episode>\n"
                + "  <ns1:Bill  xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <costPerNight>100</costPerNight>\n"
                + "    <initialStateEstimate>5</initialStateEstimate>\n"
                + "    <runningTotal>500</runningTotal>\n"
                + "    <status>ADMITTED</status>\n"
                + "  </ns1:Bill >\n"
                + "</ns0:admitSubjectResponse>", actual);
    }

    /**
     * Asserts DW-40 copies the {@code ns1:Subject} of the {@code admitSubject} of {@code original/message.xml}
     * into {@code ns0:upsertPatient}: its nine children in document order, unqualified, with the empty
     * {@code address2} and {@code address3} written as start and end tags; {@code Referer} and {@code Referral}
     * are not copied (D-156).
     *
     * @throws Exception when the classpath resource cannot be read or parsed
     */
    @Test
    @DisplayName("DW-40 toUpsertPatient copies the admitSubject Subject of original/message.xml")
    public void toUpsertPatientCopiesSubjectOfOriginalMessage() throws Exception {
        String actual = mapper.toUpsertPatient(admitSubjectOfOriginalMessage());

        assertEquals(DECLARATION + "\n"
                + "<ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Subject xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <nationalId>1234</nationalId>\n"
                + "    <firstName>Nial</firstName>\n"
                + "    <lastName>Darbey</lastName>\n"
                + "    <address1>Buenos Aires</address1>\n"
                + "    <address2></address2>\n"
                + "    <address3></address3>\n"
                + "    <nationality>Irish</nationality>\n"
                + "    <gender>Male</gender>\n"
                + "    <dateOfBirth>1970-08-07</dateOfBirth>\n"
                + "  </ns1:Subject>\n"
                + "</ns0:upsertPatient>", actual);
    }

    /**
     * Asserts DW-40 writes the self-closing {@code <ns1:Subject xmlns:ns1="…"/>} for a {@code null} input, a root
     * whose local name is not {@code admitSubject} and an {@code admitSubject} without {@code Subject} (D-156).
     *
     * @throws Exception when an input cannot be parsed
     */
    @Test
    @DisplayName("DW-40 toUpsertPatient writes a self-closing Subject when no Subject is selected")
    public void toUpsertPatientWritesEmptySubjectWhenMissing() throws Exception {
        String expected = DECLARATION + "\n"
                + "<ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Subject xmlns:ns1=\"" + NS1 + "\"/>\n"
                + "</ns0:upsertPatient>";
        Element otherRoot = parse("<ns0:getPatient xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Subject><nationalId>1234</nationalId></ns1:Subject></ns0:getPatient>");
        Element withoutSubject = parse("<ns0:admitSubject xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Referer><clientId>1234</clientId></ns1:Referer></ns0:admitSubject>");

        assertEquals(expected, mapper.toUpsertPatient(null));
        assertEquals(expected, mapper.toUpsertPatient(otherRoot));
        assertEquals(expected, mapper.toUpsertPatient(withoutSubject));
    }

    /**
     * Asserts DW-40 copies nested Subject children recursively, one depth deeper per level, each under its local
     * name without prefix, namespace declaration or attribute, and drops the text nodes of an element that has
     * child elements.
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-40 toUpsertPatient copies nested Subject children unqualified and without attributes")
    public void toUpsertPatientCopiesNestedChildrenUnqualified() throws Exception {
        Element input = parse("<ns0:admitSubject xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Subject>mixed text<nationalId type=\"passport\">1234</nationalId>"
                + "<ns1:address><line>Calle 1 &amp; 2</line><line></line></ns1:address>"
                + "<other:gender xmlns:other=\"urn:example:other\">Male</other:gender></ns1:Subject>"
                + "</ns0:admitSubject>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Subject xmlns:ns1=\"" + NS1 + "\">\n"
                + "    <nationalId>1234</nationalId>\n"
                + "    <address>\n"
                + "      <line>Calle 1 &amp; 2</line>\n"
                + "      <line></line>\n"
                + "    </address>\n"
                + "    <gender>Male</gender>\n"
                + "  </ns1:Subject>\n"
                + "</ns0:upsertPatient>", mapper.toUpsertPatient(input));
    }

    /**
     * Asserts DW-40 writes a Subject without child elements on one line holding its text: {@code Nial} for a
     * text-only Subject and a start and an end tag for an empty Subject.
     *
     * @throws Exception when an input cannot be parsed
     */
    @Test
    @DisplayName("DW-40 toUpsertPatient writes the text of a Subject without child elements on one line")
    public void toUpsertPatientWritesTextOfSubjectWithoutChildElements() throws Exception {
        Element textOnly = parse("<ns0:admitSubject xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Subject>Nial</ns1:Subject></ns0:admitSubject>");
        Element empty = parse("<ns0:admitSubject xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Subject/></ns0:admitSubject>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Subject xmlns:ns1=\"" + NS1 + "\">Nial</ns1:Subject>\n"
                + "</ns0:upsertPatient>", mapper.toUpsertPatient(textOnly));
        assertEquals(DECLARATION + "\n"
                + "<ns0:upsertPatient xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:Subject xmlns:ns1=\"" + NS1 + "\"></ns1:Subject>\n"
                + "</ns0:upsertPatient>", mapper.toUpsertPatient(empty));
    }

    /**
     * Asserts DW-41 copies the {@code ns1:PatientId} of {@code upsertPatientResponse} into
     * {@code ns0:createEpisode}, its {@code ns1:PatientId} declaring {@code xmlns:ns1} (D-156, D-393).
     *
     * @throws Exception when the input cannot be parsed
     */
    @Test
    @DisplayName("DW-41 toCreateEpisode copies upsertPatientResponse/ns1:PatientId")
    public void toCreateEpisodeCopiesPatientId() throws Exception {
        Element input = parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:upsertPatientResponse>");

        assertEquals(DECLARATION + "\n"
                + "<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:PatientId xmlns:ns1=\"" + NS1 + "\">P123</ns1:PatientId>\n"
                + "</ns0:createEpisode>", mapper.toCreateEpisode(input));
    }

    /**
     * Asserts DW-41 writes the self-closing {@code <ns1:PatientId xmlns:ns1="…"/>} for a {@code null} input, a
     * root whose local name is not {@code upsertPatientResponse}, a response without {@code PatientId}, an
     * unqualified {@code PatientId} and a DOM built without namespace awareness (D-156).
     *
     * @throws Exception when an input cannot be parsed
     */
    @Test
    @DisplayName("DW-41 toCreateEpisode writes a self-closing ns1:PatientId when no ns1:PatientId is selected")
    public void toCreateEpisodeWritesEmptyPatientIdWhenMissing() throws Exception {
        String expected = DECLARATION + "\n"
                + "<ns0:createEpisode xmlns:ns0=\"" + NS0 + "\">\n"
                + "  <ns1:PatientId xmlns:ns1=\"" + NS1 + "\"/>\n"
                + "</ns0:createEpisode>";
        String qualified = "<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:upsertPatientResponse>";
        Element otherRoot = parse("<ns0:getPatientResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:PatientId>P123</ns1:PatientId></ns0:getPatientResponse>");
        Element withoutPatientId = parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\"/>");
        Element unqualified = parse("<ns0:upsertPatientResponse xmlns:ns0=\"" + NS0 + "\">"
                + "<PatientId>P123</PatientId></ns0:upsertPatientResponse>");

        assertEquals(expected, mapper.toCreateEpisode(null));
        assertEquals(expected, mapper.toCreateEpisode(otherRoot));
        assertEquals(expected, mapper.toCreateEpisode(withoutPatientId));
        assertEquals(expected, mapper.toCreateEpisode(unqualified));
        assertEquals(expected, mapper.toCreateEpisode(parseWithoutNamespaces(qualified)));
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
        return documentBuilder(true).parse(new InputSource(new StringReader(xml))).getDocumentElement();
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
        return documentBuilder(false).parse(new InputSource(new StringReader(xml))).getDocumentElement();
    }

    /**
     * Reads classpath {@code /original/message.xml} with the namespace-aware parser and returns the first element
     * child of its SOAP {@code Body}, asserted to be {@code admitSubject} in {@code NS0}.
     *
     * @return the {@code ns:admitSubject} element of the original request
     * @throws ParserConfigurationException when the parser cannot be configured
     * @throws SAXException when the resource is not well-formed
     * @throws IOException when the resource cannot be read
     */
    private static Element admitSubjectOfOriginalMessage()
            throws ParserConfigurationException, SAXException, IOException {
        try (InputStream message = AdmissionMapperTest.class.getResourceAsStream("/original/message.xml")) {
            assertNotNull(message, "classpath resource /original/message.xml");
            Element envelope = documentBuilder(true).parse(message).getDocumentElement();
            Element body = (Element) envelope.getElementsByTagNameNS(SOAP_ENV, "Body").item(0);
            assertNotNull(body, "SOAP Body of /original/message.xml");
            List<Element> bodyChildren = elementChildren(body);
            assertFalse(bodyChildren.isEmpty(), "element child of the SOAP Body");
            Element admitSubject = bodyChildren.get(0);
            assertEquals(NS0, admitSubject.getNamespaceURI());
            assertEquals("admitSubject", admitSubject.getLocalName());
            return admitSubject;
        }
    }

    /**
     * Builds a {@code ns0:createEpisodeResponse/ns1:Episode} input with {@code episodeId} {@code E1},
     * {@code ns1:PatientId} {@code P123}, {@code admission} {@code Elective} and {@code care} {@code Private}.
     *
     * @param startDate the {@code startDate} text, or {@code null} to omit the element
     * @param endDate the {@code endDate} text, or {@code null} to omit the element
     * @return the input document text
     */
    private static String episodeInput(String startDate, String endDate) {
        return "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\" xmlns:ns1=\"" + NS1 + "\">"
                + "<ns1:Episode>"
                + "<episodeId>E1</episodeId>"
                + "<ns1:PatientId>P123</ns1:PatientId>"
                + "<admission>Elective</admission>"
                + (startDate == null ? "" : "<startDate>" + startDate + "</startDate>")
                + (endDate == null ? "" : "<endDate>" + endDate + "</endDate>")
                + "<care>Private</care>"
                + "</ns1:Episode>"
                + "</ns0:createEpisodeResponse>";
    }

    /**
     * Creates a {@link DocumentBuilder} from {@link DocumentBuilderFactory#newInstance()} with the given
     * namespace awareness and {@code disallow-doctype-decl} enabled.
     *
     * @param namespaceAware whether the parser is namespace aware
     * @return a new document builder
     * @throws ParserConfigurationException when the parser cannot be configured
     */
    private static DocumentBuilder documentBuilder(boolean namespaceAware) throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(namespaceAware);
        factory.setFeature(DISALLOW_DOCTYPE_DECL, true);
        return factory.newDocumentBuilder();
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

