package com.mulesoft.examples.foreach_processing_and_choice_routing.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without setting any header (D-066).
 *
 * <p>No {@code Content-Type}, character encoding, locale or content length is set by this class, and
 * the output stream is neither flushed nor closed: the servlet container completes the response and
 * frames the buffered body. Headers a caller set before the call are sent as set.
 *
 * <p>Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, quote.toString().getBytes(StandardCharsets.UTF_8));
 * }</pre>
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Sets the status and writes the body bytes unchanged to the response output stream (D-066).
     *
     * <p>A {@code null} or zero-length body writes no bytes and the response carries the status only.
     * The response writer is never opened.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; {@code null} or an empty array writes no body
     * @throws IOException if the response output stream cannot be obtained or written
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        if (body != null && body.length > 0) {
            response.getOutputStream().write(body);
        }
    }
}
