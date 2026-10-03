package com.mulesoft.examples.get_customer_list_from_netsuite.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON error body {@code {message, upstreamSystem}} for the connector failure modes (D-007, D-020);
 * {@code upstreamSystem} is omitted when null.
 *
 * <pre>{@code
 * new ErrorResponse("NetSuite unavailable", "NetSuite")
 * // {"message":"NetSuite unavailable","upstreamSystem":"NetSuite"}
 * new ErrorResponse("x", null)
 * // {"message":"x"}
 * }</pre>
 *
 * @param message        the failure description
 * @param upstreamSystem the vendor system that failed, for example {@code NetSuite}; omitted from the
 *                       JSON body when null
 */
public record ErrorResponse(String message, @JsonInclude(JsonInclude.Include.NON_NULL) String upstreamSystem) {
}
