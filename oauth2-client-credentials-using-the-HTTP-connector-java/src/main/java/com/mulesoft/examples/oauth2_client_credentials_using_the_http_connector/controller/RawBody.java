package com.mulesoft.examples.oauth2_client_credentials_using_the_http_connector.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Writes a status and raw body bytes without a Content-Type header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-199). A {@code Content-Type}
 * or any other header the caller set before the call is sent as the caller set it. Usage from a
 * handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 404, ("No listener for endpoint: " + request.getRequestURI())
 *         .getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} without a Content-Type header
     * (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} body is taken as an empty array (D-199);</li>
     *   <li>the response status is set to {@code status} with
     *       {@link HttpServletResponse#setStatus(int)};</li>
     *   <li>{@code Content-Length} is set to the body length (D-199);</li>
     *   <li>every byte of the body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status and
     *       headers; the stream is left open for the container to close.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and the response is neither reset nor sent through {@code sendError}. An empty or
     * {@code null} body sends {@code Content-Length: 0} and no body bytes.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 200}, {@code 404} or {@code 500}
     * @param body     the body bytes, written unchanged; {@code null} is written as an empty body
     * @throws NullPointerException if {@code response} is {@code null}; nothing is written
     * @throws UncheckedIOException if the output stream cannot be obtained, written or flushed; its
     *                              cause is the {@link IOException} the container raised (D-199)
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
            throw new UncheckedIOException(e);
        }
    }
}
