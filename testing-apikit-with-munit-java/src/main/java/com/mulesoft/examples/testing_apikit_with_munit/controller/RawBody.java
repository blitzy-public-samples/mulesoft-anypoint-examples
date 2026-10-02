package com.mulesoft.examples.testing_apikit_with_munit.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without setting a
 * {@code Content-Type} header (D-066).
 *
 * <p>No header is added; headers the caller set before the call, for example
 * {@code Location}, are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * response.setHeader("Location", "/api/console/");
 * RawBody.write(response, 302, new byte[0]);
 * }</pre>
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Sets the status, writes the body bytes unchanged through the response output stream and
     * flushes it, which commits the response. No {@code Content-Type}, character encoding or
     * locale is set, and the response writer is never opened (D-066).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; {@code null} or an empty array writes no body
     * @throws IOException if the output stream cannot be written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        ServletOutputStream out = response.getOutputStream();
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }
}
