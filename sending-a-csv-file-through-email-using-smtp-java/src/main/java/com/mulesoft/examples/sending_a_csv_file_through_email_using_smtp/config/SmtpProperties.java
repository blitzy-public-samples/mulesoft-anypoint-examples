package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SMTP connection settings bound from the {@code smtp.*} keys: server host, port (default 587), user, password
 * and the response timeout in milliseconds (default 10000). Credentials are supplied through configuration
 * (D-012).
 *
 * <p>Source: the {@code host}, {@code port}, {@code user}, {@code password} and {@code responseTimeout}
 * attributes of the {@code smtp:outbound-endpoint} of flow {@code csv-to-smtpFlow}
 * [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:20]. The defaults 587 and 10000 are
 * that endpoint's {@code port} and {@code responseTimeout} values. The endpoint's {@code from}, {@code to} and
 * {@code subject} attributes bind to {@code MailMessageProperties}.
 *
 * <p>The record is bound by constructor binding. {@code host}, {@code user} and {@code password} declare no
 * default: a key that is absent binds {@code null}, and the committed {@code application.yml} gives each a
 * placeholder value that the user replaces. Every value binds as written, with no validation, trimming or
 * decoding. Under relaxed binding the environment variables {@code SMTP_HOST}, {@code SMTP_PORT},
 * {@code SMTP_USER}, {@code SMTP_PASSWORD} and {@code SMTP_RESPONSE_TIMEOUT} set the same keys. Instances are
 * immutable. The generated {@code toString()} prints every component, {@code password} included (D-381).
 *
 * <p>Example: with only {@code smtp.response-timeout=2500} bound, {@link #port()} returns {@code 587},
 * {@link #responseTimeout()} returns {@code 2500}, and {@link #host()}, {@link #user()} and {@link #password()}
 * return {@code null}.
 *
 * @param host            {@code smtp.host}: the SMTP server host name; no default
 * @param port            {@code smtp.port}: the SMTP server port; default {@code 587}
 * @param user            {@code smtp.user}: the SMTP user name, as configured; no default
 * @param password        {@code smtp.password}: the SMTP password, as configured; no default
 * @param responseTimeout {@code smtp.response-timeout}: the endpoint's response timeout in milliseconds;
 *                        default {@code 10000}
 */
@ConfigurationProperties(prefix = "smtp")
public record SmtpProperties(
        String host,
        @DefaultValue("587") int port,
        String user,
        String password,
        @DefaultValue("10000") int responseTimeout) {
}
