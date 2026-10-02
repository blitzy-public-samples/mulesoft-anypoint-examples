/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.model;

import java.io.Serializable;

/**
 * <code>LoanQuote</code> is a loan quote from a bank
 *
 * <p>Holds the name of the quoting bank and the interest rate it offers. The loan broker
 * selects the quote with the lowest {@link #getInterestRate()} and returns its
 * {@link #toString()} form, for example {@code Bank #1, rate: 2.5}, as the response body.
 */
public class LoanQuote implements Serializable {

    /**
     * Serial version
     */
    private static final long serialVersionUID = -8432932027217141564L;

    private String bankName;
    private double interestRate = 0;

    /**
     * Creates a quote with no bank name and an interest rate of {@code 0}.
     */
    public LoanQuote() {
        super();
    }

    /**
     * @return the name of the bank that issued this quote, or {@code null} when unset
     */
    public String getBankName() {
        return bankName;
    }

    /**
     * @param bankName the name of the bank that issued this quote
     */
    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    /**
     * @return the interest rate offered by the bank
     */
    public double getInterestRate() {
        return interestRate;
    }

    /**
     * @param interestRate the interest rate offered by the bank
     */
    public void setInterestRate(double interestRate) {
        this.interestRate = interestRate;
    }

    /**
     * Renders the quote as {@code <bankName>, rate: <interestRate>}, with the rate formatted by
     * Java string concatenation, for example {@code Bank #1, rate: 2.5} or {@code null, rate: 0.0}.
     *
     * @return the bank name and interest rate of this quote
     */
    @Override
    public String toString() {
        return bankName + ", rate: " + interestRate;
    }
}
