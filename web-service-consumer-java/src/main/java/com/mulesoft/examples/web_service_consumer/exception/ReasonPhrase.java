package com.mulesoft.examples.web_service_consumer.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current response on embedded Undertow (D-010).
 *
 * <p>The phrase is stored on the Undertow {@code HttpServerExchange} of the request being handled and is written,
 * together with the status code set at that moment, when the response is committed. Without a custom phrase
 * Undertow writes its standard phrase for the status code, for example {@code OK}, {@code Not Found} or
 * {@code Internal Server Error}.
 *
 * <p>Status set on the servlet response, then the phrase:
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>Phrase set by a handler that returns a {@code ResponseEntity}; Spring MVC applies the entity's status to the
 * servlet response after the handler returns:
 * <pre>{@code
 * ReasonPhrase.set("Invalid input data");
 * return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
 * }</pre>
 *
 * <p>Both status lines read {@code HTTP/1.1 400 Invalid input data}.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the current Undertow response.
     *
     * <p>Call this before the body is written or the response is committed. On Undertow 2.3.17.Final the phrase stays
     * on the exchange until commit and is written with the status code set at that moment, whether that code was set
     * before or after this call; a call after commit leaves the status line already sent unchanged. Without a current
     * Undertow servlet request context, as in MockMvc or plain unit tests, the call does nothing (D-158).
     *
     * @param phrase the reason phrase written verbatim after the status code; {@code null} restores Undertow's
     *               standard phrase for the status code
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().setReasonPhrase(phrase);
        }
    }
}
