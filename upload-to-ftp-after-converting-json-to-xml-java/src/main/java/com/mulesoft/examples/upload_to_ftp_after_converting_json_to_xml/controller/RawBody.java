package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, a {@code Content-Length} header and raw body bytes to a servlet response,
 * with no {@code Content-Type} header (D-066, D-438).
 *
 * <p>{@code Content-Length} is the only header this class sets. Headers the caller set before the
 * call are sent as set, and a reason phrase already set for the same status stays on the status
 * line (D-010). The class is this project's own copy (D-004). Usage from a handler method that
 * returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, xmlBytes);
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits the response, with no
     * {@code Content-Type} header (D-066, D-438).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the status is set only when {@code response.getStatus()} differs from {@code status};
     *       an equal status leaves the response status, and any reason phrase already set for it,
     *       unchanged (D-010);</li>
     *   <li>{@code Content-Length} is set to {@code body.length}; an empty array sends
     *       {@code Content-Length: 0} and no body;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status and
     *       headers; the stream is left open for the container to complete the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, no other header is added, and the
     * response writer is never opened.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response, for example {@code 200}
     * @param body     the body bytes, written unchanged
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; a
     *                              {@code null} body is detected after step 1
     * @throws IOException          if the output stream cannot be obtained, written or flushed
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
