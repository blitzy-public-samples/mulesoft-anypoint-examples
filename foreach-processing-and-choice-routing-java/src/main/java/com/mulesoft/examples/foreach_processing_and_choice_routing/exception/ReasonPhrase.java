package com.mulesoft.examples.foreach_processing_and_choice_routing.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the current Undertow response status line (D-010).
 *
 * <p>The phrase is stored on the {@code HttpServerExchange} of the request handled on the calling thread and is
 * written after the status code when the response is committed. A response for which no phrase is set carries
 * Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage on the request thread, before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}.
 *
 * <p>The class holds no state and is not instantiable. This project's copy (D-004) writes the phrase of the
 * no-listener answer of {@code config.PortPathGuardFilter} (D-011).
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Writes {@code phrase} as the reason phrase of the current Undertow response status line (D-010).
     *
     * <p>The exchange comes from the Undertow servlet request context bound to the calling thread. The value is passed
     * to {@code HttpServerExchange.setReasonPhrase} unchanged; {@code null} leaves Undertow's standard phrase for the
     * status code in place. A phrase set after the response is committed leaves the status line already sent
     * unchanged.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
