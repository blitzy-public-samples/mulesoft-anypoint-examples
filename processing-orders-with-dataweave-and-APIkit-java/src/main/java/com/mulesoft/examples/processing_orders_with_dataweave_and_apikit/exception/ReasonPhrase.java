package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom HTTP reason phrase on the status line of the current Undertow exchange (D-010).
 *
 * <p>A response for which no phrase is set carries Undertow's standard phrase for its status code,
 * for example {@code HTTP/1.1 200 OK}. {@link #set(String)} takes only the phrase: the exchange it
 * writes to is the one of the request handled on the calling thread.
 *
 * <p>Usage in a handler: set the status, then the phrase, then write the body.
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * response.getOutputStream().write(body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}. The class holds no state
 * and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() { }

    /**
     * Sets the reason phrase of the current Undertow exchange; call before the response body is
     * written.
     *
     * <p>The phrase is passed unchanged to {@code HttpServerExchange.setReasonPhrase} and leaves the
     * status code as it is. A phrase set after the response is committed does not reach the status
     * line.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if the calling thread is handling no Undertow servlet request
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
