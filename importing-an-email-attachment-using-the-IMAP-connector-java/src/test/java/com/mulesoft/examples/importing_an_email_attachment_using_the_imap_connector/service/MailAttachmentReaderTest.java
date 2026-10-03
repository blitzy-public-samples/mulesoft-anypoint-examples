package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.activation.DataHandler;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.exception.NoAttachmentException;

/**
 * Unit tests for {@link MailAttachmentReader#firstAttachment(Message)} (D-395, D-121)
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:11-15].
 *
 * <p>Each test calls a {@link MailAttachmentReader} created with {@code new}, with no Spring application context,
 * no mail server and no file access. The message-structure tests build {@link MimeMessage}s in a
 * {@code Session.getInstance(new Properties())} session, serialise each one with {@link MimeMessage#writeTo}, parse
 * the bytes with {@code new MimeMessage(session, InputStream)} and read the parsed copy. The exception-translation
 * tests read {@link Message}s created with {@code Mockito.mock}.
 *
 * <p>The tests of {@code text/*} attachments compare the result with each CRLF replaced by LF. The
 * {@code application/octet-stream} attachments are compared exactly.
 *
 * <p>These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at least 0.80
 * (D-049).
 */
public class MailAttachmentReaderTest {

    /** Text of the sample attachment {@code input.csv}, with LF line endings and no trailing newline. */
    private static final String SAMPLE_CSV = "orderId,name,units,pricePerUnit\n1,aaa,2.0,10\n2,bbb,4.15,5";

    /** Text of a second attachment, distinct from {@link #SAMPLE_CSV}. */
    private static final String SECOND_CSV = "orderId,name,units,pricePerUnit\n3,ccc,1.0,7";

    /** Content type of the attachment part the original IT sends. */
    private static final String OCTET_STREAM = "application/octet-stream";

    /** Text holding the non-ASCII character {@code é}. */
    private static final String ACCENTED = "name\ncafé";

    /** Message of {@link NoAttachmentException}. */
    private static final String NO_ATTACHMENT_MESSAGE = "Mail message has no attachment";

    /** Mail session with no properties, used to build and parse the messages. */
    private final Session session = Session.getInstance(new Properties());

    /** Unit under test. */
    private final MailAttachmentReader reader = new MailAttachmentReader();

