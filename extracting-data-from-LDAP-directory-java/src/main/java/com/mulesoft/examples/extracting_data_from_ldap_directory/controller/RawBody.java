package com.mulesoft.examples.extracting_data_from_ldap_directory.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, a {@code Content-Length} header and raw body bytes to a servlet response,
 * with no {@code Content-Type} header (D-066, D-214).
 *
 * <p>{@code Content-Length} is the only header this class sets. Headers the caller set before the
 * call are sent as set, and a reason phrase already set for the same status stays on the status
 * line (D-010). Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_OK, ldifText.getBytes(StandardCharsets.UTF_8));
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
     * {@code Content-Type} header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} {@code response} or {@code body} is rejected with
     *       {@link NullPointerException} before the response is touched (D-214);</li>
     *   <li>the status is set only when {@code response.getStatus()} differs from {@code status};
     *       an equal status leaves the response status, and any reason phrase already set for it,
     *       unchanged (D-010);</li>
     *   <li>{@code Content-Length} is set to {@code body.length}; an empty array sends
     *       {@code Content-Length: 0} and no body;</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the status and
     *       headers; the stream is left open for the container to finish the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * and the response is neither reset nor sent through {@code sendError}.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response, for example {@code 200}
     * @param body     the body bytes, written unchanged
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        if (response == null) {
            throw new NullPointerException("response");
        }
        if (body == null) {
            throw new NullPointerException("body");
        }
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        response.setContentLength(body.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(body);
        out.flush();
    }
}
