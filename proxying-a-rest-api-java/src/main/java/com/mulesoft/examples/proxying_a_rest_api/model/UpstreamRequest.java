package com.mulesoft.examples.proxying_a_rest_api.model;

import java.io.InputStream;
import java.util.List;

/**
 * The request the proxy sends upstream: method, path, query string, header lines in send order, and the body
 * stream.
 *
 * @param method the request method to send
 * @param rawPath the request path to send, as received from the caller, not decoded
 * @param query the query string to send, re-encoded, or {@code null} when none is sent
 * @param headers the header lines to send, in order, repeated names kept
 * @param body the body stream to send, or {@code null} when no body is sent
 */
public record UpstreamRequest(String method, String rawPath, String query, List<HttpHeaderField> headers, InputStream body) { }
