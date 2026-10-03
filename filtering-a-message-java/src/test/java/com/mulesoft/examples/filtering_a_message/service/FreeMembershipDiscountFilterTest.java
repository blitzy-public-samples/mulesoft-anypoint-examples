package com.mulesoft.examples.filtering_a_message.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link FreeMembershipDiscountFilter#accept(Map)} over the premium and standard
 * thresholds, with no Spring application context.
 *
 * <p>Each test builds an order map with {@code order(membership, months, purchases)} and calls the filter
 * directly. The cases sit on both sides of every threshold of the original filter
 * [filtering-a-message/src/main/java/org/mule/examples/filters/FreeMembershipDiscountFilter.java:21-29]:
 * <ul>
 *   <li>{@code "premium"}: at least 6 months and at least 1000 purchases;</li>
 *   <li>any other membership, here {@code "free"}: at most 6 months and at least 2500 purchases,
 *       7 to 11 months and at least 2000 purchases, or 12 months or more and at least 1500
 *       purchases.</li>
 * </ul>
 * Further cases cover {@code months} and {@code purchases} given as strings, a missing
 * {@code membership} key and a non-numeric {@code months} value. These tests cover the
 * {@code service} package under the JaCoCo LINE covered ratio rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
public class FreeMembershipDiscountFilterTest {

    /** The filter under test. */
    private final FreeMembershipDiscountFilter filter = new FreeMembershipDiscountFilter();

    /**
     * Builds an order map holding only the non-null arguments, keyed by {@code membership},
     * {@code months} and {@code purchases}.
     *
     * @param membership the {@code membership} value, or {@code null} to leave the key out
     * @param months the {@code months} value, or {@code null} to leave the key out
     * @param purchases the {@code purchases} value, or {@code null} to leave the key out
     * @return a new mutable map with the given entries
     */
    private static HashMap<String, Object> order(Object membership, Object months, Object purchases) {
        HashMap<String, Object> order = new HashMap<>();
        if (membership != null) {
            order.put("membership", membership);
        }
        if (months != null) {
            order.put("months", months);
        }
        if (purchases != null) {
            order.put("purchases", purchases);
        }
        return order;
    }

    /** Asserts a premium order of 6 months and 1000 purchases is accepted. */
    @Test
    public void premiumSixMonthsThousandPurchasesAccepted() {
        assertTrue(filter.accept(order("premium", 6, 1000)));
    }

    /** Asserts a premium order of 5 months and 1000 purchases is rejected. */
    @Test
    public void premiumFiveMonthsThousandPurchasesRejected() {
        assertFalse(filter.accept(order("premium", 5, 1000)));
    }

    /** Asserts a premium order of 6 months and 999 purchases is rejected. */
    @Test
    public void premiumSixMonthsNineHundredNinetyNinePurchasesRejected() {
        assertFalse(filter.accept(order("premium", 6, 999)));
    }

    /** Asserts a {@code "free"} order of 6 months and 2500 purchases is accepted. */
    @Test
    public void standardSixMonthsTwoThousandFiveHundredPurchasesAccepted() {
        assertTrue(filter.accept(order("free", 6, 2500)));
    }

    /** Asserts a {@code "free"} order of 6 months and 2499 purchases is rejected. */
    @Test
    public void standardSixMonthsTwoThousandFourHundredNinetyNinePurchasesRejected() {
        assertFalse(filter.accept(order("free", 6, 2499)));
    }

    /** Asserts a {@code "free"} order of 7 months and 2000 purchases is accepted. */
    @Test
    public void standardSevenMonthsTwoThousandPurchasesAccepted() {
        assertTrue(filter.accept(order("free", 7, 2000)));
    }

    /** Asserts a {@code "free"} order of 11 months and 1999 purchases is rejected. */
    @Test
    public void standardElevenMonthsOneThousandNineHundredNinetyNinePurchasesRejected() {
        assertFalse(filter.accept(order("free", 11, 1999)));
    }

    /** Asserts a {@code "free"} order of 11 months and 2000 purchases is accepted. */
    @Test
    public void standardElevenMonthsTwoThousandPurchasesAccepted() {
        assertTrue(filter.accept(order("free", 11, 2000)));
    }

    /** Asserts a {@code "free"} order of 12 months and 1500 purchases is accepted. */
    @Test
    public void standardTwelveMonthsFifteenHundredPurchasesAccepted() {
        assertTrue(filter.accept(order("free", 12, 1500)));
    }

    /** Asserts a {@code "free"} order of 12 months and 1499 purchases is rejected. */
    @Test
    public void standardTwelveMonthsFourteenHundredNinetyNinePurchasesRejected() {
        assertFalse(filter.accept(order("free", 12, 1499)));
    }

    /**
     * Asserts a {@code "free"} order whose {@code months} and {@code purchases} are the strings
     * {@code "12"} and {@code "2000"} is accepted, the values being read through
     * {@code toString()} and {@link Integer#parseInt(String)}.
     */
    @Test
    public void standardStringMonthsAndPurchasesParsedAndAccepted() {
        assertTrue(filter.accept(order("free", "12", "2000")));
    }

    /**
     * Asserts an order without a {@code membership} key, with 12 months and 2000 purchases,
     * throws {@link NullPointerException}.
     */
    @Test
    public void missingMembershipThrowsNullPointerException() {
        Map<String, Object> map = order(null, 12, 2000);
        assertThrows(NullPointerException.class, () -> filter.accept(map));
    }

    /**
     * Asserts a {@code "free"} order whose {@code months} is the string {@code "abc"}, with 2000
     * purchases, throws {@link NumberFormatException}.
     */
    @Test
    public void nonNumericMonthsThrowsNumberFormatException() {
        Map<String, Object> map = order("free", "abc", 2000);
        assertThrows(NumberFormatException.class, () -> filter.accept(map));
    }
}
