package com.mulesoft.examples.mule_component_bindings.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status and raw body bytes to a servlet response without a {@code Content-Type}
 * header (D-066).
 *
 * <p>The only header this class sets is {@code Content-Length} (D-235). Headers the caller set
 * before the call are sent as set. The class holds no state, is safe for concurrent use and is not
 * instantiable.
 *
 * <p>Callers in this project:
 * <ul>
 *   <li>{@code TwitterSearchController.muleComponentBindingsFlow1}: 200 with the Java-serialized
 *       tweet list;</li>
 *   <li>{@code config.PortPathGuardFilter}: 404 with {@code No listener for endpoint: <path>}
 *       (D-011);</li>
 *   <li>{@code exception.GlobalExceptionHandler.unexpected}: 500 with the exception message.</li>
 * </ul>
 *
 * <p>Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, HttpServletResponse.SC_NOT_FOUND,
 *         "No listener for endpoint: /api".getBytes(StandardCharsets.UTF_8));
 * }</pre>
 */
public final class RawBody {

    /** Not instantiable; {@link #write(HttpServletResponse, int, byte[])} is the only entry point. */
    private RawBody() {
    }

    /**
     * Writes the status, {@code Content-Length} and body bytes; sets no {@code Content-Type}
     * (D-066).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>a {@code null} {@code body} is taken as an empty array (D-235);</li>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to the body length (D-235);</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()};</li>
     *   <li>the output stream is flushed, also for an empty body, which commits the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, no other header is added, and the
     * response writer is never opened (D-066).
     *
     * @param response the servlet response to write to; must not be {@code null}
     * @param status   the HTTP status code, for example {@code 200}, {@code 404} or {@code 500}
     * @param body     the body bytes; {@code null} or an empty array sends
     *                 {@code Content-Length: 0} and no body bytes
     * @throws IOException if the output stream cannot be obtained, written or flushed
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
