package com.mulesoft.examples.scatter_gather_flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Sender and recipient of the aggregation report mail, bound from the {@code mail.from} and
 * {@code mail.to} keys.
 *
 * <p>Source: the {@code from="${mail.from}"} and {@code to="${mail.to}"} attributes of the
 * {@code smtp:outbound-endpoint} of flow {@code outboundFlow}
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:64]. {@code client.ReportMailClient}
 * uses {@link #from()} as the sender address and parses {@link #to()} into the recipient list.
 *
 * <p>The record is bound by constructor binding and declares no defaults: each component takes the
 * value of its key, and an absent key binds {@code null}. The committed {@code application.yml} holds
 * {@code TODO} placeholder values for both keys, which the user replaces (D-012). Under relaxed binding
 * the environment variables {@code MAIL_FROM} and {@code MAIL_TO} set the same keys. The record holds
 * the configured strings unchanged; it neither parses nor validates them.
 *
 * <p>Example: with {@code mail.from=reports@example.com} and
 * {@code mail.to=a@example.com,b@example.com}, {@link #to()} returns
 * {@code a@example.com,b@example.com}, which the mail client sends to both addresses.
 *
 * @param from {@code mail.from}: the sender address of the report mail
 * @param to   {@code mail.to}: the recipient address of the report mail, or several addresses
 *             separated by commas
 */
@ConfigurationProperties(prefix = "mail")
public record MailAddressProperties(String from, String to) {
}
