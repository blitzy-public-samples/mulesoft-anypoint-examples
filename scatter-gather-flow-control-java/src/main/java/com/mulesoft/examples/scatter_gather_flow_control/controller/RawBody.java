package com.mulesoft.examples.scatter_gather_flow_control.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes without a {@code Content-Type} header (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets. Headers set on the response before
 * the call are sent as set. Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200,
 *         "Please find attached your Aggregation Report".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable; the only member is the static {@link #write} method. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and flushes the response.
     *
     * <p>Writes the body bytes with Content-Length and no Content-Type (D-066). The steps are:
     * <ol>
     *   <li>{@code response.setStatus(status)} runs only when {@code response.getStatus()} differs
     *       from {@code status}; an equal status leaves the status and a reason phrase already set
     *       on the exchange unchanged (D-010);</li>
     *   <li>{@code Content-Length} is set to {@code body.length}; an empty array sends
     *       {@code Content-Length: 0} and no body;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status line and
     *       headers; the stream stays open for the container to complete the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, and the response writer is never
     * opened: the response carries no media-type header (D-066).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response
     * @param body     the body bytes, written unchanged; a non-null array, possibly empty
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
