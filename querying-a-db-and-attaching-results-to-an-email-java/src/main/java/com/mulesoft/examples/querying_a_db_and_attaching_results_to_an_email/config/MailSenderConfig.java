package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Builds the SMTP mail sender of the {@code Gmail} connector and its outbound endpoint from {@code smtp.*}
 * (D-012, D-063, D-551, D-552, D-553).
 *
 * <p>Source: the global element {@code smtp:gmail-connector} named {@code Gmail}
 * [querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:4] and the {@code host},
 * {@code port}, {@code user} and {@code password} attributes of the {@code smtp:outbound-endpoint} that
 * references it [attachments.xml:36].
 *
 * <p>The sender returned by {@link #javaMailSender(SmtpProperties)} is the application's only mail sender
 * bean. No {@code spring.mail.*} key is set, and Spring Boot's mail sender auto-configuration registers no
 * other sender.
 *
 * <p>Example: with {@code smtp.host=smtp.gmail.com}, {@code smtp.port=587} and
 * {@code smtp.user=sender%40gmail.com}, the sender connects over plain SMTP to {@code smtp.gmail.com:587},
 * switches to TLS with STARTTLS when the server offers it, and authenticates as {@code sender@gmail.com}.
 */
@Configuration
public class MailSenderConfig {

    /** JavaMail protocol of the SMTP transport. */
    private static final String SMTP_PROTOCOL = "smtp";

    /** SMTP default port, used for {@code mail.smtp.socketFactory.port} when no numeric port is set. */
    private static final int DEFAULT_SMTP_PORT = 25;

    /** Form of a {@code smtp.port} value that is applied as the port number: one to five ASCII digits. */
    private static final String NUMERIC_PORT = "\\d{1,5}";

    /**
     * Creates the SMTP mail sender from the {@code smtp.*} settings.
     *
     * <p>The sender is configured with:
     * <ul>
     *   <li>protocol {@code smtp} and default encoding {@code UTF-8};</li>
     *   <li>host: {@code smtp.host}, as bound;</li>
     *   <li>port: {@code smtp.port} when its trimmed value has one to five digits. Any other value, or no
     *       value, leaves the port unset ({@code -1}), and JavaMail connects to the SMTP default port 25;</li>
     *   <li>user name and password: {@code smtp.user} and {@code smtp.password}, each percent-decoded once as
     *       UTF-8, with {@code %40} decoded to {@code @} and {@code +} to a space. A value with a malformed
     *       {@code %} sequence is used unchanged (D-551);</li>
     *   <li>authentication and STARTTLS: {@code mail.smtp.auth}, {@code mail.smtp.starttls.enable},
     *       {@code mail.smtps.auth} and {@code mail.smtps.starttls.enable}, each {@code true}. STARTTLS is
     *       used when the server offers it and is not required (D-552);</li>
     *   <li>{@code mail.smtp.socketFactory.port}: the numeric port, or {@code 25} when none is set;</li>
     *   <li>{@code mail.smtp.host}: the host, only when it is not {@code null} and not blank;</li>
     *   <li>{@code mail.smtp.rsetbeforequit}: {@code true}.</li>
     * </ul>
     *
     * <p>No other session property is set (D-552): there is no connection, read or write timeout, and
     * {@code smtp.response-timeout} is not applied to the connection (D-330). The method opens no connection; the
     * first SMTP connection is made when a message is sent (D-553).
     *
     * @param properties the {@code smtp.*} settings
     * @return the configured SMTP mail sender
     */
    @Bean
    public JavaMailSenderImpl javaMailSender(SmtpProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setProtocol(SMTP_PROTOCOL);
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        sender.setHost(properties.host());

        // A non-numeric smtp.port, such as the committed placeholder, leaves the sender's port at -1.
        int sessionPort = DEFAULT_SMTP_PORT;
        String port = properties.port();
        if (port != null) {
            String trimmed = port.trim();
            if (trimmed.matches(NUMERIC_PORT)) {
                sessionPort = Integer.parseInt(trimmed);
                sender.setPort(sessionPort);
            }
        }

        sender.setUsername(decode(properties.user()));
        sender.setPassword(decode(properties.password()));

        // Only these session properties are set (D-552); no mail.smtp.connectiontimeout, mail.smtp.timeout or
        // mail.smtp.writetimeout is set (D-330).
        Properties p = sender.getJavaMailProperties();
        p.setProperty("mail.smtp.auth", "true");
        p.setProperty("mail.smtp.starttls.enable", "true");
        p.setProperty("mail.smtps.auth", "true");
        p.setProperty("mail.smtps.starttls.enable", "true");
        p.setProperty("mail.smtp.socketFactory.port", Integer.toString(sessionPort));
        String host = properties.host();
        if (host != null && !host.isBlank()) {
            p.setProperty("mail.smtp.host", host);
        }
        p.setProperty("mail.smtp.rsetbeforequit", "true");

        // No testConnection(): the context starts without contacting the SMTP server (D-553).
        return sender;
    }

    /**
     * Percent-decodes a configured credential once as UTF-8 (D-551).
     *
     * <p>Examples: {@code sender%40example.com} gives {@code sender@example.com}, {@code a+b} gives
     * {@code a b}, and {@code 100%} (a malformed sequence) is returned unchanged.
     *
     * @param value the configured value, or {@code null}
     * @return the decoded value; the value unchanged when it holds a malformed {@code %} sequence;
     *         {@code null} for {@code null}
     */
    private static String decode(String value) {
        if (value == null) {
            return null;
        }
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformed) {
            // The configured text is used as the credential; the SMTP server evaluates it when a mail is sent (D-551).
            return value;
        }
    }
}
