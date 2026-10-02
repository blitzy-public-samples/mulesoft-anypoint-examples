package com.mulesoft.examples.cache_scope_with_fibonacci.model;

/**
 * Result of the {@code calculateFibonacci} flow: the payload text and the outbound {@code cost} of one
 * calculation (D-055).
 *
 * <p>A computed result for {@code n < 2} holds the {@code n} text with cost 1. A computed result for a larger
 * {@code n} holds the decimal text of the sum of the results for {@code n - 1} and {@code n - 2}, with the sum
 * of their costs plus 1. A result returned from the {@code fibonacci} cache holds the stored value with cost 0.
 *
 * @param value the payload text: the Fibonacci number, or the {@code n} text for {@code n < 2}
 * @param cost the calculation cost: 1 for {@code n < 2}, the sum of both recursive costs plus 1 otherwise,
 *     0 for a cached result
 */
public record FibonacciResult(String value, int cost) {
}
