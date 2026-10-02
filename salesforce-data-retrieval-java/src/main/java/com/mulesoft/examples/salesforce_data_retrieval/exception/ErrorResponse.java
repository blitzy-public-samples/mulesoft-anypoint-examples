package com.mulesoft.examples.salesforce_data_retrieval.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON error body {@code {"message": ..., "upstreamSystem": ...}} returned for the connector
 * failure modes: 502 after a failed re-authentication, 429 on a vendor rate limit and 503 on a
 * timeout or connectivity failure (D-007, D-020). {@code upstreamSystem} is omitted when null.
 *
 * @param message        the failure description
 * @param upstreamSystem the vendor system that failed, for example {@code Salesforce}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String message, String upstreamSystem) {
}
