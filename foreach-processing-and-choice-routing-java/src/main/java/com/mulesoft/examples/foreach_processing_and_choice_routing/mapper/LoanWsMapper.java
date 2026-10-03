package com.mulesoft.examples.foreach_processing_and_choice_routing.mapper;

import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CreditProfile;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.Customer;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.CustomerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanBrokerQuoteRequest;
import com.mulesoft.examples.foreach_processing_and_choice_routing.model.LoanQuote;
import org.springframework.stereotype.Component;

/**
 * Copies values between the loan broker model classes of package {@code model} and the SOAP wire
 * types that {@code jaxb2-maven-plugin} generates from the two service contracts (D-028):
 *
 * <ul>
 *   <li>{@code wsdl/CreditAgencyService.wsdl} → package {@code org.mule.example.loanbroker.creditagency}:
 *       {@code Customer}, {@code CreditProfile};</li>
 *   <li>{@code wsdl/BankService.wsdl} → package {@code org.mule.example.loanbroker.bank}:
 *       {@code LoanBrokerQuoteRequest}, {@code CustomerQuoteRequest}, {@code Customer},
 *       {@code CreditProfile}, {@code LoanQuote}.</li>
 * </ul>
 *
 * <p>Callers by side of the SOAP exchange
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml]:
 *
 * <ul>
 *   <li>credit agency client (sub-flow {@code lookupCustomerCreditProfile}, :72):
 *       {@link #toWire(Customer)} and
 *       {@link #fromWire(org.mule.example.loanbroker.creditagency.CreditProfile)};</li>
 *   <li>bank client (sub-flow {@code lookupLoanQuote}, :107):
 *       {@link #toWire(LoanBrokerQuoteRequest)} and
 *       {@link #fromWire(org.mule.example.loanbroker.bank.LoanQuote)};</li>
 *   <li>credit agency endpoint (flow {@code TheCreditAgencyService}, :138-144):
 *       {@link #fromWire(org.mule.example.loanbroker.creditagency.Customer)} and
 *       {@link #toCreditAgencyWire(CreditProfile)};</li>
 *   <li>bank endpoint (flows {@code Bank1Flow} … {@code Bank5Flow}, :146-195):
 *       {@link #fromWire(org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest)} and
 *       {@link #toWire(LoanQuote)}.</li>
 * </ul>
 *
 * <p>Every method is a plain field-by-field copy: strings are copied as the same reference,
 * {@code int} and {@code double} values unchanged. A {@code null} argument returns {@code null}.
 * A {@code null} nested member of the argument stays {@code null} on the result; JAXB writes no
 * element for a {@code null} member. A request that carries only a credit profile
 * [loanbroker-simple.xml:100-104] maps to a wire request with no {@code customerRequest} and no
 * {@code loanQuote} element.
 *
 * <p>Instances hold no state. Every method creates new objects, leaves its argument unchanged and
 * is safe for concurrent use; {@code new LoanWsMapper()} needs no Spring context.
 *
 * <pre>{@code
 * LoanWsMapper mapper = new LoanWsMapper();
 * org.mule.example.loanbroker.creditagency.Customer wire = mapper.toWire(new Customer("Muley", 1234));
 * wire.getName(); // "Muley"
 * wire.getSsn();  // 1234
 * }</pre>
 */
@Component
public class LoanWsMapper {

    // ---------------------------------------------------------------------------------------
    // Credit agency contract: client side
    // ---------------------------------------------------------------------------------------

    /**
     * Converts a model customer into the credit agency wire customer.
     *
     * @param customer the model customer, may be {@code null}
     * @return a new wire customer with {@code name} and {@code ssn} copied, or {@code null} when
     *         {@code customer} is {@code null}
     */
    public org.mule.example.loanbroker.creditagency.Customer toWire(Customer customer) {
        if (customer == null) {
            return null;
        }
        org.mule.example.loanbroker.creditagency.Customer wire =
                new org.mule.example.loanbroker.creditagency.Customer();
        wire.setName(customer.getName());
        wire.setSsn(customer.getSsn());
        return wire;
    }

    /**
     * Converts a credit agency wire credit profile into a model credit profile.
     *
     * @param profile the wire credit profile, may be {@code null}
     * @return a new model credit profile with {@code creditScore} and {@code creditHistory}
     *         copied, or {@code null} when {@code profile} is {@code null}
     */
    public CreditProfile fromWire(org.mule.example.loanbroker.creditagency.CreditProfile profile) {
        if (profile == null) {
            return null;
        }
        CreditProfile model = new CreditProfile();
        model.setCreditScore(profile.getCreditScore());
        model.setCreditHistory(profile.getCreditHistory());
        return model;
    }

