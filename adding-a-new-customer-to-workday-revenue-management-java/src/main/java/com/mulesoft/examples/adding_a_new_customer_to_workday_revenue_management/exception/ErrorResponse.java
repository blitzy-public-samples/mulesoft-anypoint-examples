package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * JSON body of the connector failure-mode responses (D-007, D-020).
 *
 * <p>Serialized as {@code {"message":"<text>","upstreamSystem":"Workday"}}; a {@code null}
 * component is omitted.
 *
 * @param message        description of the failure
 * @param upstreamSystem name of the upstream system that failed, {@code Workday} in this project
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(String message, String upstreamSystem) {
}
