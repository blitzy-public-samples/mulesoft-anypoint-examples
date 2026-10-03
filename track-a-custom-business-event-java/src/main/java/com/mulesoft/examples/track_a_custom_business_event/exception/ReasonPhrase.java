package com.mulesoft.examples.track_a_custom_business_event.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom HTTP/1.1 reason phrase on the current Undertow exchange (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the {@code HttpServerExchange} of the servlet request handled on the
 * calling thread. When the response is committed, Undertow writes the stored phrase after the status code the
 * response carries at that point. A response for which no phrase is set carries Undertow's standard phrase for its
 * status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage on the request thread, before the first body byte is written:
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
    private ReasonPhrase() { }

    /**
     * Sets the reason phrase of the current response; the response must not yet be committed (D-010).
     *
     * <p>The phrase is passed unchanged to {@code HttpServerExchange.setReasonPhrase} on the exchange of the Undertow
     * servlet request bound to the calling thread. The stored phrase stays in place whether the status code is set
     * before or after the call. A phrase set after the response is committed does not reach the status line, and
     * {@code null} restores the standard phrase of the status code.
     *
     * @param reasonPhrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String reasonPhrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(reasonPhrase);
    }
}
