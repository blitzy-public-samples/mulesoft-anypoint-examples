package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Database configuration {@code MySQL_Configuration}: the data source built from {@code jdbc.url}, the
 * named-parameter JDBC template that runs the {@code orders} insert, and the local JDBC transaction manager used by
 * {@code @Transactional} (D-025, D-063).
 *
 * <p>Source: {@code using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:4}, the global element
 * {@code db:generic-config} named {@code MySQL_Configuration} with {@code url="${jdbc.url}"}.
 *
 * <p>Configuration:
 * <ul>
 *   <li>{@code jdbc.url} is the full JDBC connection string, user and password included where the server requires
 *       them, for example {@code jdbc:mysql://<host>:3306/<database>?user=<user>&password=<password>}. The committed
 *       {@code application.yml} holds a marked placeholder that is not a JDBC URL (D-012).</li>
 *   <li>The {@code dataSource} bean checks the structure of {@code jdbc.url} with {@code requireSupportedUrl} before
 *       it builds the pool (D-356). An unset, blank or placeholder value, surrounding whitespace, a prefix other
 *       than {@code jdbc:mysql:} or {@code jdbc:h2:}, a malformed MySQL protocol, percent escape, host list, port,
 *       database name or query, or a malformed H2 database name or setting fails the bean with
 *       {@link IllegalArgumentException}. The message names {@code jdbc.url} and contains no part of the value, and
 *       the application context does not start. No connection is opened at startup.</li>
 *   <li>The JDBC driver class is derived from the URL prefix: {@code jdbc:mysql:} selects the MySQL Connector/J
 *       driver of {@code mysql-connector-j} (D-063) and {@code jdbc:h2:} selects the H2 driver.</li>
 * </ul>
 *
 * <p>The three beans replace Boot's auto-configured data source, transaction manager and
 * {@link NamedParameterJdbcTemplate}; no {@code spring.datasource.*} key is read.
 */
@Configuration
@EnableConfigurationProperties(DataSourceConfig.JdbcProperties.class)
public class DataSourceConfig {

    /** Configuration key of the JDBC connection string. */
    static final String URL_KEY = "jdbc.url";

    /** URL prefix of the MySQL Connector/J driver. */
    static final String MYSQL_PREFIX = "jdbc:mysql:";

    /** URL prefix of the H2 driver. */
    static final String H2_PREFIX = "jdbc:h2:";

    /** Connector/J protocols accepted after {@value #MYSQL_PREFIX}, each ending in the {@code //} of the host list. */
    static final List<String> MYSQL_PROTOCOLS = List.of("//", "loadbalance://", "replication://");

    /** Form of {@value #URL_KEY} named by the messages for an unset, blank or placeholder value. */
    static final String EXPECTED_FORM = "jdbc:mysql://<host>[:<port>]/<database>[?<property>=<value>[&...]]";

    /** Highest TCP port number. */
    static final int MAX_PORT = 65535;

    /** Case-insensitive start of a Connector/J {@code address=(<key>=<value>)...} host entry. */
    private static final String ADDRESS_PREFIX = "address=";

    /** Key of a Connector/J host property, query parameter or key-value pair that holds a port, in any case. */
    private static final String PORT_KEY = "port";

    /** Characters besides ASCII letters and digits allowed in a Connector/J property or key name. */
    private static final String KEY_NAME_PUNCTUATION = "_.-%";

    /** Characters not allowed in the host name of a {@code <host>[:<port>]} host entry. */
    private static final String HOST_NAME_EXCLUDED = "[]()=";

    /**
     * Builds the pooled data source of {@code MySQL_Configuration} from {@code jdbc.url} (D-063, D-356).
     *
     * <p>The URL is checked by {@code requireSupportedUrl} and then set on the builder. Only the URL is set; the
     * driver class is derived from the URL prefix, and user and password are read from the URL by the driver. The
     * pool opens its first connection on first use, not at startup.
     *
     * @param properties the bound {@code jdbc.*} keys
     * @return a HikariCP data source for {@link JdbcProperties#url()}
     * @throws IllegalArgumentException when {@code jdbc.url} is not set, is blank, holds a placeholder such as the
     *         committed one, or is not a structurally valid {@code jdbc:mysql:} or {@code jdbc:h2:} URL; the message
     *         names {@code jdbc.url} and contains no part of its value
     */
    @Bean
    public DataSource dataSource(JdbcProperties properties) {
        return DataSourceBuilder.create().url(requireSupportedUrl(properties.url())).build();
    }

    /**
     * Returns {@code url} unchanged when it is a supported JDBC URL (D-356).
     *
     * <p>The checks run in this order, and the first one that fails ends the validation:
     * <ol>
     *   <li>The value is set, is not blank and contains a {@code :}; a value without a {@code :}, such as the marked
     *       placeholder of the committed {@code application.yml}, is a placeholder.</li>
     *   <li>The value has no leading or trailing whitespace.</li>
     *   <li>The value starts with {@value #MYSQL_PREFIX} or {@value #H2_PREFIX}, in lower case.</li>
     *   <li>A MySQL URL is read as Connector/J's {@code protocol//hosts/database?properties}:
     *     <ul>
     *       <li>the protocol after {@value #MYSQL_PREFIX} is {@code //}, {@code loadbalance://} or
     *           {@code replication://};</li>
     *       <li>after the protocol and before an optional {@code #} fragment, which is not checked, every
     *           {@code %} is followed by two hexadecimal digits;</li>
     *       <li>the host list, the text after the protocol up to the first {@code /}, {@code ?} or {@code #}, is
     *           not empty, its {@code [ ]} and {@code ( )} pairs are balanced, and each of its entries, split at the
     *           commas outside brackets and parentheses and trimmed, is not empty;</li>
     *       <li>an entry is {@code [<user info>@]<host>}: the user info, which is not checked, ends at the first
     *           {@code @} of the entry, that {@code @} lies outside {@code [ ]} and {@code ( )}, and the host
     *           holds no further {@code @} outside them;</li>
     *       <li>a host that starts with {@code [} holds either an IPv6 address that {@link URI} accepts, with an
     *           optional {@code %<zone>}, such as {@code [::1]} or {@code [fe80::1%25eth0]}, followed by nothing or
     *           by {@code :<port>}, or a non-blank host sub-list such as {@code [h1:3306,h2:3307]} followed by
     *           nothing; each sub-list entry, split and trimmed like the host list, is not empty, has no user info
     *           and no nested sub-list, and is checked like a host; after user info, an IPv6 address without a
     *           port holds only hexadecimal digits and {@code :};</li>
     *       <li>a host that starts with {@value #ADDRESS_PREFIX}, in any case, continues with one or more
     *           {@code (<key>=<value>)} groups, optionally separated by whitespace, and nothing else, such as
     *           {@code address=(host=h1)(port=3306)};</li>
     *       <li>a host that starts with {@code (} ends with {@code )} and holds one or more comma-separated
     *           {@code <key>=<value>} pairs, such as {@code (host=h1,port=3306)};</li>
     *       <li>the key of a group or pair, and the property name of a query piece, is a non-empty name of ASCII
     *           letters, digits, {@code _}, {@code .}, {@code -} and {@code %} escapes, with optional surrounding
     *           whitespace;</li>
     *       <li>any other host is {@code <host>[:<port>]}: the host name before the first {@code :} is not empty
     *           and holds none of {@code [ ] ( ) =};</li>
     *       <li>a port, after {@code :} or as the value of a {@value #PORT_KEY} key or query property in any
     *           case, is percent-decoded as UTF-8 with {@code +} kept literally, and is then a decimal number from
     *           1 to {@value #MAX_PORT} of the digits {@code 0}-{@code 9} only, so {@code %33%33%30%36} is port
     *           3306;</li>
     *       <li>a non-empty database name follows the host list after {@code /}, up to {@code ?} or {@code #},
     *           and does not start with {@code /} after optional whitespace;</li>
     *       <li>the query, the text after {@code ?} up to {@code #}, does not start with {@code ?} after optional
     *           whitespace, and each of its non-empty {@code &}-separated pieces is {@code <property>=<value>};
     *           any value other than a port may be empty.</li>
     *     </ul>
     *   </li>
     *   <li>An H2 URL is read as {@code jdbc:h2:<name>[;<setting>=<value>]...}: the name before the first
     *       {@code ;} is not empty; the rest is split at each {@code ;} not escaped by a backslash, a backslash
     *       standing for the character after it, so {@code INIT=CREATE SCHEMA a\;SET SCHEMA a} is one setting;
     *       each non-empty setting is {@code <setting>=<value>} with a non-empty setting name, and a setting name
     *       repeated in any case holds the same value each time.</li>
     * </ol>
     *
     * <p>The checks are structural only: the method resolves no host, loads no driver and opens no connection, and
     * it checks no property or setting value other than a port.
     *
     * <pre>{@code
     * requireSupportedUrl("jdbc:mysql://localhost:3306/company");  // "jdbc:mysql://localhost:3306/company"
     * requireSupportedUrl("jdbc:mysql://localhost:99999/company"); // IllegalArgumentException:
     *     // jdbc.url host entry 1 has a port that is not a number from 1 to 65535
     * }</pre>
     *
     * @param url the value of {@value #URL_KEY}, {@code null} when the key is not set
     * @return {@code url}, unchanged
     * @throws IllegalArgumentException when a check fails; the message starts with {@value #URL_KEY}, states the
     *         failed check and contains no part of {@code url}
     */
    static String requireSupportedUrl(String url) {
        if (url == null) {
            throw invalid("is not set; supply a JDBC URL of the form " + EXPECTED_FORM);
        }
        if (url.isBlank()) {
            throw invalid("is blank; supply a JDBC URL of the form " + EXPECTED_FORM);
        }
        if (url.indexOf(':') < 0) {
            throw invalid("holds a placeholder, not a JDBC URL; supply a JDBC URL of the form " + EXPECTED_FORM);
        }
        if (!url.equals(url.strip())) {
            throw invalid("has leading or trailing whitespace");
        }
        if (url.startsWith(MYSQL_PREFIX)) {
            requireMySqlStructure(url.substring(MYSQL_PREFIX.length()));
        } else if (url.startsWith(H2_PREFIX)) {
            requireH2Structure(url.substring(H2_PREFIX.length()));
        } else {
            throw invalid("must start with " + MYSQL_PREFIX + " or " + H2_PREFIX);
        }
        return url;
    }

    /**
     * Checks the part of a MySQL URL after {@value #MYSQL_PREFIX}: protocol, percent escapes, host list, database
     * name and query, in that order.
     *
     * @param afterPrefix the URL without its {@value #MYSQL_PREFIX} prefix
     * @throws IllegalArgumentException when a check fails
     */
    private static void requireMySqlStructure(String afterPrefix) {
        String protocol = MYSQL_PROTOCOLS.stream()
                .filter(afterPrefix::startsWith)
                .findFirst()
                .orElseThrow(() -> invalid("names no supported MySQL protocol; the MySQL prefix must be followed by"
                        + " //, loadbalance:// or replication://"));
        String afterProtocol = afterPrefix.substring(protocol.length());
        int fragmentStart = afterProtocol.indexOf('#');
        String checked = fragmentStart < 0 ? afterProtocol : afterProtocol.substring(0, fragmentStart);
        requirePercentEscapes(checked);

        int hostListEnd = checked.length();
        for (int i = 0; i < checked.length(); i++) {
            char c = checked.charAt(i);
            if (c == '/' || c == '?') {
                hostListEnd = i;
                break;
            }
        }
        String hostList = checked.substring(0, hostListEnd);
        if (hostList.isEmpty()) {
            throw invalid("names no host");
        }
        List<String> entries = splitHostList(hostList);
        for (int i = 0; i < entries.size(); i++) {
            requireHostEntry(entries.get(i), i + 1);
        }

        String afterHostList = checked.substring(hostListEnd);
        int queryStart = afterHostList.indexOf('?');
        String path = queryStart < 0 ? afterHostList : afterHostList.substring(0, queryStart);
        if (path.length() <= 1) {
            throw invalid("names no database");
        }
        if (startsWithAfterWhitespace(path, 1, '/')) {
            throw invalid("database name starts with /");
        }
        if (queryStart >= 0) {
            requireQuery(afterHostList.substring(queryStart + 1));
        }
    }

    /**
     * Checks that every {@code %} in {@code text} is followed by two hexadecimal digits.
     *
     * @param text the MySQL URL text after the protocol and before the fragment
     * @throws IllegalArgumentException when a {@code %} is not followed by two hexadecimal digits
     */
    private static void requirePercentEscapes(String text) {
        for (int i = text.indexOf('%'); i >= 0; i = text.indexOf('%', i + 3)) {
            if (i + 2 >= text.length() || !isHexDigit(text.charAt(i + 1)) || !isHexDigit(text.charAt(i + 2))) {
                throw invalid("has a % that is not followed by two hexadecimal digits");
            }
        }
    }

    /**
     * Splits a MySQL host list at the commas outside {@code [ ]} and {@code ( )}.
     *
     * @param hostList the non-empty host list
     * @return the entries in URL order, empty entries included
     * @throws IllegalArgumentException when the brackets or parentheses are unbalanced
     */
    private static List<String> splitHostList(String hostList) {
        List<String> entries = new ArrayList<>();
        Deque<Character> open = new ArrayDeque<>();
        int entryStart = 0;
        for (int i = 0; i < hostList.length(); i++) {
            char c = hostList.charAt(i);
            if (c == '[' || c == '(') {
                open.push(c);
            } else if (c == ']' || c == ')') {
                char expected = c == ']' ? '[' : '(';
                if (open.isEmpty() || open.pop() != expected) {
                    throw unbalancedHostList();
                }
            } else if (c == ',' && open.isEmpty()) {
                entries.add(hostList.substring(entryStart, i));
                entryStart = i + 1;
            }
        }
        if (!open.isEmpty()) {
            throw unbalancedHostList();
        }
        entries.add(hostList.substring(entryStart));
        return entries;
    }

    /**
     * Checks one entry of a balanced MySQL host list, {@code [<user info>@]<host>}.
     *
     * <p>The entry is trimmed with {@link String#trim()}. Its user info is the text before its first {@code @},
     * which lies outside {@code [ ]} and {@code ( )}; the user info itself is not checked. The host after it holds
     * no further {@code @} outside {@code [ ]} and {@code ( )} and is checked by {@code requireHost}.
     *
     * @param entry  the entry, balanced in brackets and parentheses
     * @param number the 1-based position of the entry in the host list
     * @throws IllegalArgumentException when the entry is empty, its first {@code @} lies inside {@code [ ]} or
     *         {@code ( )}, its host has another {@code @} outside them, or its host fails a check
     */
    private static void requireHostEntry(String entry, int number) {
        String trimmed = entry.trim();
        if (trimmed.isEmpty()) {
            throw invalid("host entry " + number + " is empty");
        }
        int userInfoEnd = trimmed.indexOf('@');
        if (userInfoEnd < 0) {
            requireHost(trimmed, number, false, false);
            return;
        }
        if (userInfoEnd != indexOutsideBrackets(trimmed, '@')) {
            throw invalid("host entry " + number + " has an @ inside brackets before any @ outside them");
        }
        String host = trimmed.substring(userInfoEnd + 1);
        if (indexOutsideBrackets(host, '@') >= 0) {
            throw invalid("host entry " + number + " has more than one @ outside brackets");
        }
        requireHost(host.trim(), number, true, false);
    }

    /**
     * Checks the host of a MySQL host entry or of an entry of a host sub-list, by the form its first characters
     * name.
     *
     * <ul>
     *   <li>{@code [} starts an IPv6 address or a host sub-list, checked by {@code requireBracketedHost};</li>
     *   <li>{@value #ADDRESS_PREFIX}, in any case, starts {@code (<key>=<value>)} groups, checked by
     *       {@code requireAddressHost};</li>
     *   <li>{@code (} starts {@code <key>=<value>} pairs, checked by {@code requireKeyValueHost};</li>
     *   <li>any other text is {@code <host>[:<port>]}, checked by {@code requireGenericHost}.</li>
     * </ul>
     *
     * @param host        the trimmed host, balanced in brackets and parentheses
     * @param number      the 1-based position of the host list entry that holds the host
     * @param hasUserInfo whether user info precedes the host
     * @param inSubList   whether the host is an entry of a host sub-list
     * @throws IllegalArgumentException when the host fails the check of its form
     */
    private static void requireHost(String host, int number, boolean hasUserInfo, boolean inSubList) {
        if (host.startsWith("[")) {
            requireBracketedHost(host, number, hasUserInfo, inSubList);
        } else if (host.regionMatches(true, 0, ADDRESS_PREFIX, 0, ADDRESS_PREFIX.length())) {
            requireAddressHost(host.substring(ADDRESS_PREFIX.length()), number);
        } else if (host.startsWith("(")) {
            requireKeyValueHost(host, number);
        } else {
            requireGenericHost(host, number);
        }
    }

    /**
     * Checks a host that starts with {@code [}: an IPv6 address {@code [<address>[%<zone>]][:<port>]} or a host
     * sub-list {@code [<entry>,...]}.
     *
     * <p>The text up to the matching {@code ]} is an IPv6 address when {@code isIpv6Address} accepts it. An IPv6
     * address is followed by nothing or by {@code :<port>}; preceded by user info and followed by nothing, it holds
     * only hexadecimal digits and {@code :}. Any other text is a host sub-list: it is outside any other host
     * sub-list, nothing follows its {@code ]}, it is not blank, and each of its entries, split at the commas
     * outside {@code [ ]} and {@code ( )} and trimmed, passes {@code requireSubListEntry}.
     *
     * @param host        the trimmed host, starting with {@code [} and balanced in brackets and parentheses
     * @param number      the 1-based position of the host list entry that holds the host
     * @param hasUserInfo whether user info precedes the host
     * @param inSubList   whether the host is an entry of a host sub-list
     * @throws IllegalArgumentException when the host fails a check
     */
    private static void requireBracketedHost(String host, int number, boolean hasUserInfo, boolean inSubList) {
        int closing = closingIndex(host);
        String inside = host.substring(1, closing);
        String afterBracket = host.substring(closing + 1);
        if (isIpv6Address(inside)) {
            if (afterBracket.isEmpty()) {
                if (hasUserInfo && !isHexDigitsAndColons(inside)) {
                    throw invalid("host entry " + number
                            + " has user info before a dotted or zoned IPv6 address without a port");
                }
                return;
            }
            if (afterBracket.charAt(0) != ':') {
                throw invalid("host entry " + number + " continues after ] with text other than :<port>");
            }
            requirePort(afterBracket.substring(1), "host entry " + number);
            return;
        }
        if (inSubList) {
            throw invalid("host entry " + number + " has a host sub-list inside a host sub-list");
        }
        if (!afterBracket.isEmpty()) {
            throw invalid("host entry " + number + " continues after the ] of a host sub-list");
        }
        if (inside.trim().isEmpty()) {
            throw invalid("host entry " + number + " has an empty host sub-list");
        }
        for (String subEntry : splitHostList(inside)) {
            requireSubListEntry(subEntry.trim(), number);
        }
    }

    /**
     * Checks one trimmed entry of a host sub-list: it is not empty, holds no {@code @} outside {@code [ ]} and
     * {@code ( )}, and passes {@code requireHost}.
     *
     * @param entry  the trimmed entry, balanced in brackets and parentheses
     * @param number the 1-based position of the host list entry that holds the host sub-list
     * @throws IllegalArgumentException when the entry is empty, has user info or fails the check of its form
     */
    private static void requireSubListEntry(String entry, int number) {
        if (entry.isEmpty()) {
            throw invalid("host entry " + number + " has an empty entry in its host sub-list");
        }
        if (indexOutsideBrackets(entry, '@') >= 0) {
            throw invalid("host entry " + number + " has user info inside its host sub-list");
        }
        requireHost(entry, number, false, true);
    }

    /**
     * Checks the text after {@value #ADDRESS_PREFIX}: trimmed with {@link String#trim()}, it is one or more
     * {@code (<key>=<value>)} groups, optionally separated by whitespace, with nothing before, between or after
     * them. The text of each group, between {@code (} and the next {@code )}, holds no {@code (} and passes
     * {@code requireKeyValuePair}.
     *
     * @param groups the text after {@value #ADDRESS_PREFIX}, balanced in brackets and parentheses
     * @param number the 1-based position of the host list entry that holds the host
     * @throws IllegalArgumentException when there is no group, text lies outside the groups, or a group fails a
     *         check
     */
    private static void requireAddressHost(String groups, int number) {
        String trimmed = groups.trim();
        if (trimmed.isEmpty()) {
            throw invalid("host entry " + number + " has no key-value pair");
        }
        int position = 0;
        while (position < trimmed.length()) {
            int closing = trimmed.indexOf(')', position);
            if (trimmed.charAt(position) != '(' || closing < 0) {
                throw invalid("host entry " + number + " has text outside the (<key>=<value>) groups of "
                        + ADDRESS_PREFIX);
            }
            String pair = trimmed.substring(position + 1, closing);
            if (pair.indexOf('(') >= 0) {
                throw keyValuePairMismatch(number);
            }
            requireKeyValuePair(pair, number);
            position = skipWhitespace(trimmed, closing + 1);
        }
    }

    /**
     * Checks a host that starts with {@code (}: it ends with {@code )}, and the text between them, split at every
     * comma, holds at least one non-blank pair; each non-blank pair passes {@code requireKeyValuePair}.
     *
     * @param host   the trimmed host, starting with {@code (} and balanced in brackets and parentheses
     * @param number the 1-based position of the host list entry that holds the host
     * @throws IllegalArgumentException when the host does not end with {@code )}, holds no pair or a pair fails a
     *         check
     */
    private static void requireKeyValueHost(String host, int number) {
        if (!host.endsWith(")")) {
            throw invalid("host entry " + number + " continues after the ) of its key-value pairs");
        }
        int pairs = 0;
        for (String pair : host.substring(1, host.length() - 1).split(",", -1)) {
            if (skipWhitespace(pair, 0) == pair.length()) {
                continue;
            }
            pairs++;
            requireKeyValuePair(pair, number);
        }
        if (pairs == 0) {
            throw invalid("host entry " + number + " has no key-value pair");
        }
    }

    /**
     * Checks one {@code <key>=<value>} pair of a host entry: it holds a {@code =}, the text before it passes
     * {@code isKeyName}, and the value of a {@value #PORT_KEY} key passes {@code requirePort}.
     *
     * @param pair   the text of the pair
     * @param number the 1-based position of the host list entry that holds the pair
     * @throws IllegalArgumentException when the pair has no {@code =}, an invalid key or an invalid port
     */
    private static void requireKeyValuePair(String pair, int number) {
        int equals = pair.indexOf('=');
        if (equals < 0 || !isKeyName(pair.substring(0, equals))) {
            throw keyValuePairMismatch(number);
        }
        if (isPortKey(pair.substring(0, equals))) {
            requirePort(pair.substring(equals + 1), "host entry " + number);
        }
    }

    /**
     * Checks a {@code <host>[:<port>]} host: the host name before the first {@code :} is not empty and holds none
     * of {@code [ ] ( ) =}, and the port after that {@code :} passes {@code requirePort}.
     *
     * @param host   the trimmed host
     * @param number the 1-based position of the host list entry that holds the host
     * @throws IllegalArgumentException when the host name is empty or holds an excluded character, or the port is
     *         invalid
     */
    private static void requireGenericHost(String host, int number) {
        int portSeparator = host.indexOf(':');
        String name = portSeparator < 0 ? host : host.substring(0, portSeparator);
        if (name.isEmpty()) {
            throw invalid("host entry " + number + " names no host");
        }
        for (int i = 0; i < name.length(); i++) {
            if (HOST_NAME_EXCLUDED.indexOf(name.charAt(i)) >= 0) {
                throw invalid("host entry " + number + " has a [, ], (, ) or = in its host name");
            }
        }
        if (portSeparator >= 0) {
            requirePort(host.substring(portSeparator + 1), "host entry " + number);
        }
    }

    /**
     * Returns whether {@code address} followed by an optional {@code %<zone>} is an IPv6 address that
     * {@link URI} accepts as the bracketed host {@code [<address>]} of a server-based authority.
     *
     * @param address the text between {@code [} and its matching {@code ]}
     * @return {@code true} for an IPv6 address, such as {@code ::1}, {@code ::ffff:1.2.3.4} or
     *         {@code fe80::1%25eth0}
     */
    private static boolean isIpv6Address(String address) {
        try {
            return new URI("mysql://[" + address + "]").getHost() != null;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    /**
     * Returns whether {@code text} is not empty and holds only ASCII hexadecimal digits and {@code :}.
     *
     * @param text the text between {@code [} and {@code ]}
     * @return {@code true} for an IPv6 address without a dotted part or zone
     */
    private static boolean isHexDigitsAndColons(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != ':' && !isHexDigit(text.charAt(i))) {
                return false;
            }
        }
        return !text.isEmpty();
    }

    /**
     * Returns whether {@code text}, without surrounding whitespace, is a non-empty property or key name of ASCII
     * letters, digits and the characters of {@value #KEY_NAME_PUNCTUATION}.
     *
     * @param text the text before the {@code =} of a key-value pair or query parameter
     * @return {@code true} for a valid name
     */
    private static boolean isKeyName(String text) {
        int start = skipWhitespace(text, 0);
        int end = text.length();
        while (end > start && isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        if (start == end) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            boolean letterOrDigit = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!letterOrDigit && KEY_NAME_PUNCTUATION.indexOf(c) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a valid key name, trimmed and percent-decoded, is {@value #PORT_KEY} in any case.
     *
     * @param keyName a text that passes {@code isKeyName}
     * @return {@code true} for a port key
     */
    private static boolean isPortKey(String keyName) {
        return decodePercentEscapes(keyName.trim()).equalsIgnoreCase(PORT_KEY);
    }

    /**
     * Returns the index of the first {@code c} in {@code text} outside {@code [ ]} and {@code ( )}.
     *
     * @param text the text, balanced in brackets and parentheses
     * @param c    the character to find
     * @return the index, or {@code -1} when {@code c} occurs only inside brackets or parentheses or not at all
     */
    private static int indexOutsideBrackets(String text, char c) {
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (current == '[' || current == '(') {
                depth++;
            } else if (current == ']' || current == ')') {
                depth--;
            } else if (current == c && depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the index of the {@code ]} that closes the {@code [} at the start of a balanced host entry.
     *
     * @param entry a balanced host entry that starts with {@code [}
     * @return the index of the closing {@code ]}
     * @throws IllegalArgumentException when the opening {@code [} is not closed
     */
    private static int closingIndex(String entry) {
        int depth = 0;
        for (int i = 0; i < entry.length(); i++) {
            char c = entry.charAt(i);
            if (c == '[' || c == '(') {
                depth++;
            } else if ((c == ']' || c == ')') && --depth == 0) {
                return i;
            }
        }
        throw unbalancedHostList();
    }

    /**
     * Checks that {@code port}, percent-decoded by {@code decodePercentEscapes}, is a decimal number from 1 to
     * {@value #MAX_PORT} of the digits {@code 0}-{@code 9} only.
     *
     * @param port    the text after the {@code :} of a host, or the value of a {@value #PORT_KEY} key
     * @param subject the part of the URL that holds the port, such as {@code host entry 1}, named by the message
     * @throws IllegalArgumentException when the decoded port is empty, holds a character other than
     *         {@code 0}-{@code 9}, such as a sign or whitespace, or is outside the range
     */
    private static void requirePort(String port, String subject) {
        String decoded = decodePercentEscapes(port);
        int value = 0;
        for (int i = 0; i < decoded.length() && value <= MAX_PORT; i++) {
            char c = decoded.charAt(i);
            value = c >= '0' && c <= '9' ? value * 10 + (c - '0') : MAX_PORT + 1;
        }
        if (value < 1 || value > MAX_PORT) {
            throw invalid(subject + " has a port that is not a number from 1 to " + MAX_PORT);
        }
    }

    /**
     * Checks a MySQL query: it does not start with {@code ?} after optional whitespace, and each non-empty
     * {@code &}-separated piece is {@code <property>=<value>} whose property name passes {@code isKeyName}; the
     * value of a {@value #PORT_KEY} property passes {@code requirePort}, and any other value may be empty.
     *
     * @param query the text after {@code ?} and before the fragment
     * @throws IllegalArgumentException when the query starts with {@code ?}, a piece has no {@code =} or an invalid
     *         property name, or a {@value #PORT_KEY} value is invalid
     */
    private static void requireQuery(String query) {
        if (startsWithAfterWhitespace(query, 0, '?')) {
            throw invalid("query starts with ?");
        }
        int number = 0;
        for (String piece : query.split("&", -1)) {
            if (piece.isEmpty()) {
                continue;
            }
            number++;
            int equals = piece.indexOf('=');
            if (equals < 0 || !isKeyName(piece.substring(0, equals))) {
                throw invalid("query parameter " + number + " is not of the form <property>=<value>");
            }
            if (isPortKey(piece.substring(0, equals))) {
                requirePort(piece.substring(equals + 1), "query parameter " + number);
            }
        }
    }

    /**
     * Checks the part of an H2 URL after {@value #H2_PREFIX}, read as H2 reads it: the database name is the text
     * before the first {@code ;}, without escape processing, and is not empty; the text after that {@code ;} is
     * split into settings by {@code splitH2Settings}. Each non-empty setting is {@code <setting>=<value>} with a
     * non-empty setting name, and a setting name repeated in any case holds the same value each time. Setting
     * names and values are not checked further.
     *
     * @param afterPrefix the URL without its {@value #H2_PREFIX} prefix
     * @throws IllegalArgumentException when the name is empty, a non-empty setting has no {@code =} or an empty
     *         setting name, or a repeated setting name holds another value
     */
    private static void requireH2Structure(String afterPrefix) {
        int settingsStart = afterPrefix.indexOf(';');
        String name = settingsStart < 0 ? afterPrefix : afterPrefix.substring(0, settingsStart);
        if (name.isEmpty()) {
            throw invalid("names no H2 database");
        }
        if (settingsStart < 0) {
            return;
        }
        Map<String, String> values = new HashMap<>();
        int number = 0;
        for (String setting : splitH2Settings(afterPrefix.substring(settingsStart + 1))) {
            if (setting.isEmpty()) {
                continue;
            }
            number++;
            int equals = setting.indexOf('=');
            if (equals < 1) {
                throw invalid("H2 setting " + number + " is not of the form <setting>=<value>");
            }
            String value = setting.substring(equals + 1);
            String earlier = values.putIfAbsent(setting.substring(0, equals).toUpperCase(Locale.ENGLISH), value);
            if (earlier != null && !earlier.equals(value)) {
                throw invalid("H2 setting " + number + " repeats an earlier setting name with another value");
            }
        }
    }

    /**
     * Splits the settings of an H2 URL at each {@code ;} not escaped by a backslash: a backslash followed by any
     * character stands for that character, so {@code \;} is a literal {@code ;} and {@code \\} a literal
     * backslash, and a backslash at the end of the text is kept.
     *
     * @param settings the text after the first {@code ;} of the URL
     * @return the unescaped settings in URL order, empty settings included
     */
    private static List<String> splitH2Settings(String settings) {
        List<String> split = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < settings.length(); i++) {
            char c = settings.charAt(i);
            if (c == ';') {
                split.add(current.toString());
                current.setLength(0);
            } else if (c == '\\' && i < settings.length() - 1) {
                current.append(settings.charAt(++i));
            } else {
                current.append(c);
            }
        }
        split.add(current.toString());
        return split;
    }

    /**
     * Returns {@code text} percent-decoded as UTF-8 by {@link URLDecoder}, with each {@code +} kept as a literal
     * {@code +}.
     *
     * @param text a part of a MySQL URL whose {@code %} escapes {@code requirePercentEscapes} has accepted
     * @return the decoded text
     */
    private static String decodePercentEscapes(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    /**
     * Returns whether the first character of {@code text} at or after {@code from} that is not whitespace is
     * {@code c}.
     *
     * @param text the text
     * @param from the index to start at
     * @param c    the character to look for
     * @return {@code true} when that character exists and is {@code c}
     */
    private static boolean startsWithAfterWhitespace(String text, int from, char c) {
        int position = skipWhitespace(text, from);
        return position < text.length() && text.charAt(position) == c;
    }

    /**
     * Returns the index of the first character of {@code text} at or after {@code from} that is not whitespace.
     *
     * @param text the text
     * @param from the index to start at
     * @return that index, or {@code text.length()} when only whitespace follows
     */
    private static int skipWhitespace(String text, int from) {
        int position = from;
        while (position < text.length() && isWhitespace(text.charAt(position))) {
            position++;
        }
        return position;
    }

    /**
     * Returns whether {@code c} is whitespace in the sense of the regular-expression class {@code \s}: space, tab,
     * line feed, vertical tab, form feed or carriage return.
     *
     * @param c the character
     * @return {@code true} for one of those six characters
     */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
    }

    /**
     * Returns whether {@code c} is an ASCII hexadecimal digit.
     *
     * @param c the character
     * @return {@code true} for {@code 0}-{@code 9}, {@code a}-{@code f} and {@code A}-{@code F}
     */
    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /**
     * Returns the exception for an unbalanced MySQL host list.
     *
     * @return an {@link IllegalArgumentException} naming {@value #URL_KEY}
     */
    private static IllegalArgumentException unbalancedHostList() {
        return invalid("host list has an unbalanced [ ] or ( ) pair");
    }

    /**
     * Returns the exception for a key-value pair of a host entry that is not {@code <key>=<value>} with a valid key.
     *
     * @param number the 1-based position of the host list entry that holds the pair
     * @return an {@link IllegalArgumentException} naming {@value #URL_KEY}
     */
    private static IllegalArgumentException keyValuePairMismatch(int number) {
        return invalid("host entry " + number + " has a key-value pair that is not of the form <key>=<value>");
    }

    /**
     * Returns an {@link IllegalArgumentException} whose message is {@value #URL_KEY} followed by {@code detail}.
     *
     * @param detail the failed check, without any part of the URL
     * @return the exception
     */
    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(URL_KEY + " " + detail);
    }

    /**
     * Named-parameter JDBC template over {@code MySQL_Configuration}; it runs the {@code orders} insert of
     * {@code transactionsFlow1} [transactions.xml:13-16] (D-063).
     *
     * @param dataSource the {@code MySQL_Configuration} data source
     * @return a template bound to {@code dataSource}
     */
    @Bean
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Local JDBC transaction manager used by {@code @Transactional} (D-025).
     *
     * <p>It is the only transaction manager in the context. The JMS listener container does not use it: the
     * listener session is locally transacted, and the JDBC transaction begins and ends inside it, as
     * {@code ee:multi-transactional action="ALWAYS_BEGIN"} does [transactions.xml:12-21].
     *
     * @param dataSource the {@code MySQL_Configuration} data source
     * @return a transaction manager bound to {@code dataSource}
     */
    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * Binds the key {@code jdbc.url}, the {@code ${jdbc.url}} placeholder of {@code MySQL_Configuration}
     * [transactions.xml:4]; its value comes from {@code application.yml} or an override of the same key.
     *
     * @param url the JDBC connection string
     */
    @ConfigurationProperties("jdbc")
    public record JdbcProperties(String url) {
    }
}
