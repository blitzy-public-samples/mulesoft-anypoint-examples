package com.mulesoft.examples.filtering_a_message.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the HTTP/1.1 reason phrase of the current Undertow exchange (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the {@code HttpServerExchange} of the Undertow servlet
 * request handled on the calling thread. Undertow writes the stored phrase after the status code when
 * the response is committed. Setting the status code before or after the call leaves the stored phrase
 * in place. A response for which no phrase is set carries Undertow's standard phrase for its status
 * code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>The constants hold the standard phrases of the {@code 405} and {@code 404} answers of the
 * {@code filteringFlow1} listener and of the {@code 500} answer of its default exception strategy.
 *
 * <p>Usage on the request thread, before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
 * ReasonPhrase.set(ReasonPhrase.METHOD_NOT_ALLOWED);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 405 Method Not Allowed}.
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    /** Reason phrase of the {@code 405} status: {@value}. */
    public static final String METHOD_NOT_ALLOWED = "Method Not Allowed";

    /** Reason phrase of the {@code 404} status: {@value}. */
    public static final String NOT_FOUND = "Not Found";

    /** Reason phrase of the {@code 500} status: {@value}. */
    public static final String INTERNAL_SERVER_ERROR = "Internal Server Error";

    private ReasonPhrase() { }

    /**
     * Sets the HTTP/1.1 reason phrase of the current Undertow exchange (D-010).
     *
     * <p>The exchange is the one of the Undertow servlet request bound to the calling thread. The value
     * is passed to {@code HttpServerExchange.setReasonPhrase} unchanged. A phrase set after the response
     * is committed does not reach the status line.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@link #METHOD_NOT_ALLOWED}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
