/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.mapper;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.mule_component_bindings.model.Tweet;

import twitter4j.v1.Status;

/**
 * Converts Twitter search statuses into {@link Tweet} objects carrying the status id as a decimal
 * string and the status text, in the order received.
 *
 * <p>The class holds no state, performs no I/O and has no collaborators. Each call builds and returns
 * a new list. Concurrent calls on the single Spring bean share nothing. The status type is
 * {@code twitter4j.v1.Status} of twitter4j 4.1.2 (D-033).
 *
 * <p>Example, with two statuses of ids {@code 123456789012345678L} and {@code 42L} and texts
 * {@code "a"} and {@code "b"}:
 * <pre>{@code
 * List<Tweet> tweets = twitterResponseMapper.toTweets(queryResult.getTweets());
 * // tweets.get(0): getId() "123456789012345678", getText() "a", getSentiment() null
 * // tweets.get(1): getId() "42", getText() "b", getSentiment() null
 * }</pre>
 */
@Component
public class TwitterResponseMapper {

    /**
     * Returns one {@link Tweet} per status, in the iteration order of {@code statuses}, with:
     * <ul>
     *   <li>{@code id}: {@link Status#getId()} rendered as a decimal string, for example
     *       {@code 42L} gives {@code "42"};</li>
     *   <li>{@code text}: {@link Status#getText()} unchanged, {@code null} included;</li>
     *   <li>{@code sentiment}: left unset ({@code null}).</li>
     * </ul>
     *
     * <p>Every status yields one tweet: none is filtered, sorted or deduplicated. An empty
     * {@code statuses} list gives an empty list.
     *
     * @param statuses the statuses of a Twitter search result; must not be {@code null}
     * @return a new mutable {@link ArrayList} of the mapped tweets
     * @throws NullPointerException if {@code statuses} is {@code null} or holds a {@code null} element
     */
    public List<Tweet> toTweets(List<Status> statuses) {
        List<Tweet> tweets = new ArrayList<>();
        for (Status status : statuses) {
            Tweet tweet = new Tweet();
            // id is the long status id as a decimal string
            tweet.setId(status.getId() + "");
            tweet.setText(status.getText());
            tweets.add(tweet);
        }
        return tweets;
    }

}
