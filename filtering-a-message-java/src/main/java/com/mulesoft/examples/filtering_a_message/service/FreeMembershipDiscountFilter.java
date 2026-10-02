/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.filtering_a_message.service;

import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * Decides whether an order qualifies for the free-membership discount.
 *
 * <p>Ported from the {@code custom-filter} class of {@code filteringFlow1} in
 * {@code filtering-a-message/src/main/app/filtering.xml}, with its thresholds unchanged.
 * {@code DiscountService} calls {@link #accept(Map)} with the parsed JSON request; the bean holds
 * no state.
 */
@Component
public class FreeMembershipDiscountFilter {

    /**
     * Returns true when the order qualifies for the free-membership discount.
     *
     * <p>A {@code membership} equal to the string {@code "premium"} needs at least 6 months and at
     * least 1000 purchases. Any other membership needs one of:
     * <ul>
     *   <li>at most 6 months and at least 2500 purchases;</li>
     *   <li>7 to 11 months and at least 2000 purchases;</li>
     *   <li>12 months or more and at least 1500 purchases.</li>
     * </ul>
     *
     * <p>{@code months} and {@code purchases} are read with {@link Integer#parseInt(String)} on
     * the value's {@code toString()}. A missing {@code membership}, {@code months} or
     * {@code purchases} key, or a {@code null} value or map, raises {@link NullPointerException};
     * a value that is not an {@code int} literal, such as {@code 12.0}, raises
     * {@link NumberFormatException}. Neither is caught here.
     *
     * @param order the parsed request, keyed by {@code membership}, {@code months} and
     *     {@code purchases}
     * @return {@code true} when the discount is granted, {@code false} when the order is filtered
     *     out
     * @throws NullPointerException when {@code order} or one of the three values is {@code null}
     * @throws NumberFormatException when {@code months} or {@code purchases} is not an {@code int}
     *     literal
     */
    public boolean accept(Map<String, Object> order) {
        boolean isPremium = order.get("membership").equals("premium");
        int months = Integer.parseInt(order.get("months").toString());
        int purchase = Integer.parseInt(order.get("purchases").toString());
        if (isPremium) {
            return months >= 6 && purchase >= 1000;
        }
        return (months <= 6 && purchase >= 2500)
                || (months > 6 && months < 12 && purchase >= 2000)
                || (months >= 12 && purchase >= 1500);
    }
}
