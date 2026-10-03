/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.springframework.stereotype.Service;

/**
 * Provides the credit profile for a customer. This is the mock credit agency of flow
 * {@code TheCreditAgencyService}, whose SOAP operation {@code getCreditProfile} is served by
 * {@code endpoint.CreditAgencyEndpoint}.
 *
 * <p>Each profile holds a random credit history length from 1 to 19 and a random credit score from
 * 300 to 899. Every call takes two draws from the random source, the credit history first and the
 * credit score second. The customer's SSN is read and does not change either value.
 * Apart from the random source the class holds no state (D-385).
 *
 * <pre>{@code
 * CreditProfile profile = new CreditAgencyService().theCreditAgencyService(new Customer("Muley", 1234));
 * profile.getCreditHistory(); // 1 ... 19
 * profile.getCreditScore();   // 300 ... 899
 * }</pre>
 */
@Service
public class CreditAgencyService {

    /** Source of the values in [0, 1) from which the credit history and the credit score are derived. */
    private final DoubleSupplier random;

    /**
     * Creates the service over {@link Math#random()}. Spring creates the single bean of this class
     * through this constructor.
     */
    public CreditAgencyService() {
        this(Math::random);
    }

    /**
     * Creates the service over the given source of values in [0, 1) (D-385).
     *
     * @param random source of the draws; called once for the credit history and then once for the
     *               credit score of each profile
     * @throws NullPointerException if {@code random} is {@code null}
     */
    CreditAgencyService(DoubleSupplier random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    /**
     * Returns a credit profile with a random credit history length from 1 to 19 and a random credit
     * score from 300 to 899. The credit history is drawn before the credit score.
     *
     * @param customer the customer whose credit profile is requested; its SSN does not change the result
     * @return a new profile holding the drawn credit history and credit score
     * @throws NullPointerException if {@code customer} is {@code null}
     */
    public CreditProfile theCreditAgencyService(Customer customer) {
        CreditProfile cp = new CreditProfile();
        cp.setCreditHistory(getCreditHistoryLength(customer.getSsn()));
        cp.setCreditScore(getCreditScore(customer.getSsn()));
        return cp;
    }

    /**
     * Returns {@code (int) (draw * 600 + 300)}, a credit score from 300 to 899 for a draw in [0, 1).
     *
     * @param ssn the customer's SSN; not read
     * @return the credit score
     */
    private int getCreditScore(int ssn) {
        return (int) (random.getAsDouble() * 600 + 300);
    }

    /**
     * Returns {@code (int) (draw * 19 + 1)}, a credit history length from 1 to 19 for a draw in [0, 1).
     *
     * @param ssn the customer's SSN; not read
     * @return the credit history length
     */
    private int getCreditHistoryLength(int ssn) {
        return (int) (random.getAsDouble() * 19 + 1);
    }
}
