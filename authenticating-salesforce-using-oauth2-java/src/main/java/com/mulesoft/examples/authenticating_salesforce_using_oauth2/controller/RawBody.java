package com.mulesoft.examples.authenticating_salesforce_using_oauth2.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Writes a status and raw body bytes with no Content-Type header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-192). Headers the caller set
 * before the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, reply.getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable. */
    private RawBody() {
    }

    /**
     * Writes a status and raw body bytes with no Content-Type header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} body is written as an empty body (D-192);</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to the body length; the response is sent with that fixed
     *       length and is not chunked (D-192);</li>
     *   <li>every byte of the body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, and is left open for the
     *       container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and neither {@code sendError} nor {@code sendRedirect} is called. An empty or {@code null}
     * body sends {@code Content-Length: 0} and no body bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 200} or {@code 500}
     * @param body     the body bytes, written unchanged; {@code null} is written as an empty body
     * @throws NullPointerException if {@code response} is {@code null}; nothing is written
     * @throws UncheckedIOException if the output stream cannot be obtained, written or flushed; the
     *                              message names the status and body length of the failed write and
     *                              the cause is the {@link IOException} raised by the stream (D-192)
     */
    public static void write(HttpServletResponse response, int status, byte[] body) {
        byte[] bytes = body == null ? new byte[0] : body;
        response.setStatus(status);
        response.setContentLength(bytes.length);
        try {
            ServletOutputStream out = response.getOutputStream();
            out.write(bytes);
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Writing the " + status + " response body of " + bytes.length + " bytes failed", e);
        }
    }
}
