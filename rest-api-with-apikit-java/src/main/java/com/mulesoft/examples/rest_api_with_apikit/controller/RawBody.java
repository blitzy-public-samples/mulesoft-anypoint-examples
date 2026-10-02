package com.mulesoft.examples.rest_api_with_apikit.controller;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a status and raw bytes without setting a {@code Content-Type} header (D-066).
 *
 * <p>The class adds no header of its own; headers the caller set before the call, for example
 * {@code Location}, are sent as set. It holds no state, is safe for concurrent use and is not
 * instantiable. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * response.setHeader(HttpHeaders.LOCATION, "/api/console/");
 * RawBody.write(response, HttpServletResponse.SC_FOUND, new byte[0]);
 * }</pre>
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes a status and raw bytes without setting a {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>a non-empty {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}; a {@code null} or zero-length body
     *       writes no bytes;</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the response.</li>
     * </ol>
     *
     * <p>No content type, content length, character encoding or locale is set, and the response
     * writer is never opened; the container frames the flushed body (D-066, D-163).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes; {@code null} or an empty array writes no body
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        response.setStatus(status);
        var out = response.getOutputStream();
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }
}
