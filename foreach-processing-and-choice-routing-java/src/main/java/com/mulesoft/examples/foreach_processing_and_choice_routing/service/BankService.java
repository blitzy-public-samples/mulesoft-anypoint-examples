/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import com.mulesoft.examples.foreach_processing_and_choice_routing.config.MockBankProperties;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * <code>BankService</code> is a representation of the five mock banks from which to obtain loan
 * quotes: the bank components of flows {@code Bank1Flow} … {@code Bank5Flow}
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:146-195], whose SOAP
 * operation {@code getLoanQuote} is served by {@code endpoint.BankEndpoint}.
 *
 * <p>Each bank holds a name and three interest rates. At construction each bank, bank 1 first and
 * bank 5 last, takes three draws in [0, 1) from the random source, multiplies each by 10 and sorts
 * the three rates ascending. The names and rates are never modified after construction, and every
 * call reads them only. A quote carries the bank's name and the rate for the request's credit score:
 * <ul>
 *   <li>below 500: the highest of the three rates;</li>
 *   <li>500 to 699: the middle rate;</li>
 *   <li>700 and above: the lowest rate.</li>
 * </ul>
 * Each quote is logged at INFO as {@code Returning Rate is: <bankName>, rate: <rate>}.
 *
 * <p>The bank names are read once, at construction, from {@link MockBankProperties#bankName(int)}:
 * the keys {@code bank1-flow.bank-name} … {@code bank5-flow.bank-name}, {@code Bank #1} …
 * {@code Bank #5} in {@code application.yml} (D-302).
 *
 * <pre>{@code
 * BankService banks = new BankService(properties);
 * LoanQuote quote = banks.bank1Flow(request); // credit score 650
 * quote.getBankName();                        // "Bank #1"
 * quote.toString();                           // "Bank #1, rate: <middle rate of bank 1>"
 * }</pre>
 */
@Service
public class BankService {

    /** Index of the lowest rate, quoted for a credit score of 700 and above. */
    private static final int PLATINUM_PROFILE_INDEX = 0;

    /** Index of the middle rate, quoted for a credit score from 500 to 699. */
    private static final int GOLD_PROFILE_INDEX = 1;

    /** Index of the highest rate, quoted for a credit score below 500. */
    private static final int BASIC_PROFILE_INDEX = 2;

    /** Number of mock banks, one per flow {@code Bank1Flow} … {@code Bank5Flow}. */
    private static final int BANK_COUNT = 5;

    /**
     * logger used by this class
     */
    private static final Logger logger = LoggerFactory.getLogger(BankService.class);

    /** Name of each bank: index 0 holds bank 1's name, index 4 bank 5's. */
    private final String[] bankNames;

    /** Rates of each bank, lowest first: row 0 holds bank 1's three rates, row 4 bank 5's. */
    private final double[][] rates;

    /**
     * Creates the five banks with rates drawn from {@link Math#random()}. Spring creates the single
     * bean of this class through this constructor.
     *
     * @param properties source of the five bank names
     * @throws NullPointerException if {@code properties} is {@code null}
     */
    @Autowired
    public BankService(MockBankProperties properties) {
        this(properties, Math::random);
    }

    /**
     * Creates the five banks with rates drawn from the given source of values in [0, 1).
     *
     * <p>The source is called 15 times, in bank order: draws 1 to 3 give the rates of bank 1, draws 4
     * to 6 those of bank 2, and draws 13 to 15 those of bank 5. Each draw is multiplied by 10, and each
     * bank's three rates are sorted ascending. Bank {@code n}'s name is
     * {@code properties.bankName(n)}, which is {@code null} when its key is not bound.
     *
     * @param properties source of the five bank names
     * @param random     source of the 15 rate draws
     * @throws NullPointerException if {@code properties} or {@code random} is {@code null}
     */
    BankService(MockBankProperties properties, DoubleSupplier random) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(random, "random");
        bankNames = new String[BANK_COUNT];
        rates = new double[BANK_COUNT][];
        for (int bank = 0; bank < BANK_COUNT; bank++) {
            rates[bank] = new double[] {random.getAsDouble() * 10, random.getAsDouble() * 10,
                random.getAsDouble() * 10};
            Arrays.sort(rates[bank]);
            bankNames[bank] = properties.bankName(bank + 1);
        }
    }

    /**
     * Returns a loan quote from bank 1, the bank of flow {@code Bank1Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:146-154], at the rate
     * matching the request's credit score.
     *
     * @param request the loan broker request; its credit profile supplies the credit score
     * @return a new quote holding bank 1's name and the selected rate
     * @throws NullPointerException if {@code request} or its credit profile is {@code null}
     */
    public LoanQuote bank1Flow(LoanBrokerQuoteRequest request) {
        return getLoanQuote(0, request);
    }

    /**
     * Returns a loan quote from bank 2, the bank of flow {@code Bank2Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:156-165], at the rate
     * matching the request's credit score.
     *
     * @param request the loan broker request; its credit profile supplies the credit score
     * @return a new quote holding bank 2's name and the selected rate
     * @throws NullPointerException if {@code request} or its credit profile is {@code null}
     */
    public LoanQuote bank2Flow(LoanBrokerQuoteRequest request) {
        return getLoanQuote(1, request);
    }

    /**
     * Returns a loan quote from bank 3, the bank of flow {@code Bank3Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:167-175], at the rate
     * matching the request's credit score.
     *
     * @param request the loan broker request; its credit profile supplies the credit score
     * @return a new quote holding bank 3's name and the selected rate
     * @throws NullPointerException if {@code request} or its credit profile is {@code null}
     */
    public LoanQuote bank3Flow(LoanBrokerQuoteRequest request) {
        return getLoanQuote(2, request);
    }

    /**
     * Returns a loan quote from bank 4, the bank of flow {@code Bank4Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:177-185], at the rate
     * matching the request's credit score.
     *
     * @param request the loan broker request; its credit profile supplies the credit score
     * @return a new quote holding bank 4's name and the selected rate
     * @throws NullPointerException if {@code request} or its credit profile is {@code null}
     */
    public LoanQuote bank4Flow(LoanBrokerQuoteRequest request) {
        return getLoanQuote(3, request);
    }

    /**
     * Returns a loan quote from bank 5, the bank of flow {@code Bank5Flow}
     * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:187-195], at the rate
     * matching the request's credit score.
     *
     * @param request the loan broker request; its credit profile supplies the credit score
     * @return a new quote holding bank 5's name and the selected rate
     * @throws NullPointerException if {@code request} or its credit profile is {@code null}
     */
    public LoanQuote bank5Flow(LoanBrokerQuoteRequest request) {
        return getLoanQuote(4, request);
    }

    /**
     * Returns a loan quote from the given bank at the rate matching the request's credit score, and
     * logs it at INFO as {@code Returning Rate is: <bankName>, rate: <rate>}.
     *
     * @param bank    the bank index: 0 for bank 1 to 4 for bank 5
     * @param request the loan broker request; its credit profile supplies the credit score
     * @return a new quote holding the bank's name and the selected rate
     * @throws NullPointerException if {@code request} or its credit profile is {@code null}
     */
    private LoanQuote getLoanQuote(int bank, LoanBrokerQuoteRequest request) {
        LoanQuote quote = new LoanQuote();
        quote.setBankName(bankNames[bank]);
        int creditScore = request.getCreditProfile().getCreditScore();
        quote.setInterestRate(getCreditScoreRate(bank, creditScore));
        logger.info("Returning Rate is: {}", quote);

        return quote;
    }

    /**
     * Returns the given bank's rate for a credit score: the highest rate below 500, the middle rate
     * from 500 to 699, and the lowest rate from 700.
     *
     * @param bank        the bank index: 0 for bank 1 to 4 for bank 5
     * @param creditScore the customer's credit score
     * @return the selected rate of the bank
     */
    private double getCreditScoreRate(int bank, int creditScore) {
        // 300 <= creditScore < 900, higher values means better customer credit profile
        int index;
        if (creditScore < 500) {
            index = BASIC_PROFILE_INDEX;
        } else if (creditScore < 700) {
            index = GOLD_PROFILE_INDEX;
        } else {
            index = PLATINUM_PROFILE_INDEX;
        }

        return rates[bank][index];
    }
}
