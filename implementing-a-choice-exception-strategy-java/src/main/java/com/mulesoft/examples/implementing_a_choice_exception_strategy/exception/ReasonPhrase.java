package com.mulesoft.examples.implementing_a_choice_exception_strategy.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom HTTP reason phrase on the current Undertow exchange; does nothing when no Undertow
 * request is active (D-010).
 *
 * <p>Usage, after the status is set and before the response is committed:
 *
 * <pre>{@code
 * response.setStatus(400);
 * ReasonPhrase.set("Invalid input data");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 400 Invalid input data}; with
 * {@code "Missing input data"} it reads {@code HTTP/1.1 400 Missing input data}. The class holds no
 * state and is not instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the response to the Undertow request handled on the calling thread.
     * Leaves the status code unchanged. Does nothing, and throws nothing, when the calling thread has
     * no Undertow request context, for example in a plain unit test or a MockMvc request (D-234).
     *
     * <p>Call after the response status is set and before the response is committed.
     *
     * @param reasonPhrase the text written after the status code on the HTTP/1.1 status line, for
     *     example {@code "Invalid input data"}; {@code null} leaves Undertow's standard phrase for the
     *     status code, for example {@code Internal Server Error} for 500
     */
    public static void set(String reasonPhrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null) {
            ctx.getExchange().setReasonPhrase(reasonPhrase);
        }
    }
}
