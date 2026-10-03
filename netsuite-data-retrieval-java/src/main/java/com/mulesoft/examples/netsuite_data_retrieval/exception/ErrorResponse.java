package com.mulesoft.examples.netsuite_data_retrieval.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Body of the connector failure-mode responses (D-020): 502 after a failed re-authentication,
 * 429 on a NetSuite rate limit and 503 on a timeout or connectivity failure. Jackson writes the
 * components in declaration order, for example
 * {@code {"message":"NetSuite unavailable: queryIds customer","upstreamSystem":"NetSuite"}};
 * {@code upstreamSystem} is omitted when null (D-007).
 *
 * <p>The APIkit mapping responses (404, 405, 415, 406 and 400) return their literal
 * {@code { "message": ... }} bodies and do not use this record.
 *
 * <pre>{@code
 * byte[] body = objectMapper.writeValueAsBytes(new ErrorResponse(ex.getMessage(), "NetSuite"));
 * }</pre>
 *
 * @param message        the failure text, for example {@code NetSuite unavailable: queryIds customer}
 * @param upstreamSystem the vendor system that failed, {@code NetSuite}, or {@code null} to omit the member
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String message, String upstreamSystem) {
}
