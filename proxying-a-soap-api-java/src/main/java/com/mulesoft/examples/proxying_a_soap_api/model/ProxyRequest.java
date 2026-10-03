package com.mulesoft.examples.proxying_a_soap_api.model;

import org.springframework.http.HttpHeaders;

/**
 * One request received by flow {@code main} on {@code /}, carried as raw bytes (D-059).
 *
 * @param method         the HTTP method as received, for example {@code POST} or {@code GET}
 * @param headers        every request header name and value, in the order received
 * @param query          the raw query string without the leading {@code ?}, for example {@code wsdl} for
 *                       {@code GET /?wsdl}; {@code null} when the request has none
 * @param body           the raw request body bytes; an empty array when the request has no body, never
 *                       {@code null}. The constructor does not check this; the controller supplies it
 * @param requestAddress the request URL without the query string, for example
 *                       {@code http://localhost:8081/}; the {@code soap:address} written into a served WSDL
 */
public record ProxyRequest(String method, HttpHeaders headers, String query, byte[] body, String requestAddress) {
}
