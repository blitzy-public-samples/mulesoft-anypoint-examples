/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.cache_scope_with_fibonacci.mapper;

import com.mulesoft.examples.cache_scope_with_fibonacci.model.FibonacciResult;
import org.springframework.stereotype.Component;

/**
 * Creates the cached response for the fibonacci calculation: the cached value with cost 0 (D-055).
 *
 * <p>The {@code fibonacci} cache passes every stored {@link FibonacciResult} through
 * {@link #toCachedResponse(FibonacciResult)} on a cache hit: a hit reports {@code COST: 0}, and the stored entry
 * keeps the cost of its original calculation.
 */
@Component
public class FibonacciResponseMapper {

    /**
     * Returns a copy of the cached result with the same value and cost 0.
     *
     * <p>The argument is not modified; a new {@link FibonacciResult} is returned on every call.
     *
     * @param cached the result held in the {@code fibonacci} cache
     * @return a new result with {@code cached.value()} and cost 0
     */
    public FibonacciResult toCachedResponse(FibonacciResult cached) {
        return new FibonacciResult(cached.value(), 0);
    }
}
