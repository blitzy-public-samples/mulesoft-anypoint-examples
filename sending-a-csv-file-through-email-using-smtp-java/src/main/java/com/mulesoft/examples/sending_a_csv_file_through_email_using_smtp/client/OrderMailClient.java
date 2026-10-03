package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.client;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config.MailMessageProperties;

/**
 * Sends one plain-text order mail through the configured SMTP sender.
 *
 * <p>Replaces the {@code smtp:outbound-endpoint} of flow {@code csv-to-smtpFlow}
 * [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:20] with Spring's
 * {@link JavaMailSender} over Jakarta Mail (D-063, D-690). The sender address, the recipients and the subject come
 * from {@link MailMessageProperties}: {@code mail.from}, {@code mail.to} and {@code mail.subject}. The SMTP host,
 * port, credentials and timeouts are those of the injected {@link JavaMailSender} bean.
 *
 * <p>Each mail is a single-part {@code text/plain; charset=UTF-8} message whose content is the given body string:
 * no attachment, no CC, BCC or Reply-To, and no extra headers. The sender stamps the sent date on sending.
 *
 * <p>The mail is sent synchronously on the calling thread, one message per call, over one SMTP connection. Failures
 * are neither classified nor retried, and nothing is logged: a {@link MessagingException} raised while the message
 * is built becomes a {@link MailPreparationException} and nothing is sent, and an
 * {@link org.springframework.mail.MailException} raised by the sender propagates unchanged. Creating the client
 * opens no connection. The instance holds only its two immutable collaborators, and concurrent calls build
 * separate messages.
 */
@Component
public class OrderMailClient {

    /** Character encoding of the mail text and of the encoded header values. */
    private static final String MAIL_ENCODING = "UTF-8";

    /** SMTP mail sender that creates and sends the order messages. */
    private final JavaMailSender mailSender;

    /** Sender address, recipient list and subject of the order mail, bound from {@code mail.*}. */
    private final MailMessageProperties properties;

    /**
     * Creates the client over the application's mail sender and the bound {@code mail.*} addressing.
     *
     * @param mailSender the SMTP mail sender that creates and sends the messages
     * @param properties the sender address, recipient list and subject of the order mail
     */
    public OrderMailClient(JavaMailSender mailSender, MailMessageProperties properties) {
        this.mailSender = mailSender;
        this.properties = properties;
    }

    /**
     * Sends one mail whose plain-text content is {@code body}.
     *
     * <p>The message is built in this order and then handed to the sender once:
     * <ol>
     *   <li>a new {@link MimeMessage} from the sender, non-multipart, UTF-8 encoded;</li>
     *   <li>the sender address {@code mail.from};</li>
     *   <li>the recipients {@code mail.to}, parsed as a comma-separated address list, one recipient per
     *       address;</li>
     *   <li>the subject {@code mail.subject};</li>
     *   <li>{@code body} as the text, {@code text/plain; charset=UTF-8}.</li>
     * </ol>
     *
     * @param body the mail text, sent unchanged
     * @throws MailPreparationException when the message cannot be built, for example when {@code mail.from} or
     *         {@code mail.to} is not a parsable address; no message is sent
     * @throws IllegalArgumentException when {@code mail.from}, {@code mail.to}, {@code mail.subject} or
     *         {@code body} is {@code null}; no message is sent
     * @throws org.springframework.mail.MailException when the sender fails to connect, authenticate or deliver,
     *         for example {@link org.springframework.mail.MailSendException} or
     *         {@link org.springframework.mail.MailAuthenticationException}
     */
    public void send(String body) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, false, MAIL_ENCODING);
            helper.setFrom(properties.from());
            // A null mail.to raises IllegalArgumentException, as a null mail.from, mail.subject or body does (D-690).
            if (properties.to() == null) {
                throw new IllegalArgumentException("To address must not be null");
            }
            helper.setTo(InternetAddress.parse(properties.to()));
            helper.setSubject(properties.subject());
            helper.setText(body, false);
        } catch (MessagingException e) {
            throw new MailPreparationException("Could not prepare order mail", e);
        }
        mailSender.send(message);
    }
}
