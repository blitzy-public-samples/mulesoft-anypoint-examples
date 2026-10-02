package com.mulesoft.examples.cache_scope_with_fibonacci.model;

/**
 * Reply of the {@code cache-exampleFlow1} flow, passed from the service to the controller.
 *
 * @param body the response text
 * @param cost the value of the {@code cost} response header, or {@code null} when no header is sent
 */
public record FibonacciReply(String body, Integer cost) {
}
