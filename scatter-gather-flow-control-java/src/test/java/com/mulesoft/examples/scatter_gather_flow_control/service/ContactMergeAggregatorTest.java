package com.mulesoft.examples.scatter_gather_flow_control.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link ContactMergeAggregator#aggregate(List)} and its
 * {@link ContactMergeAggregator.RouteResult} factories, called directly with no Spring application
 * context, using the source lists of routes A and B
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:10-43].
 */
class ContactMergeAggregatorTest {

    private static final String FAILURE_MESSAGE =
            "Data from at least one source was not able to be obtained correctly.";

    private final ContactMergeAggregator aggregator = new ContactMergeAggregator();

    /**
     * Two successful results, route A first, merge to the contacts of A followed by the new contact
     * of B, with {@code IDInB} set on the contact both routes share.
     */
    @Test
    void twoSuccessesMerged() {
        List<Map<String, String>> result = aggregator.aggregate(List.of(
                ContactMergeAggregator.RouteResult.success(sourceA()),
                ContactMergeAggregator.RouteResult.success(sourceB())));

        assertThat(result).containsExactly(
                merged("vlado", "vlado@email.com", "1", "2"),
                merged("michal", "michal@email.com", "2", ""),
                merged("peter", "peter@email.com", "", "1"));
    }

    /**
     * The first successful result is merged as route A and the second as route B: with route B's list
     * first, its contacts lead the merged list and carry their ids in {@code IDInA}.
     */
    @Test
    void routeOrderRespected() {
        List<Map<String, String>> result = aggregator.aggregate(List.of(
                ContactMergeAggregator.RouteResult.success(sourceB()),
                ContactMergeAggregator.RouteResult.success(sourceA())));

        assertThat(result).containsExactly(
                merged("peter", "peter@email.com", "1", ""),
                merged("vlado", "vlado@email.com", "2", "1"),
                merged("michal", "michal@email.com", "", "2"));
    }

    /**
     * One successful result and one failed result raise {@link IllegalStateException} with the
     * failure message.
     */
    @Test
    void oneFailure() {
        List<ContactMergeAggregator.RouteResult> results = List.of(
                ContactMergeAggregator.RouteResult.success(sourceA()),
                ContactMergeAggregator.RouteResult.failure(new IllegalArgumentException("route B")));

        assertThatThrownBy(() -> aggregator.aggregate(results))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(FAILURE_MESSAGE);
    }

    /**
     * Two failed results raise {@link IllegalStateException} with the failure message.
     */
    @Test
    void twoFailures() {
        List<ContactMergeAggregator.RouteResult> results = List.of(
                ContactMergeAggregator.RouteResult.failure(new IllegalArgumentException("route A")),
                ContactMergeAggregator.RouteResult.failure(new IllegalArgumentException("route B")));

        assertThatThrownBy(() -> aggregator.aggregate(results))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(FAILURE_MESSAGE);
    }

    /**
     * Three successful results raise {@link IllegalStateException} with the failure message.
     */
    @Test
    void threeSuccesses() {
        List<ContactMergeAggregator.RouteResult> results = List.of(
                ContactMergeAggregator.RouteResult.success(sourceA()),
                ContactMergeAggregator.RouteResult.success(sourceB()),
                ContactMergeAggregator.RouteResult.success(sourceA()));

        assertThatThrownBy(() -> aggregator.aggregate(results))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(FAILURE_MESSAGE);
    }

    /**
     * An empty result list raises {@link IllegalStateException} with the failure message.
     */
    @Test
    void noResults() {
        List<ContactMergeAggregator.RouteResult> results = List.of();

        assertThatThrownBy(() -> aggregator.aggregate(results))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage(FAILURE_MESSAGE);
    }

    /**
     * {@code success} holds the given list and no failure and reports success; {@code failure} holds
     * the given exception and no payload and reports no success.
     */
    @Test
    void routeResultFactories() {
        List<Map<String, String>> payload = sourceA();
        Throwable failure = new IllegalArgumentException("route failed");

        ContactMergeAggregator.RouteResult successResult = ContactMergeAggregator.RouteResult.success(payload);
        ContactMergeAggregator.RouteResult failureResult = ContactMergeAggregator.RouteResult.failure(failure);

        assertThat(successResult.isSuccess()).isTrue();
        assertThat(successResult.payload()).isSameAs(payload);
        assertThat(successResult.failure()).isNull();
        assertThat(failureResult.isSuccess()).isFalse();
        assertThat(failureResult.failure()).isSameAs(failure);
        assertThat(failureResult.payload()).isNull();
    }

    /**
     * Creates a source contact with the given id, name and email.
     *
     * @param id the contact id
     * @param name the contact name
     * @param email the contact email
     * @return the contact as a map with the keys {@code Id}, {@code Name} and {@code Email}
     */
    private Map<String, String> contact(String id, String name, String email) {
        Map<String, String> contact = new HashMap<>();
        contact.put("Id", id);
        contact.put("Name", name);
        contact.put("Email", email);
        return contact;
    }

    /**
     * Creates an expected merged contact.
     *
     * @param name the contact name
     * @param email the contact email
     * @param idInA the contact's id in the first merged list, or {@code ""}
     * @param idInB the contact's id in the second merged list, or {@code ""}
     * @return the contact as a map with the keys {@code Name}, {@code Email}, {@code IDInA} and
     *         {@code IDInB}
     */
    private Map<String, String> merged(String name, String email, String idInA, String idInB) {
        Map<String, String> merged = new HashMap<>();
        merged.put("Name", name);
        merged.put("Email", email);
        merged.put("IDInA", idInA);
        merged.put("IDInB", idInB);
        return merged;
    }

    /**
     * Creates the contact list of route A [scatter-gather-flow-control/src/main/app/scatter-gather.xml:10-26].
     *
     * @return vlado (id 1) and michal (id 2)
     */
    private List<Map<String, String>> sourceA() {
        return List.of(
                contact("1", "vlado", "vlado@email.com"),
                contact("2", "michal", "michal@email.com"));
    }

    /**
     * Creates the contact list of route B [scatter-gather-flow-control/src/main/app/scatter-gather.xml:27-43].
     *
     * @return peter (id 1) and vlado (id 2)
     */
    private List<Map<String, String>> sourceB() {
        return List.of(
                contact("1", "peter", "peter@email.com"),
                contact("2", "vlado", "vlado@email.com"));
    }
}
