package com.mulesoft.examples.scatter_gather_flow_control.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SMTP settings of the aggregation-report mail, bound from the {@code smtp.*} keys: server host, port, user,
 * password, message subject and response timeout in milliseconds (D-463). Credentials are supplied through
 * configuration (D-012).
 *
 * <p>Source: the global connector {@code <smtp:gmail-connector name="Gmail" validateConnections="true"/>}
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:3] and the {@code host}, {@code port},
 * {@code user}, {@code password}, {@code subject} and {@code responseTimeout} attributes of the
 * {@code smtp:outbound-endpoint} of sub-flow {@code outboundFlow}
 * [scatter-gather-flow-control/src/main/app/scatter-gather.xml:64]. The endpoint's {@code from} and {@code to}
 * attributes bind to {@code MailAddressProperties}.
 *
 * <p>The committed {@code application.yml} sets {@code smtp.port} to {@code 587} (D-271), {@code smtp.subject} to
 * {@code Scatter gather example - aggregation} and {@code smtp.response-timeout} to {@code 10000}, the endpoint's
 * {@code subject} and {@code responseTimeout} literals. It gives {@code smtp.host}, {@code smtp.user} and
 * {@code smtp.password} placeholder values that the user replaces (D-012).
 *
 * <p>{@code @ConfigurationPropertiesScan} on {@code ScatterGatherFlowControlApplication} registers the record, and
 * Spring Boot binds it through its constructor. No component declares a Java default: a key that is absent binds
 * {@code null} for {@code host}, {@code user}, {@code password} and {@code subject}, {@code 0} for {@code port} and
 * {@code 0L} for {@code responseTimeout}. Every value binds as written, with no validation, trimming or decoding;
 * a {@code smtp.port} or {@code smtp.response-timeout} that is not a number fails the binding at startup. Under
 * relaxed binding the environment variables {@code SMTP_HOST}, {@code SMTP_PORT}, {@code SMTP_USER},
 * {@code SMTP_PASSWORD}, {@code SMTP_SUBJECT} and {@code SMTP_RESPONSETIMEOUT} set the same keys. Instances are
 * immutable. The generated {@code toString()} prints every component, {@code password} included (D-463).
 *
 * <p>Consumers: {@code GmailSmtpConfig#reportMailSender} reads {@link #host()}, {@link #port()},
 * {@link #user()}, {@link #password()} and {@link #responseTimeout()}; {@code client.ReportMailClient#send} reads
 * {@link #subject()}.
 *
 * <p>Example: with the committed {@code application.yml}, {@link #port()} returns {@code 587},
 * {@link #subject()} returns {@code "Scatter gather example - aggregation"} and {@link #responseTimeout()} returns
 * {@code 10000L}.
 *
 * @param host            {@code smtp.host}: the SMTP server host name
 * @param port            {@code smtp.port}: the SMTP server port
 * @param user            {@code smtp.user}: the SMTP user name, as configured
 * @param password        {@code smtp.password}: the SMTP password, as configured
 * @param subject         {@code smtp.subject}: the subject of the aggregation-report mail
 * @param responseTimeout {@code smtp.response-timeout}: the endpoint's response timeout in milliseconds
 */
@ConfigurationProperties(prefix = "smtp")
public record SmtpProperties(String host, int port, String user, String password, String subject, long responseTimeout) { }
