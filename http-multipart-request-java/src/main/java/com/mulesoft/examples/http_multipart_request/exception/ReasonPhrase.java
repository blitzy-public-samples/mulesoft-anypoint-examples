package com.mulesoft.examples.http_multipart_request.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the HTTP/1.1 reason phrase of the current Undertow exchange (D-010).
 *
 * <p>{@link #set(String)} takes only the phrase and writes it to the exchange of the Undertow
 * servlet request handled on the calling thread (D-238). A response for which no phrase is set
 * carries Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 * This project carries its own copy of the class (D-004).
 *
 * <p>Usage, after the status is set and before the first body byte is written:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_OK);
 * ReasonPhrase.set("File was uploaded.");
 * response.getOutputStream().write(body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 200 File was uploaded.}. The class holds no state
 * and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow exchange (D-010).
     *
     * <p>Call after the status is set and before the first body byte is written; a committed
     * response keeps its status line. The phrase is passed unchanged to
     * {@code HttpServerExchange.setReasonPhrase} and the status code is left as it is.
     * {@code null} restores Undertow's standard phrase for the status code.
     *
     * @param phrase the reason phrase to send
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
