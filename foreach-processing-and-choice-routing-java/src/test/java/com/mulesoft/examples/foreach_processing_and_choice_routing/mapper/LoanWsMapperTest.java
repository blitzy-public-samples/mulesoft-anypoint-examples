package com.mulesoft.examples.foreach_processing_and_choice_routing.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CustomerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import org.junit.jupiter.api.Test;

/**
 * Unit tests of {@link LoanWsMapper}, which copies values between the {@code model} classes and the wire
 * types generated from {@code wsdl/CreditAgencyService.wsdl} (package
 * {@code org.mule.example.loanbroker.creditagency}) and {@code wsdl/BankService.wsdl} (package
 * {@code org.mule.example.loanbroker.bank}) (D-028); coverage floor D-049.
 *
 * <p>The mapper is created with {@code new}, with no Spring context and no mocks. Each test asserts the
 * copied fields one by one: strings and {@code int} values with {@code assertEquals}, {@code double}
 * values with a delta of {@code 0.0}, and {@code null} members with {@code assertNull}. Generated types
 * are named by their fully qualified names.
 */
class LoanWsMapperTest {

    private final LoanWsMapper mapper = new LoanWsMapper();

    /**
     * Returns a model request with every member set: customer request {@code ("Ross", 1234)},
     * {@code 15000.0}, {@code 24}; credit profile {@code 750}, {@code 12}; loan quote {@code "Bank3"},
     * {@code 4.25}.
     *
     * @return the populated model request
     */
    private static LoanBrokerQuoteRequest fullModelRequest() {
        CreditProfile profile = new CreditProfile();
        profile.setCreditScore(750);
        profile.setCreditHistory(12);
        LoanQuote quote = new LoanQuote();
        quote.setBankName("Bank3");
        quote.setInterestRate(4.25);
        LoanBrokerQuoteRequest request = new LoanBrokerQuoteRequest();
        request.setCustomerRequest(new CustomerQuoteRequest(new Customer("Ross", 1234), 15000.0, 24));
        request.setCreditProfile(profile);
        request.setLoanQuote(quote);
        return request;
    }

    /**
     * Returns a bank wire request with every member set to the values of {@link #fullModelRequest()}.
     *
     * @return the populated wire request
     */
    private static org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest fullWireRequest() {
        org.mule.example.loanbroker.bank.Customer customer = new org.mule.example.loanbroker.bank.Customer();
        customer.setName("Ross");
        customer.setSsn(1234);
        org.mule.example.loanbroker.bank.CustomerQuoteRequest customerRequest =
                new org.mule.example.loanbroker.bank.CustomerQuoteRequest();
        customerRequest.setCustomer(customer);
        customerRequest.setLoanAmount(15000.0);
        customerRequest.setLoanDuration(24);
        org.mule.example.loanbroker.bank.CreditProfile profile = new org.mule.example.loanbroker.bank.CreditProfile();
        profile.setCreditScore(750);
        profile.setCreditHistory(12);
        org.mule.example.loanbroker.bank.LoanQuote quote = new org.mule.example.loanbroker.bank.LoanQuote();
        quote.setBankName("Bank3");
        quote.setInterestRate(4.25);
        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest request =
                new org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest();
        request.setCustomerRequest(customerRequest);
        request.setCreditProfile(profile);
        request.setLoanQuote(quote);
        return request;
    }

    @Test
    void customerToCreditAgencyWireCopiesNameAndSsn() {
        org.mule.example.loanbroker.creditagency.Customer wire = mapper.toWire(new Customer("Ross", 1234));

        assertNotNull(wire);
        assertEquals("Ross", wire.getName());
        assertEquals(1234, wire.getSsn());
    }

    @Test
    void creditAgencyWireCustomerToModelCopiesNameAndSsn() {
        org.mule.example.loanbroker.creditagency.Customer wire = new org.mule.example.loanbroker.creditagency.Customer();
        wire.setName("Ross");
        wire.setSsn(1234);

        Customer model = mapper.fromWire(wire);

        assertNotNull(model);
        assertEquals("Ross", model.getName());
        assertEquals(1234, model.getSsn());
    }

