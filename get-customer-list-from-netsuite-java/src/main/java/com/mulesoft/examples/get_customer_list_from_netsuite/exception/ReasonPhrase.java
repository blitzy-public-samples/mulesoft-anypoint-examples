package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Sets the reason phrase of the current HTTP response's status line on Undertow; no effect outside
 * an Undertow request (D-010). Must be called before the response is committed.
 *
 * <p>Responses for which no phrase is set carry Undertow's standard phrase for their status code,
 * for example {@code 200 OK}, {@code 404 Not Found} or {@code 500 Internal Server Error}. Each
 * project carries its own copy of this class (D-004).
 *
 * <p>Usage, before the response is committed:
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

    private ReasonPhrase() {
    }

    /**
     * Sets the reason phrase of the current HTTP response's status line on Undertow; no effect
     * outside an Undertow request (D-010), for example in a plain unit test, a MockMvc request or a
     * thread without an Undertow request context, where the call returns without an exception or a
     * log entry (D-190). Leaves the status code unchanged.
     *
     * <p>Must be called before the response is committed. A phrase set after the response is
     * committed does not reach the status line.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Invalid input data"}
     */
    public static void set(String phrase) {
        ServletRequestContext context = ServletRequestContext.current();
        if (context != null) {
            context.getExchange().setReasonPhrase(phrase);
        }
    }
}
