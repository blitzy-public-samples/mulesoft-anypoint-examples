package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.exception.NoAttachmentException;

/**
 * Returns the text of the first attachment part of a mail, in depth-first MIME part order; replaces the
 * {@code attachments-list} {@code *} expression and {@code payload[0].getContent()} of
 * {@code imap-to-csvFlow1}
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:11-15]
 * (D-034, D-063, D-121, D-395).
 *
 * <p>Attachment test: a part is an attachment when its {@code Content-Disposition} is
 * {@code attachment}, in any letter case, or when it has a file name (the {@code filename} parameter of
 * {@code Content-Disposition} or the {@code name} parameter of {@code Content-Type}). A
 * {@code multipart/*} container is never an attachment itself: its body parts are searched in order,
 * depth first, at every nesting level, for example a {@code multipart/mixed} nested inside a
 * {@code multipart/alternative}. A message that is not multipart is its own single candidate part.
 *
 * <p>Only the first part that passes the test is read; later attachments are ignored. Its text is
 * returned exactly as decoded, with no trimming and no line-ending conversion.
 *
 * <p>Instances hold no state and are safe for concurrent use; the class has a public no-argument
 * constructor and needs no Spring context.
 *
 * <pre>{@code
 * MailAttachmentReader reader = new MailAttachmentReader();
 * String csv = reader.firstAttachment(message);
 * // a multipart/mixed mail whose only part is an application/octet-stream "report" holding
 * // input.csv yields the input.csv text
 * }</pre>
 */
@Component
public class MailAttachmentReader {

    /** MIME type pattern of a multipart container. */
    private static final String MULTIPART = "multipart/*";

    /** MIME type pattern of a text part. */
    private static final String TEXT = "text/*";

    /** Name of the {@code Content-Type} parameter that declares a text part's charset. */
    private static final String CHARSET_PARAMETER = "charset";

    /**
     * Returns the text of the first attachment part of {@code message}.
     *
     * <p>The parts are visited depth first, in MIME order; the first part whose disposition is
     * {@code attachment} (any letter case) or that has a file name is read. Its content becomes text as
     * follows:
     * <ul>
     *   <li>{@code text/*}: the string Jakarta Mail decodes in the part's declared charset; for a text
     *       subtype without a content handler (for example {@code text/csv}), the transfer-decoded bytes
     *       decoded in the {@code charset} parameter of {@code Content-Type}, or in UTF-8 when the
     *       parameter is absent;</li>
     *   <li>any other type (for example {@code application/octet-stream}): the transfer-decoded bytes
     *       decoded as UTF-8.</li>
     * </ul>
     * Transfer encodings ({@code base64}, {@code quoted-printable}) are undone before decoding. The
     * resulting text is returned unchanged.
     *
     * @param message the received mail; never {@code null}
     * @return the text of the first attachment part
     * @throws NullPointerException when {@code message} is {@code null}
     * @throws NoAttachmentException when no part of the message passes the attachment test (D-121)
     * @throws IllegalStateException wrapping the {@link MessagingException} raised while the message
     *         structure, a header or a part's content is read
     * @throws UncheckedIOException wrapping the {@link IOException} raised while a part's content is
     *         read, including an {@link UnsupportedEncodingException} for a declared charset the JVM
     *         does not support
     */
    public String firstAttachment(Message message) {
        Objects.requireNonNull(message, "message");
        try {
            Part attachment = findFirstAttachment(message);
            if (attachment == null) {
                throw new NoAttachmentException();
            }
            return readContent(attachment);
        } catch (MessagingException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the first attachment part of {@code part} in depth-first part order, or {@code null} when
     * there is none. A {@code multipart/*} part is searched body part by body part, from index 0 up,
     * and is never returned itself; any other part is returned when it passes the attachment test.
     *
     * @param part the message or body part to search
     * @return the first attachment part, or {@code null}
     * @throws MessagingException when the part structure cannot be read, or when a {@code multipart/*}
     *         part does not hold {@link Multipart} content
     * @throws IOException when a multipart body cannot be read
     */
    private Part findFirstAttachment(Part part) throws MessagingException, IOException {
        if (part.isMimeType(MULTIPART)) {
            Object content = part.getContent();
            if (!(content instanceof Multipart multipart)) {
                throw new MessagingException(
                        "Part of type " + part.getContentType() + " does not hold multipart content");
            }
            int count = multipart.getCount();
            for (int i = 0; i < count; i++) {
                Part found = findFirstAttachment(multipart.getBodyPart(i));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        return isAttachment(part) ? part : null;
    }

    /**
     * Tells whether {@code part} is an attachment: its disposition is {@code attachment}, in any letter
     * case, or it has a file name. A body part with an {@code inline} or absent disposition and no file
     * name is not an attachment.
     *
     * @param part a non-multipart part
     * @return {@code true} for an attachment part
     * @throws MessagingException when the disposition or file name header cannot be read
     */
    private boolean isAttachment(Part part) throws MessagingException {
        return Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || part.getFileName() != null;
    }

    /**
     * Returns the content of {@code part} as text: for {@code text/*}, the decoded content string or,
     * when Jakarta Mail returns a stream, the bytes decoded in the declared charset (UTF-8 when none is
     * declared); for any other type, the bytes decoded as UTF-8. Every stream opened here is closed.
     *
     * @param part the attachment part
     * @return the attachment text, unaltered
     * @throws MessagingException when the content type or the content cannot be read
     * @throws IOException when the content bytes cannot be read, or the declared charset is not supported
     */
    private String readContent(Part part) throws MessagingException, IOException {
        if (part.isMimeType(TEXT)) {
            Object content = part.getContent();
            if (content instanceof String text) {
                return text;
            }
            Charset charset = declaredCharset(part);
            try (InputStream in = content instanceof InputStream stream ? stream : part.getInputStream()) {
                return new String(in.readAllBytes(), charset);
            }
        }
        try (InputStream in = part.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns the charset named by the {@code charset} parameter of the part's {@code Content-Type}, or
     * UTF-8 when the part declares no content type or no charset.
     *
     * @param part a {@code text/*} part
     * @return the declared charset, or {@link StandardCharsets#UTF_8}
     * @throws MessagingException when the {@code Content-Type} header cannot be read or parsed
     * @throws UnsupportedEncodingException when the declared charset name is illegal or not supported
     *         by the JVM
     */
    private Charset declaredCharset(Part part) throws MessagingException, UnsupportedEncodingException {
        String contentType = part.getContentType();
        String charsetName = contentType == null
                ? null
                : new ContentType(contentType).getParameter(CHARSET_PARAMETER);
        if (charsetName == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            return Charset.forName(charsetName);
        } catch (IllegalArgumentException e) {
            UnsupportedEncodingException unsupported = new UnsupportedEncodingException(charsetName);
            unsupported.initCause(e);
            throw unsupported;
        }
    }
}
