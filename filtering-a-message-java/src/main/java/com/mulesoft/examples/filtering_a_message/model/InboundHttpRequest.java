package com.mulesoft.examples.filtering_a_message.model;

/**
 * HTTP request data bound by DiscountController for DiscountService.
 *
 * @param method           the request method exactly as received, for example {@code POST}
 * @param contentLength    the {@code Content-Length} value, or {@code -1} when the header is absent
 * @param transferEncoding the raw {@code Transfer-Encoding} header value, or {@code null} when absent
 * @param contentType      the raw {@code Content-Type} header value, or {@code null} when absent
 * @param body             the bytes read from the request; never {@code null}, and empty for a request without a body
 */
public record InboundHttpRequest(String method, long contentLength, String transferEncoding, String contentType, byte[] body) {
}
