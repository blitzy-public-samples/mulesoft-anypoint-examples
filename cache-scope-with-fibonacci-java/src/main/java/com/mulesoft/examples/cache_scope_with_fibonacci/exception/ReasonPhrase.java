package com.mulesoft.examples.cache_scope_with_fibonacci.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase onto the HTTP/1.1 status line of the current Undertow exchange
 * (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the Undertow exchange of the servlet request bound
 * to the calling thread. When the response is committed, Undertow writes the stored phrase after
 * the status code the response carries at that point. A response for which no phrase is set
 * carries Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage from an exception handler that writes its own response:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set("Not Found");
 * RawBody.write(response, 404, body);
 * }</pre>
 *
 * <p>The status line of that response reads {@code HTTP/1.1 404 Not Found}. The class holds no
 * state and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current request's status line (D-010).
     *
     * <p>Call after the status is set and before the body is written. A status set after the call
     * keeps the stored phrase. The value is passed to {@code HttpServerExchange.setReasonPhrase}
     * unchanged; {@code null} leaves Undertow's standard phrase for the status code.
     *
     * @param phrase the text written after the status code on the status line
     * @throws IllegalStateException when no Undertow request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
