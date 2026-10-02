package com.mulesoft.examples.testing_apikit_with_munit.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the HTTP/1.1 status line on the underlying Undertow exchange; does
 * nothing when the request is not served by Undertow. See D-010.
 *
 * <p>The exchange is the one of the Undertow request handled on the calling thread. Undertow writes
 * the stored phrase after the status code when the response is committed. A response for which no
 * phrase is set carries Undertow's standard phrase for its status code, for example {@code 200 OK}.
 *
 * <p>Usage from a handler, before the response is committed:
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

    /** Not instantiable. */
    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} unchanged as the reason phrase of the response to the Undertow request
     * handled on the calling thread. Leaves the status code unchanged. Reads the Undertow request
     * context bound to the calling thread; without one, for example in a plain unit test or a
     * MockMvc request, the call does nothing and throws nothing (D-160).
     *
     * <p>Precondition: call before the response is committed. A phrase set after the response is
     * committed does not reach the status line.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}; {@code null} restores the standard phrase of the status code
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().setReasonPhrase(phrase);
        }
    }
}
