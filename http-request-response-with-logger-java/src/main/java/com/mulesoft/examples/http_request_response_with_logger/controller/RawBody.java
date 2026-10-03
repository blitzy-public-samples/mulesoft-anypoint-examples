package com.mulesoft.examples.http_request_response_with_logger.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, a {@code Content-Length} header and raw body bytes to a servlet response
 * with no {@code Content-Type} header (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets (D-152, D-222). Headers the caller
 * set before the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, "/echo".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits the response, with no
     * {@code Content-Type} header (D-066).
     *
     * <p>The writes, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the response buffer is flushed with {@link HttpServletResponse#flushBuffer()}, also for an
     *       empty body, which commits the status and headers.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, and the response writer is never
     * opened. An empty array sends {@code Content-Length: 0} and no body bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response
     * @param body     the body bytes, written unchanged
     * @throws NullPointerException if {@code response} is {@code null}, or if {@code body} is
     *                              {@code null}, in which case the status is already set
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
