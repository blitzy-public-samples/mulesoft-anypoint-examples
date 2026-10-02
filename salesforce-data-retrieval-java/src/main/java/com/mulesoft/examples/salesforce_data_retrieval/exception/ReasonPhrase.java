package com.mulesoft.examples.salesforce_data_retrieval.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current response served by
 * embedded Undertow (D-010).
 *
 * <p>Responses for which no phrase is set carry Undertow's standard phrase for their status code,
 * for example {@code 200 OK}. A caller sets the status first, for example through a
 * {@code ResponseEntity}, and then calls {@link #set(String)}:
 *
 * <pre>{@code
 * ResponseEntity<String> response = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
 * ReasonPhrase.set("Invalid input data");
 * return response;
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() { }

    /**
     * Writes {@code phrase} as the HTTP/1.1 reason phrase of the current Undertow exchange (D-010).
     *
     * <p>Must be called on the request thread before the response is committed. The value is
     * passed to {@code HttpServerExchange.setReasonPhrase} unchanged.
     *
     * @param phrase the reason phrase written after the status code on the status line
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread
     */
    public static void set(String phrase) {
        ServletRequestContext.requireCurrent().getExchange().setReasonPhrase(phrase);
    }
}
