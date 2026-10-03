package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase on the HTTP/1.1 status line of the current Undertow exchange (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the {@code HttpServerExchange} of the Undertow servlet request
 * handled on the calling thread. When the response is committed, Undertow writes the stored phrase after the
 * status code the response carries at that point. A response with no stored phrase carries Undertow's standard
 * phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage from an exception handler on the request thread, before the first body byte is written:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set("No listener for endpoint: /x");
 * RawBody.write(response, 404, body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 404 No listener for endpoint: /x}.
 *
 * <p>Per-project copy (D-004). The class holds no state and is not instantiable (D-420).
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the current response (D-010, D-420).
     *
     * <p>Call after the status is set and before the response is committed. The phrase is passed unchanged to
     * {@code HttpServerExchange.setReasonPhrase} on the exchange of the Undertow servlet request bound to the
     * calling thread, and is sent with the status code the response carries when it is committed. A call after
     * the response is committed leaves the status line already sent unchanged, and {@code null} restores the
     * standard phrase of the status code.
     *
     * <p>With no current Undertow servlet request on the calling thread (a MockMvc request, a non-Undertow
     * container or a thread outside request handling) the method does nothing and throws nothing.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "No listener for endpoint: /x"}
     */
    public static void set(String phrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null) {
            ctx.getExchange().setReasonPhrase(phrase);
        }
    }
}