    /**
     * Serialises {@code message} with {@link MimeMessage#writeTo} and parses the bytes into a new message.
     *
     * @param message the message to serialise
     * @return the parsed copy
     * @throws MessagingException if the message cannot be serialised or parsed
     * @throws IOException if the message bytes cannot be written
     */
    private MimeMessage roundTrip(MimeMessage message) throws MessagingException, IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return new MimeMessage(session, new ByteArrayInputStream(out.toByteArray()));
    }

    /**
     * Builds a {@code multipart/mixed} message holding {@code parts} in the given order and returns its parsed copy.
     *
     * @param parts the body parts, in MIME order
     * @return the parsed copy of the message
     * @throws MessagingException if the message cannot be built, serialised or parsed
     * @throws IOException if the message bytes cannot be written
     */
    private MimeMessage multipartMessage(BodyPart... parts) throws MessagingException, IOException {
        MimeMultipart multipart = new MimeMultipart();
        for (BodyPart part : parts) {
            multipart.addBodyPart(part);
        }
        MimeMessage message = new MimeMessage(session);
        message.setContent(multipart);
        return roundTrip(message);
    }

    /**
     * Builds a body part holding {@code content} with the content type {@code contentType} and the file name
     * {@code fileName}; {@link MimeBodyPart#setFileName} also sets the {@code attachment} disposition.
     *
     * @param fileName the file name of the part
     * @param content the bytes of the part
     * @param contentType the content type of the part
     * @return the body part
     * @throws MessagingException if the part cannot be built
     */
    private static MimeBodyPart filePart(String fileName, byte[] content, String contentType)
            throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(content, contentType)));
        part.setFileName(fileName);
        return part;
    }

    /**
     * Builds the {@code application/octet-stream} body part named {@code report} that the original IT sends.
     *
     * @param text the text of the part, written as UTF-8
     * @return the body part
     * @throws MessagingException if the part cannot be built
     */
    private static MimeBodyPart reportPart(String text) throws MessagingException {
        return filePart("report", text.getBytes(StandardCharsets.UTF_8), OCTET_STREAM);
    }

    /**
     * Builds a {@code text/plain; charset=UTF-8} body part with no disposition and no file name.
     *
     * @param text the text of the part
     * @return the body part
     * @throws MessagingException if the part cannot be built
     */
    private static MimeBodyPart textBody(String text) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text, "UTF-8");
        return part;
    }

    /**
     * Replaces every CRLF in {@code text} with LF.
     *
     * @param text the text to normalise
     * @return the text with LF line endings
     */
    private static String lf(String text) {
        return text.replace("\r\n", "\n");
    }

    @Test
    @DisplayName("returns the application/octet-stream attachment named report exactly")
    public void returnsOctetStreamReportAttachmentExactly() throws Exception {
        MimeMessage message = multipartMessage(reportPart(SAMPLE_CSV));

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, result);
    }

    @Test
    @DisplayName("returns only the first of two attachments")
    public void returnsFirstOfTwoAttachments() throws Exception {
        MimeMessage message = multipartMessage(
                reportPart(SAMPLE_CSV),
                filePart("second.csv", SECOND_CSV.getBytes(StandardCharsets.UTF_8), OCTET_STREAM));

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, result);
    }

    @Test
    @DisplayName("skips a text body that precedes the attachment")
    public void skipsTextBodyBeforeAttachment() throws Exception {
        MimeMessage message = multipartMessage(textBody("The orders are attached."), reportPart(SAMPLE_CSV));

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, result);
    }

    @Test
    @DisplayName("returns an attachment of a nested multipart before a later attachment of the outer multipart")
    public void returnsNestedAttachmentBeforeLaterOuterAttachment() throws Exception {
        MimeMultipart inner = new MimeMultipart("mixed");
        inner.addBodyPart(textBody("Inner body."));
        inner.addBodyPart(reportPart(SAMPLE_CSV));
        MimeBodyPart innerHolder = new MimeBodyPart();
        innerHolder.setContent(inner);
        MimeMessage message = multipartMessage(
                textBody("Outer body."),
                innerHolder,
                filePart("second.csv", SECOND_CSV.getBytes(StandardCharsets.UTF_8), OCTET_STREAM));

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, result);
    }

    @Test
    @DisplayName("decodes a text/plain attachment in its declared ISO-8859-1 charset")
    public void decodesTextPlainAttachmentInDeclaredIso88591Charset() throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(ACCENTED, "ISO-8859-1");
        part.setFileName("notes.txt");
        MimeMessage message = multipartMessage(part);

        String result = reader.firstAttachment(message);

        assertTrue(result.contains("é"), result);
        assertEquals(ACCENTED, lf(result));
    }

    @Test
    @DisplayName("decodes a text/csv attachment in the charset parameter of its Content-Type")
    public void decodesTextCsvAttachmentInCharsetParameter() throws Exception {
        MimeMessage message = multipartMessage(
                filePart("orders.csv", ACCENTED.getBytes(StandardCharsets.ISO_8859_1), "text/csv; charset=ISO-8859-1"));

        String result = reader.firstAttachment(message);

        assertEquals(ACCENTED, lf(result));
    }

    @Test
    @DisplayName("decodes a text/csv attachment without a charset parameter as UTF-8")
    public void decodesTextCsvAttachmentWithoutCharsetAsUtf8() throws Exception {
        MimeBodyPart part = filePart("orders.csv", ACCENTED.getBytes(StandardCharsets.UTF_8), "text/csv");
        part.setHeader("Content-Type", "text/csv; name=orders.csv");
        MimeMessage message = multipartMessage(part);
        BodyPart parsed = ((Multipart) message.getContent()).getBodyPart(0);
        assertEquals("text/csv; name=orders.csv", parsed.getContentType());

        String result = reader.firstAttachment(message);

        assertEquals(ACCENTED, lf(result));
    }

    @Test
    @DisplayName("throws UncheckedIOException wrapping UnsupportedEncodingException for an unknown charset")
    public void throwsUncheckedIoExceptionForUnsupportedCharset() throws Exception {
        MimeMessage message = multipartMessage(filePart(
                "orders.csv", SAMPLE_CSV.getBytes(StandardCharsets.UTF_8), "text/csv; charset=x-no-such-charset"));

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> reader.firstAttachment(message));

        UnsupportedEncodingException cause = assertInstanceOf(UnsupportedEncodingException.class, thrown.getCause());
        assertEquals("x-no-such-charset", cause.getMessage());
    }

    @Test
    @DisplayName("returns the text of a single-part message with an attachment disposition and a file name")
    public void returnsTextOfSinglePartAttachmentMessage() throws Exception {
        MimeMessage built = new MimeMessage(session);
        built.setText(SAMPLE_CSV, "UTF-8");
        built.setDisposition(Part.ATTACHMENT);
        built.setFileName("input.csv");
        MimeMessage message = roundTrip(built);

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, lf(result));
    }

    @Test
    @DisplayName("treats a part with an inline disposition and a file name as an attachment")
    public void treatsInlinePartWithFileNameAsAttachment() throws Exception {
        MimeBodyPart part = reportPart(SAMPLE_CSV);
        part.setDisposition(Part.INLINE);
        MimeMessage message = multipartMessage(textBody("Inline report follows."), part);
        BodyPart parsed = ((Multipart) message.getContent()).getBodyPart(1);
        assertEquals(Part.INLINE, parsed.getDisposition());
        assertEquals("report", parsed.getFileName());

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, result);
    }

    @Test
    @DisplayName("treats an upper-case ATTACHMENT disposition without a file name as an attachment")
    public void treatsUpperCaseAttachmentDispositionAsAttachment() throws Exception {
        MimeBodyPart part = textBody(SAMPLE_CSV);
        part.setHeader("Content-Disposition", "ATTACHMENT");
        MimeMessage message = multipartMessage(textBody("Body text."), part);
        BodyPart parsed = ((Multipart) message.getContent()).getBodyPart(1);
        assertEquals("ATTACHMENT", parsed.getDisposition());
        assertNull(parsed.getFileName());

        String result = reader.firstAttachment(message);

        assertEquals(SAMPLE_CSV, lf(result));
    }

    @Test
    @DisplayName("throws NoAttachmentException for a single-part text message")
    public void throwsNoAttachmentExceptionForSinglePartTextMessage() throws Exception {
        MimeMessage built = new MimeMessage(session);
        built.setText("No orders today.", "UTF-8");
        MimeMessage message = roundTrip(built);

        NoAttachmentException thrown =
                assertThrows(NoAttachmentException.class, () -> reader.firstAttachment(message));

        assertEquals(NO_ATTACHMENT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("throws NoAttachmentException for a multipart message holding only a text body")
    public void throwsNoAttachmentExceptionForMultipartWithOnlyTextBody() throws Exception {
        MimeMessage message = multipartMessage(textBody("No orders today."));

        NoAttachmentException thrown =
                assertThrows(NoAttachmentException.class, () -> reader.firstAttachment(message));

        assertEquals(NO_ATTACHMENT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("throws NoAttachmentException when the only non-body part is inline without a file name")
    public void throwsNoAttachmentExceptionForInlinePartWithoutFileName() throws Exception {
        MimeBodyPart inline = textBody("Inline note.");
        inline.setDisposition(Part.INLINE);
        MimeMessage message = multipartMessage(textBody("Body text."), inline);

        NoAttachmentException thrown =
                assertThrows(NoAttachmentException.class, () -> reader.firstAttachment(message));

        assertEquals(NO_ATTACHMENT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("throws NullPointerException for a null message")
    public void throwsNullPointerExceptionForNullMessage() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () -> reader.firstAttachment(null));

        assertEquals("message", thrown.getMessage());
    }

    @Test
    @DisplayName("wraps a MessagingException from the message structure in IllegalStateException")
    public void wrapsMessagingExceptionInIllegalStateException() throws Exception {
        Message message = mock(Message.class);
        MessagingException failure = new MessagingException("folder closed");
        when(message.isMimeType("multipart/*")).thenThrow(failure);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> reader.firstAttachment(message));

        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("wraps an IOException from the message content in UncheckedIOException")
    public void wrapsIoExceptionInUncheckedIoException() throws Exception {
        Message message = mock(Message.class);
        IOException failure = new IOException("connection reset");
        when(message.isMimeType("multipart/*")).thenReturn(true);
        when(message.getContent()).thenThrow(failure);

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> reader.firstAttachment(message));

        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("throws IllegalStateException for a multipart message whose content is not a Multipart")
    public void throwsIllegalStateExceptionForMultipartTypeWithoutMultipartContent() throws Exception {
        Message message = mock(Message.class);
        when(message.isMimeType("multipart/*")).thenReturn(true);
        when(message.getContent()).thenReturn("plain text");
        when(message.getContentType()).thenReturn("multipart/mixed");

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> reader.firstAttachment(message));

        MessagingException cause = assertInstanceOf(MessagingException.class, thrown.getCause());
        assertEquals("Part of type multipart/mixed does not hold multipart content", cause.getMessage());
    }

    @Test
    @DisplayName("decodes a text stream as UTF-8 when the part declares no content type and closes the stream")
    public void decodesTextStreamWithoutContentTypeAsUtf8AndClosesIt() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ByteArrayInputStream stream = new ByteArrayInputStream(ACCENTED.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };
        Message message = mock(Message.class);
        when(message.getDisposition()).thenReturn(Part.ATTACHMENT);
        when(message.isMimeType("text/*")).thenReturn(true);
        when(message.getContent()).thenReturn(stream);
        when(message.getContentType()).thenReturn(null);

        String result = reader.firstAttachment(message);

        assertEquals(ACCENTED, result);
        assertTrue(closed.get(), "content stream closed");
    }
}
