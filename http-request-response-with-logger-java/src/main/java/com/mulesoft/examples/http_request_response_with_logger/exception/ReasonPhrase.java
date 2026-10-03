package com.mulesoft.examples.http_request_response_with_logger.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current Undertow exchange (D-010).
 *
 * <p>The phrase is stored on the {@code HttpServerExchange} of the request handled on the calling thread and is
 * written after the status code when the response is committed. A response for which no phrase is set carries
 * Undertow's standard phrase for its status code, for example {@code HTTP/1.1 200 OK}. Each project carries its own
 * copy of this class (D-004).
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
     * <p>The exchange is taken from the Undertow servlet request context bound to the calling thread, and the phrase
     * is handed to {@code HttpServerExchange.setReasonPhrase} unchanged; the status code is left as it is. When no
     * Undertow request context is present (a plain unit test, a MockMvc request or a thread that serves no Undertow
     * request), the method does nothing: it throws no exception and writes no log entry.
     *
     * <p>Must be called before the response is committed. A phrase set after the response is committed does not
     * reach the status line.
     *
     * @param phrase the text written after the status code on the HTTP/1.1 status line, for example
     *     {@code "Invalid input data"}
     */
    public static void set(String phrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null) {
            ctx.getExchange().setReasonPhrase(phrase);
        }
    }
}
