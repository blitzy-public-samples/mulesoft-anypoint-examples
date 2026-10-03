/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.content_based_routing.exception;

import io.undertow.servlet.handlers.ServletRequestContext;

/**
 * Writes a custom reason phrase on the HTTP/1.1 status line of the current Undertow exchange (D-010).
 *
 * <p>Usage on the request thread, after the status is set and before the body is written:
 *
 * <pre>{@code
 * response.setStatus(HttpServletResponse.SC_NOT_FOUND);
 * ReasonPhrase.set("Not Found");
 * }</pre>
 *
 * <p>The status line then reads {@code HTTP/1.1 404 Not Found}. A response for which no phrase is
 * set carries Undertow's standard phrase for its status code. The class holds no state and is not
 * instantiable.
 */
public final class ReasonPhrase {

    private ReasonPhrase() {
    }

    /**
     * Sets {@code phrase} as the reason phrase of the current request's status line; has no effect
     * when no Undertow request context is bound.
     *
     * <p>The phrase is stored on the {@code HttpServerExchange} of the Undertow servlet request
     * context bound to the calling thread and is written after the status code when the response
     * is committed. A call on a thread without that context, such as a plain unit test or a MockMvc
     * request, returns without effect and without an exception or a log entry.
     *
     * @param phrase the text written after the status code on the status line, for example
     *     {@code "Internal Server Error"}
     */
    public static void set(String phrase) {
        ServletRequestContext ctx = ServletRequestContext.current();
        if (ctx != null) {
            ctx.getExchange().setReasonPhrase(phrase);
        }
    }
}
