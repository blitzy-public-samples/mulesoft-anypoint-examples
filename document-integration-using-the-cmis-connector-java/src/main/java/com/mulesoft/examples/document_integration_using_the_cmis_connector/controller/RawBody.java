package com.mulesoft.examples.document_integration_using_the_cmis_connector.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Writes a status and raw body bytes to a servlet response without setting a {@code Content-Type}
 * or any other header (D-066).
 *
 * <p>Headers the caller set before the call are sent as set. Usage from a handler method that
 * returns {@code void}:
 *
 * <pre>{@code
 * response.setStatus(404);
 * ReasonPhrase.set("Not Found");
 * RawBody.write(response, 404, "No listener for endpoint: /unknown".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 *
 * <p>The class holds no state and is not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} with no {@code Content-Type} or
     * other header (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the status is set only when it differs from {@link HttpServletResponse#getStatus()}, and
     *       an unchanged status keeps the reason phrase already on the response (D-010);</li>
     *   <li>a {@code null} body is written as an empty body;</li>
     *   <li>a non-empty body is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the response; the
     *       stream is left open for the container to finish the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding, content length or locale is set, no header is set or
     * added, and the response writer is never opened.
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response
     * @param body     the body bytes, written unchanged; {@code null} or an empty array writes no body
     * @throws UncheckedIOException if the output stream cannot be obtained, written or flushed; its
     *                              cause is the {@link IOException}
     * @throws NullPointerException if {@code response} is {@code null}
     */
    public static void write(HttpServletResponse response, int status, byte[] body) {
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        byte[] bytes = body != null ? body : new byte[0];
        try {
            ServletOutputStream out = response.getOutputStream();
            if (bytes.length > 0) {
                out.write(bytes);
            }
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
