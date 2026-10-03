package com.mulesoft.examples.foreach_processing_and_choice_routing.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

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
 * <p>The constructor checks each entry present under {@code http-listener-configuration-1} …
 * {@code -7} while the context starts and throws {@link IllegalArgumentException} for the first
 * unusable field, with a message that starts with its key, for example
 * {@code listener.http-listener-configuration-4.port}: a {@code host} that is absent, blank or starts
 * with {@code TODO}; a {@code port} that is absent, which binds as {@code -1} and is reported as
 * required; or a {@code port} outside {@code 0} … {@code 65535} for listener 1 and outside
 * {@code 1} … {@code 65535} for listeners 2 … 7. The {@code base-path} is not checked. An absent
 * entry is not rejected here: {@link #listener(int)} and the helpers that read it throw for it.
 * Keys of any other form, such as {@code httplistenerconfiguration1}, are not checked. The check
 * makes no network request and binds no port.
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

    /** Property path of the {@code listener} map, used in the missing-entry and invalid-field messages. */
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

    /** Listener number of the loan broker ({@code HTTP_Listener_Configuration_1}). */
    private static final int PRIMARY = 1;

    /** Lowest port of listener 1; {@code 0} selects a free port. */
    private static final int MIN_PRIMARY_PORT = 0;

    /** Lowest port of listeners 2 … 7. */
    private static final int MIN_ADDITIONAL_PORT = 1;

    /** Highest port of any listener. */
    private static final int MAX_PORT = 65535;

    /** Port an entry binds when its {@code port} key is absent. */
    private static final int ABSENT_PORT = -1;

    /** Text that opens an unfilled placeholder value. */
    private static final String PLACEHOLDER = "TODO";

    /**
     * Replaces a {@code null} map with {@link Map#of()} and stores any other map as an unmodifiable
     * copy that keeps the binder's key order, then checks the entries present under
     * {@code http-listener-configuration-1} … {@code -7} in that order.
     *
     * @param listener listener entries by key {@code http-listener-configuration-<n>}, or {@code null}
     * @throws IllegalArgumentException whose message starts with
     *         {@code listener.http-listener-configuration-<n>.host} or {@code .port} for the first
     *         present entry whose host is absent, blank or starts with {@code TODO}, whose port is
     *         absent (bound as {@code -1}), or whose port is outside {@code 0} … {@code 65535}
     *         (listener 1) or {@code 1} … {@code 65535} (listeners 2 … 7)
     */
    public ListenerProperties {
        listener = (listener == null)
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(listener));
        requireUsableEntries(listener);
    }

    /**
     * Checks the host and port of each entry present under {@code http-listener-configuration-1} …
     * {@code -7}, in ascending order. Absent entries and keys of any other form are not checked.
     *
     * @param listener bound listener entries by key
     * @throws IllegalArgumentException with the message
     *         {@code listener.http-listener-configuration-<n>.host is required} when the host is
     *         absent, {@code listener.http-listener-configuration-<n>.host must not be blank or the TODO
     *         placeholder: <host>} when it is blank or starts with {@code TODO},
     *         {@code listener.http-listener-configuration-<n>.port is required} when the port is
     *         {@code -1}, the value an absent port binds, and
     *         {@code listener.http-listener-configuration-<n>.port must be between <min> and 65535:
     *         <port>} when any other port is below {@code <min>}, {@code 0} for listener 1 and
     *         {@code 1} for listeners 2 … 7, or above {@code 65535}
     */
    private static void requireUsableEntries(Map<String, Listener> listener) {
        for (int n = PRIMARY; n <= LAST_ADDITIONAL; n++) {
            String key = KEY_PREFIX + n;
            Listener entry = listener.get(key);
            if (entry == null) {
                continue;
            }
            String host = entry.host();
            String hostKey = PROPERTY_PATH + key + ".host";
            if (host == null) {
                throw new IllegalArgumentException(hostKey + " is required");
            }
            if (host.isBlank() || host.strip().startsWith(PLACEHOLDER)) {
                throw new IllegalArgumentException(
                        hostKey + " must not be blank or the TODO placeholder: " + host);
            }
            int minPort = (n == PRIMARY) ? MIN_PRIMARY_PORT : MIN_ADDITIONAL_PORT;
            int port = entry.port();
            if (port == ABSENT_PORT) {
                throw new IllegalArgumentException(PROPERTY_PATH + key + ".port is required");
            }
            if (port < minPort || port > MAX_PORT) {
                throw new IllegalArgumentException(PROPERTY_PATH + key + ".port must be between "
                        + minPort + " and " + MAX_PORT + ": " + port);
            }
        }
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
     * @param port     TCP port the listener accepts on; an absent {@code port} key binds as {@code -1},
     *                 which {@link ListenerProperties} reports as required
     * @param basePath path prefix of every path the listener serves; a {@code null} value binds as
     *                 {@code ""}, the value of listener 1, which has no base path
     */
    public record Listener(String host, @DefaultValue("-1") int port, String basePath) {

        /**
         * Replaces a {@code null} base path with {@code ""}.
         *
         * @param host     interface address the listener binds
         * @param port     TCP port the listener accepts on, or {@code -1} when the {@code port} key is
         *                 absent
         * @param basePath path prefix of the listener, or {@code null}
         */
        public Listener {
            basePath = (basePath == null) ? "" : basePath;
        }
    }
}
