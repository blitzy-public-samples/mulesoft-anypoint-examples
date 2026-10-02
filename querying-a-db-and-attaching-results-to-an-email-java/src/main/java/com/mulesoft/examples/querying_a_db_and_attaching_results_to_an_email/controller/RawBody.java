package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response and sets no {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-152). Headers the caller set
 * before the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, csv.getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and sets no {@code Content-Type}
     * header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length} (D-152);</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and is left open for the
     *       container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and the response is not reset.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; an empty array sends {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
