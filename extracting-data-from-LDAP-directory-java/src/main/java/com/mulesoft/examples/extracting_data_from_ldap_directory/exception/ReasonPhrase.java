package com.mulesoft.examples.extracting_data_from_ldap_directory.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Reason phrase on the HTTP/1.1 status line (D-010).
 *
 * <p>{@link #set(String)} stores a reason phrase on the Undertow exchange of the request that the
 * calling thread is handling. Undertow writes that phrase after the status code when the response
 * is committed, for example {@code HTTP/1.1 404 Not Found}. On a thread that handles no Undertow
 * request, such as a plain unit test or a MockMvc request, the call does nothing.
 *
 * <p>Usage, after the status is set:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_NOT_FOUND);
 * ReasonPhrase.set("Not Found");
 * }</pre>
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the response to the Undertow request handled on the calling thread.
     * Leaves the status code unchanged. Does nothing when the calling thread handles no Undertow
     * request.
     *
     * <p>Precondition: Call after the status is set and before the response is committed. A phrase
     * set after the response is committed does not reach the status line.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Not Found"}; {@code null} restores the standard phrase of the status code
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().setReasonPhrase(phrase);
        }
    }
}
