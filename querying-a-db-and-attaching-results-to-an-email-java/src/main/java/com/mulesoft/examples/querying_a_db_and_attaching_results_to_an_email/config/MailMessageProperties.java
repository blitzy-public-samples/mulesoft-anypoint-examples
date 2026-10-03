package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Message addressing bound from {@code mail.*}: sender, recipients and subject of the report mail sent by the
 * SMTP outbound endpoint.
 *
 * <p>Source: the {@code from="${mail.from}"}, {@code to="${mail.to}"} and {@code subject="${mail.subject}"}
 * attributes of the {@code smtp:outbound-endpoint}
 * [querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:36].
 *
 * <p>The record is bound by constructor binding from {@code application.yml} and declares no defaults: every
 * value comes from its key, and an absent key binds {@code null}. In the committed {@code application.yml} the
 * three keys hold marked placeholder values that the user replaces (D-012). Under relaxed binding the environment
 * variables {@code MAIL_FROM}, {@code MAIL_TO} and {@code MAIL_SUBJECT} set the same keys.
 *
 * <p>Example: with {@code mail.from=reports@example.com}, {@code mail.to=a@example.com,b@example.com} and
 * {@code mail.subject=Mule flow completed!}, {@link #to()} returns {@code a@example.com,b@example.com}.
 *
 * @param from    {@code mail.from}: the sender address of the report mail
 * @param to      {@code mail.to}: the recipient list as configured, one address or several separated by commas;
 *                the mail client passes it to the mail message unchanged
 * @param subject {@code mail.subject}: the subject line of the report mail
 */
@ConfigurationProperties("mail")
public record MailMessageProperties(String from, String to, String subject) {
}
