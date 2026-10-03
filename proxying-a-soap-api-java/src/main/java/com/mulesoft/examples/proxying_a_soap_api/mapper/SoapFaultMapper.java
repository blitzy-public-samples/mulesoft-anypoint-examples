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

    /**
     * Returns the UTF-8 bytes of a one-line SOAP 1.1 envelope carrying a {@code soap:Server} fault
     * (D-023, D-245):
     *
     * <pre>{@code
     * <soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/"><soap:Body><soap:Fault><faultcode>soap:Server</faultcode><faultstring>TEXT</faultstring></soap:Fault></soap:Body></soap:Envelope>
     * }</pre>
     *
     * <p>{@code TEXT} is {@code faultString} with {@code &} written as {@code &amp;}, then {@code <} as
     * {@code &lt;}, then {@code >} as {@code &gt;}. Every other character, quotes, apostrophes and control
     * characters included, is written unchanged. A {@code null} argument gives an empty
     * {@code <faultstring></faultstring>}. The bytes hold no XML declaration and no whitespace between
     * tags.
     *
     * <p>Example: {@code serverFault("a<b>&c")} returns the envelope with
     * {@code <faultstring>a&lt;b&gt;&amp;c</faultstring>}.
     *
     * @param faultString the exception message to carry as {@code faultstring}; may be {@code null}
     * @return the envelope encoded as UTF-8; never {@code null}
     */
    public byte[] serverFault(String faultString) {
        return (ENVELOPE_PREFIX + escape(faultString) + ENVELOPE_SUFFIX).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Replaces {@code &}, then {@code <}, then {@code >} with their predefined XML entities.
     *
     * @param text the text to escape; may be {@code null}
     * @return the escaped text; the empty string for {@code null}
     */
    private static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
