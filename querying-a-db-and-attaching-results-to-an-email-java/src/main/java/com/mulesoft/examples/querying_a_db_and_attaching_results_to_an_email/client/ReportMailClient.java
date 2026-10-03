package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.client;

import java.nio.charset.StandardCharsets;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config.MailMessageProperties;

/**
 * Sends the employee report mail through the configured SMTP sender.
 *
 * <p>Replaces the {@code set-attachment} and the {@code smtp:outbound-endpoint} of flow {@code attachmentsFlow1}
 * [querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:35-36] with Spring's
 * {@link JavaMailSender} over Jakarta Mail (D-063). The sender address, the recipients and the subject come from
 * {@link MailMessageProperties}: {@code mail.from}, {@code mail.to} and {@code mail.subject}. The SMTP host, port
 * and credentials are those of the injected {@link JavaMailSender} bean (D-012).
 *
 * <p>Each mail is a {@code multipart/mixed} message with exactly two body parts, in this order:
 * <ol>
 *   <li>the CSV report as the text, {@code text/plain; charset=UTF-8}, with no file name and no disposition;</li>
 *   <li>the CSV report as the attachment {@code employees.csv}, content type {@code text/plain}, holding the
 *       report's UTF-8 bytes.</li>
 * </ol>
 *
 * <p>The mail is sent synchronously on the calling thread, one message per call. Failures are neither classified
 * nor retried, and nothing is logged: a {@link MessagingException} raised while the message is built becomes a
 * {@link MailPreparationException}, and an {@link org.springframework.mail.MailException} raised by the sender
 * propagates unchanged. The instance holds only its two immutable collaborators, and concurrent calls build
 * separate messages.
 *
 * <p>Example: with {@code mail.from=reports@example.com}, {@code mail.to=a@example.com,b@example.com} and
 * {@code mail.subject=Mule flow completed!}, {@code send(report)} sends one mail with subject
 * {@code Mule flow completed!} from {@code reports@example.com} to both recipients, whose text and
 * {@code employees.csv} attachment are {@code report}.
 */
@Component
public class ReportMailClient {

    /** File name of the report attachment, as set by {@code set-attachment attachmentName} [attachments.xml:35]. */
    private static final String ATTACHMENT_NAME = "employees.csv";

    /** Content type of the report attachment, as set by {@code set-attachment contentType} [attachments.xml:35]. */
    private static final String ATTACHMENT_CONTENT_TYPE = "text/plain";

    /** SMTP mail sender that creates and sends the report messages. */
    private final JavaMailSender mailSender;

    /** Sender address, recipient list and subject of the report mail, bound from {@code mail.*}. */
    private final MailMessageProperties mail;

    /**
     * Creates the client over the application's mail sender and the bound {@code mail.*} addressing.
     *
     * @param mailSender the SMTP mail sender that creates and sends the messages
     * @param mail       the sender address, recipient list and subject of the report mail
     */
    public ReportMailClient(JavaMailSender mailSender, MailMessageProperties mail) {
        this.mailSender = mailSender;
        this.mail = mail;
    }

    /**
     * Sends one mail whose plain-text body and {@code employees.csv} attachment are the CSV report.
     *
     * <p>The message is built in this order and then handed to the sender once:
     * <ol>
     *   <li>a new {@link MimeMessage} from the sender, with a {@code multipart/mixed} root and UTF-8 encoding;</li>
     *   <li>the sender address {@code mail.from};</li>
     *   <li>the recipients {@code mail.to}, parsed as a comma-separated address list, one recipient per address;</li>
     *   <li>the subject {@code mail.subject};</li>
     *   <li>the text part, body part 0: {@code csv} as plain text;</li>
     *   <li>the attachment, body part 1: {@code employees.csv}, {@code text/plain}, the UTF-8 bytes of
     *       {@code csv}.</li>
     * </ol>
     * No sent date, reply-to, CC, BCC, priority or other header is set while the message is built.
     *
     * @param csv the CSV report, not {@code null}: the text of body part 0 and, encoded as UTF-8, the content of the
     *            {@code employees.csv} attachment
     * @throws MailPreparationException when the message cannot be built, for example when {@code mail.to} is not a
     *                                  valid address list; it carries the message and the cause of the
     *                                  {@link MessagingException}
     * @throws org.springframework.mail.MailException when the sender fails, for example a
     *                                  {@link org.springframework.mail.MailSendException} when the SMTP server
     *                                  cannot be reached or rejects the message
     */
    public void send(String csv) {
        MimeMessage message = mailSender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, MimeMessageHelper.MULTIPART_MODE_MIXED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(mail.from());
            helper.setTo(InternetAddress.parse(mail.to()));
            helper.setSubject(mail.subject());
            // The text part is added first: it is body part 0, and the attachment below is body part 1.
            helper.setText(csv, false);
            helper.addAttachment(ATTACHMENT_NAME, new ByteArrayResource(csv.getBytes(StandardCharsets.UTF_8)),
                    ATTACHMENT_CONTENT_TYPE);
        } catch (MessagingException e) {
            throw new MailPreparationException(e.getMessage(), e);
        }
        mailSender.send(message);
    }
}