    // ---------------------------------------------------------------------------------------
    // Credit agency contract: endpoint side
    // ---------------------------------------------------------------------------------------

    /**
     * Converts a credit agency wire customer into a model customer.
     *
     * @param customer the wire customer, may be {@code null}
     * @return a new model customer with {@code name} and {@code ssn} copied, or {@code null} when
     *         {@code customer} is {@code null}
     */
    public Customer fromWire(org.mule.example.loanbroker.creditagency.Customer customer) {
        if (customer == null) {
            return null;
        }
        return new Customer(customer.getName(), customer.getSsn());
    }

    /**
     * Converts a model credit profile into the credit agency wire credit profile.
     *
     * @param profile the model credit profile, may be {@code null}
     * @return a new wire credit profile with {@code creditScore} and {@code creditHistory} copied,
     *         or {@code null} when {@code profile} is {@code null}
     */
    public org.mule.example.loanbroker.creditagency.CreditProfile toCreditAgencyWire(CreditProfile profile) {
        if (profile == null) {
            return null;
        }
        org.mule.example.loanbroker.creditagency.CreditProfile wire =
                new org.mule.example.loanbroker.creditagency.CreditProfile();
        wire.setCreditScore(profile.getCreditScore());
        wire.setCreditHistory(profile.getCreditHistory());
        return wire;
    }

    // ---------------------------------------------------------------------------------------
    // Bank contract: client side
    // ---------------------------------------------------------------------------------------

    /**
     * Converts a model loan broker quote request into the bank wire request.
     *
     * <p>Copies {@code creditProfile} ({@code creditScore}, {@code creditHistory}),
     * {@code customerRequest} ({@code customer} with {@code name} and {@code ssn},
     * {@code loanAmount}, {@code loanDuration}) and {@code loanQuote} ({@code bankName},
     * {@code interestRate}). Each {@code null} member, at any depth, stays {@code null} on the
     * result.
     *
     * @param request the model request, may be {@code null}
     * @return a new wire request, or {@code null} when {@code request} is {@code null}
     */
    public org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest toWire(LoanBrokerQuoteRequest request) {
        if (request == null) {
            return null;
        }
        org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest wire =
                new org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest();
        wire.setCreditProfile(toBankWire(request.getCreditProfile()));
        wire.setCustomerRequest(toBankWire(request.getCustomerRequest()));
        wire.setLoanQuote(toWire(request.getLoanQuote()));
        return wire;
    }

    /**
     * Converts a bank wire loan quote into a model loan quote.
     *
     * @param quote the wire loan quote, may be {@code null}
     * @return a new model loan quote with {@code bankName} and {@code interestRate} copied, or
     *         {@code null} when {@code quote} is {@code null}
     */
    public LoanQuote fromWire(org.mule.example.loanbroker.bank.LoanQuote quote) {
        if (quote == null) {
            return null;
        }
        LoanQuote model = new LoanQuote();
        model.setBankName(quote.getBankName());
        model.setInterestRate(quote.getInterestRate());
        return model;
    }

    // ---------------------------------------------------------------------------------------
    // Bank contract: endpoint side
    // ---------------------------------------------------------------------------------------

    /**
     * Converts a bank wire loan broker quote request into a model request; the inverse of
     * {@link #toWire(LoanBrokerQuoteRequest)}.
     *
     * <p>Copies {@code creditProfile}, {@code customerRequest} (with its nested {@code customer})
     * and {@code loanQuote}. Each {@code null} member, at any depth, stays {@code null} on the
     * result.
     *
     * @param request the wire request, may be {@code null}
     * @return a new model request, or {@code null} when {@code request} is {@code null}
     */
    public LoanBrokerQuoteRequest fromWire(org.mule.example.loanbroker.bank.LoanBrokerQuoteRequest request) {
        if (request == null) {
            return null;
        }
        LoanBrokerQuoteRequest model = new LoanBrokerQuoteRequest();
        model.setCreditProfile(fromWire(request.getCreditProfile()));
        model.setCustomerRequest(fromWire(request.getCustomerRequest()));
        model.setLoanQuote(fromWire(request.getLoanQuote()));
        return model;
    }

