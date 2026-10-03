package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Provides the SMTP {@code JavaMailSender} built from {@link SmtpProperties}: SMTP with authentication and
 * STARTTLS, UTF-8 default encoding, and connection, read and write timeouts equal to
 * {@code smtp.response-timeout} (D-063, D-568). Credentials come from configuration (D-012).
 *
 * <p>Source: the SMTP connector declared at
 * [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:3] and the {@code host},
 * {@code port}, {@code user}, {@code password} and {@code responseTimeout} attributes of the
 * {@code smtp:outbound-endpoint} of flow {@code csv-to-smtpFlow} [same file:20], read through the
 * {@code smtp.*} keys of {@link SmtpProperties}.
 *
 * <p>The sender is the only {@code JavaMailSender} bean of the context; no {@code spring.mail.*} key is read.
 * It opens no connection when the context starts. Each {@code send} connects to the configured server, and a
 * missing host or a wrong credential surfaces as a failure of that send.
 */
@Configuration
public class SmtpConfig {

    /**
     * Builds the SMTP sender.
     *
     * <p>Settings applied:
     * <ul>
     *   <li>host: {@link SmtpProperties#host()}, which may be {@code null};</li>
     *   <li>port: {@link SmtpProperties#port()};</li>
     *   <li>protocol: {@code smtp};</li>
     *   <li>default encoding: {@code UTF-8};</li>
     *   <li>user name: {@link SmtpProperties#user()} passed through {@link #decode(String)};</li>
     *   <li>password: {@link SmtpProperties#password()} passed through {@link #decode(String)};</li>
     *   <li>{@code mail.smtp.auth}: {@code true};</li>
     *   <li>{@code mail.smtp.starttls.enable}: {@code true};</li>
     *   <li>{@code mail.smtp.connectiontimeout}, {@code mail.smtp.timeout} and {@code mail.smtp.writetimeout}:
     *       {@link SmtpProperties#responseTimeout()} in milliseconds, as a decimal string.</li>
     * </ul>
     * No other JavaMail property is set, and no test connection is made (D-568).
     *
     * <p>Example: with only {@code smtp.host=h} bound, the sender has host {@code h}, port {@code 587},
     * a {@code null} user name and password, and all three timeouts {@code "10000"}.
     *
     * @param p the bound {@code smtp.*} settings
     * @return the configured sender, assignable to {@code JavaMailSender} and {@code MailSender}
     * @throws IllegalArgumentException if {@code smtp.user} or {@code smtp.password} holds a malformed
     *                                  percent escape
     */
    @Bean
    public JavaMailSenderImpl javaMailSender(SmtpProperties p) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(p.host());
        sender.setPort(p.port());
        sender.setProtocol("smtp");
        sender.setDefaultEncoding("UTF-8");
        sender.setUsername(decode(p.user()));
        sender.setPassword(decode(p.password()));
        Properties props = sender.getJavaMailProperties();
        String timeout = String.valueOf(p.responseTimeout());
        props.setProperty("mail.smtp.auth", "true");
        props.setProperty("mail.smtp.starttls.enable", "true");
        props.setProperty("mail.smtp.connectiontimeout", timeout);
        props.setProperty("mail.smtp.timeout", timeout);
        props.setProperty("mail.smtp.writetimeout", timeout);
        return sender;
    }

    /**
     * Returns {@code null} for {@code null}; otherwise percent-decodes the value as UTF-8 while keeping
     * {@code +} literal; throws {@code IllegalArgumentException} for a malformed escape (D-567).
     *
     * <p>Each {@code %XX} escape becomes the character it encodes ({@code %40} becomes {@code @},
     * {@code %2B} becomes {@code +}, {@code %25} becomes {@code %}). A {@code +} stays a plus sign and never
     * becomes a space: it is escaped to {@code %2B} before decoding. A value without {@code %} is
     * returned unchanged. A {@code %} not followed by two hexadecimal digits, for example {@code %zz} or a
     * trailing {@code %}, raises the {@code IllegalArgumentException} of {@link URLDecoder}.
     *
     * <p>Examples: {@code decode("name%40host")} returns {@code "name@host"}, {@code decode("a+b")} returns
     * {@code "a+b"}, and {@code decode("plain")} returns {@code "plain"}.
     *
     * @param v the configured value, possibly percent-encoded; may be {@code null}
     * @return the decoded value, or {@code null} when {@code v} is {@code null}
     * @throws IllegalArgumentException if {@code v} contains a malformed percent escape
     */
    public static String decode(String v) {
        if (v == null) {
            return null;
        }
        return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
