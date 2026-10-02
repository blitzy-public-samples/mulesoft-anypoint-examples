package com.mulesoft.examples.rest_api_with_apikit.exception;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current response served by
 * embedded Undertow (D-010).
 *
 * <p>{@link #set(String)} stores the phrase on the Undertow exchange of the request handled on the
 * calling thread. When the response is committed, Undertow writes the stored phrase after the status
 * code the response carries at that point. Setting the status code before or after the call leaves
 * the stored phrase in place. A response for which no phrase is set carries Undertow's standard
 * phrase for its status code, for example {@code HTTP/1.1 404 Not Found}.
 *
 * <p>Usage from an exception handler that returns a {@code ResponseEntity}:
 *
 * <pre>{@code
 * ReasonPhrase.set("Invalid input data");
 * return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}.
 *
 * <p>The class holds no state.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Writes {@code phrase} as the reason phrase of the current Undertow response status line (D-010).
     *
     * <p>Must be called on the request thread before the response is committed; a phrase set after
     * the commit does not reach the status line. The value is passed to
     * {@link HttpServerExchange#setReasonPhrase(String)} unchanged. Undertow writes the phrase one
     * byte per character and replaces each CR or LF with a space; the phrase is expected to be
     * US-ASCII. {@code null} restores the standard phrase of the status code.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     * @throws IllegalStateException if no Undertow servlet request is bound to the calling thread,
     *     such as a plain unit test thread or a MockMvc request
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            throw new IllegalStateException("No Undertow request is bound to the current thread");
        }
        HttpServerExchange exchange = context.getExchange();
        exchange.setReasonPhrase(phrase);
    }
}
