package com.mulesoft.examples.proxying_a_soap_api.model;

import org.springframework.http.HttpHeaders;

/**
 * The upstream answer to one request of flow {@code main}, carried as raw bytes (D-059).
 *
 * @param status  the HTTP status code returned by the upstream service
 * @param headers the response headers to send to the caller, names and values in upstream order
 * @param body    the raw response body bytes; an empty array when there is none. The client supplies it;
 *                the constructor does not check this
 */
public record ProxyResponse(int status, HttpHeaders headers, byte[] body) {
}
