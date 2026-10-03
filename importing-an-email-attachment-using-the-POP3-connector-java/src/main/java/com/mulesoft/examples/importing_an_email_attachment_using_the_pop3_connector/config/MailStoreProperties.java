package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * POP3S mailbox settings, bound by constructor binding from the {@code pop3.*} keys of
 * {@code application.yml} (see DECISIONS.md D-318).
 *
 * <p>The record carries the {@code checkFrequency} attribute of the {@code pop3s:connector}
 * [importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml:3] and the
 * {@code host}, {@code port}, {@code user} and {@code password} attributes of the
 * {@code pop3s:inbound-endpoint} of flow {@code pop-to-xmlFlow1} [pop-to-xml.xml:6]. Keys and the
 * values a component takes when its key is absent:
 * <ul>
 *   <li>{@code pop3.host} &rarr; {@link #host()}: no default, {@code null};</li>
 *   <li>{@code pop3.port} &rarr; {@link #port()}: {@code 995};</li>
 *   <li>{@code pop3.user} &rarr; {@link #user()}: no default, {@code null};</li>
 *   <li>{@code pop3.password} &rarr; {@link #password()}: no default, {@code null};</li>
 *   <li>{@code pop3.check-frequency} &rarr; {@link #checkFrequency()}: {@code 100} milliseconds;</li>
 *   <li>{@code pop3.session-properties} &rarr; {@link #sessionProperties()}: an empty map.</li>
 * </ul>
 * The committed {@code application.yml} gives {@code pop3.host}, {@code pop3.user} and
 * {@code pop3.password} placeholder values that the user replaces (see DECISIONS.md D-012).
 *
 * <p>{@link #loginUser()} and {@link #loginPassword()} return {@code user} and {@code password} with
 * their {@code %XX} percent-escapes decoded as UTF-8; every other character, {@code +} included, is
 * kept unchanged (see DECISIONS.md D-319).
 *
 * <p>Each entry of {@code pop3.session-properties} is one extra Jakarta Mail {@code Session} property
 * of the POP3S store session (see DECISIONS.md D-098). A key that contains dots is written in bracket
 * form, for example {@code pop3.session-properties[mail.pop3s.ssl.trust]=*} as a property, or
 * {@code "[mail.pop3s.ssl.trust]": "*"} under {@code pop3.session-properties} in YAML.
 *
 * <p>The endpoint's {@code responseTimeout="10000"} and the connector's
 * {@code validateConnections="true"} have no component and no key (see DECISIONS.md D-099).
 *
 * <p>Instances are immutable and every method is safe to call from any thread. The record keeps the
 * generated {@code equals}, {@code hashCode} and {@code toString}; {@code toString} prints every
 * component, {@code password} included (see DECISIONS.md D-318).
 *
 * @param host              {@code pop3.host}, bound from {@code host="${pop3.host}"}
 *                          [pop-to-xml.xml:6]: the POP3S server host name; {@code null} when the key
 *                          is absent
 * @param port              {@code pop3.port}, the original literal {@code port="995"}
 *                          [pop-to-xml.xml:6]: the POP3S server port; {@code 995} when the key is
 *                          absent
 * @param user              {@code pop3.user}, bound from {@code user="${pop3.user}"}
 *                          [pop-to-xml.xml:6]: the mailbox user as configured, plain or
 *                          percent-encoded, for example {@code receiveremailaddress%40gmail.com};
 *                          {@code null} when the key is absent; {@link #loginUser()} gives the decoded
 *                          form
 * @param password          {@code pop3.password}, bound from {@code password="${pop3.password}"}
 *                          [pop-to-xml.xml:6]: the mailbox password as configured; {@code null} when
 *                          the key is absent; {@link #loginPassword()} gives the decoded form
 * @param checkFrequency    {@code pop3.check-frequency}, the original literal
 *                          {@code checkFrequency="100"} of the {@code pop3s:connector}
 *                          [pop-to-xml.xml:3]: the delay between two mailbox polls in milliseconds;
 *                          {@code 100} when the key is absent
 * @param sessionProperties {@code pop3.session-properties}: extra Jakarta Mail {@code Session}
 *                          properties of the POP3S store session (see DECISIONS.md D-098); an empty
 *                          map when the key is absent; held as an unmodifiable map in the bound key
 *                          order that accepts {@code null} values
 */
@ConfigurationProperties(prefix = "pop3")
public record MailStoreProperties(
        String host,
        @DefaultValue("995") int port,
        String user,
        String password,
        @DefaultValue("100") long checkFrequency,
        @DefaultValue Map<String, String> sessionProperties) {

    /** Marker character that opens a percent-escape. */
    private static final char ESCAPE_MARKER = '%';

    /** Number of hexadecimal digits that follow {@link #ESCAPE_MARKER} in one escape. */
    private static final int ESCAPE_DIGITS = 2;

    /** Value {@link #hexValue(char)} returns for a character that is not a hexadecimal digit. */
    private static final int NOT_HEX = -1;

    /**
     * Creates the settings.
     *
     * <p>A {@code null} {@code sessionProperties} becomes an empty map. Any other map is copied into
     * a new {@link LinkedHashMap}, which keeps its iteration order and its {@code null} values, and is
     * stored as an unmodifiable view of that copy; later changes to the argument do not reach the
     * record. Every other component is stored as given, neither trimmed nor validated.
     *
     * @param host              the POP3S server host name, {@code pop3.host}; may be {@code null}
     * @param port              the POP3S server port, {@code pop3.port}
     * @param user              the mailbox user as configured, {@code pop3.user}; may be {@code null}
     * @param password          the mailbox password as configured, {@code pop3.password}; may be
     *                          {@code null}
     * @param checkFrequency    the delay between two mailbox polls in milliseconds,
     *                          {@code pop3.check-frequency}
     * @param sessionProperties the extra Jakarta Mail {@code Session} properties,
     *                          {@code pop3.session-properties}; {@code null} gives an empty map
     */
    public MailStoreProperties {
        Map<String, String> source = sessionProperties == null ? Map.of() : sessionProperties;
        sessionProperties = Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * Returns {@link #user()} with its percent-escapes decoded, or {@code null} when {@code user} is
     * {@code null} (see DECISIONS.md D-319).
     *
     * <p>Decoding rule, applied left to right:
     * <ul>
     *   <li>a {@code %} followed by two hexadecimal digits ({@code 0-9}, {@code a-f}, {@code A-F}) is
     *       one byte;</li>
     *   <li>each run of consecutive escape bytes is decoded as UTF-8, a malformed byte sequence giving
     *       U+FFFD;</li>
     *   <li>every other character is kept unchanged: {@code +}, non-ASCII characters and a {@code %}
     *       not followed by two hexadecimal digits included.</li>
     * </ul>
     * The value is neither trimmed nor case-converted.
     *
     * <pre>{@code
     * receiveremailaddress%40gmail.com  ->  receiveremailaddress@gmail.com
     * user@gmail.com                    ->  user@gmail.com
     * a+b                               ->  a+b
     * 100%   %4   %zz                   ->  unchanged
     * %c3%a9                            ->  U+00E9
     * %E2%82%AC                         ->  U+20AC
     * }</pre>
     *
     * @return the decoded mailbox user, or {@code null} when {@code pop3.user} is unset
     */
    public String loginUser() {
        return percentDecode(user);
    }

    /**
     * Returns {@link #password()} with its percent-escapes decoded, or {@code null} when
     * {@code password} is {@code null} (see DECISIONS.md D-319).
     *
     * <p>The decoding rule is the one of {@link #loginUser()}: each {@code %XX} escape with two
     * hexadecimal digits is one byte, each run of such bytes is decoded as UTF-8 (U+FFFD for a
     * malformed sequence), and every other character, {@code +} and an incomplete or non-hexadecimal
     * escape included, is kept unchanged.
     *
     * @return the decoded mailbox password, or {@code null} when {@code pop3.password} is unset
     */
    public String loginPassword() {
        return percentDecode(password);
    }

    /**
     * Decodes the percent-escapes of {@code value} by the rule documented on {@link #loginUser()}.
     *
     * @param value the text to decode; may be {@code null}
     * @return the decoded text, or {@code null} when {@code value} is {@code null}
     */
    private static String percentDecode(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        StringBuilder decoded = new StringBuilder(length);
        ByteArrayOutputStream escapedBytes = new ByteArrayOutputStream();
        int index = 0;
        while (index < length) {
            char current = value.charAt(index);
            if (current == ESCAPE_MARKER && index + ESCAPE_DIGITS < length) {
                int high = hexValue(value.charAt(index + 1));
                int low = hexValue(value.charAt(index + 2));
                if (high != NOT_HEX && low != NOT_HEX) {
                    escapedBytes.write((high << 4) | low);
                    index += 1 + ESCAPE_DIGITS;
                    continue;
                }
            }
            appendUtf8(escapedBytes, decoded);
            decoded.append(current);
            index++;
        }
        appendUtf8(escapedBytes, decoded);
        return decoded.toString();
    }

    /**
     * Appends the pending escape bytes to {@code target} decoded as UTF-8, a malformed sequence
     * giving U+FFFD, and empties {@code pending}. Does nothing when {@code pending} is empty.
     *
     * @param pending the escape bytes collected since the last non-escape character
     * @param target  the decoded text built so far
     */
    private static void appendUtf8(ByteArrayOutputStream pending, StringBuilder target) {
        if (pending.size() == 0) {
            return;
        }
        target.append(new String(pending.toByteArray(), StandardCharsets.UTF_8));
        pending.reset();
    }

    /**
     * Returns the value of an ASCII hexadecimal digit.
     *
     * @param digit the character to read
     * @return {@code 0} to {@code 15} for {@code 0-9}, {@code a-f} and {@code A-F}; {@link #NOT_HEX}
     *         for any other character
     */
    private static int hexValue(char digit) {
        if (digit >= '0' && digit <= '9') {
            return digit - '0';
        }
        if (digit >= 'a' && digit <= 'f') {
            return digit - 'a' + 10;
        }
        if (digit >= 'A' && digit <= 'F') {
            return digit - 'A' + 10;
        }
        return NOT_HEX;
    }
}
