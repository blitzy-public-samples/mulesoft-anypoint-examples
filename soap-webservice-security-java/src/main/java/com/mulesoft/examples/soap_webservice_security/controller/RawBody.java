package com.mulesoft.examples.soap_webservice_security.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without a {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-152). Headers the caller set
 * before the call are sent as set. Usage from an exception handler method that returns
 * {@code void}:
 *
 * <pre>{@code
 * byte[] body = message.getBytes(StandardCharsets.UTF_8);
 * RawBody.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, body);
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable. */
    private RawBody() {
    }

    /**
     * Writes the status, a {@code Content-Length} header and the body bytes to {@code response};
     * sets no {@code Content-Type} and no character encoding (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} {@code body} is written as a zero-length body;</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to the body length (D-152);</li>
     *   <li>every byte of the body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the response; the
     *       stream is left open for the container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, and the response writer is never
     * opened. A {@code null} or empty body sends {@code Content-Length: 0} and no body bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 200} or {@code 500}
     * @param body     the body bytes, written unchanged; {@code null} is written as an empty body
     * @throws NullPointerException if {@code response} is {@code null}
     * @throws IOException          if the response output stream cannot be obtained, written or
     *                              flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body;
        response.setStatus(status);
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
