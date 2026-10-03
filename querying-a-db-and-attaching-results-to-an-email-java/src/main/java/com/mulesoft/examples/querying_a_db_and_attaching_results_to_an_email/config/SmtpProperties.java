package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * SMTP connection settings bound from {@code smtp.*} for the {@code Gmail} SMTP connector and its outbound
 * endpoint (D-012, D-330).
 *
 * <p>The record replaces the {@code smtp:gmail-connector} {@code Gmail}
 * [querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:4] and the {@code host},
 * {@code port}, {@code user}, {@code password} and {@code responseTimeout} attributes of the
 * {@code smtp:outbound-endpoint} that references it [attachments.xml:36]. The endpoint's {@code from},
 * {@code to} and {@code subject} attributes bind to {@code MailMessageProperties}.
 *
 * <p>{@code host}, {@code port}, {@code user} and {@code password} declare no defaults: an absent key binds
 * {@code null}, and the committed {@code application.yml} gives each a placeholder value that the user
 * replaces. Every value binds as written, with no parsing, trimming or decoding. Instances are immutable.
 * The generated {@code toString()} prints every component, {@code password} included.
 *
 * <pre>{@code
 * smtp:
 *   host: smtp.gmail.com
 *   port: 587
 *   user: sender%40gmail.com
 * }</pre>
 * binds {@code host()} {@code "smtp.gmail.com"}, {@code port()} {@code "587"}, {@code user()}
 * {@code "sender%40gmail.com"} and {@code responseTimeout()} {@code 10000}.
 *
 * @param host            {@code smtp.host}: the SMTP server host name
 * @param port            {@code smtp.port}: the SMTP server port as text; {@code MailSenderConfig} applies it
 *                        as the port number only when it is numeric
 * @param user            {@code smtp.user}: the SMTP user name; the value may be percent-encoded, and
 *                        {@code MailSenderConfig} decodes it
 * @param password        {@code smtp.password}: the SMTP password; the value may be percent-encoded, and
 *                        {@code MailSenderConfig} decodes it
 * @param responseTimeout Value of the endpoint's {@code responseTimeout} attribute, bound from
 *                        {@code smtp.response-timeout}; not applied to the SMTP connection.
 */
@ConfigurationProperties("smtp")
public record SmtpProperties(String host, String port, String user, String password,
                             @DefaultValue("10000") Integer responseTimeout) {
}
