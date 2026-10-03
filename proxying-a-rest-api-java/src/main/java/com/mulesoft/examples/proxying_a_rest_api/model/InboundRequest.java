package com.mulesoft.examples.proxying_a_rest_api.model;

import java.io.InputStream;
import java.util.List;

/**
 * The request received by the proxy listener: method, undecoded path, raw query string, header lines
 * in arrival order, whether a body was sent, and the body stream. See D-059.
 *
 * @param method the request method as received ({@code HttpServletRequest.getMethod()})
 * @param rawPath the request URI path as received, not decoded
 *     ({@code HttpServletRequest.getRequestURI()})
 * @param rawQuery the query string as received, not decoded
 *     ({@code HttpServletRequest.getQueryString()}), or {@code null} when the request has none
 * @param headers every request header line, in arrival order, repeated names kept
 * @param bodyPresent {@code true} when the request carried a body ({@code Content-Length} greater
 *     than 0, or chunked transfer coding)
 * @param body the request body stream
 */
public record InboundRequest(String method, String rawPath, String rawQuery, List<HttpHeaderField> headers,
        boolean bodyPresent, InputStream body) { }
