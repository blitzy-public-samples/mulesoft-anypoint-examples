package com.mulesoft.examples.proxying_a_soap_api.mapper;

import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Builds the SOAP 1.1 server fault of the default branch of flow {@code main}
 * [proxying-a-soap-api/src/main/app/soap-api-proxy.xml:5-12]. Behind its {@code cxf:proxy-service} (:7)
 * a failure is answered with the fault code {@code soap:Server} and the exception message as
 * {@code faultstring} (D-245).
 *
 * <p>The class only builds the body bytes. It sets no HTTP status and no header. It holds no state, and
 * {@link #serverFault} has no side effects and is safe for concurrent use.
 */
@Component
public class SoapFaultMapper {

    /** Envelope text up to and including the opening {@code faultstring} tag. */
    private static final String ENVELOPE_PREFIX =
            "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">"
                    + "<soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>";

    /** Envelope text from the closing {@code faultstring} tag to the end of the envelope. */
    private static final String ENVELOPE_SUFFIX = "</faultstring></soap:Fault></soap:Body></soap:Envelope>";

    /** Message of the exception raised for fault text holding a code point XML 1.0 does not allow. */
    private static final String INVALID_TEXT = "Fault string holds a character that XML 1.0 does not allow";

    /**
     * Returns the UTF-8 bytes of a one-line SOAP 1.1 envelope carrying a {@code soap:Server} fault
     * (D-023, D-245):
     *
     * <pre>{@code
     * <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>TEXT</faultstring></soap:Fault></soap:Body></soap:Envelope>
     * }</pre>
     *
     * <p>{@code TEXT} is {@code faultString} with {@code &} written as {@code &amp;}, then {@code <} as
     * {@code &lt;}, then {@code >} as {@code &gt;}. Every other character XML 1.0 allows (U+0009, U+000A,
     * U+000D, U+0020 to U+D7FF, U+E000 to U+FFFD and U+10000 to U+10FFFF, a surrogate pair counting as one
     * character), quotes, apostrophes, tab, LF and CR included, is written unchanged. Text holding any
     * other code point, for example U+0000, U+FFFE or a surrogate that is not part of a pair, raises an
     * {@link IllegalArgumentException} with the message
     * {@code Fault string holds a character that XML 1.0 does not allow}, which does not repeat the text,
     * and no envelope is built (D-361). A {@code null} argument gives an empty
     * {@code <faultstring></faultstring>}. The bytes hold no XML declaration and no whitespace between
     * tags.
     *
     * <p>Example: {@code serverFault("a<b>&c")} returns the envelope with
     * {@code <faultstring>a&lt;b&gt;&amp;c</faultstring>}.
     *
     * @param faultString the exception message to carry as {@code faultstring}; may be {@code null}
     * @return the envelope encoded as UTF-8; never {@code null}
     * @throws IllegalArgumentException if {@code faultString} holds a code point XML 1.0 does not allow
     */
    public byte[] serverFault(String faultString) {
        return (ENVELOPE_PREFIX + escape(faultString) + ENVELOPE_SUFFIX).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Checks that every code point of the text is one XML 1.0 allows, then replaces {@code &}, then
     * {@code <}, then {@code >} with their predefined XML entities (D-361).
     *
     * @param text the text to escape; may be {@code null}
     * @return the escaped text; the empty string for {@code null}
     * @throws IllegalArgumentException if the text holds a code point XML 1.0 does not allow, an
     *     unpaired surrogate included
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (!isXmlChar(codePoint)) {
                throw new IllegalArgumentException(INVALID_TEXT);
            }
            index += Character.charCount(codePoint);
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Tells whether a code point belongs to the XML 1.0 {@code Char} production: U+0009, U+000A, U+000D,
     * U+0020 to U+D7FF, U+E000 to U+FFFD and U+10000 to U+10FFFF. A surrogate code unit read on its own,
     * U+D800 to U+DFFF, does not.
     *
     * @param codePoint the code point
     * @return {@code true} when XML 1.0 allows the code point in text
     */
    private static boolean isXmlChar(int codePoint) {
        return codePoint == 0x9
                || codePoint == 0xA
                || codePoint == 0xD
                || (codePoint >= 0x20 && codePoint <= 0xD7FF)
                || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
                || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
