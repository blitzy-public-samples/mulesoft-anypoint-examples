package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Unit tests for {@link SmtpConfig}, the Java form of the SMTP connector {@code Gmail}
 * [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:3] and of the {@code host},
 * {@code port}, {@code user}, {@code password} and {@code responseTimeout} attributes of the
 * {@code smtp:outbound-endpoint} of flow {@code csv-to-smtpFlow} [same file:20] (D-567, D-568).
 *
 * <p>The decode tests call the static {@link SmtpConfig#decode(String)} with the percent-encoded user
 * name form of the original README, {@code senderemailid%40gmail.com}
 * [sending-a-csv-file-through-email-using-smtp/README.md:27], and with plus signs, plain text,
 * {@code null} and a malformed escape. The sender tests call
 * {@link SmtpConfig#javaMailSender(SmtpProperties)} on a {@link SmtpConfig} created with {@code new} and
 * check the host, port, credentials, default encoding and JavaMail properties of the returned
 * {@link JavaMailSenderImpl}.
 *
 * <p>Each test runs without a Spring application context and with no mocks. No test sends a message,
 * tests a connection or opens a transport, and none opens a network connection (D-039). Every JavaMail
 * property is read as {@code String.valueOf(props.get(key))}.
 *
 * <p>The class and each test method are public; each is a backward row of this project's section of
 * {@code TRACEABILITY.md} (D-075).
 */
public class SmtpConfigTest {

    /** Host bound in the sender tests. */
    private static final String HOST = "smtp.example.com";

    /** Port bound in the sender tests, the endpoint's {@code port} of csv-to-smtp.xml:20. */
    private static final int PORT = 587;

    /** Percent-encoded user name bound in the sender tests. */
    private static final String ENCODED_USER = "u%40x.com";

    /** Percent-encoded password bound in the sender tests. */
    private static final String ENCODED_PASSWORD = "p%2Bw";

    /** Response timeout in milliseconds, the endpoint's {@code responseTimeout} of csv-to-smtp.xml:20. */
    private static final int RESPONSE_TIMEOUT = 10000;

    /** The three JavaMail timeout keys set from {@link SmtpProperties#responseTimeout()}. */
    private static final String[] TIMEOUT_KEYS = {
        "mail.smtp.connectiontimeout", "mail.smtp.timeout", "mail.smtp.writetimeout"
    };

    /** Asserts {@code %40} decodes to {@code @} in the README's user name form. */
    @Test
    public void decodesEncodedAtSign() {
        assertEquals("senderemailid@gmail.com", SmtpConfig.decode("senderemailid%40gmail.com"));
    }

    /** Asserts {@code %2B} decodes to {@code +}. */
    @Test
    public void decodesEncodedPlusSign() {
        assertEquals("p+w", SmtpConfig.decode("p%2Bw"));
    }

    /** Asserts a literal {@code +} stays a plus sign and does not become a space. */
    @Test
    public void keepsLiteralPlusSign() {
        assertEquals("a+b", SmtpConfig.decode("a+b"));
    }

    /** Asserts a value without {@code %} or {@code +} is returned unchanged. */
    @Test
    public void keepsPlainText() {
        assertEquals("plain-user", SmtpConfig.decode("plain-user"));
    }

    /** Asserts {@code null} decodes to {@code null}. */
    @Test
    public void returnsNullForNull() {
        assertNull(SmtpConfig.decode(null));
    }

    /** Asserts the malformed escape {@code %zz} raises {@link IllegalArgumentException}. */
    @Test
    public void rejectsMalformedEscape() {
        assertThrows(IllegalArgumentException.class, () -> SmtpConfig.decode("%zz"));
    }

    /**
     * Asserts the sender built from {@code smtp.example.com}, 587, {@code u%40x.com}, {@code p%2Bw} and
     * 10000 has host {@code smtp.example.com}, port 587, protocol {@code smtp}, user name {@code u@x.com},
     * password {@code p+w} and default encoding {@code UTF-8}, and exactly five JavaMail properties:
     * {@code mail.smtp.auth} and {@code mail.smtp.starttls.enable} each {@code "true"}, and
     * {@code mail.smtp.connectiontimeout}, {@code mail.smtp.timeout} and {@code mail.smtp.writetimeout}
     * each {@code "10000"}.
     */
    @Test
    public void buildsSenderFromSmtpProperties() {
        JavaMailSenderImpl sender = new SmtpConfig().javaMailSender(
                new SmtpProperties(HOST, PORT, ENCODED_USER, ENCODED_PASSWORD, RESPONSE_TIMEOUT));
        Properties props = sender.getJavaMailProperties();

        assertEquals(HOST, sender.getHost());
        assertEquals(PORT, sender.getPort());
        assertEquals("smtp", sender.getProtocol());
        assertEquals("u@x.com", sender.getUsername());
        assertEquals("p+w", sender.getPassword());
        assertEquals("UTF-8", sender.getDefaultEncoding());

        assertEquals(5, props.size());
        assertEquals("true", String.valueOf(props.get("mail.smtp.auth")));
        assertEquals("true", String.valueOf(props.get("mail.smtp.starttls.enable")));
        for (String key : TIMEOUT_KEYS) {
            assertEquals("10000", String.valueOf(props.get(key)), key);
        }
    }

    /**
     * Asserts the three JavaMail timeouts equal the record's {@code responseTimeout}: a sender built with
     * 2500 has {@code mail.smtp.connectiontimeout}, {@code mail.smtp.timeout} and
     * {@code mail.smtp.writetimeout} each {@code "2500"}.
     */
    @Test
    public void usesResponseTimeoutForAllThreeTimeouts() {
        JavaMailSenderImpl sender = new SmtpConfig().javaMailSender(
                new SmtpProperties(HOST, PORT, ENCODED_USER, ENCODED_PASSWORD, 2500));
        Properties props = sender.getJavaMailProperties();

        for (String key : TIMEOUT_KEYS) {
            assertEquals("2500", String.valueOf(props.get(key)), key);
        }
    }
}
