package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Writes the XML text of the two DataWeave 1.0 transforms of the Mule mock flow {@code PatientService}
 * [xml-only-soap-webservice/src/main/app/mocks.xml:4-44], re-implemented by hand (D-034):
 *
 * <ul>
 *   <li>{@link #upsertPatientResponse(OffsetDateTime)}: DW-42 [mocks.xml:12-19], the {@code upsertPatient}
 *       branch of the flow's {@code choice} [mocks.xml:10];</li>
 *   <li>{@link #getPatientResponse(OffsetDateTime)}: DW-43 [mocks.xml:24-40], its {@code otherwise} branch
 *       [mocks.xml:22].</li>
 * </ul>
 *
 * <p>Both methods write the DW 1.0 XML writer layout:
 *
 * <ul>
 *   <li>the first line is {@code <?xml version='1.0' encoding='UTF-8'?>};</li>
 *   <li>one element per line, indented with two spaces per depth below the root;</li>
 *   <li>lines are joined with {@code \n}, and no newline follows the root end tag;</li>
 *   <li>the root element declares {@code xmlns:ns0="http://www.mule-health.com/SOA/message/1.0"}, and the
 *       one {@code ns1} element below it declares {@code xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"};
 *       the children of {@code ns1:Patient} are unqualified and carry no namespace declaration;</li>
 *   <li>element text is written with {@code &} as {@code &amp;}, {@code <} as {@code &lt;} and {@code >} as
 *       {@code &gt;}; every other character is written unchanged.</li>
 * </ul>
 *
 * <p>The script expression {@code now as :string} is written as the given {@link OffsetDateTime} in its own
 * offset with the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX}: offset {@code +02:00} gives
 * {@code 2026-10-01T13:55:31.190+02:00}, and offset zero gives {@code 2026-10-01T11:55:31.190Z}. Digits below
 * the millisecond are not written.
 *
 * <p>Instances hold no state. Both methods are side-effect free and safe for concurrent use.
 */
public final class PatientMockMapper {

    /** The first output line of both documents. */
    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";

    /** The namespace bound to the {@code ns0} prefix by both scripts [mocks.xml:13,25]. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** The namespace bound to the {@code ns1} prefix by both scripts [mocks.xml:14,26]. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    private static final String NS0_PREFIX = "ns0";
    private static final String NS1_PREFIX = "ns1";

    private static final String NEWLINE = "\n";
    private static final String INDENT = "  ";

    /** The rendering of {@code now as :string}, applied in the value's own offset. */
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private static final String UPSERT_PATIENT_RESPONSE = "ns0:upsertPatientResponse";
    private static final String GET_PATIENT_RESPONSE = "ns0:getPatientResponse";
    private static final String PATIENT_ID = "ns1:PatientId";
    private static final String PATIENT = "ns1:Patient";

    /** The DW-43 literal {@code |1930-01-01|}, a DW date, written in its ISO local date form [mocks.xml:32]. */
    private static final String DATE_OF_BIRTH = "1930-01-01";
    private static final String GENDER = "Male";
    private static final String NATIONALITY = "USA";
    private static final String ADDRESS1 = "DisneyLand";
    private static final String LAST_NAME = "Duck";
    private static final String FIRST_NAME = "Donald";

    /** Initial buffer sizes, each above the length of the document it holds. */
    private static final int UPSERT_PATIENT_RESPONSE_CAPACITY = 320;
    private static final int GET_PATIENT_RESPONSE_CAPACITY = 640;

    /** Creates a mapper; instances hold no state. */
    public PatientMockMapper() {
        // Stateless: every member is a static constant or a local of the calling method.
    }

    /**
     * Writes the DW-42 document [mocks.xml:12-19], script
     * {@code ns0#"upsertPatientResponse": { ns1#"PatientId": now as :string }}: the root
     * {@code ns0:upsertPatientResponse} holding one {@code ns1:PatientId} element whose text is {@code now}
     * in the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX} (D-034).
     *
     * <p>For {@code now} = {@code 2026-10-01T13:55:31.190+02:00} the result is these four lines, joined with
     * {@code \n} and without a trailing newline:
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:upsertPatientResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:PatientId xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">2026-10-01T13:55:31.190+02:00</ns1:PatientId>
     * </ns0:upsertPatientResponse>
     * }</pre>
     *
     * @param now the instant the script's {@code now} evaluates to, written in its own offset
     * @return the XML text of the {@code upsertPatientResponse} document
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public String upsertPatientResponse(OffsetDateTime now) {
        String nowText = formatNow(now);
        StringBuilder xml = new StringBuilder(UPSERT_PATIENT_RESPONSE_CAPACITY);
        xml.append(XML_DECLARATION);
        startTag(xml, 0, UPSERT_PATIENT_RESPONSE, namespaceDeclaration(NS0_PREFIX, NS0));
        textElement(xml, 1, PATIENT_ID, namespaceDeclaration(NS1_PREFIX, NS1), nowText);
        endTag(xml, 0, UPSERT_PATIENT_RESPONSE);
        return xml.toString();
    }

    /**
     * Writes the DW-43 document [mocks.xml:24-40]: the root {@code ns0:getPatientResponse} holding one
     * {@code ns1:Patient} element whose unqualified children follow the script order [mocks.xml:31-38]:
     *
     * <ol>
     *   <li>{@code patientId}: {@code now} in the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX};</li>
     *   <li>{@code dateOfBirth}: {@code 1930-01-01};</li>
     *   <li>{@code gender}: {@code Male};</li>
     *   <li>{@code nationality}: {@code USA};</li>
     *   <li>{@code address1}: {@code DisneyLand};</li>
     *   <li>{@code lastName}: {@code Duck};</li>
     *   <li>{@code firstName}: {@code Donald};</li>
     *   <li>{@code nationalId}: the same {@code now} text as {@code patientId}.</li>
     * </ol>
     *
     * <p>For {@code now} = {@code 2026-10-01T13:55:31.190+02:00} the result is these lines, joined with
     * {@code \n} and without a trailing newline (D-034):
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:getPatientResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:Patient xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *     <patientId>2026-10-01T13:55:31.190+02:00</patientId>
     *     <dateOfBirth>1930-01-01</dateOfBirth>
     *     <gender>Male</gender>
     *     <nationality>USA</nationality>
     *     <address1>DisneyLand</address1>
     *     <lastName>Duck</lastName>
     *     <firstName>Donald</firstName>
     *     <nationalId>2026-10-01T13:55:31.190+02:00</nationalId>
     *   </ns1:Patient>
     * </ns0:getPatientResponse>
     * }</pre>
     *
     * @param now the instant the script's {@code now} evaluates to, written in its own offset for both
     *     {@code patientId} and {@code nationalId}
     * @return the XML text of the {@code getPatientResponse} document
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public String getPatientResponse(OffsetDateTime now) {
        String nowText = formatNow(now);
        StringBuilder xml = new StringBuilder(GET_PATIENT_RESPONSE_CAPACITY);
        xml.append(XML_DECLARATION);
        startTag(xml, 0, GET_PATIENT_RESPONSE, namespaceDeclaration(NS0_PREFIX, NS0));
        startTag(xml, 1, PATIENT, namespaceDeclaration(NS1_PREFIX, NS1));
        textElement(xml, 2, "patientId", "", nowText);
        textElement(xml, 2, "dateOfBirth", "", DATE_OF_BIRTH);
        textElement(xml, 2, "gender", "", GENDER);
        textElement(xml, 2, "nationality", "", NATIONALITY);
        textElement(xml, 2, "address1", "", ADDRESS1);
        textElement(xml, 2, "lastName", "", LAST_NAME);
        textElement(xml, 2, "firstName", "", FIRST_NAME);
        textElement(xml, 2, "nationalId", "", nowText);
        endTag(xml, 1, PATIENT);
        endTag(xml, 0, GET_PATIENT_RESPONSE);
        return xml.toString();
    }

    /**
     * Returns {@code now} in the pattern {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX} in its own offset.
     *
     * @throws NullPointerException if {@code now} is {@code null}
     */
    private static String formatNow(OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        return NOW.format(now);
    }

    /** Returns the attribute text {@code  xmlns:<prefix>="<uri>"}, with its leading space. */
    private static String namespaceDeclaration(String prefix, String uri) {
        return " xmlns:" + prefix + "=\"" + uri + "\"";
    }

    /**
     * Starts a new line at {@code depth} and writes the start tag of {@code name} with the given namespace
     * declaration text, which is empty for an element that declares none.
     */
    private static void startTag(StringBuilder xml, int depth, String name, String namespaceDeclaration) {
        newLine(xml, depth);
        xml.append('<').append(name).append(namespaceDeclaration).append('>');
    }

    /** Starts a new line at {@code depth} and writes the end tag of {@code name}. */
    private static void endTag(StringBuilder xml, int depth, String name) {
        newLine(xml, depth);
        xml.append("</").append(name).append('>');
    }

    /**
     * Starts a new line at {@code depth} and writes {@code name} as one element holding the escaped
     * {@code text}, with the given namespace declaration text on its start tag.
     */
    private static void textElement(
            StringBuilder xml, int depth, String name, String namespaceDeclaration, String text) {
        newLine(xml, depth);
        xml.append('<').append(name).append(namespaceDeclaration).append('>')
                .append(escape(text))
                .append("</").append(name).append('>');
    }

    /** Ends the current line and indents the next one by two spaces per {@code depth}. */
    private static void newLine(StringBuilder xml, int depth) {
        xml.append(NEWLINE);
        indent(xml, depth);
    }

    /** Appends two spaces per {@code depth}. */
    private static void indent(StringBuilder xml, int depth) {
        for (int level = 0; level < depth; level++) {
            xml.append(INDENT);
        }
    }

    /** Replaces {@code &}, then {@code <} and {@code >}, with their XML entity references. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
