package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.exception;

/**
 * Thrown when a mail message contains no attachment part; corresponds to the empty
 * {@code attachments-list} read by {@code payload[0].getContent()} at
 * {@code imap-to-xml.xml:11-15} (D-121).
 *
 * <p>{@code MailAttachmentReader.firstAttachment} throws it when the message has no attachment part,
 * where the original flow fails on {@code payload[0]} of an empty list. The exception is unchecked
 * and travels unchanged through {@code AttachmentToXmlService.imapToCsvFlow1} to
 * {@code ImapAttachmentPoller}, which logs it at ERROR; the message, already flagged
 * {@code DELETED}, is not processed again and no {@code orders.xml} is written for it.
 *
 * <pre>{@code
 * if (attachment == null) {
 *     throw new NoAttachmentException();
 * }
 * }</pre>
 */
public class NoAttachmentException extends RuntimeException {

    /** Serialization version of this exception class. */
    private static final long serialVersionUID = 1L;

    /** Creates the exception with the message {@code Mail message has no attachment}. */
    public NoAttachmentException() {
        super("Mail message has no attachment");
    }
}
