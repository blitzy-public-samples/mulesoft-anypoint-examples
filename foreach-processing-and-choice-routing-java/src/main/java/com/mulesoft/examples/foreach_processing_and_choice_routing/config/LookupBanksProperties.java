package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bank URIs of the {@code lookupBanks} sub-flow
 * [foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:79-94], bound by constructor
 * binding from the {@code lookup-banks.*} keys of {@code application.yml}.
 *
 * <p>Each component binds the key of the same name in kebab case, {@code bank-1-uri} to
 * {@code bank-5-uri}, and Spring's conversion service turns each value into a {@link URI}. The record
 * declares no defaults: a key that is absent binds {@code null}.
 * {@code service/LoanBrokerService.lookupBanks} returns Bank1 and Bank2, Bank3 and Bank4, or Bank5 from
 * these accessors (D-043), and {@code client/BankClient} addresses each bank call with the URI's host,
 * port and path, as {@code HTTP_Request_Configuration_2} does [loanbroker-simple.xml:6].
 *
 * @param bank1Uri {@code lookup-banks.bank-1-uri}: the URI of Bank1, first of the two banks of the
 *                 {@code >= 20000} branch [loanbroker-simple.xml:83];
 *                 {@code http://0.0.0.0:10080/mule/TheBank1} in {@code application.yml}
 * @param bank2Uri {@code lookup-banks.bank-2-uri}: the URI of Bank2, second of the two banks of the
 *                 {@code >= 20000} branch [loanbroker-simple.xml:83];
 *                 {@code http://0.0.0.0:20080/mule/TheBank2} in {@code application.yml}
 * @param bank3Uri {@code lookup-banks.bank-3-uri}: the URI of Bank3, first of the two banks of the
 *                 {@code >= 10000 || <= 19999} branch [loanbroker-simple.xml:86];
 *                 {@code http://0.0.0.0:30080/mule/TheBank3} in {@code application.yml}
 * @param bank4Uri {@code lookup-banks.bank-4-uri}: the URI of Bank4, second of the two banks of the
 *                 {@code >= 10000 || <= 19999} branch [loanbroker-simple.xml:86];
 *                 {@code http://0.0.0.0:40080/mule/TheBank4} in {@code application.yml}
 * @param bank5Uri {@code lookup-banks.bank-5-uri}: the URI of Bank5, the only bank of the
 *                 {@code otherwise} branch [loanbroker-simple.xml:89], which no loan amount reaches
 *                 (D-043); {@code http://0.0.0.0:50080/mule/TheBank5} in {@code application.yml}
 */
@ConfigurationProperties("lookup-banks")
public record LookupBanksProperties(URI bank1Uri, URI bank2Uri, URI bank3Uri, URI bank4Uri, URI bank5Uri) {
}
