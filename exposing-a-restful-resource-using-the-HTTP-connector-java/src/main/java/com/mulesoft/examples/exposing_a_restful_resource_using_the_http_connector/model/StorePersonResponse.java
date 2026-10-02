package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.model;

/**
 * The {@code Location} header value and the response body of a person stored by
 * {@code storePersonFlow}.
 *
 * @param location value of the Location response header
 * @param body     response body of a stored person
 */
public record StorePersonResponse(String location, String body) {
}
