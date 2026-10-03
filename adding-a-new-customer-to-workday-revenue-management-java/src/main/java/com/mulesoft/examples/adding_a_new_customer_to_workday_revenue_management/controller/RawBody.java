package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.controller;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Writes an HTTP status, a {@code Content-Length} header and raw body bytes to a servlet response
 * without a {@code Content-Type} header (D-066, D-176).
 */
public final class RawBody {

    private RawBody() {
    }

    /**
     * Writes {@code status}, {@code Content-Length} and the given bytes, a {@code null} body as zero
     * bytes, and flushes the output stream without setting a {@code Content-Type} header (D-066);
     * the status is set only when it differs from the current one (D-010, D-176).
     *
     * @param response the servlet response to write to
     * @param status   the HTTP status code
     * @param body     the body bytes, written unchanged; {@code null} writes no bytes
     * @throws IOException if the output stream cannot be obtained, written or flushed
     */
    public static void write(HttpServletResponse response, int status, byte[] body) throws IOException {
        if (response.getStatus() != status) {
            response.setStatus(status);
        }
        byte[] bytes = body == null ? new byte[0] : body;
        response.setContentLength(bytes.length);
        ServletOutputStream out = response.getOutputStream();
        out.write(bytes);
        out.flush();
    }
}
