package com.mulesoft.examples.http_multipart_request.controller;

import com.mulesoft.examples.http_multipart_request.exception.ReasonPhrase;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Objects;

/**
 * Writes a status, an optional reason phrase and raw body bytes without a {@code Content-Type}
 * header (D-066).
 *
 * <p>{@code Content-Length} is the only header this class sets, and every write commits the
 * response (D-439). Headers set and bytes written on the response after the call are not sent.
 * Usage from a handler method that returns {@code void}:
 *
 * <pre>{@code
 * RawBody.write(response, 200, fileUploadService.httpRenderFlow());
 * RawBody.write(response, 200, UploadController.FILE_UPLOADED, body);
 * }</pre>
 *
 * <p>The second call puts {@code HTTP/1.1 200 File was uploaded.} on the status line (D-010). The
 * class holds no state and is not instantiable.
 */
public final class RawBody {

    /** Not instantiable; the class exposes only the static {@code write} methods. */
    private RawBody() {
    }

    /**
     * Writes {@code status} and {@code body} to {@code response} and commits the response, with no
     * {@code Content-Type} header (D-066, D-439).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}; an empty array writes nothing;</li>
     *   <li>{@link HttpServletResponse#flushBuffer()} sends the status line, the headers and the
     *       body. Commits the response.</li>
     * </ol>
     *
     * <p>No content type, character encoding or locale is set, the response writer is never opened,
     * {@code sendError} is never called and no other header is added. The reason phrase is
     * Undertow's standard phrase for {@code status}, for example {@code HTTP/1.1 200 OK}, unless one
     * was set on the exchange before the call (D-010).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code, for example {@code 200}, {@code 404}, {@code 405} or
     *                 {@code 500}
     * @param body     the body bytes, written unchanged; an empty array sends
     *                 {@code Content-Length: 0} and no body
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}; the response
     *                              is then left untouched
     * @throws IOException          if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        requireArguments(response, body);
        response.setStatus(status);
        writeBody(response, body);
    }

    /**
     * Writes {@code status}, {@code reasonPhrase} and {@code body} to {@code response} and commits
     * the response, with no {@code Content-Type} header (D-066, D-439).
     *
     * <p>The steps, in order:
     * <ol>
     *   <li>the response status is set to {@code status};</li>
     *   <li>{@code reasonPhrase} is set through {@link ReasonPhrase#set(String)}. Sets the reason
     *       phrase of the status line (D-010); {@code null} restores Undertow's standard phrase for
     *       {@code status};</li>
     *   <li>{@code Content-Length} is set to {@code body.length};</li>
     *   <li>every byte of {@code body} is written unchanged through
     *       {@link HttpServletResponse#getOutputStream()}; an empty array writes nothing;</li>
     *   <li>{@link HttpServletResponse#flushBuffer()} sends the status line, the headers and the
     *       body. Commits the response.</li>
     * </ol>
     *
     * <p>{@code write(response, 200, "File was uploaded.", body)} sends the status line
     * {@code HTTP/1.1 200 File was uploaded.}. No content type, character encoding or locale is set,
     * the response writer is never opened, {@code sendError} is never called and no other header is
     * added.
     *
     * @param response     the servlet response of the Undertow request handled on the calling
     *                     thread
     * @param status       the HTTP status code, for example {@code 200}
     * @param reasonPhrase the reason phrase sent after the status code, for example
     *                     {@code File was uploaded.}
     * @param body         the body bytes, written unchanged; an empty array sends
     *                     {@code Content-Length: 0} and no body
     * @throws NullPointerException  if {@code response} or {@code body} is {@code null}; the
     *                               response is then left untouched
     * @throws IllegalStateException if no Undertow servlet request is active on the calling thread;
     *                               the status is then set and no header or body byte is written
     * @throws IOException           if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, String reasonPhrase, byte[] body)
            throws IOException {
        requireArguments(response, body);
        response.setStatus(status);
        ReasonPhrase.set(reasonPhrase);
        writeBody(response, body);
    }

    /**
     * Rejects a {@code null} response or body before the response is touched.
     *
     * @param response the servlet response to write to
     * @param body     the body bytes
     * @throws NullPointerException if {@code response} or {@code body} is {@code null}
     */
    private static void requireArguments(HttpServletResponse response, byte[] body) {
        Objects.requireNonNull(response, "response");
        Objects.requireNonNull(body, "body");
    }

    /**
     * Sets {@code Content-Length} to {@code body.length}, writes {@code body} through the servlet
     * output stream and commits the response with {@link HttpServletResponse#flushBuffer()}.
     *
     * @param response the servlet response whose status is already set
     * @param body     the body bytes, written unchanged
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    private static void writeBody(HttpServletResponse response, byte[] body) throws IOException {
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
        response.flushBuffer();
    }
}
