package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the seven HTTP listener addresses of the loan broker from the root {@code listener} map of
 * {@code application.yml} (D-011, D-295).
 *
 * <p>Sources: {@code foreach-processing-and-choice-routing/src/main/app/loanbroker-simple.xml:4,7-12},
 * the {@code http:listener-config} elements {@code HTTP_Listener_Configuration_1} …
 * {@code HTTP_Listener_Configuration_7}. Each element maps to one entry keyed
 * {@code http-listener-configuration-<n>}, with the properties {@code host}, {@code port} and
 * {@code base-path}:
 * <ul>
 *   <li>listener 1: the loan broker (flow {@code loan-broker-sync}), served on {@code server.port};</li>
 *   <li>listener 2: the SOAP credit agency (flow {@code TheCreditAgencyService});</li>
 *   <li>listeners 3 … 7: the SOAP banks (flows {@code Bank1Flow} … {@code Bank5Flow}).</li>
 * </ul>
 *
 * <p>The map keys keep their dashed YAML form. The record is registered by
 * {@code @ConfigurationPropertiesScan} on the application class.
 *
 * <p>Usage:
 * <pre>{@code
 * Listener bank2 = listenerProperties.listener(4);              // HTTP_Listener_Configuration_4, Bank2
 * Optional<Listener> owner = listenerProperties.additionalOnPort(request.getLocalPort());
 * }</pre>
 *
 * @param listener listener entries by key {@code http-listener-configuration-<n>}; a {@code null} map
 *                 binds as an empty map, and the bound map is an unmodifiable copy in key order
 */
@ConfigurationProperties
public record ListenerProperties(Map<String, Listener> listener) {

    /** Key prefix of every listener entry; the listener number follows it. */
    private static final String KEY_PREFIX = "http-listener-configuration-";

    /** Property path of the {@code listener} map, used in the missing-entry message. */
    private static final String PROPERTY_PATH = "listener.";

    /** Listener number of the SOAP credit agency ({@code HTTP_Listener_Configuration_2}). */
    private static final int CREDIT_AGENCY = 2;

    /** Listener number of the first bank ({@code HTTP_Listener_Configuration_3}, Bank1). */
    private static final int FIRST_BANK = 3;

    /** Listener number of the last bank ({@code HTTP_Listener_Configuration_7}, Bank5). */
    private static final int LAST_BANK = 7;

    /** Listener number of the first listener opened beside {@code server.port}. */
    private static final int FIRST_ADDITIONAL = 2;

    /** Listener number of the last listener opened beside {@code server.port}. */
    private static final int LAST_ADDITIONAL = 7;

    /**
     * Replaces a {@code null} map with {@link Map#of()} and stores any other map as an unmodifiable
     * copy that keeps the binder's key order.
     *
     * @param listener listener entries by key {@code http-listener-configuration-<n>}, or {@code null}
     */
    public ListenerProperties {
        listener = (listener == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(listener));
    }

    /**
     * Returns the entry bound under {@code listener.http-listener-configuration-<n>}.
     *
     * @param n listener number, {@code 1} … {@code 7} in {@code loanbroker-simple.xml}
     * @return the listener address bound for that number
     * @throws IllegalStateException with the message
     *         {@code Missing listener configuration: listener.http-listener-configuration-<n>} when no
     *         entry is bound under that key
     */
    public Listener listener(int n) {
        String key = KEY_PREFIX + n;
        Listener entry = listener.get(key);
        if (entry == null) {
            throw new IllegalStateException("Missing listener configuration: " + PROPERTY_PATH + key);
        }
        return entry;
    }

    /**
     * Returns listener 2, the SOAP credit agency (flow {@code TheCreditAgencyService}).
     *
     * @return the entry for {@code http-listener-configuration-2}
     * @throws IllegalStateException when that entry is not bound
     */
    public Listener creditAgency() {
        return listener(CREDIT_AGENCY);
    }

    /**
     * Returns listeners 3, 4, 5, 6 and 7 in that order: Bank1 … Bank5.
     *
     * @return an unmodifiable list of five entries, Bank1 first
     * @throws IllegalStateException naming the first bank entry that is not bound
     */
    public List<Listener> banks() {
        return range(FIRST_BANK, LAST_BANK);
    }

    /**
     * Returns listeners 2 … 7 in order: the credit agency, then Bank1 … Bank5. These are the
     * listeners opened beside {@code server.port}, which serves listener 1.
     *
     * @return an unmodifiable list of six entries, the credit agency first
     * @throws IllegalStateException naming the first entry among 2 … 7 that is not bound
     */
    public List<Listener> additional() {
        return range(FIRST_ADDITIONAL, LAST_ADDITIONAL);
    }

    /**
     * Returns the listener among 2 … 7 whose {@code port} equals {@code localPort}. An empty result
     * identifies a request accepted on any other port, which belongs to listener 1.
     *
     * @param localPort local port that accepted the request
     * @return the first of listeners 2 … 7 bound on that port, or {@link Optional#empty()} when none is
     * @throws IllegalStateException naming the first entry among 2 … 7 that is not bound
     */
    public Optional<Listener> additionalOnPort(int localPort) {
        return additional().stream()
                .filter(entry -> entry.port() == localPort)
                .findFirst();
    }

    /**
     * Returns the entries numbered {@code first} … {@code last}, inclusive, in ascending order.
     *
     * @param first first listener number
     * @param last  last listener number
     * @return an unmodifiable list of the entries
     * @throws IllegalStateException naming the first entry in the range that is not bound
     */
    private List<Listener> range(int first, int last) {
        return IntStream.rangeClosed(first, last)
                .mapToObj(this::listener)
                .toList();
    }

    /**
     * Address of one HTTP listener: {@code listener.http-listener-configuration-<n>.host},
     * {@code .port} and {@code .base-path}.
     *
     * @param host     interface address the listener binds, for example {@code 0.0.0.0}
     * @param port     TCP port the listener accepts on
     * @param basePath path prefix of every path the listener serves; a {@code null} value binds as
     *                 {@code ""}, the value of listener 1, which has no base path
     */
    public record Listener(String host, int port, String basePath) {

        /**
         * Replaces a {@code null} base path with {@code ""}.
         *
         * @param host     interface address the listener binds
         * @param port     TCP port the listener accepts on
         * @param basePath path prefix of the listener, or {@code null}
         */
        public Listener {
            basePath = (basePath == null) ? "" : basePath;
        }
    }
}
