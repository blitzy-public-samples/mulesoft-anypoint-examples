package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link CreditAgencyService}, the port of {@code DefaultCreditAgency}
 * [foreach-processing-and-choice-routing/src/main/java/org/mule/example/loanbroker/creditagency/DefaultCreditAgency.java:19-43]
 * served by flow {@code TheCreditAgencyService}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:138-144]; coverage floor D-049.
 *
 * <p>The service is instantiated directly, with no Spring context and no mocks. The package-private
 * constructor takes a {@link DoubleSupplier} that replaces {@link Math#random()} (D-385); the tests feed it
 * fixed draws and assert the credit history {@code (int) (draw * 19 + 1)}, the credit score
 * {@code (int) (draw * 600 + 300)} and the order of the two draws.
 */
class CreditAgencyServiceTest {

    /** Lowest credit score, the score for a draw of 0. */
    private static final int MIN_SCORE = 300;

    /** Highest credit score, the score for a draw just below 1. */
    private static final int MAX_SCORE = 899;

    /** Lowest credit history length, the length for a draw of 0. */
    private static final int MIN_HISTORY = 1;

    /** Highest credit history length, the length for a draw just below 1. */
    private static final int MAX_HISTORY = 19;

    /** Number of profiles drawn from {@link Math#random()} by {@link #defaultConstructorStaysWithinRanges()}. */
    private static final int DEFAULT_CONSTRUCTOR_CALLS = 1000;

    /**
     * Returns a supplier that yields {@code values} in order, one per call, and throws
     * {@link IllegalStateException} on any call after the last value.
     *
     * @param values the draws to return, in order
     * @return a supplier over {@code values}
     */
    private static DoubleSupplier sequence(double... values) {
        double[] draws = values.clone();
        int[] next = {0};
        return () -> {
            if (next[0] >= draws.length) {
                throw new IllegalStateException(
                        "Draw " + (next[0] + 1) + " requested; the sequence holds " + draws.length + " values");
            }
            return draws[next[0]++];
        };
    }

    @Test
    void zeroDrawGivesMinimumScoreAndHistory() {
        // A draw of 0.0 gives history (int) (0 * 19 + 1) = 1 and score (int) (0 * 600 + 300) = 300.
        CreditAgencyService service = new CreditAgencyService(() -> 0.0);

        CreditProfile profile = service.theCreditAgencyService(new Customer("Muley", 1234));

        assertThat(profile).isNotNull();
        assertThat(profile.getCreditScore()).isEqualTo(MIN_SCORE);
        assertThat(profile.getCreditHistory()).isEqualTo(MIN_HISTORY);
    }

    @Test
    void highestDrawGivesMaximumScoreAndHistory() {
        // A draw of 0.9999999 gives history (int) 19.9999981 = 19 and score (int) 899.99994 = 899.
        CreditAgencyService service = new CreditAgencyService(() -> 0.9999999);

        CreditProfile profile = service.theCreditAgencyService(new Customer("Muley", 1234));

        assertThat(profile.getCreditScore()).isEqualTo(MAX_SCORE);
        assertThat(profile.getCreditHistory()).isEqualTo(MAX_HISTORY);
    }

    @Test
    void historyIsDrawnBeforeScore() {
        // History is drawn first (0.5 gives (int) 10.5 = 10), then score (0.0 gives 300).
        // The reverse order would give history 1 and score 600. A third draw fails the sequence.
        CreditAgencyService service = new CreditAgencyService(sequence(0.5, 0.0));

        CreditProfile profile = service.theCreditAgencyService(new Customer("Muley", 1234));

        assertThat(profile.getCreditHistory()).isEqualTo(10);
        assertThat(profile.getCreditScore()).isEqualTo(MIN_SCORE);
    }

    @Test
    void ssnDoesNotChangeProfile() {
        // Two customers with different names and SSNs get the same profile from the same constant draw.
        CreditAgencyService first = new CreditAgencyService(() -> 0.42);
        CreditAgencyService second = new CreditAgencyService(() -> 0.42);

        CreditProfile muley = first.theCreditAgencyService(new Customer("Muley", 1234));
        CreditProfile other = second.theCreditAgencyService(new Customer("Other", 987654));

        assertThat(muley.getCreditScore()).isEqualTo(other.getCreditScore());
        assertThat(muley.getCreditHistory()).isEqualTo(other.getCreditHistory());
    }

    @Test
    void defaultConstructorStaysWithinRanges() {
        // The public constructor draws from Math.random(); every profile stays within [300, 899] and [1, 19].
        CreditAgencyService service = new CreditAgencyService();
        Customer customer = new Customer("Muley", 1234);

        for (int call = 0; call < DEFAULT_CONSTRUCTOR_CALLS; call++) {
            CreditProfile profile = service.theCreditAgencyService(customer);

            assertThat(profile).isNotNull();
            assertThat(profile.getCreditScore()).isBetween(MIN_SCORE, MAX_SCORE);
            assertThat(profile.getCreditHistory()).isBetween(MIN_HISTORY, MAX_HISTORY);
        }
    }
}
