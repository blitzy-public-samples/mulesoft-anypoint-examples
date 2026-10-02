package com.mulesoft.examples.munit_short_tutorial.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes the reason phrase of the HTTP/1.1 status line on the current Undertow exchange (D-010).
 *
 * <p>Usage, after the status is set and before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_NOT_FOUND);
 * ReasonPhrase.set("Not Found");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 404 Not Found}. The class holds no state and is
 * not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Replaces the reason text of the status line of the response to the current request with
     * {@code phrase}. Leaves the status code unchanged. Does nothing when the calling thread has no
     * Undertow request context, for example in a plain unit test or a MockMvc request (D-128).
     *
     * <p>Precondition: the caller sets the status before calling this method and calls it before
     * the response is committed.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Not Found"}
     */
    public static void set(String phrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null) {
            ctx.getExchange().setReasonPhrase(phrase);
        }
    }
}
