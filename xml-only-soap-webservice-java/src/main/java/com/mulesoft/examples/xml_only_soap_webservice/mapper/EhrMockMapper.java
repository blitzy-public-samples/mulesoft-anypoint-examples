package com.mulesoft.examples.xml_only_soap_webservice.mapper;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * Writes the two responses of the {@code EHRService} mock flow
 * [xml-only-soap-webservice/src/main/app/mocks.xml:45-84] as the XML text of its two DataWeave 1.0
 * scripts, re-implemented by hand (D-034):
 *
 * <ul>
 *   <li>{@link #createEpisodeResponse(Element, OffsetDateTime)}: DW-44 [mocks.xml:52-66], the
 *       {@code when} branch for the operation {@code createEpisode} [mocks.xml:50];</li>
 *   <li>{@link #findEpisodesResponse(Element)}: DW-45 [mocks.xml:71-80], the {@code otherwise}
 *       branch.</li>
 * </ul>
 *
 * <p>Text form of both outputs (D-165):
 *
 * <ul>
 *   <li>the first line is {@code <?xml version='1.0' encoding='UTF-8'?>};</li>
 *   <li>each element is on its own line, indented with two spaces per depth; lines are joined with
 *       {@code \n} and no newline follows the root end tag;</li>
 *   <li>the root element declares {@code xmlns:ns0="http://www.mule-health.com/SOA/message/1.0"},
 *       {@code ns1:Episode} declares {@code xmlns:ns1="http://www.mule-health.com/SOA/model/1.0"},
 *       and {@code ns1:PatientId} and the unqualified children declare no namespace;</li>
 *   <li>text content is written with {@code &} as {@code &amp;}, {@code <} as {@code &lt;} and
 *       {@code >} as {@code &gt;}; every other character is written unchanged.</li>
 * </ul>
 *
 * <p>Request selector {@code payload.ns0#<operation>.ns1#PatientId} (D-165): the request element
 * passed in matches only when its namespace URI is {@code http://www.mule-health.com/SOA/message/1.0}
 * and its local name is the operation name ({@code createEpisode} for DW-44, {@code findEpisodes}
 * for DW-45). The selected value is then its first direct child element whose namespace URI is
 * {@code http://www.mule-health.com/SOA/model/1.0} and whose local name is {@code PatientId}, and
 * its untrimmed {@link Node#getTextContent() text content} is written. Names are compared with
 * {@link Node#getNamespaceURI()} and {@link Node#getLocalName()}, so an element of a DOM built
 * without namespace awareness never matches. The {@code ns1:PatientId} line takes one of three
 * forms:
 *
 * <ul>
 *   <li>{@code <ns1:PatientId/>} when the value is absent: a {@code null} request, a request
 *       element with another name or namespace, or no qualified {@code PatientId} child;</li>
 *   <li>{@code <ns1:PatientId></ns1:PatientId>} when the selected element has empty text;</li>
 *   <li>{@code <ns1:PatientId>} + escaped text + {@code </ns1:PatientId>} otherwise.</li>
 * </ul>
 *
 * <p>Instances hold no state; both methods are side-effect free and safe for concurrent use.
 */
public final class EhrMockMapper {

    /** Namespace bound to the {@code ns0} prefix: the SOA message schema. */
    private static final String NS0 = "http://www.mule-health.com/SOA/message/1.0";

    /** Namespace bound to the {@code ns1} prefix: the SOA model schema. */
    private static final String NS1 = "http://www.mule-health.com/SOA/model/1.0";

    /** Text form of the {@code now} value: milliseconds and the value's own offset, UTC as {@code Z}. */
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private static final String XML_DECLARATION = "<?xml version='1.0' encoding='UTF-8'?>";
    private static final String NEWLINE = "\n";
    private static final String INDENT = "  ";

    private static final String CREATE_EPISODE = "createEpisode";
    private static final String FIND_EPISODES = "findEpisodes";
    private static final String PATIENT_ID = "PatientId";

    private static final String CREATE_EPISODE_RESPONSE_OPEN =
            "<ns0:createEpisodeResponse xmlns:ns0=\"" + NS0 + "\">";
    private static final String CREATE_EPISODE_RESPONSE_CLOSE = "</ns0:createEpisodeResponse>";
    private static final String FIND_EPISODES_RESPONSE_OPEN =
            "<ns0:findEpisodesResponse xmlns:ns0=\"" + NS0 + "\">";
    private static final String FIND_EPISODES_RESPONSE_CLOSE = "</ns0:findEpisodesResponse>";
    private static final String EPISODE_OPEN = "<ns1:Episode xmlns:ns1=\"" + NS1 + "\">";
    private static final String EPISODE_CLOSE = "</ns1:Episode>";
    private static final String PATIENT_ID_EMPTY_ELEMENT = "<ns1:PatientId/>";
    private static final String PATIENT_ID_OPEN = "<ns1:PatientId>";
    private static final String PATIENT_ID_CLOSE = "</ns1:PatientId>";

    private static final String ADMISSION = "Elective";
    private static final String CARE = "Private";

    private static final int INITIAL_CAPACITY = 512;

    /** Creates a mapper; instances hold no state. */
    public EhrMockMapper() {
    }

    /**
     * Writes the DW-44 response [xml-only-soap-webservice/src/main/app/mocks.xml:52-66]: the root
     * {@code ns0:createEpisodeResponse} holding one {@code ns1:Episode} whose children are, in this
     * order, {@code episodeId}, {@code ns1:PatientId}, {@code admission}, {@code startDate},
     * {@code endDate} and {@code care}.
     *
     * <p>{@code episodeId}, {@code startDate} and {@code endDate} hold the same text: {@code now}
     * formatted once as {@code yyyy-MM-dd'T'HH:mm:ss.SSSXXX} in its own offset, for example
     * {@code 2026-10-01T13:55:31.190+02:00}, or {@code 2026-10-01T11:55:31.190Z} for a UTC value
     * (D-165). {@code admission} holds {@code Elective} and {@code care} holds {@code Private}.
     * {@code ns1:PatientId} is selected from {@code createEpisode} by the qualified selector
     * {@code payload.ns0#createEpisode.ns1#PatientId} described on this class.
     *
     * <p>Example: a request {@code <ns0:createEpisode>} holding {@code <ns1:PatientId>P1</ns1:PatientId>}
     * and {@code now} = {@code 2026-10-01T13:55:31.190+02:00} give
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:createEpisodeResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:Episode xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *     <episodeId>2026-10-01T13:55:31.190+02:00</episodeId>
     *     <ns1:PatientId>P1</ns1:PatientId>
     *     <admission>Elective</admission>
     *     <startDate>2026-10-01T13:55:31.190+02:00</startDate>
     *     <endDate>2026-10-01T13:55:31.190+02:00</endDate>
     *     <care>Private</care>
     *   </ns1:Episode>
     * </ns0:createEpisodeResponse>
     * }</pre>
     *
     * <p>An absent value replaces the fourth line with {@code     <ns1:PatientId/>}; every other line
     * is unchanged.
     *
     * @param createEpisode the request element: the SOAP Body child, or {@code null}
     * @param now the instant written as {@code episodeId}, {@code startDate} and {@code endDate}
     * @return the XML text, starting with the declaration line and ending with the root end tag
     * @throws NullPointerException if {@code now} is {@code null}
     */
    public String createEpisodeResponse(Element createEpisode, OffsetDateTime now) {
        Objects.requireNonNull(now, "now");
        String timestamp = formatNow(now);
        String patientIdLine = patientIdLine(createEpisode, CREATE_EPISODE);

        StringBuilder xml = new StringBuilder(INITIAL_CAPACITY);
        xml.append(XML_DECLARATION).append(NEWLINE);
        appendLine(xml, 0, CREATE_EPISODE_RESPONSE_OPEN);
        appendLine(xml, 1, EPISODE_OPEN);
        appendLine(xml, 2, textElement("episodeId", timestamp));
        appendLine(xml, 2, patientIdLine);
        appendLine(xml, 2, textElement("admission", ADMISSION));
        appendLine(xml, 2, textElement("startDate", timestamp));
        appendLine(xml, 2, textElement("endDate", timestamp));
        appendLine(xml, 2, textElement("care", CARE));
        appendLine(xml, 1, EPISODE_CLOSE);
        xml.append(CREATE_EPISODE_RESPONSE_CLOSE);
        return xml.toString();
    }

    /**
     * Writes the DW-45 response [xml-only-soap-webservice/src/main/app/mocks.xml:71-80]: the root
     * {@code ns0:findEpisodesResponse} holding one {@code ns1:Episode} whose only child is
     * {@code ns1:PatientId}, selected from {@code findEpisodes} by the qualified selector
     * {@code payload.ns0#findEpisodes.ns1#PatientId} described on this class.
     *
     * <p>Example: a request {@code <ns0:findEpisodes>} holding {@code <ns1:PatientId>P1</ns1:PatientId>}
     * gives
     *
     * <pre>{@code
     * <?xml version='1.0' encoding='UTF-8'?>
     * <ns0:findEpisodesResponse xmlns:ns0="http://www.mule-health.com/SOA/message/1.0">
     *   <ns1:Episode xmlns:ns1="http://www.mule-health.com/SOA/model/1.0">
     *     <ns1:PatientId>P1</ns1:PatientId>
     *   </ns1:Episode>
     * </ns0:findEpisodesResponse>
     * }</pre>
     *
     * <p>An absent value replaces the fourth line with {@code     <ns1:PatientId/>}.
     *
     * @param findEpisodes the request element: the SOAP Body child, or {@code null}
     * @return the XML text, starting with the declaration line and ending with the root end tag
     */
    public String findEpisodesResponse(Element findEpisodes) {
        String patientIdLine = patientIdLine(findEpisodes, FIND_EPISODES);

        StringBuilder xml = new StringBuilder(INITIAL_CAPACITY);
        xml.append(XML_DECLARATION).append(NEWLINE);
        appendLine(xml, 0, FIND_EPISODES_RESPONSE_OPEN);
        appendLine(xml, 1, EPISODE_OPEN);
        appendLine(xml, 2, patientIdLine);
        appendLine(xml, 1, EPISODE_CLOSE);
        xml.append(FIND_EPISODES_RESPONSE_CLOSE);
        return xml.toString();
    }

    /** Formats {@code now} with {@link #NOW} in the value's own offset. */
    private static String formatNow(OffsetDateTime now) {
        return NOW.format(now);
    }

    /**
     * Returns the {@code ns1:PatientId} element text for {@code payload.ns0#<operation>.ns1#PatientId}
     * evaluated on {@code request}: the self-closing form when the value is absent, otherwise the
     * escaped text content between start and end tags.
     */
    private static String patientIdLine(Element request, String operation) {
        Element patientId = matchesQualified(request, NS0, operation)
                ? firstQualifiedChild(request, NS1, PATIENT_ID)
                : null;
        if (patientId == null) {
            return PATIENT_ID_EMPTY_ELEMENT;
        }
        return PATIENT_ID_OPEN + escape(patientId.getTextContent()) + PATIENT_ID_CLOSE;
    }

    /** Returns the first direct child element of {@code parent} with the given namespace URI and local name, or {@code null}. */
    private static Element firstQualifiedChild(Element parent, String namespaceUri, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element element && matchesQualified(element, namespaceUri, localName)) {
                return element;
            }
        }
        return null;
    }

    /**
     * Tells whether {@code element} is not {@code null} and has exactly the given namespace URI and
     * local name; an element without a namespace URI or local name never matches.
     */
    private static boolean matchesQualified(Element element, String namespaceUri, String localName) {
        return element != null
                && namespaceUri.equals(element.getNamespaceURI())
                && localName.equals(element.getLocalName());
    }

    /** Returns an unqualified element with the escaped {@code text} between its start and end tags. */
    private static String textElement(String name, String text) {
        return "<" + name + ">" + escape(text) + "</" + name + ">";
    }

    /** Replaces {@code &}, then {@code <} and {@code >}, with their XML entity references. */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Appends {@code content} indented by two spaces per {@code depth}, followed by {@code \n}. */
    private static void appendLine(StringBuilder xml, int depth, String content) {
        xml.append(INDENT.repeat(depth)).append(content).append(NEWLINE);
    }
}
