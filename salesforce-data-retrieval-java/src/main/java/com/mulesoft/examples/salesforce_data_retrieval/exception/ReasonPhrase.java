package com.mulesoft.examples.salesforce_data_retrieval.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current response served by
 * embedded Undertow (D-010).
 *
 * <p>Responses for which no phrase is set carry Undertow's standard phrase for their status code,
 * for example {@code 200 OK}. {@link #set(String)} stores the phrase on the Undertow exchange of
 * the request handled on the calling thread. When the response is committed, Undertow writes the
 * stored phrase after the status code the response carries at that point. Setting the status code
 * before or after the call leaves the stored phrase in place.
 *
 * <p>Status set on the servlet response, then the phrase:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>Phrase set by a handler that returns a {@code ResponseEntity}; Spring MVC applies the
 * entity's status to the servlet response after the handler returns:
 *
 * <pre>{@code
 * ReasonPhrase.set("Invalid input data");
 * return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
 * }</pre>
 *
 * <p>Both status lines read {@code HTTP/1.1 400 Invalid input data}.
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
