package com.mulesoft.examples.proxying_a_rest_api.model;

import java.util.List;

/**
 * The response received from the upstream API: status code, every response header value in iteration order,
 * and the raw body bytes.
 *
 * <p>The accessors return the list and the array passed to the constructor; {@code equals} and {@code hashCode}
 * compare {@code body} by reference (D-374).
 *
 * @param status the upstream HTTP status code.
 * @param headers every upstream response header value, one entry per value, in iteration order.
 * @param body the upstream response body bytes, unparsed.
 */
public record UpstreamResponse(int status, List<HttpHeaderField> headers, byte[] body) { }