    @Test
    void creditAgencyWireCreditProfileToModelCopiesScoreAndHistory() {
        org.mule.example.loanbroker.creditagency.CreditProfile wire =
                new org.mule.example.loanbroker.creditagency.CreditProfile();
        wire.setCreditScore(750);
        wire.setCreditHistory(12);

        CreditProfile model = mapper.fromWire(wire);

        assertNotNull(model);
        assertEquals(750, model.getCreditScore());
        assertEquals(12, model.getCreditHistory());
    }

    @Test
    void creditProfileToCreditAgencyWireCopiesScoreAndHistory() {
        CreditProfile model = new CreditProfile();
        model.setCreditScore(750);
        model.setCreditHistory(12);

        org.mule.example.loanbroker.creditagency.CreditProfile wire = mapper.toCreditAgencyWire(model);

        assertNotNull(wire);
        assertEquals(750, wire.getCreditScore());
        assertEquals(12, wire.getCreditHistory());
    }

    @Test
    void loanQuoteToBankWireCopiesBankNameAndRate() {
        LoanQuote model = new LoanQuote();
        model.setBankName("Bank1");
        model.setInterestRate(3.5);

        org.mule.example.loanbroker.bank.LoanQuote wire = mapper.toWire(model);

        assertNotNull(wire);
        assertEquals("Bank1", wire.getBankName());
        assertEquals(3.5, wire.getInterestRate(), 0.0);
    }

    @Test
    void bankWireLoanQuoteToModelCopiesBankNameAndRate() {
        org.mule.example.loanbroker.bank.LoanQuote wire = new org.mule.example.loanbroker.bank.LoanQuote();
        wire.setBankName("Bank1");
        wire.setInterestRate(3.5);

        LoanQuote model = mapper.fromWire(wire);

        assertNotNull(model);
        assertEquals("Bank1", model.getBankName());
        assertEquals(3.5, model.getInterestRate(), 0.0);
        assertEquals("Bank1, rate: 3.5", model.toString());
    }

    @Test
    void fullQuoteRequestToBankWireCopiesEveryNestedField() {
        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest wire = mapper.toWire(fullModelRequest());

        assertNotNull(wire);
        assertNotNull(wire.getCustomerRequest());
        assertNotNull(wire.getCustomerRequest().getCustomer());
        assertEquals("Ross", wire.getCustomerRequest().getCustomer().getName());
        assertEquals(1234, wire.getCustomerRequest().getCustomer().getSsn());
        assertEquals(15000.0, wire.getCustomerRequest().getLoanAmount(), 0.0);
        assertEquals(24, wire.getCustomerRequest().getLoanDuration());
        assertNotNull(wire.getCreditProfile());
        assertEquals(750, wire.getCreditProfile().getCreditScore());
        assertEquals(12, wire.getCreditProfile().getCreditHistory());
        assertNotNull(wire.getLoanQuote());
        assertEquals("Bank3", wire.getLoanQuote().getBankName());
        assertEquals(4.25, wire.getLoanQuote().getInterestRate(), 0.0);
    }

    @Test
    void fullQuoteRequestFromBankWireCopiesEveryNestedField() {
        LoanBrokerQuoteRequest model = mapper.fromWire(fullWireRequest());

        assertNotNull(model);
        assertNotNull(model.getCustomerRequest());
        assertNotNull(model.getCustomerRequest().getCustomer());
        assertEquals("Ross", model.getCustomerRequest().getCustomer().getName());
        assertEquals(1234, model.getCustomerRequest().getCustomer().getSsn());
        assertEquals(15000.0, model.getCustomerRequest().getLoanAmount(), 0.0);
        assertEquals(24, model.getCustomerRequest().getLoanDuration());
        assertNotNull(model.getCreditProfile());
        assertEquals(750, model.getCreditProfile().getCreditScore());
        assertEquals(12, model.getCreditProfile().getCreditHistory());
        assertNotNull(model.getLoanQuote());
        assertEquals("Bank3", model.getLoanQuote().getBankName());
        assertEquals(4.25, model.getLoanQuote().getInterestRate(), 0.0);
    }

