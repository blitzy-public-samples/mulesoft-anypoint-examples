package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.exception.NoAttachmentException;

/**
 * Reads the first attachment of a received mail message, re-implementing by hand (D-034) the
 * {@code expression-transformer} with {@code return-argument evaluator="attachments-list"
 * expression="*"} and the {@code set-payload value="#[payload[0].getContent()]"} of flow
 * {@code pop-to-xmlFlow1} (pop-to-xml.xml:7-10). The selection rule is D-391.
 *
 * <p><b>Selection.</b> The parts of the message's {@link Multipart} content are searched
 * depth-first in MIME order:
 *
 * <ul>
 *   <li>a part of type {@code multipart/*} whose content is a {@link Multipart} is searched in turn,
 *       and is never itself returned;</li>
 *   <li>any other part is the attachment when its disposition is {@link Part#ATTACHMENT}, compared
 *       case-insensitively, or when it carries a non-empty file name;</li>
 *   <li>an inline part without a file name, such as a plain-text body, is skipped.</li>
 * </ul>
 *
 * <p>The first matching part wins and every later part is ignored. For the original example's mail,
 * a single-part {@code MimeMultipart} whose part has type {@code application/octet-stream} and the
 * file name {@code report} ({@code Content-Disposition: attachment; filename=report}), that part is
 * returned.
 *
 * <p><b>Result.</b> The bytes of the selected part with its transfer encoding (for example
 * {@code base64} or {@code quoted-printable}) removed by {@link Part#getInputStream()}, otherwise
 * unchanged: no charset conversion and no trimming. The caller decodes them as text.
 *
 * <p><b>Failures.</b>
 *
 * <ul>
 *   <li>content that is not a {@link Multipart}, or a multipart with no attachment part, throws
 *       {@link NoAttachmentException} with the message {@code Message has no attachment};</li>
 *   <li>a {@link MessagingException} raised while reading the message or its parts is rethrown as
 *       {@link IllegalStateException} with the same message and the original as its cause;</li>
 *   <li>an {@link IOException} raised while reading the message or its parts is rethrown as
 *       {@link UncheckedIOException} with the original as its cause.</li>
 * </ul>
 *
 * <p>Only unchecked exceptions leave {@link #firstAttachment(Message)}. The reader uses the Jakarta
 * Mail API of {@code spring-boot-starter-mail} (D-063), holds no state and is safe for concurrent
 * use. This class is the POP3 project's own copy and shares no code with any other project (D-004).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * byte[] csv = mailAttachmentReader.firstAttachment(message);
 * }</pre>
 */
@Component
public class MailAttachmentReader {

    /**
     * Returns the decoded bytes of the first attachment part of {@code message}, searching its
     * multipart content depth-first in MIME order. A part is an attachment when its disposition is
     * {@code attachment} (case-insensitive) or it has a non-empty file name; nested {@code multipart/*}
     * parts are searched, never returned.
     *
     * @param message the received mail message; its content is read once through
     *        {@link Message#getContent()}
     * @return the bytes of the first attachment, transfer encoding removed and otherwise unchanged
     * @throws NoAttachmentException if the content is not a {@link Multipart} or holds no attachment
     *         part; the message is {@code Message has no attachment}
     * @throws IllegalStateException wrapping a {@link MessagingException} raised while reading the
     *         message or its parts, with that exception's message and the exception as cause
     * @throws UncheckedIOException wrapping an {@link IOException} raised while reading the message
     *         or its parts, with the exception as cause
     */
    public byte[] firstAttachment(Message message) {
        try {
            Object content = message.getContent();
            Part attachment = content instanceof Multipart multipart ? findFirstAttachment(multipart) : null;
            if (attachment == null) {
                throw new NoAttachmentException("Message has no attachment");
            }
            try (InputStream in = attachment.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (MessagingException e) {
            throw new IllegalStateException(e.getMessage(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Searches {@code multipart} depth-first in MIME order and returns the first attachment part,
     * descending into every part of type {@code multipart/*} whose content is a {@link Multipart}.
     *
     * @param multipart the multipart to search
     * @return the first attachment part, or {@code null} when neither this multipart nor any nested
     *         one holds an attachment part
     * @throws MessagingException if a part or its headers cannot be read
     * @throws IOException if the content of a nested part cannot be read
     */
    private Part findFirstAttachment(Multipart multipart) throws MessagingException, IOException {
        for (int i = 0; i < multipart.getCount(); i++) {
            BodyPart part = multipart.getBodyPart(i);
            if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart nested) {
                Part found = findFirstAttachment(nested);
                if (found != null) {
                    return found;
                }
            } else if (isAttachment(part)) {
                return part;
            }
        }
        return null;
    }

    /**
     * Tells whether {@code part} is an attachment: its disposition equals {@link Part#ATTACHMENT}
     * ignoring case, or its file name is present and not empty.
     *
     * @param part the part to test
     * @return {@code true} for an attachment part, {@code false} otherwise
     * @throws MessagingException if the part's headers cannot be read
     */
    private boolean isAttachment(Part part) throws MessagingException {
        if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            return true;
        }
        String fileName = part.getFileName();
        return fileName != null && !fileName.isEmpty();
    }
}
