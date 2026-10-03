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
 * Financial and social statistics of a stock for one closing date.
 *
 * <p>The body of the {@code GET /api/stockStats} response. {@code financialStats} and
 * {@code socialStats} are created with the instance and are never replaced; callers fill them
 * through {@link #getFinancialStats()} and {@link #getSocialStats()}. Jackson writes the members
 * in the order {@code closingDate}, {@code financialStats}, {@code socialStats} (D-687), and a
 * {@code null} closing date as {@code "closingDate":null}. An instance with closing date
 * {@code 2012-10-26}, a close of 1, a low of 2 and one {@code addTweet(new Tweet())} serialises as:
 *
 * <pre>{@code {"closingDate":"2012-10-26","financialStats":{"open":0.0,"close":1.0,"high":0.0,"low":2.0,"volume":0},"socialStats":{"tweetsLoaded":1,"moreTweets":false,"negativeTweets":0,"neutralTweets":0,"positiveTweets":1}}}</pre>
 *
 * <p>Two instances are equal when their closing dates, financial statistics and social statistics
 * are equal. Instances are not thread-safe.
 */
@JsonPropertyOrder({"closingDate", "financialStats", "socialStats"})
public class StockStats {

    private String closingDate;
    private FinancialStats financialStats = new FinancialStats();
    private SocialStats socialStats = new SocialStats();

    /**
     * Returns the closing date the statistics refer to.
     *
     * @return the closing date, {@code null} when not set
     */
    public String getClosingDate() {
        return closingDate;
    }

    /**
     * Sets the closing date the statistics refer to.
     *
     * @param closingDate the closing date
     */
    public void setClosingDate(String closingDate) {
        this.closingDate = closingDate;
    }

    /**
     * Returns the financial statistics held by this instance.
     *
     * @return the financial statistics; never {@code null}
     */
    public FinancialStats getFinancialStats() {
        return financialStats;
    }

    /**
     * Returns the social statistics held by this instance.
     *
     * @return the social statistics; never {@code null}
     */
    public SocialStats getSocialStats() {
        return socialStats;
    }

    /**
     * Returns a hash code combining {@code closingDate} ({@code 0} when {@code null}),
     * {@code financialStats} and {@code socialStats}, in that order, with the multiplier 31.
     *
     * @return the hash code of the three values
     */
    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result
                + ((closingDate == null) ? 0 : closingDate.hashCode());
        result = prime * result
                + ((financialStats == null) ? 0 : financialStats.hashCode());
        result = prime * result
                + ((socialStats == null) ? 0 : socialStats.hashCode());
        return result;
    }

    /**
     * Compares this instance with another object of exactly the same class.
     *
     * @param obj the object to compare with
     * @return {@code true} when {@code obj} is this instance, or is a {@code StockStats} of the same
     *     runtime class whose {@code closingDate}, {@code financialStats} and {@code socialStats}
     *     equal this instance's, two {@code null} values counting as equal; {@code false}
     *     otherwise, including for {@code null}
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        StockStats other = (StockStats) obj;
        if (closingDate == null) {
            if (other.closingDate != null)
                return false;
        } else if (!closingDate.equals(other.closingDate))
            return false;
        if (financialStats == null) {
            if (other.financialStats != null)
                return false;
        } else if (!financialStats.equals(other.financialStats))
            return false;
        if (socialStats == null) {
            if (other.socialStats != null)
                return false;
        } else if (!socialStats.equals(other.socialStats))
            return false;
        return true;
    }
}
