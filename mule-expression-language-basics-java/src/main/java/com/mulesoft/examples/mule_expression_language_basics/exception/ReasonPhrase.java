package com.mulesoft.examples.mule_expression_language_basics.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the HTTP/1.1 reason phrase of the current Undertow exchange (D-010).
 *
 * <p>A response for which no phrase is set carries Undertow's standard phrase for its status
 * code, for example {@code HTTP/1.1 200 OK} or {@code HTTP/1.1 404 Not Found}.
 *
 * <p>Usage on the request thread, after the status is set and before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}.
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Writes {@code phrase} as the reason phrase of the HTTP/1.1 status line of the response to
     * the Undertow servlet request active on the calling thread.
     *
     * <p>The phrase is passed unchanged to {@code HttpServerExchange.setReasonPhrase} and is
     * written after the status code when the response is committed. The status code is left
     * unchanged. A {@code null} phrase leaves Undertow's standard phrase for the status code on the
     * status line.
     *
     * <p>Must be called after the status is set and before the response is committed. A phrase
     * set after the commit does not change the status line already sent.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
