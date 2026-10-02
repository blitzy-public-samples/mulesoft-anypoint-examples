package com.mulesoft.examples.login_form_using_the_http_connector.model;

/**
 * HTTP status, Content-Type header value and body bytes of a rendered page or a relayed response.
 *
 * @param status      the HTTP status code to write
 * @param contentType the exact {@code Content-Type} header value, or {@code null} when no header
 *                    is sent (D-066)
 * @param body        the raw response body bytes
 */
public record PageResponse(int status, String contentType, byte[] body) {
}
