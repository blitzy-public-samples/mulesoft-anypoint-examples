package com.mulesoft.examples.soap_webservice_security.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current response on embedded Undertow (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the {@code HttpServerExchange} of the Undertow servlet request handled
 * on the calling thread. When the response is committed, Undertow writes the stored phrase after the status code.
 * A response for which no phrase is set carries Undertow's standard phrase for its status code, for example
 * {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage on the request thread: set the status, then the phrase, then write the body.
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}.
 *
 * <p>No class of this project calls it; every response of this project carries Undertow's standard phrase for its
 * status code (D-010, D-269). Each project holds its own copy of this class (D-004). The class holds no state
 * and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the current Undertow exchange (D-010).
     *
     * <p>Set the status before calling this method, and call it before the response is committed. A phrase set after
     * the response is committed does not reach the status line. The phrase is passed unchanged to
     * {@code HttpServerExchange.setReasonPhrase}; {@code null} restores Undertow's standard phrase for the status
     * code.
     *
     * @param reasonPhrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String reasonPhrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(reasonPhrase);
    }
}
