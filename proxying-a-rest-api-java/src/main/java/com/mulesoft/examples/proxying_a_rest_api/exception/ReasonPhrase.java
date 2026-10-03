package com.mulesoft.examples.proxying_a_rest_api.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase onto the HTTP/1.1 status line of the response to the current
 * Undertow request (D-010).
 *
 * <p>Usage from a handler method that runs on the request thread, before the response is
 * committed:
 *
 * <pre>{@code
 * ReasonPhrase.set(reason);
 * return ResponseEntity.status(status).body(body);
 * }</pre>
 *
 * <p>A response for which no phrase is set carries Undertow's standard phrase for its status code,
 * for example {@code 200 OK}. The class holds no state and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow exchange, which writes it
     * onto the HTTP/1.1 status line beside the status code when the response is committed (D-010).
     *
     * <p>Callers call this method before the response is committed. The status code may be set
     * before or after the call: the phrase stays on the exchange until the status line is written
     * (D-242). A call after the response is committed leaves the status line already sent
     * unchanged. When the calling thread has no Undertow servlet request context, for example
     * under MockMvc or in a plain unit test, the method returns without effect and throws nothing
     * (D-242).
     *
     * @param phrase the reason phrase, sent unchanged; {@code null} keeps Undertow's standard
     *               phrase for the status code
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            return;
        }
        context.getExchange().setReasonPhrase(phrase);
    }
}
