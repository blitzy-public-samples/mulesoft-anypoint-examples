package com.mulesoft.examples.scatter_gather_flow_control.config;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Declares {@code reportMailSender}, the SMTP sender of the aggregation-report mail: a Spring
 * {@link JavaMailSenderImpl} on Jakarta Mail (D-063).
 *
 * <p>Source: the global connector {@code <smtp:gmail-connector name="Gmail" validateConnections="true"/>}
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:3] and the connection attributes {@code host},
 * {@code port}, {@code user}, {@code password} and {@code responseTimeout} of the {@code smtp:outbound-endpoint}
 * of {@code outboundFlow} that references it [scatter-gather-flow-control/src/main/app/scatter-gather.xml:64].
 * The values are read from {@link SmtpProperties}; the committed {@code application.yml} holds {@code TODO} for
 * {@code smtp.host}, {@code smtp.user} and {@code smtp.password} (D-012).
 *
 * <p>{@code reportMailSender} is the only {@link JavaMailSender} of the application context:
 * {@code application.yml} sets no {@code spring.mail.*} key, and Spring Boot's mail sender auto-configuration
 * declares no second sender beside it (D-656). {@code client.ReportMailClient} injects it by its bean name.
 *
 * <p>Example: with the committed {@code application.yml} the sender targets host {@code TODO} on port
 * {@code 587} with user {@code TODO}; with {@code smtp.user=senderemailid%40gmail.com} it authenticates as
 * {@code senderemailid@gmail.com} (D-655).
 */
@Configuration
public class GmailSmtpConfig {

    /**
     * Builds the {@code reportMailSender} bean from the bound {@code smtp.*} settings (D-656):
     * <ul>
     *   <li>host {@code smtp.host}, port {@code smtp.port}, protocol {@code smtp} and default message
     *       encoding {@code UTF-8};</li>
     *   <li>user name {@code smtp.user} and password {@code smtp.password}, each passed through
     *       {@link #percentDecode(String)} (D-655);</li>
     *   <li>exactly three JavaMail session properties: {@code mail.smtp.auth=true},
     *       {@code mail.smtp.starttls.enable=true}, and {@code mail.smtp.timeout} set to
     *       {@code smtp.response-timeout} in milliseconds ({@code 10000} with the committed
     *       {@code application.yml}).</li>
     * </ul>
     *
     * <p>The connection switches to TLS with STARTTLS when the server offers it; STARTTLS is not required.
     * Creating the bean opens no connection and runs no connection test: each {@code send} connects to the
     * server, authenticates with the decoded user name and password, transmits and closes the connection.
     * The context starts with the committed {@code TODO} host, user and password (D-012), and a wrong host,
     * port or credential surfaces as a {@code MailException} thrown by the first {@code send}.
     *
     * @param smtp the bound {@code smtp.*} settings
     * @return the configured sender
     */
    @Bean
    public JavaMailSender reportMailSender(SmtpProperties smtp) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(smtp.host());
        sender.setPort(smtp.port());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");
        sender.setUsername(percentDecode(smtp.user()));
        sender.setPassword(percentDecode(smtp.password()));

        // The session carries these three entries and no other (D-656).
        Properties props = sender.getJavaMailProperties();
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.starttls.enable", "true");
        props.setProperty("mail.smtp.timeout", String.valueOf(smtp.responseTimeout()));
        return sender;
    }

    /**
     * Percent-decodes an SMTP user name or password (D-655).
     *
     * <p>The value is scanned left to right. A {@code %} followed by two ASCII hexadecimal digits
     * ({@code 0-9}, {@code a-f}, {@code A-F}) is an escape that stands for one byte. Each maximal run of
     * consecutive escapes is decoded as one UTF-8 byte sequence, and each malformed UTF-8 sequence in a run
     * becomes U+FFFD. Every other character is kept unchanged: {@code +} stays {@code +} (it never becomes a
     * space), non-ASCII characters stay as they are, and a {@code %} without two hexadecimal digits after it
     * (a lone {@code %}, {@code %4} at the end, {@code %zz}) is kept as written. Nothing is trimmed or
     * case-converted, and the method never throws.
     *
     * <p>Examples:
     * <ul>
     *   <li>{@code senderemailid%40gmail.com} gives {@code senderemailid@gmail.com};</li>
     *   <li>{@code %C3%A9} (two escapes, one run) gives U+00E9 ({@code é});</li>
     *   <li>{@code a+b%zz%4} gives {@code a+b%zz%4};</li>
     *   <li>{@code %} gives {@code %};</li>
     *   <li>a value without {@code %} gives itself, and {@code null} gives {@code null}.</li>
     * </ul>
     *
     * @param value the configured text, or {@code null}
     * @return the decoded text, or {@code null} when {@code value} is {@code null}
     */
    public static String percentDecode(String value) {
        if (value == null) {
            return null;
        }
        if (value.indexOf('%') < 0) {
            return value;
        }
        int length = value.length();
        StringBuilder decoded = new StringBuilder(length);
        ByteArrayOutputStream run = new ByteArrayOutputStream();
        int i = 0;
        while (i < length) {
            if (isEscapeAt(value, i)) {
                run.reset();
                while (i < length && isEscapeAt(value, i)) {
                    run.write((hexValue(value.charAt(i + 1)) << 4) | hexValue(value.charAt(i + 2)));
                    i += 3;
                }
                decoded.append(new String(run.toByteArray(), StandardCharsets.UTF_8));
            } else {
                decoded.append(value.charAt(i));
                i++;
            }
        }
        return decoded.toString();
    }

    /**
     * Tells whether a {@code %} escape with two ASCII hexadecimal digits starts at {@code index}.
     *
     * @param value the scanned text
     * @param index a position inside {@code value}
     * @return {@code true} when {@code value.charAt(index)} is {@code %} and the two following characters exist
     *         and are ASCII hexadecimal digits
     */
    private static boolean isEscapeAt(String value, int index) {
        return value.charAt(index) == '%'
                && index + 2 < value.length()
                && hexValue(value.charAt(index + 1)) >= 0
                && hexValue(value.charAt(index + 2)) >= 0;
    }

    /**
     * Returns the value of an ASCII hexadecimal digit.
     *
     * @param c the character to read
     * @return {@code 0} to {@code 15} for {@code 0-9}, {@code a-f} and {@code A-F}; {@code -1} for any other
     *         character, non-ASCII digits included
     */
    private static int hexValue(char c) {
        return c < 0x80 ? Character.digit(c, 16) : -1;
    }
}
