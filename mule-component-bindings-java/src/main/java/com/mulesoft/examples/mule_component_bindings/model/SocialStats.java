/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.mule_component_bindings.model;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Counts of loaded tweets by sentiment, and whether more tweets were available.
 *
 * <p>The {@code socialStats} member of the {@code StockStats} response. Every counter starts at
 * {@code 0} and {@code moreTweets} at {@code false}; {@link #addTweet(Tweet)} is the only method
 * that changes the counters. Jackson writes the members in the order {@code tweetsLoaded},
 * {@code moreTweets}, {@code negativeTweets}, {@code neutralTweets}, {@code positiveTweets}
 * (D-608), the counters as JSON integers and {@code moreTweets} as a JSON boolean. An instance
 * after one {@code addTweet(new Tweet())} serialises as:
 *
 * <pre>{@code {"tweetsLoaded":1,"moreTweets":false,"negativeTweets":0,"neutralTweets":0,"positiveTweets":1}}</pre>
 *
 * <p>Two instances are equal when all five values are equal. Instances are not thread-safe.
 */
@JsonPropertyOrder({"tweetsLoaded", "moreTweets", "negativeTweets", "neutralTweets", "positiveTweets"})
public class SocialStats {

    private int tweetsLoaded;
    private boolean moreTweets;
    private int negativeTweets;
    private int neutralTweets;
    private int positiveTweets;

    /**
     * Returns whether more tweets were available than were loaded.
     *
     * @return {@code true} when more tweets were available, {@code false} when not set
     */
    public boolean isMoreTweets() {
        return moreTweets;
    }

    /**
     * Sets whether more tweets were available than were loaded.
     *
     * @param moreTweets {@code true} when more tweets were available
     */
    public void setMoreTweets(boolean moreTweets) {
        this.moreTweets = moreTweets;
    }

    /**
     * Returns the number of tweets added through {@link #addTweet(Tweet)}.
     *
     * @return the number of loaded tweets, {@code 0} when none was added
     */
    public int getTweetsLoaded() {
        return tweetsLoaded;
    }

    /**
     * Returns the number of added tweets whose sentiment is {@link Sentiment#NEGATIVE}.
     *
     * @return the number of negative tweets
     */
    public int getNegativeTweets() {
        return negativeTweets;
    }

    /**
     * Returns the number of added tweets whose sentiment is {@link Sentiment#NEUTRAL}.
     *
     * @return the number of neutral tweets
     */
    public int getNeutralTweets() {
        return neutralTweets;
    }

    /**
     * Returns the number of added tweets whose sentiment is neither {@link Sentiment#NEGATIVE} nor
     * {@link Sentiment#NEUTRAL}, that is {@link Sentiment#POSITIVE} or {@code null}.
     *
     * @return the number of positive tweets
     */
    public int getPositiveTweets() {
        return positiveTweets;
    }

    /**
     * Counts one tweet: increments {@code tweetsLoaded}, then increments {@code negativeTweets} for
     * a {@link Sentiment#NEGATIVE} sentiment, {@code neutralTweets} for a {@link Sentiment#NEUTRAL}
     * sentiment and {@code positiveTweets} for any other sentiment, {@code null} included.
     *
     * @param tweet the tweet to count; must not be {@code null}
     * @throws NullPointerException when {@code tweet} is {@code null}, after {@code tweetsLoaded}
     *     has been incremented
     */
    public void addTweet(Tweet tweet) {
        tweetsLoaded++;
        if (tweet.getSentiment() == Sentiment.NEGATIVE) {
            negativeTweets++;
        } else if (tweet.getSentiment() == Sentiment.NEUTRAL) {
            neutralTweets++;
        } else {
            positiveTweets++;
        }
    }

    /**
     * Returns a hash code combining {@code moreTweets} (as 1231 or 1237), {@code negativeTweets},
     * {@code neutralTweets}, {@code positiveTweets} and {@code tweetsLoaded}, in that order, with
     * the multiplier 31.
     *
     * @return the hash code of the five values
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + (moreTweets ? 1231 : 1237);
        result = prime * result + negativeTweets;
        result = prime * result + neutralTweets;
        result = prime * result + positiveTweets;
        result = prime * result + tweetsLoaded;
        return result;
    }

    /**
     * Compares this instance with another object of exactly the same class.
     *
     * @param obj the object to compare with
     * @return {@code true} when {@code obj} is this instance, or is a {@code SocialStats} of the
     *     same runtime class whose {@code moreTweets}, {@code negativeTweets},
     *     {@code neutralTweets}, {@code positiveTweets} and {@code tweetsLoaded} equal this
     *     instance's; {@code false} otherwise, including for {@code null}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        SocialStats other = (SocialStats) obj;
        if (moreTweets != other.moreTweets)
            return false;
        if (negativeTweets != other.negativeTweets)
            return false;
        if (neutralTweets != other.neutralTweets)
            return false;
        if (positiveTweets != other.positiveTweets)
            return false;
        if (tweetsLoaded != other.tweetsLoaded)
            return false;
        return true;
    }
}
