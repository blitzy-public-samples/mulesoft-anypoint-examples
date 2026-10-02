package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Writes the XML text of the three DataWeave 1.0 {@code set-payload} transforms of the hospital
 * admission flow [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml], each
 * re-implemented by hand as one method (D-034):
 *
 * <ul>
 *   <li>DW-39 (:26-46, flow {@code admitPatientService}): {@link #toAdmitSubjectResponse(Element)},
 *       {@code createEpisodeResponse} to {@code admitSubjectResponse};</li>
 *   <li>DW-40 (:53-61, sub-flow {@code upsertPatient}): {@link #toUpsertPatient(Element)},
 *       {@code admitSubject} to {@code upsertPatient};</li>
 *   <li>DW-41 (:73-80, sub-flow {@code createEpisode}): {@link #toCreateEpisode(Element)},
 *       {@code upsertPatientResponse} to {@code createEpisode}.</li>
 * </ul>
 *
 * <p>Layout of every returned document (D-156):
 *
 * <ul>
 *   <li>the first line is {@code <?xml version='1.0' encoding='UTF-8'?>};</li>
 *   <li>one element per line, indented by two spaces per depth; lines are joined with {@code \n}
 *       and no newline follows the root end tag;</li>
 *   <li>the root {@code ns0:} element declares
 *       {@code xmlns:ns0="http://www.mule-health.com/SOA/message/1.0"}; every {@code ns1:} child of
 *       the root declares {@code xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"}; the
 *       {@code ns1:PatientId} inside {@code ns1:Episode} declares nothing; unqualified elements carry
 *       no namespace and no declaration;</li>
 *   <li>a value is the untrimmed {@link Node#getTextContent() text content} of the selected element,
 *       with {@code &}, {@code <} and {@code >} written as {@code &amp;}, {@code &lt;} and
 *       {@code &gt;} and every other character unchanged;</li>
 *   <li>an absent value is written as a self-closing element, for example {@code <episodeId/>}; a
 *       selected element with empty text is written as a start and an end tag, for example
 *       {@code <episodeId></episodeId>}.</li>
 * </ul>
 *
 * <p>Selectors. An unqualified selector picks the first direct child element whose local name
 * matches, in any namespace; the qualified selector {@code ns1#PatientId} of DW-41 also requires the
 * namespace {@code http://www.mule-health.com/SOA/model/1.0}. The root selector of each script
 * applies to the input element itself: an input whose local name differs, or a {@code null} input,
 * selects nothing. A node without a local name (a DOM built without namespace awareness) is matched
 * by its node name with any prefix removed.
 *
 * <p>Instances hold no state; every method is side-effect free and safe for concurrent use.
 */
public final class AdmissionMapper {

    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    private static final String DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String NS0_DECLARATION = " xmlns:ns0=\"" + NS0 + "\"";
    private static final String NS1_DECLARATION = " xmlns:ns1=\"" + NS1 + "\"";
    private static final String NO_DECLARATION = "";

    private static final String NEWLINE = "\n";
    private static final String INDENT = "  ";

    /** Start tag of the {@code Bill } element: its name keeps the trailing space (D-029, D-156). */
    private static final String BILL_START_TAG = "<ns1:Bill " + NS1_DECLARATION + ">";
    /** End tag of the {@code Bill } element (D-029, D-156). */
    private static final String BILL_END_TAG = "</ns1:Bill >";

    private static final int ISO_DATE_LENGTH = 10;
    private static final long END_DATE_DAYS_ADDED = 5;

    /** Creates a mapper; instances hold no state. */
    public AdmissionMapper() {
        // Stateless: every method works on its arguments only.
    }

    /**
     * Writes the DW-39 {@code admitSubjectResponse} document
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:26-46].
     *
     * <p>The input matches when its local name is {@code createEpisodeResponse}; its first child
     * {@code Episode} supplies the values. The root {@code ns0:admitSubjectResponse} holds:
     *
     * <ol>
     *   <li>{@code ns1:Episode} with, in this order, {@code episodeId}, {@code ns1:PatientId} (the
     *       Episode child with local name {@code PatientId}), {@code admission}, {@code startDate},
     *       {@code endDate} and {@code care};</li>
     *   <li>the element named {@code Bill } with its trailing space, written as the start tag
     *       {@code <ns1:Bill  xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">} and the end tag
     *       {@code </ns1:Bill >}, holding the literals {@code costPerNight} {@code 100},
     *       {@code initialStateEstimate} {@code 5}, {@code runningTotal} {@code 500} and
     *       {@code status} {@code ADMITTED} whatever the input (D-029, D-043).</li>
     * </ol>
     *
     * <p>{@code startDate} is written only when the Episode has a {@code startDate} child, and
     * {@code endDate} only when it has an {@code endDate} child. The first ten characters of the
     * child's text (the whole text when shorter) are read as an ISO {@code yyyy-MM-dd} date and
     * written as {@code yyyy-MM-dd}; {@code endDate} is that date plus five days (D-156). A
     * {@code null} input, a root with another local name or a missing Episode gives
     * {@code <episodeId/>}, {@code <ns1:PatientId/>}, {@code <admission/>} and {@code <care/>}, no
     * dates, and the Bill unchanged.
     *
     * <p>Example: {@code episodeId} {@code E1}, {@code PatientId} {@code P1}, {@code admission}
     * {@code Elective}, {@code startDate} and {@code endDate} {@code 2026-01-28T10:15:30.123+01:00}
     * and {@code care} {@code Private} give
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:admitSubjectResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:Episode xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *     <episodeId>E1</episodeId>
     *     <ns1:PatientId>P1</ns1:PatientId>
     *     <admission>Elective</admission>
     *     <startDate>2026-01-28</startDate>
     *     <endDate>2026-02-02</endDate>
     *     <care>Private</care>
     *   </ns1:Episode>
     *   <ns1:Bill  xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *     <costPerNight>100</costPerNight>
     *     <initialStateEstimate>5</initialStateEstimate>
     *     <runningTotal>500</runningTotal>
     *     <status>ADMITTED</status>
     *   </ns1:Bill >
     * </ns0:admitSubjectResponse>
     * }</pre>
     *
     * @param createEpisodeResponse the {@code createEpisodeResponse} element returned by the EHR
     *     service, or {@code null}
     * @return the {@code admitSubjectResponse} document text
     * @throws DateTimeParseException if a present {@code startDate} or {@code endDate} text does not
     *     begin with an ISO {@code yyyy-MM-dd} date, the empty text included
     */
    public String toAdmitSubjectResponse(Element createEpisodeResponse) {
        Element root = matchRoot(createEpisodeResponse, "createEpisodeResponse");
        Element episode = firstChild(root, "Episode");
        StringBuilder xml = startDocument("admitSubjectResponse");
        indent(xml, 1).append("<ns1:Episode").append(NS1_DECLARATION).append('>');
        appendSimple(xml, 2, "episodeId", NO_DECLARATION, text(firstChild(episode, "episodeId")));
        appendSimple(xml, 2, "ns1:PatientId", NO_DECLARATION, text(firstChild(episode, "PatientId")));
        appendSimple(xml, 2, "admission", NO_DECLARATION, text(firstChild(episode, "admission")));
        Element startDate = firstChild(episode, "startDate");
        if (startDate != null) {
            LocalDate start = coerceDate(startDate.getTextContent());
            appendSimple(xml, 2, "startDate", NO_DECLARATION, start.toString());
        }
        Element endDate = firstChild(episode, "endDate");
        if (endDate != null) {
            LocalDate end = coerceDate(endDate.getTextContent()).plusDays(END_DATE_DAYS_ADDED);
            appendSimple(xml, 2, "endDate", NO_DECLARATION, end.toString());
        }
        appendSimple(xml, 2, "care", NO_DECLARATION, text(firstChild(episode, "care")));
        indent(xml, 1).append("</ns1:Episode>");
        indent(xml, 1).append(BILL_START_TAG);
        appendSimple(xml, 2, "costPerNight", NO_DECLARATION, "100");
        appendSimple(xml, 2, "initialStateEstimate", NO_DECLARATION, "5");
        appendSimple(xml, 2, "runningTotal", NO_DECLARATION, "500");
        appendSimple(xml, 2, "status", NO_DECLARATION, "ADMITTED");
        indent(xml, 1).append(BILL_END_TAG);
        return endDocument(xml, "admitSubjectResponse");
    }

    /**
     * Writes the DW-40 {@code upsertPatient} document
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:53-61].
     *
     * <p>The input is the {@code admitSubject} child of the request SOAP Body; it matches when its
     * local name is {@code admitSubject}, and its first child with local name {@code Subject} is
     * copied. The root {@code ns0:upsertPatient} holds {@code ns1:Subject}, which declares
     * {@code xmlns:ns1}. The Subject's child elements are copied recursively in document order, each
     * under its local name, unqualified, without attributes and without a namespace declaration:
     *
     * <ul>
     *   <li>an element with child elements gives its start tag on one line, its child elements one
     *       depth deeper, and its end tag on one line; its text nodes are not copied;</li>
     *   <li>an element without child elements gives {@code <name>text</name>} on one line, so an
     *       empty element stays {@code <address2></address2>}.</li>
     * </ul>
     *
     * <p>A Subject without child elements is written on one line with its text, for example
     * {@code <ns1:Subject xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"></ns1:Subject>}. A
     * {@code null} input, a root with another local name or a missing Subject gives
     * {@code <ns1:Subject xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"/>} (D-156). The
     * request's {@code Referer} and {@code Referral} are not copied.
     *
     * <p>Example: the {@code admitSubject} of the original {@code message.xml} gives
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:upsertPatient xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:Subject xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *     <nationalId>1234</nationalId>
     *     <firstName>Nial</firstName>
     *     <lastName>Darbey</lastName>
     *     <address1>Buenos Aires</address1>
     *     <address2></address2>
     *     <address3></address3>
     *     <nationality>Irish</nationality>
     *     <gender>Male</gender>
     *     <dateOfBirth>1970-08-07</dateOfBirth>
     *   </ns1:Subject>
     * </ns0:upsertPatient>
     * }</pre>
     *
     * @param admitSubject the {@code admitSubject} element of the request Body, or {@code null}
     * @return the {@code upsertPatient} document text
     */
    public String toUpsertPatient(Element admitSubject) {
        Element subject = firstChild(matchRoot(admitSubject, "admitSubject"), "Subject");
        StringBuilder xml = startDocument("upsertPatient");
        if (subject == null) {
            appendSimple(xml, 1, "ns1:Subject", NS1_DECLARATION, null);
        } else {
            appendCopy(xml, 1, "ns1:Subject", NS1_DECLARATION, subject);
        }
        return endDocument(xml, "upsertPatient");
    }

    /**
     * Writes the DW-41 {@code createEpisode} document
     * [xml-only-soap-webservice/src/main/app/Hospital_Admissions_SOA.xml:73-80].
     *
     * <p>The input matches when its local name is {@code upsertPatientResponse}; its first child in
     * the namespace {@code http://www.mule-health.com/SOA/model/1.0} with local name
     * {@code PatientId} supplies the value. The root {@code ns0:createEpisode} holds
     * {@code ns1:PatientId}, which declares {@code xmlns:ns1}. An unqualified {@code PatientId}, a
     * {@code null} input or a root with another local name gives
     * {@code <ns1:PatientId xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"/>} (D-156).
     *
     * <p>Example: {@code PatientId} {@code 2026-01-28T10:15:30.123+01:00} gives
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:createEpisode xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:PatientId xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">2026-01-28T10:15:30.123+01:00</ns1:PatientId>
     * </ns0:createEpisode>
     * }</pre>
     *
     * @param upsertPatientResponse the {@code upsertPatientResponse} element returned by the patient
     *     service, or {@code null}
     * @return the {@code createEpisode} document text
     */
    public String toCreateEpisode(Element upsertPatientResponse) {
        Element root = matchRoot(upsertPatientResponse, "upsertPatientResponse");
        Element patientId = firstChildNs(root, NS1, "PatientId");
        StringBuilder xml = startDocument("createEpisode");
        appendSimple(xml, 1, "ns1:PatientId", NS1_DECLARATION, text(patientId));
        return endDocument(xml, "createEpisode");
    }

    /** Starts a document: the declaration line, then the {@code ns0:} root start tag and its declaration. */
    private static StringBuilder startDocument(String rootName) {
        return new StringBuilder(DECLARATION).append(NEWLINE)
                .append("<ns0:").append(rootName).append(NS0_DECLARATION).append('>');
    }

    /** Ends a document with the {@code ns0:} root end tag on its own line and returns its text. */
    private static String endDocument(StringBuilder xml, String rootName) {
        return xml.append(NEWLINE).append("</ns0:").append(rootName).append('>').toString();
    }

    /** Starts a new line indented by two spaces per {@code depth} and returns {@code xml}. */
    private static StringBuilder indent(StringBuilder xml, int depth) {
        return xml.append(NEWLINE).append(INDENT.repeat(depth));
    }

    /**
     * Writes one element on one line: {@code <name declaration/>} for a {@code null} value, otherwise
     * {@code <name declaration>escaped value</name>}.
     */
    private static void appendSimple(
            StringBuilder xml, int depth, String name, String declaration, String value) {
        indent(xml, depth).append('<').append(name).append(declaration);
        if (value == null) {
            xml.append("/>");
        } else {
            xml.append('>').append(escape(value)).append("</").append(name).append('>');
        }
    }

    /**
     * Copies {@code element} under {@code name}: with child elements, as a start tag, each child
     * element copied under its local name one depth deeper, and an end tag; without child elements,
     * as one line holding its text.
     */
    private static void appendCopy(
            StringBuilder xml, int depth, String name, String declaration, Element element) {
        Element child = nextElement(element.getFirstChild());
        if (child == null) {
            appendSimple(xml, depth, name, declaration, element.getTextContent());
            return;
        }
        indent(xml, depth).append('<').append(name).append(declaration).append('>');
        for (; child != null; child = nextElement(child.getNextSibling())) {
            appendCopy(xml, depth + 1, localName(child), NO_DECLARATION, child);
        }
        indent(xml, depth).append("</").append(name).append('>');
    }

    /**
     * Returns {@code element} when it is not {@code null} and its local name is {@code localName};
     * otherwise {@code null}.
     */
    private static Element matchRoot(Element element, String localName) {
        return element != null && localName.equals(localName(element)) ? element : null;
    }

    /**
     * Returns the first child element of {@code parent} with {@code localName} in any namespace, or
     * {@code null}.
     */
    private static Element firstChild(Element parent, String localName) {
        return firstChildNs(parent, null, localName);
    }

    /**
     * Returns the first child element of {@code parent} with {@code localName} and, when
     * {@code namespaceUri} is not {@code null}, that namespace; {@code null} when there is none or
     * {@code parent} is {@code null}.
     */
    private static Element firstChildNs(Element parent, String namespaceUri, String localName) {
        Element child = parent == null ? null : nextElement(parent.getFirstChild());
        for (; child != null; child = nextElement(child.getNextSibling())) {
            if (localName.equals(localName(child))
                    && (namespaceUri == null || namespaceUri.equals(child.getNamespaceURI()))) {
                return child;
            }
        }
        return null;
    }

    /** Returns {@code node} or its first following sibling that is an element, or {@code null}. */
    private static Element nextElement(Node node) {
        Node current = node;
        while (current != null && current.getNodeType() != Node.ELEMENT_NODE) {
            current = current.getNextSibling();
        }
        return (Element) current;
    }

    /** Returns the local name of {@code node}, or its node name without prefix when it has none. */
    private static String localName(Node node) {
        String localName = node.getLocalName();
        String nodeName = node.getNodeName();
        return localName != null ? localName : nodeName.substring(nodeName.indexOf(':') + 1);
    }

    /** Returns the text content of {@code element}, or {@code null} when it is {@code null}. */
    private static String text(Element element) {
        return element == null ? null : element.getTextContent();
    }

    /**
     * Reads the first ten characters of {@code text} (all of it when shorter) as an ISO
     * {@code yyyy-MM-dd} date.
     *
     * @throws DateTimeParseException if those characters are not an ISO date
     */
    private static LocalDate coerceDate(String text) {
        String date = text.length() >= ISO_DATE_LENGTH ? text.substring(0, ISO_DATE_LENGTH) : text;
        return LocalDate.parse(date);
    }

    /** Replaces {@code &}, {@code <} and {@code >} with their entity references, {@code &} first. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
