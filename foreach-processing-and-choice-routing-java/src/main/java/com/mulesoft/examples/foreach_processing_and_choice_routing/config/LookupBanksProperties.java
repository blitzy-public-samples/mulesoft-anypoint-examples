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
 * declares no defaults. Its constructor checks the five URIs in bank order while the context starts
 * and throws {@link IllegalArgumentException}, naming the first key whose value is absent or empty,
 * starts with {@code TODO}, is not an absolute {@code http} or {@code https} URI with a host, or names
 * a port outside {@code 1} … {@code 65535}; a URI without a port is accepted. The check makes no
 * network request.
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

    /** Property path of every bank URI key up to the bank number. */
    private static final String KEY_PREFIX = "lookup-banks.bank-";

    /** Property path of every bank URI key after the bank number. */
    private static final String KEY_SUFFIX = "-uri";

    /** Text that opens an unfilled placeholder value. */
    private static final String PLACEHOLDER = "TODO";

    /** Value of {@link URI#getPort()} for a URI that names no port. */
    private static final int NO_PORT = -1;

    /** Lowest port a bank URI may name. */
    private static final int MIN_PORT = 1;

    /** Highest port a bank URI may name. */
    private static final int MAX_PORT = 65535;

    /**
     * Checks {@code bank1Uri} … {@code bank5Uri} in that order and throws for the first unusable one.
     *
     * @param bank1Uri URI bound from {@code lookup-banks.bank-1-uri}
     * @param bank2Uri URI bound from {@code lookup-banks.bank-2-uri}
     * @param bank3Uri URI bound from {@code lookup-banks.bank-3-uri}
     * @param bank4Uri URI bound from {@code lookup-banks.bank-4-uri}
     * @param bank5Uri URI bound from {@code lookup-banks.bank-5-uri}
     * @throws IllegalArgumentException whose message starts with the key of the first URI that is
     *                                  {@code null}, starts with {@code TODO}, is not an absolute
     *                                  {@code http} or {@code https} URI with a host, or names a port
     *                                  outside {@code 1} … {@code 65535}, and ends with the rejected URI
     */
    public LookupBanksProperties {
        requireBankUri(1, bank1Uri);
        requireBankUri(2, bank2Uri);
        requireBankUri(3, bank3Uri);
        requireBankUri(4, bank4Uri);
        requireBankUri(5, bank5Uri);
    }

    /**
     * Checks the URI bound from {@code lookup-banks.bank-<bank>-uri}.
     *
     * @param bank number of the bank, {@code 1} … {@code 5}
     * @param uri  bound URI, or {@code null} when the key is absent or empty
     * @throws IllegalArgumentException with the message {@code <key> is required} when {@code uri} is
     *         {@code null}; {@code <key> must be an absolute http or https URI with a host: <uri>} when
     *         its text starts with {@code TODO}, its scheme is neither {@code http} nor {@code https}
     *         (in any case), or its host is absent, blank or starts with {@code TODO}; and
     *         {@code <key> must name a port between 1 and 65535: <uri>} when it names a port outside
     *         that range
     */
    private static void requireBankUri(int bank, URI uri) {
        String key = KEY_PREFIX + bank + KEY_SUFFIX;
        if (uri == null) {
            throw new IllegalArgumentException(key + " is required");
        }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean usable = !isPlaceholder(uri.toString())
                && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && host != null
                && !host.isBlank()
                && !isPlaceholder(host);
        if (!usable) {
            throw new IllegalArgumentException(
                    key + " must be an absolute http or https URI with a host: " + uri);
        }
        int port = uri.getPort();
        if (port != NO_PORT && (port < MIN_PORT || port > MAX_PORT)) {
            throw new IllegalArgumentException(
                    key + " must name a port between " + MIN_PORT + " and " + MAX_PORT + ": " + uri);
        }
    }

    /**
     * Tells whether a value is the unfilled placeholder.
     *
     * @param value value to test
     * @return {@code true} when {@code value}, stripped of surrounding white space, starts with
     *         {@code TODO}
     */
    private static boolean isPlaceholder(String value) {
        return value.strip().startsWith(PLACEHOLDER);
    }
}
