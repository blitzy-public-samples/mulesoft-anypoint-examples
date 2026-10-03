package com.mulesoft.examples.http_oauth_provider.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current response served by
 * embedded Undertow (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the Undertow exchange of the servlet request handled
 * on the calling thread. When the response is committed, Undertow writes the stored phrase after
 * the status code the response carries at that point. The status code stays with the caller:
 * setting it before or after the call leaves the stored phrase in place. A response for which no
 * phrase is set carries Undertow's standard phrase for its status code, for example
 * {@code HTTP/1.1 200 OK}.
 *
 * <p>Usage from a handler that returns a {@code ResponseEntity}:
 *
 * <pre>{@code
 * ReasonPhrase.set("Invalid input data");
 * return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}.
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Sets the HTTP/1.1 reason phrase of the current Undertow exchange (D-010).
     *
     * <p>The method takes the phrase alone, the {@code set(String)} signature of the
     * {@code ReasonPhrase} class of every converted project, and does not change the status code.
     * It must be called on the request thread before the response is committed. The value is passed
     * to {@code HttpServerExchange.setReasonPhrase} unchanged; {@code null} restores the standard
     * phrase of the status code.
     *
     * @param phrase the reason phrase written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
