package com.mulesoft.examples.foreach_processing_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mulesoft.examples.foreach_processing_and_choice_routing.config.MockBankProperties;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of {@link BankService}, the port of {@code Bank} used by the flows {@code Bank1Flow} …
 * {@code Bank5Flow} [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:146-195];
 * coverage floor D-049.
 *
 * <p>The service is instantiated directly, with no Spring context and no mocks. The package-private
 * constructor takes a {@link DoubleSupplier} that replaces {@link Math#random()}; the tests feed it the
 * 15 draws of {@link #SCRIPT} and assert, for each bank, that its rates are its own three draws times 10
 * sorted ascending, that the credit score selects the highest rate below 500, the middle rate from 500
 * to 699 and the lowest rate from 700, that the bank names come from {@link MockBankProperties}, and that
 * each quote is logged at INFO as {@code Returning Rate is: <bankName>, rate: <rate>}.
 */
class BankServiceTest {

    /**
     * The 15 draws, three per bank in bank order. Bank {@code k}'s triple is
     * {@code {0.03 + d, 0.01 + d, 0.02 + d}} with {@code d = (k - 1) * 0.15}: unsorted, and distinct
     * from every other bank's.
     */
    private static final double[] SCRIPT = {
        0.03, 0.01, 0.02,
        0.18, 0.16, 0.17,
        0.33, 0.31, 0.32,
        0.48, 0.46, 0.47,
        0.63, 0.61, 0.62
    };

    /** Number of draws per bank. */
    private static final int DRAWS_PER_BANK = 3;

    /** Index of the lowest rate in a sorted triple. */
    private static final int LOWEST = 0;

    /** Index of the middle rate in a sorted triple. */
    private static final int MIDDLE = 1;

    /** Index of the highest rate in a sorted triple. */
    private static final int HIGHEST = 2;

    /** Bank names in bank order, as bound from {@code bank1-flow.bank-name} … {@code bank5-flow.bank-name}. */
    private static final List<String> NAMES = List.of("Bank #1", "Bank #2", "Bank #3", "Bank #4", "Bank #5");

    /** The five bank names, built through the record constructors of {@link MockBankProperties}. */
    private static final MockBankProperties PROPERTIES = new MockBankProperties(
            new MockBankProperties.Bank1Flow(NAMES.get(0)),
            new MockBankProperties.Bank2Flow(NAMES.get(1)),
            new MockBankProperties.Bank3Flow(NAMES.get(2)),
            new MockBankProperties.Bank4Flow(NAMES.get(3)),
            new MockBankProperties.Bank5Flow(NAMES.get(4)));

    /** The five bank flows in bank order: element 0 is {@code bank1Flow}, element 4 {@code bank5Flow}. */
    private static final List<BiFunction<BankService, LoanBrokerQuoteRequest, LoanQuote>> FLOWS = List.of(
            BankService::bank1Flow,
            BankService::bank2Flow,
            BankService::bank3Flow,
            BankService::bank4Flow,
            BankService::bank5Flow);

    /** Number of rounds of calls made by {@link #ratesStayStableAcrossCalls()}. */
    private static final int ROUNDS = 3;

    /** Number of services built on {@link Math#random()} by {@link #defaultConstructorDrawsRatesBelowTen()}. */
    private static final int DEFAULT_CONSTRUCTIONS = 10;

    /** The Logback logger of {@link BankService}. */
    private Logger logger;

    /** Level of {@link #logger} before the test. */
    private Level previousLevel;

    /** Captures the events logged by {@link BankService} during the test. */
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(BankService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /**
     * Returns a supplier that yields {@code values} in order, one per call. Every call is counted; call
     * {@code n} beyond the last value throws {@link IllegalStateException} with the message
     * {@code Draw <n> requested; the sequence holds <values.length> values}.
     *
     * @param values the draws to return, in order
     * @return a supplier over {@code values}
     */
    static DoubleSupplier sequence(double... values) {
        double[] draws = values.clone();
        AtomicInteger calls = new AtomicInteger();
        return () -> {
            int call = calls.incrementAndGet();
            if (call > draws.length) {
                throw new IllegalStateException(
                        "Draw " + call + " requested; the sequence holds " + draws.length + " values");
            }
            return draws[call - 1];
        };
    }

    /**
     * Returns a loan broker request whose credit profile holds {@code creditScore}.
     *
     * @param creditScore the customer's credit score
     * @return a request with a credit profile and no customer request or loan quote
     */
    static LoanBrokerQuoteRequest request(int creditScore) {
        CreditProfile profile = new CreditProfile();
        profile.setCreditScore(creditScore);
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCreditProfile(profile);
        return request;
    }

    /**
     * Returns the rates bank {@code bank} holds after construction from {@link #SCRIPT}: its three
     * draws, each multiplied by 10, sorted ascending.
     *
     * @param bank the bank number, 1 to 5
     * @return the bank's three rates, lowest first
     */
    private static double[] expectedRates(int bank) {
        int first = (bank - 1) * DRAWS_PER_BANK;
        double[] rates = new double[DRAWS_PER_BANK];
        for (int draw = 0; draw < DRAWS_PER_BANK; draw++) {
            rates[draw] = SCRIPT[first + draw] * 10;
        }
        Arrays.sort(rates);
        return rates;
    }

    /**
     * Returns the quote of bank {@code bank} for a request with {@code creditScore}.
     *
     * @param service     the service under test
     * @param bank        the bank number, 1 to 5
     * @param creditScore the customer's credit score
     * @return the quote returned by the bank's flow method
     */
    private static LoanQuote quote(BankService service, int bank, int creditScore) {
        return FLOWS.get(bank - 1).apply(service, request(creditScore));
    }

    @Test
    void ratesAreDrawnInBankOrderAndSorted() {
        // Bank k quotes draws 3k-2 to 3k of the script, each times 10, sorted ascending:
        // 700 gets the lowest, 500 the middle and 499 the highest, strictly increasing.
        BankService service = new BankService(PROPERTIES, sequence(SCRIPT));

        for (int bank = 1; bank <= NAMES.size(); bank++) {
            double[] expected = expectedRates(bank);

            double lowest = quote(service, bank, 700).getInterestRate();
            double middle = quote(service, bank, 500).getInterestRate();
            double highest = quote(service, bank, 499).getInterestRate();

            assertThat(lowest).isEqualTo(expected[LOWEST]);
            assertThat(middle).isEqualTo(expected[MIDDLE]);
            assertThat(highest).isEqualTo(expected[HIGHEST]);
            assertThat(lowest).isLessThan(middle);
            assertThat(middle).isLessThan(highest);
        }
    }

    @Test
    void ratesStayStableAcrossCalls() {
        // Every round of calls returns the same rates; construction takes exactly 15 draws and the calls take none.
        DoubleSupplier draws = sequence(SCRIPT);
        BankService service = new BankService(PROPERTIES, draws);

        for (int round = 0; round < ROUNDS; round++) {
            for (int bank = 1; bank <= NAMES.size(); bank++) {
                double[] expected = expectedRates(bank);

                assertThat(quote(service, bank, 800).getInterestRate()).isEqualTo(expected[LOWEST]);
                assertThat(quote(service, bank, 600).getInterestRate()).isEqualTo(expected[MIDDLE]);
                assertThat(quote(service, bank, 300).getInterestRate()).isEqualTo(expected[HIGHEST]);
            }
        }

        assertThatThrownBy(draws::getAsDouble)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Draw 16 requested; the sequence holds 15 values");
    }

    @Test
    void basicScoreUsesHighestRate() {
        // A credit score of 499 is below 500 and gets rate index 2, the highest.
        BankService service = new BankService(PROPERTIES, sequence(SCRIPT));

        for (int bank = 1; bank <= NAMES.size(); bank++) {
            assertThat(quote(service, bank, 499).getInterestRate()).isEqualTo(expectedRates(bank)[HIGHEST]);
        }
    }

    @Test
    void goldScoresUseMiddleRate() {
        // Credit scores 500 and 699 are from 500 to 699 and get rate index 1, the middle.
        BankService service = new BankService(PROPERTIES, sequence(SCRIPT));

        for (int bank = 1; bank <= NAMES.size(); bank++) {
            double middle = expectedRates(bank)[MIDDLE];

            assertThat(quote(service, bank, 500).getInterestRate()).isEqualTo(middle);
            assertThat(quote(service, bank, 699).getInterestRate()).isEqualTo(middle);
        }
    }

    @Test
    void platinumScoreUsesLowestRate() {
        // A credit score of 700 is 700 or above and gets rate index 0, the lowest.
        BankService service = new BankService(PROPERTIES, sequence(SCRIPT));

        for (int bank = 1; bank <= NAMES.size(); bank++) {
            assertThat(quote(service, bank, 700).getInterestRate()).isEqualTo(expectedRates(bank)[LOWEST]);
        }
    }

    @Test
    void bankFlowsReturnConfiguredNames() {
        // bank1Flow … bank5Flow quote under the names Bank #1 … Bank #5 of the properties.
        BankService service = new BankService(PROPERTIES, sequence(SCRIPT));

        for (int bank = 1; bank <= NAMES.size(); bank++) {
            assertThat(quote(service, bank, 600).getBankName()).isEqualTo("Bank #" + bank);
        }
    }

    @Test
    void everyQuoteIsLogged() {
        // Each call logs one INFO event, Returning Rate is: <bankName>, rate: <rate>, in call order.
        BankService service = new BankService(PROPERTIES, sequence(SCRIPT));

        for (int bank = 1; bank <= NAMES.size(); bank++) {
            quote(service, bank, 600);
        }

        assertThat(appender.list).hasSize(NAMES.size());
        for (int bank = 1; bank <= NAMES.size(); bank++) {
            ILoggingEvent event = appender.list.get(bank - 1);

            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .isEqualTo("Returning Rate is: " + NAMES.get(bank - 1) + ", rate: " + expectedRates(bank)[MIDDLE]);
        }
    }

    @Test
    void defaultConstructorDrawsRatesBelowTen() {
        // With Math.random(), every bank's rates for scores 800, 600 and 300 lie in [0, 10) and do not decrease.
        for (int construction = 0; construction < DEFAULT_CONSTRUCTIONS; construction++) {
            BankService service = new BankService(PROPERTIES);

            for (int bank = 1; bank <= NAMES.size(); bank++) {
                double lowest = quote(service, bank, 800).getInterestRate();
                double middle = quote(service, bank, 600).getInterestRate();
                double highest = quote(service, bank, 300).getInterestRate();

                assertThat(lowest).isGreaterThanOrEqualTo(0.0).isLessThan(10.0);
                assertThat(middle).isGreaterThanOrEqualTo(0.0).isLessThan(10.0);
                assertThat(highest).isGreaterThanOrEqualTo(0.0).isLessThan(10.0);
                assertThat(lowest).isLessThanOrEqualTo(middle);
                assertThat(middle).isLessThanOrEqualTo(highest);
            }
        }
    }
}
