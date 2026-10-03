package com.mulesoft.examples.proxying_a_rest_api.model;

import java.util.List;

/**
 * The response the proxy listener returns to the caller: status code, the header lines to write, and
 * the body bytes. See D-059.
 *
 * <p>The components hold the list and the array passed to the constructor, not copies (D-383).
 *
 * @param status the status code to write (200 for every forwarded exchange)
 * @param headers the header lines to write, in order, repeated names kept
 * @param body the body bytes to write, unchanged from the upstream response
 */
public record ProxyResponse(int status, List<HttpHeaderField> headers, byte[] body) { }