    @Test
    void brokerRequestWithOnlyCreditProfileToBankWireLeavesOtherMembersNull() {
        CreditProfile profile = new CreditProfile();
        profile.setCreditScore(750);
        profile.setCreditHistory(12);
        LoanBrokerQuoteRequest model = new LoanBrokerQuoteRequest();
        model.setCreditProfile(profile);

        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest wire = mapper.toWire(model);

        assertNotNull(wire);
        assertNotNull(wire.getCreditProfile());
        assertEquals(750, wire.getCreditProfile().getCreditScore());
        assertEquals(12, wire.getCreditProfile().getCreditHistory());
        assertNull(wire.getCustomerRequest());
        assertNull(wire.getLoanQuote());
    }

    @Test
    void brokerRequestWithOnlyCreditProfileFromBankWireLeavesOtherMembersNull() {
        org.mule.example.loanbroker.bank.CreditProfile profile = new org.mule.example.loanbroker.bank.CreditProfile();
        profile.setCreditScore(750);
        profile.setCreditHistory(12);
        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest wire =
                new org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest();
        wire.setCreditProfile(profile);

        LoanBrokerQuoteRequest model = mapper.fromWire(wire);

        assertNotNull(model);
        assertNotNull(model.getCreditProfile());
        assertEquals(750, model.getCreditProfile().getCreditScore());
        assertEquals(12, model.getCreditProfile().getCreditHistory());
        assertNull(model.getCustomerRequest());
        assertNull(model.getLoanQuote());
    }

    @Test
    void customerRequestWithoutCustomerKeepsCustomerNullInBothDirections() {
        // A request holding only a customer request with no customer: amount and duration are copied, the
        // customer, credit profile and loan quote stay null, model to wire and wire to model.
        LoanBrokerQuoteRequest model = new LoanBrokerQuoteRequest();
        model.setCustomerRequest(new CustomerQuoteRequest(null, 15000.0, 24));

        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest toWire = mapper.toWire(model);

        assertNotNull(toWire);
        assertNotNull(toWire.getCustomerRequest());
        assertNull(toWire.getCustomerRequest().getCustomer());
        assertEquals(15000.0, toWire.getCustomerRequest().getLoanAmount(), 0.0);
        assertEquals(24, toWire.getCustomerRequest().getLoanDuration());
        assertNull(toWire.getCreditProfile());
        assertNull(toWire.getLoanQuote());

        org.mule.example.loanbroker.bank.CustomerQuoteRequest customerRequest =
                new org.mule.example.loanbroker.bank.CustomerQuoteRequest();
        customerRequest.setLoanAmount(15000.0);
        customerRequest.setLoanDuration(24);
        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest wire =
                new org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest();
        wire.setCustomerRequest(customerRequest);

        LoanBrokerQuoteRequest fromWire = mapper.fromWire(wire);

        assertNotNull(fromWire);
        assertNotNull(fromWire.getCustomerRequest());
        assertNull(fromWire.getCustomerRequest().getCustomer());
        assertEquals(15000.0, fromWire.getCustomerRequest().getLoanAmount(), 0.0);
        assertEquals(24, fromWire.getCustomerRequest().getLoanDuration());
        assertNull(fromWire.getCreditProfile());
        assertNull(fromWire.getLoanQuote());
    }

    @Test
    void nullInputReturnsNullForEveryPublicMethod() {
        assertNull(mapper.toWire((Customer) null));
        assertNull(mapper.fromWire((org.mule.example.loanbroker.creditagency.Customer) null));
        assertNull(mapper.fromWire((org.mule.example.loanbroker.creditagency.CreditProfile) null));
        assertNull(mapper.toCreditAgencyWire((CreditProfile) null));
        assertNull(mapper.toWire((LoanBrokerQuoteRequest) null));
        assertNull(mapper.fromWire((org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest) null));
        assertNull(mapper.fromWire((org.mule.example.loanbroker.bank.LoanQuote) null));
        assertNull(mapper.toWire((LoanQuote) null));
    }
}
