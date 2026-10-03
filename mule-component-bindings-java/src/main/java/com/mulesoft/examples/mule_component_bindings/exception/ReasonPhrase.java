package com.mulesoft.examples.mule_component_bindings.exception;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the response that embedded Undertow
 * is serving on the calling thread (D-010). Each project carries its own copy of this class (D-004).
 *
 * <p>Responses for which no phrase is set carry Undertow's standard phrase for their status code,
 * for example {@code HTTP/1.1 200 OK} or {@code HTTP/1.1 404 Not Found}.
 *
 * <p>Usage from a handler, after the status code is set and before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}. The class holds no state
 * and is not instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Writes {@code phrase} as the reason phrase of the current Undertow exchange's HTTP/1.1 status
     * line (D-010). The method takes the phrase only and reaches the exchange through the Undertow
     * servlet request context bound to the calling thread (D-229).
     *
     * <p>Callers set the status code first and call this method before the response is committed.
     * The phrase is handed unchanged to {@link HttpServerExchange#setReasonPhrase(String)} and is
     * written after the status code the response carries when it is committed; {@code null} leaves
     * Undertow's standard phrase for that status code.
     *
     * <p>The call returns without effect, without an exception and without a log entry when:
     *
     * <ul>
     *   <li>no Undertow request is bound to the calling thread, for example in a plain unit test, a
     *       MockMvc request or a thread other than the request thread (D-229);
     *   <li>the response is already committed, leaving the status line already sent unchanged
     *       (D-229).
     * </ul>
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            return;
        }
        HttpServerExchange exchange = context.getExchange();
        if (exchange.isResponseStarted()) {
            return;
        }
        exchange.setReasonPhrase(phrase);
    }
}
