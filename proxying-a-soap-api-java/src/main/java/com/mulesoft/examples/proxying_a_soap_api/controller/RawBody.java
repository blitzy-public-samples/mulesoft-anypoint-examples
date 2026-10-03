package com.mulesoft.examples.proxying_a_soap_api.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without setting a
 * {@code Content-Type} header (D-066), for the listener of
 * {@code proxying-a-soap-api/src/main/app/soap-api-proxy.xml:6}. The class holds no state and is
 * not instantiable.
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and sets no header (D-066, D-262).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status} only when
     *       {@link HttpServletResponse#getStatus()} returns a different code; an equal status is not
     *       applied again, and a reason phrase set through {@code ReasonPhrase} before the call stays
     *       on the status line (D-010);</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}; a {@code null} or empty body writes zero
     *       bytes;</li>
     *   <li>the output stream is flushed in every case, which commits the status and headers; the
     *       stream is left open for the container to complete the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or content length is set, no header is set or added,
     * the response writer is never opened, the response is never reset and {@code sendError} is
     * never called. Headers the caller added before the call, such as a copied upstream
     * {@code Content-Type}, are sent exactly as added; the container frames the body (D-262).
     *
     * <p>Usage from a handler that has not committed the response, after it has added the headers
     * the response carries:
     *
     * <pre>{@code
     * response.addHeader("Content-Type", upstreamContentType);
     * RawBody.write(response, upstreamStatus, upstreamBody);
     * }</pre>
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code of the response
     * @param body     the body bytes, written unchanged; {@code null} writes zero bytes
     * @throws NullPointerException if {@code response} is {@code null}
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        // Status applied only when it differs from the current one; the reason phrase on the
        // exchange is left as the caller set it (D-010, D-262).
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        // No Content-Length or other framing header is set; Undertow frames the flushed body (D-262).
        ServletOutputStream out = response.getOutputStream();
        if (body != null && body.length > 0) {
            out.write(body);
        }
        out.flush();
    }
}
