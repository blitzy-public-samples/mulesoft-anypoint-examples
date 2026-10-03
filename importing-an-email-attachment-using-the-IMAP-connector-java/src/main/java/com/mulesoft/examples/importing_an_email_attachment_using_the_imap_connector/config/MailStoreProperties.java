package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IMAPS store settings, bound by constructor binding from the {@code imap.*} keys of
 * {@code application.yml}.
 *
 * <p>The record reproduces the {@code checkFrequency} attribute of {@code imaps:connector}
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:5] and the
 * {@code host}, {@code port}, {@code user}, {@code password} and {@code responseTimeout} attributes of
 * {@code imaps:inbound-endpoint} [imap-to-xml.xml:10]. The committed {@code application.yml} gives
 * {@code imap.host}, {@code imap.user} and {@code imap.password} placeholder values that the user
 * replaces (D-012). The Jakarta Mail store opened by {@code scheduler.ImapAttachmentPoller} reads
 * these values (D-063).
 *
 * <p>The record declares no defaults: every value comes from the bound keys, a missing {@code String}
 * key binds {@code null} and a missing number key binds {@code 0}. Instances are immutable and every
 * method is safe to call from any thread. The generated {@code toString()} prints every component,
 * {@code password} included (D-287).
 *
 * <pre>{@code
 * Session session = Session.getInstance(props.sessionProperties());
 * Store store = session.getStore("imaps");
 * store.connect(props.host(), props.port(), props.decodedUser(), props.password());
 * }</pre>
 *
 * @param host            {@code imap.host}: the IMAPS server host name
 * @param port            {@code imap.port}: the IMAPS server port, {@code 993} in the original
 * @param user            {@code imap.user}: the mailbox user, plain or percent-encoded, for example
 *                        {@code receiveremailaddress%40gmail.com}; {@link #decodedUser()} gives the
 *                        login name
 * @param password        {@code imap.password}: the mailbox password
 * @param checkFrequency  {@code imap.check-frequency}: the delay between two polls in milliseconds,
 *                        {@code 100} in the original
 * @param responseTimeout {@code imap.response-timeout}: the IMAPS connect and read timeout in
 *                        milliseconds, {@code 10000} in the original
 */
@ConfigurationProperties(prefix = "imap")
public record MailStoreProperties(String host, int port, String user, String password,
                                  long checkFrequency, int responseTimeout) {

    /** Jakarta Mail session key naming the default store protocol. */
    private static final String STORE_PROTOCOL_KEY = "mail.store.protocol";

    /** Store protocol of the {@code imaps:inbound-endpoint}. */
    private static final String IMAPS_PROTOCOL = "imaps";

    /** Jakarta Mail session key of the IMAPS server host name. */
    private static final String HOST_KEY = "mail.imaps.host";

    /** Jakarta Mail session key of the IMAPS server port. */
    private static final String PORT_KEY = "mail.imaps.port";

    /** Jakarta Mail session key of the IMAPS socket connect timeout in milliseconds. */
    private static final String CONNECTION_TIMEOUT_KEY = "mail.imaps.connectiontimeout";

    /** Jakarta Mail session key of the IMAPS socket read timeout in milliseconds. */
    private static final String READ_TIMEOUT_KEY = "mail.imaps.timeout";

    /** Percent-escape of a literal {@code +}, substituted before decoding. */
    private static final String ENCODED_PLUS = "%2B";

    /**
     * Returns the user with percent-escapes decoded; a literal {@code +} is kept; {@code null} when
     * unset (D-285).
     *
     * <p>Each {@code %XX} sequence is decoded as UTF-8. No other change is made: the value is neither
     * trimmed nor lower-cased.
     *
     * <pre>{@code
     * receiver%40example.com  ->  receiver@example.com
     * receiver@example.com    ->  receiver@example.com
     * a+b@x.com               ->  a+b@x.com
     * }</pre>
     *
     * @return the decoded user, or {@code null} when {@code imap.user} is unset
     * @throws IllegalArgumentException when {@code user} holds a malformed percent-escape, such as a
     *                                  {@code %} not followed by two hexadecimal digits
     */
    public String decodedUser() {
        if (user == null) {
            return null;
        }
        return URLDecoder.decode(user.replace("+", ENCODED_PLUS), StandardCharsets.UTF_8);
    }

    /**
     * Returns the system properties overlaid with the IMAPS protocol, host, port and connect/read
     * timeouts (D-286).
     *
     * <p>The result is a new {@link Properties} instance on every call. It first receives a copy of
     * every entry of {@link System#getProperties()}, which is read and never modified, and then these
     * entries, each replacing a copied entry of the same key:
     * <ul>
     *   <li>{@code mail.store.protocol} = {@code imaps};</li>
     *   <li>{@code mail.imaps.host} = {@link #host()}, left unset when {@code host} is {@code null};</li>
     *   <li>{@code mail.imaps.port} = {@link #port()};</li>
     *   <li>{@code mail.imaps.connectiontimeout} = {@link #responseTimeout()};</li>
     *   <li>{@code mail.imaps.timeout} = {@link #responseTimeout()}.</li>
     * </ul>
     * Numbers are written as decimal strings, for example {@code "993"} and {@code "10000"}.
     *
     * @return the Jakarta Mail session properties of the IMAPS store
     */
    public Properties sessionProperties() {
        Properties properties = new Properties();
        properties.putAll(System.getProperties());
        properties.setProperty(STORE_PROTOCOL_KEY, IMAPS_PROTOCOL);
        if (host != null) {
            properties.setProperty(HOST_KEY, host);
        }
        properties.setProperty(PORT_KEY, String.valueOf(port));
        String timeout = String.valueOf(responseTimeout);
        properties.setProperty(CONNECTION_TIMEOUT_KEY, timeout);
        properties.setProperty(READ_TIMEOUT_KEY, timeout);
        return properties;
    }
}
