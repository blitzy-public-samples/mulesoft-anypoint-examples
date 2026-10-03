package com.mulesoft.examples.http_oauth_provider.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes a status and raw body bytes to a servlet response without setting a Content-Type header
 * (D-066). The only header it sets is {@code Content-Length} (D-244).
 *
 * <p>Usage, with the caller's own headers set before the call:
 *
 * <pre>{@code
 * response.setHeader("Location", location);
 * RawBody.write(response, HttpServletResponse.SC_FOUND, body);
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits it, without setting a
     * {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} body is taken as an empty body;</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to the body length (D-244);</li>
     *   <li>a non-empty body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>{@link HttpServletResponse#flushBuffer()} commits the status line and headers, also for
     *       an empty body.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * {@code sendError} is never called, the reason phrase is left as it is, and no other header is
     * added, removed or changed. An {@link IOException} propagates to the caller.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes, written unchanged; {@code null} is written as an empty body
     * @throws IOException when the response cannot be written
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body;
        response.setStatus(status);
        response.setContentLength(bytes.length);
        if (bytes.length > 0) {
            ServletOutputStream out = response.getOutputStream();
            out.write(bytes);
        }
        response.flushBuffer();
    }
}
