package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sender, recipient and subject of the order e-mail, bound from the {@code mail.*} keys.
 *
 * <p>The three components carry the {@code from="${mail.from}"}, {@code to="${mail.to}"} and
 * {@code subject="${mail.subject}"} attributes of the {@code smtp:outbound-endpoint} of flow
 * {@code csv-to-smtpFlow} [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:20].
 * {@code OrderMailClient} reads them when it builds the order e-mail.
 *
 * <p>The record is bound by constructor binding and declares no defaults: each component takes the value of its
 * key, and an absent key binds {@code null}. Addresses are supplied through configuration (D-012): the committed
 * {@code application.yml} holds marked placeholder values for {@code mail.from} and {@code mail.to}, which the
 * user replaces, and the value {@code Export from Excel} for {@code mail.subject}. Under relaxed binding the
 * environment variables {@code MAIL_FROM}, {@code MAIL_TO} and {@code MAIL_SUBJECT} set the same keys. The record
 * holds the configured strings unchanged; it neither parses nor validates them.
 *
 * @param from    {@code mail.from}: the sender address of the order e-mail
 * @param to      {@code mail.to}: the recipient of the order e-mail, as the configured string
 * @param subject {@code mail.subject}: the subject line of the order e-mail
 */
@ConfigurationProperties(prefix = "mail")
public record MailMessageProperties(String from, String to, String subject) {
}
