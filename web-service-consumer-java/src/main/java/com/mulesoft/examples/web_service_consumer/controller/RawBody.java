package com.mulesoft.examples.web_service_consumer.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a status and a raw byte body to a servlet response without setting a {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length}. Headers the caller set before the
 * call are sent as set. Usage from an exception handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 500, message.getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response}; no {@code Content-Type} header is
     * written (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>the content length is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and is left open for the
     *       container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, and the response writer is never
     * opened. An empty array sends {@code Content-Length: 0} and no body bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 404} or {@code 500}
     * @param body     the body bytes, written unchanged
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}
     * @throws IOException          if the response output stream cannot be obtained, written or
     *                              flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
