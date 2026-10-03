/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.scatter_gather_flow_control.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Merges the contact lists of the two scatter-gather routes into one list; the identity of a
 * contact is its email. Ported from {@code ContactMergeAggregationStrategy} (AAP 0.4.3).
 *
 * <p>The merge itself is {@link ContactMerge#mergeList(List, List)}: every contact of route A,
 * then every contact of route B whose email is not already present, with {@code IDInA} and
 * {@code IDInB} holding the contact's {@code Id} in each route or {@code ""}.
 *
 * <pre>{@code
 * List<Map<String, String>> merged = aggregator.aggregate(List.of(
 *         ContactMergeAggregator.RouteResult.success(contactsFromA),
 *         ContactMergeAggregator.RouteResult.success(contactsFromB)));
 * }</pre>
 *
 * <p>Instances hold no state and are safe for concurrent use.
 */
@Component
public class ContactMergeAggregator {

    /**
     * Merges the payloads of the successful route results.
     *
     * <p>The successful results are taken in the order of {@code results}. Exactly two
     * successful results are required; any other count raises {@link IllegalStateException}.
     * The first successful result is route A and the second is route B (AAP 0.3.2).
     *
     * @param results the outcome of each scatter-gather route, in route order
     * @return the merged contact list returned by {@link ContactMerge#mergeList(List, List)};
     *         the list itself is returned, not an iterator over it (AAP 0.4.3)
     * @throws IllegalStateException if the number of successful results is not two
     * @throws NullPointerException if {@code results} or one of its elements is {@code null}
     */
    public List<Map<String, String>> aggregate(List<RouteResult> results) {
        List<RouteResult> successful = new ArrayList<>(results.size());
        for (RouteResult result : results) {
            if (result.isSuccess()) {
                successful.add(result);
            }
        }

        // Exactly two successful results are required.
        if (successful.size() != 2) {
            throw new IllegalStateException("Data from at least one source was not able to be obtained correctly.");
        }

        // Result 0 is route A and result 1 is route B (AAP 0.3.2).
        return new ContactMerge().mergeList(successful.get(0).payload(), successful.get(1).payload());
    }

    /**
     * The outcome of one scatter-gather route: either the contact list the route produced or
     * the exception it ended with.
     *
     * @param payload the contacts the route produced; {@code null} for a failed route
     * @param failure the exception the route ended with; {@code null} for a successful route
     */
    public record RouteResult(List<Map<String, String>> payload, Throwable failure) {

        /**
         * Creates the result of a route that completed normally.
         *
         * @param payload the contacts the route produced
         * @return a result holding {@code payload} and no failure
         */
        public static RouteResult success(List<Map<String, String>> payload) {
            return new RouteResult(payload, null);
        }

        /**
         * Creates the result of a route that ended with an exception.
         *
         * @param failure the exception the route ended with
         * @return a result holding {@code failure} and no payload
         */
        public static RouteResult failure(Throwable failure) {
            return new RouteResult(null, failure);
        }

        /**
         * Reports whether the route completed normally.
         *
         * @return {@code true} when no failure is held
         */
        public boolean isSuccess() {
            return failure == null;
        }
    }
}
