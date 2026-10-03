package com.mulesoft.examples.munit_short_tutorial.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, a {@code Content-Length} header and raw body bytes to a servlet response,
 * without a {@code Content-Type} header or any other header of its own. See D-066.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits the response.
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length}; an empty array sends
     *       {@code Content-Length: 0} and no body;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and left open for the container
     *       to finish the response.</li>
     * </ol>
     *
     * <p>No {@code Content-Type} and no character encoding is set, the response writer is never
     * opened, and {@code Content-Length} is the only header this method sets (D-066, D-152).
     * Headers set on {@code response} before the call are sent as set. Usage from a handler method
     * that returns {@code void}:
     *
     * <pre>{@code
     * RawBody.write(response, HttpServletResponse.SC_OK,
     *         "response_payload_1".getBytes(StandardCharsets.UTF_8));
     * }</pre>
     *
     * @param response the servlet response
     * @param status   the HTTP status code
     * @param body     the exact bytes sent as the response body
     * @throws NullPointerException if {@code response} is {@code null}, or if {@code body} is
     *                              {@code null}, in which case the status is already set
     * @throws IOException          when the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
