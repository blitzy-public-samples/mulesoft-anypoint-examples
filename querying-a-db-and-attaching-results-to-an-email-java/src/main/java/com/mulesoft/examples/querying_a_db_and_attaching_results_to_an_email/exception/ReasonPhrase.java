package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.exception;

import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase onto the HTTP/1.1 status line of the current Undertow exchange; does nothing when
 * no Undertow exchange is reachable (D-010).
 *
 * <p>The phrase is stored on the {@code HttpServerExchange} of the request served by the calling thread and is
 * written after the status code when the response is committed. A response for which no phrase is set carries
 * Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK} or
 * {@code HTTP/1.1 500 Internal Server Error}. This project carries its own copy of the class (D-004).
 *
 * <p>Usage on the request thread, before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}. The class holds no state and is not
 * instantiable.
 */
public final class ReasonPhrase {

    /** Not instantiable; the class exposes only the static {@link #set(String)}. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current Undertow exchange (D-010).
     *
     * <p>The method takes the phrase alone. The exchange is the one held by the Undertow servlet request context
     * bound to the calling thread, and the phrase is handed to {@code HttpServerExchange.setReasonPhrase}
     * unchanged. The status code, headers and body are left as they are, and the status code may be set before or
     * after the call.
     *
     * <p>The method returns without effect, throwing no exception and writing no log entry, when the calling thread
     * has no Undertow request context or the context holds no exchange: a plain unit test, a MockMvc request or a
     * thread that serves no Undertow request. A phrase set after the response is committed does not reach the
     * status line, and a {@code null} phrase leaves Undertow's standard phrase for the status code on it.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Invalid input data"}; {@code null} selects the standard phrase
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context == null) {
            return;
        }
        HttpServerExchange exchange = context.getExchange();
        if (exchange != null) {
            exchange.setReasonPhrase(phrase);
        }
    }
}