    /**
     * Converts a model loan quote into the bank wire loan quote.
     *
     * @param quote the model loan quote, may be {@code null}
     * @return a new wire loan quote with {@code bankName} and {@code interestRate} copied, or
     *         {@code null} when {@code quote} is {@code null}
     */
    public org.mule.example.loanbroker.bank.LoanQuote toWire(LoanQuote quote) {
        if (quote == null) {
            return null;
        }
        org.mule.example.loanbroker.bank.LoanQuote wire = new org.mule.example.loanbroker.bank.LoanQuote();
        wire.setBankName(quote.getBankName());
        wire.setInterestRate(quote.getInterestRate());
        return wire;
    }

    // ---------------------------------------------------------------------------------------
    // Bank contract: nested members
    // ---------------------------------------------------------------------------------------

    /**
     * Converts a model credit profile into the bank wire credit profile.
     *
     * @param profile the model credit profile, may be {@code null}
     * @return a new wire credit profile with {@code creditScore} and {@code creditHistory} copied,
     *         or {@code null} when {@code profile} is {@code null}
     */
    private org.mule.example.loanbroker.bank.CreditProfile toBankWire(CreditProfile profile) {
        if (profile == null) {
            return null;
        }
        org.mule.example.loanbroker.bank.CreditProfile wire = new org.mule.example.loanbroker.bank.CreditProfile();
        wire.setCreditScore(profile.getCreditScore());
        wire.setCreditHistory(profile.getCreditHistory());
        return wire;
    }

    /**
     * Converts a model customer quote request, with its nested customer, into the bank wire
     * customer quote request.
     *
     * @param request the model customer quote request, may be {@code null}
     * @return a new wire customer quote request with {@code customer}, {@code loanAmount} and
     *         {@code loanDuration} copied, or {@code null} when {@code request} is {@code null}
     */
    private org.mule.example.loanbroker.bank.CustomerQuoteRequest toBankWire(CustomerQuoteRequest request) {
        if (request == null) {
            return null;
        }
        org.mule.example.loanbroker.bank.CustomerQuoteRequest wire =
                new org.mule.example.loanbroker.bank.CustomerQuoteRequest();
        wire.setCustomer(toBankWire(request.getCustomer()));
        wire.setLoanAmount(request.getLoanAmount());
        wire.setLoanDuration(request.getLoanDuration());
        return wire;
    }

    /**
     * Converts a model customer into the bank wire customer.
     *
     * @param customer the model customer, may be {@code null}
     * @return a new wire customer with {@code name} and {@code ssn} copied, or {@code null} when
     *         {@code customer} is {@code null}
     */
    private org.mule.example.loanbroker.bank.Customer toBankWire(Customer customer) {
        if (customer == null) {
            return null;
        }
        org.mule.example.loanbroker.bank.Customer wire = new org.mule.example.loanbroker.bank.Customer();
        wire.setName(customer.getName());
        wire.setSsn(customer.getSsn());
        return wire;
    }

    /**
     * Converts a bank wire credit profile into a model credit profile.
     *
     * @param profile the wire credit profile, may be {@code null}
     * @return a new model credit profile with {@code creditScore} and {@code creditHistory}
     *         copied, or {@code null} when {@code profile} is {@code null}
     */
    private CreditProfile fromWire(org.mule.example.loanbroker.bank.CreditProfile profile) {
        if (profile == null) {
            return null;
        }
        CreditProfile model = new CreditProfile();
        model.setCreditScore(profile.getCreditScore());
        model.setCreditHistory(profile.getCreditHistory());
        return model;
    }

    /**
     * Converts a bank wire customer quote request, with its nested customer, into a model
     * customer quote request.
     *
     * @param request the wire customer quote request, may be {@code null}
     * @return a new model customer quote request with {@code customer}, {@code loanAmount} and
     *         {@code loanDuration} copied, or {@code null} when {@code request} is {@code null}
     */
    private CustomerQuoteRequest fromWire(org.mule.example.loanbroker.bank.CustomerQuoteRequest request) {
        if (request == null) {
            return null;
        }
        return new CustomerQuoteRequest(
                fromWire(request.getCustomer()), request.getLoanAmount(), request.getLoanDuration());
    }

    /**
     * Converts a bank wire customer into a model customer.
     *
     * @param customer the wire customer, may be {@code null}
     * @return a new model customer with {@code name} and {@code ssn} copied, or {@code null} when
     *         {@code customer} is {@code null}
     */
    private Customer fromWire(org.mule.example.loanbroker.bank.Customer customer) {
        if (customer == null) {
            return null;
        }
        return new Customer(customer.getName(), customer.getSsn());
    }
}
