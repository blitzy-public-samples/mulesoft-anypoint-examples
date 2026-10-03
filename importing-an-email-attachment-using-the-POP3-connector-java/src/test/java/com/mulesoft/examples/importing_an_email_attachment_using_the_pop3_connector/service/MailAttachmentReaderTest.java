package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Properties;

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

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.exception.NoAttachmentException;

/**
 * Unit tests of {@link MailAttachmentReader#firstAttachment(Message)}, the re-implementation of the
 * {@code attachments-list} expression and the {@code #[payload[0].getContent()]} payload of flow
 * {@code pop-to-xmlFlow1} [importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml:7-10]
 * (D-034).
 *
 * <p>Each test calls a {@link MailAttachmentReader} created with {@code new}, with no Spring application
 * context, no mail server and no network. The selection tests build {@link MimeMessage} instances on a
 * {@link Session} created from empty {@link Properties}, write each one to bytes and read it back with
 * {@link MimeMessage#MimeMessage(Session, java.io.InputStream)}, and pass the parsed message to the
 * reader. The message and part builders are private static helpers of this class (D-004). The
 * remaining tests pass Mockito mocks of {@link Message}, {@link Multipart} and {@link BodyPart}; in
 * the failure-translation tests among them a stubbed method throws {@link MessagingException} or
 * {@link IOException}.
 *
 * <p>The cases asserted are: one, two and zero attachments; an inline text body before the
 * attachment; nested {@code multipart/*} parts; disposition and file-name selection; transfer
 * decoding with no charset conversion and no trimming; a single-part message; and the translation of
 * checked exceptions into {@link IllegalStateException} and {@link UncheckedIOException}, each
 * reached through {@link Message#getContent()} or a part read. The selection rule they pin is D-391;
 * the case set is D-576.
 *
 * <p>These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at
 * least 0.80 (D-049).
 */
public class MailAttachmentReaderTest {

    /** The attachment of the original example's mail: the sample {@code input.csv}, 57 bytes. */
    private static final String ORDERS_CSV =
        "orderId,name,units,pricePerUnit\n1,aaa,2.0,10\n2,bbb,4.15,5";

    /** A second CSV attachment, distinct from {@link #ORDERS_CSV}. */
    private static final String SECOND_CSV = "orderId,name,units,pricePerUnit\n9,zzz,1.0,1";

    /** The detail message of every {@link NoAttachmentException} the reader throws. */
    private static final String NO_ATTACHMENT_MESSAGE = "Message has no attachment";

    /** The file name of the original example's attachment. */
    private static final String REPORT_FILE_NAME = "report";

    private static final String OCTET_STREAM = "application/octet-stream";

    private static final Session SESSION = Session.getInstance(new Properties());

    private final MailAttachmentReader reader = new MailAttachmentReader();

    @Test
    @DisplayName("One application/octet-stream attachment named report yields the orders CSV bytes exactly")
    public void singleOctetStreamAttachmentNamedReportReturnsCsvBytesExactly() throws Exception {
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, utf8(ORDERS_CSV)));
        MimeMessage parsed = roundTrip(messageWith(content));

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
        assertEquals(57, attachment.length);
        assertEquals(ORDERS_CSV, new String(attachment, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Of two attachments only the first in MIME order is read")
    public void twoAttachmentsReturnsOnlyTheFirstInMimeOrder() throws Exception {
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, utf8(ORDERS_CSV)));
        content.addBodyPart(octetStreamAttachment("second.csv", utf8(SECOND_CSV)));
        MimeMessage parsed = roundTrip(messageWith(content));

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("An inline text body without a file name before the attachment is skipped")
    public void textBodyBeforeAttachmentIsSkippedAndAttachmentReturned() throws Exception {
        MimeBodyPart body = textBody("Please find the orders attached.");
        body.setDisposition(Part.INLINE);
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(body);
        content.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, utf8(ORDERS_CSV)));
        MimeMessage parsed = roundTrip(messageWith(content));
        BodyPart parsedBody = ((Multipart) parsed.getContent()).getBodyPart(0);
        assertEquals(Part.INLINE, parsedBody.getDisposition());
        assertNull(parsedBody.getFileName());

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("A nested multipart/alternative without attachment is searched and the later attachment returned")
    public void nestedMultipartWithoutAttachmentIsSearchedThenLaterAttachmentReturned() throws Exception {
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(nested(alternativeBodies()));
        content.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, utf8(ORDERS_CSV)));
        MimeMessage parsed = roundTrip(messageWith(content));

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("An attachment inside a nested multipart is returned before a later top-level attachment")
    public void attachmentInsideNestedMultipartIsReturnedBeforeLaterTopLevelAttachment() throws Exception {
        MimeMultipart inner = new MimeMultipart("mixed");
        inner.addBodyPart(textBody("Nested body."));
        inner.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, utf8(ORDERS_CSV)));
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(nested(alternativeBodies()));
        content.addBodyPart(nested(inner));
        content.addBodyPart(octetStreamAttachment("second.csv", utf8(SECOND_CSV)));
        MimeMessage parsed = roundTrip(messageWith(content));

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("A disposition of ATTACHMENT in upper case with no file name selects the part")
    public void upperCaseAttachmentDispositionWithoutFileNameIsSelected() throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(utf8(ORDERS_CSV), OCTET_STREAM)));
        part.setHeader("Content-Disposition", "ATTACHMENT");
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(textBody("Body."));
        content.addBodyPart(part);
        MimeMessage parsed = roundTrip(messageWith(content));

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("An inline part with a non-empty file name is selected as the attachment")
    public void inlinePartWithFileNameIsSelected() throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(utf8(ORDERS_CSV), OCTET_STREAM)));
        part.setHeader("Content-Disposition", "inline; filename=report");
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(textBody("Body."));
        content.addBodyPart(part);
        MimeMessage parsed = roundTrip(messageWith(content));
        BodyPart parsedPart = ((Multipart) parsed.getContent()).getBodyPart(1);
        assertEquals(Part.INLINE, parsedPart.getDisposition());
        assertEquals(REPORT_FILE_NAME, parsedPart.getFileName());

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("An inline part with an empty file name is skipped")
    public void inlinePartWithEmptyFileNameIsSkipped() throws Exception {
        MimeBodyPart emptyName = new MimeBodyPart();
        emptyName.setDataHandler(new DataHandler(new ByteArrayDataSource(utf8(SECOND_CSV), OCTET_STREAM)));
        emptyName.setHeader("Content-Disposition", "inline; filename=\"\"");
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(emptyName);
        content.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, utf8(ORDERS_CSV)));
        MimeMessage parsed = roundTrip(messageWith(content));
        assertEquals("", ((Multipart) parsed.getContent()).getBodyPart(0).getFileName());

        byte[] attachment = reader.firstAttachment(parsed);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("A base64 attachment is returned decoded with CR LF, NUL, 0xFF and trailing spaces unchanged")
    public void base64AttachmentIsDecodedWithBytesOtherwiseUnchanged() throws Exception {
        byte[] binary = {'a', ',', 'b', '\r', '\n', 0x00, (byte) 0xFF, (byte) 0x80, ' ', ' ', '\r', '\n'};
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(octetStreamAttachment(REPORT_FILE_NAME, binary));
        MimeMessage message = messageWith(content);
        String wire = new String(serialize(message), StandardCharsets.US_ASCII);
        assertTrue(wire.contains("Content-Transfer-Encoding: base64"));

        byte[] attachment = reader.firstAttachment(parse(serialize(message)));

        assertArrayEquals(binary, attachment);
    }

    @Test
    @DisplayName("A quoted-printable text/plain ISO-8859-1 attachment is returned as its ISO-8859-1 bytes")
    public void quotedPrintableLatin1TextAttachmentReturnsLatin1BytesWithoutCharsetConversion() throws Exception {
        String text = "1,caf\u00e9 cr\u00e8me,2.0,10";
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text, "ISO-8859-1");
        part.setHeader("Content-Transfer-Encoding", "quoted-printable");
        part.setFileName("orders-latin1.csv");
        part.setDisposition(Part.ATTACHMENT);
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(part);
        MimeMessage message = messageWith(content);
        String wire = new String(serialize(message), StandardCharsets.US_ASCII);
        assertTrue(wire.contains("text/plain; charset=ISO-8859-1"));
        assertTrue(wire.contains("caf=E9 cr=E8me"));

        byte[] attachment = reader.firstAttachment(parse(serialize(message)));

        assertArrayEquals(text.getBytes(StandardCharsets.ISO_8859_1), attachment);
        assertEquals(text, new String(attachment, StandardCharsets.ISO_8859_1));
        assertFalse(Arrays.equals(text.getBytes(StandardCharsets.UTF_8), attachment));
    }

    @Test
    @DisplayName("A single-part message whose whole body is an attachment throws NoAttachmentException")
    public void singlePartAttachmentMessageThrowsNoAttachmentException() throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setSubject("orders");
        message.setDataHandler(new DataHandler(new ByteArrayDataSource(utf8(ORDERS_CSV), OCTET_STREAM)));
        message.setFileName(REPORT_FILE_NAME);
        message.setDisposition(Part.ATTACHMENT);
        MimeMessage parsed = roundTrip(message);
        assertEquals(Part.ATTACHMENT, parsed.getDisposition());
        assertEquals(REPORT_FILE_NAME, parsed.getFileName());

        NoAttachmentException thrown =
            assertThrows(NoAttachmentException.class, () -> reader.firstAttachment(parsed));

        assertEquals(NO_ATTACHMENT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("A text-only message throws NoAttachmentException with message 'Message has no attachment'")
    public void textOnlyMessageThrowsNoAttachmentException() throws Exception {
        MimeMessage message = new MimeMessage(SESSION);
        message.setSubject("orders");
        message.setText("No attachment here.", "UTF-8");
        MimeMessage parsed = roundTrip(message);

        NoAttachmentException thrown =
            assertThrows(NoAttachmentException.class, () -> reader.firstAttachment(parsed));

        assertEquals(NO_ATTACHMENT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("A multipart message of text parts only, nested alternative included, throws NoAttachmentException")
    public void textOnlyMultipartThrowsNoAttachmentException() throws Exception {
        MimeMultipart content = new MimeMultipart();
        content.addBodyPart(textBody("First body."));
        content.addBodyPart(nested(alternativeBodies()));
        MimeMessage parsed = roundTrip(messageWith(content));

        NoAttachmentException thrown =
            assertThrows(NoAttachmentException.class, () -> reader.firstAttachment(parsed));

        assertEquals(NO_ATTACHMENT_MESSAGE, thrown.getMessage());
    }

    @Test
    @DisplayName("The message content is read once and the attachment stream is closed")
    public void messageContentReadOnceAndAttachmentStreamClosed() throws Exception {
        boolean[] closed = {false};
        ByteArrayInputStream stream = new ByteArrayInputStream(utf8(ORDERS_CSV)) {
            @Override
            public void close() throws IOException {
                closed[0] = true;
                super.close();
            }
        };
        BodyPart part = mock(BodyPart.class);
        when(part.getDisposition()).thenReturn(Part.ATTACHMENT);
        when(part.getInputStream()).thenReturn(stream);
        Message message = messageWithParts(part);

        byte[] attachment = reader.firstAttachment(message);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
        assertTrue(closed[0]);
        verify(message, times(1)).getContent();
    }

    @Test
    @DisplayName("A multipart/* part whose content is not a Multipart is tested as an attachment candidate")
    public void multipartTypedPartWithNonMultipartContentIsTestedAsAttachment() throws Exception {
        BodyPart part = mock(BodyPart.class);
        when(part.isMimeType("multipart/*")).thenReturn(true);
        when(part.getContent()).thenReturn("unparsed multipart body");
        when(part.getFileName()).thenReturn(REPORT_FILE_NAME);
        when(part.getInputStream()).thenReturn(new ByteArrayInputStream(utf8(ORDERS_CSV)));
        Message message = messageWithParts(part);

        byte[] attachment = reader.firstAttachment(message);

        assertArrayEquals(utf8(ORDERS_CSV), attachment);
    }

    @Test
    @DisplayName("A MessagingException from Message.getContent becomes IllegalStateException with its message and cause")
    public void messagingExceptionFromMessageContentBecomesIllegalStateException() throws Exception {
        MessagingException failure = new MessagingException("Folder is not Open");
        Message message = mock(Message.class);
        when(message.getContent()).thenThrow(failure);

        IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> reader.firstAttachment(message));

        assertEquals("Folder is not Open", thrown.getMessage());
        assertSame(failure, thrown.getCause());
        verify(message).getContent();
    }

    @Test
    @DisplayName("An IOException from Message.getContent becomes UncheckedIOException with it as cause")
    public void ioExceptionFromMessageContentBecomesUncheckedIoException() throws Exception {
        IOException failure = new IOException("Connection reset");
        Message message = mock(Message.class);
        when(message.getContent()).thenThrow(failure);

        UncheckedIOException thrown =
            assertThrows(UncheckedIOException.class, () -> reader.firstAttachment(message));

        assertSame(failure, thrown.getCause());
        assertEquals("java.io.IOException: Connection reset", thrown.getMessage());
        verify(message).getContent();
    }

    @Test
    @DisplayName("A MessagingException from Multipart.getBodyPart becomes IllegalStateException with its message and cause")
    public void messagingExceptionFromMultipartBodyPartBecomesIllegalStateException() throws Exception {
        MessagingException failure = new MessagingException("Unable to load BODYSTRUCTURE");
        Multipart multipart = mock(Multipart.class);
        when(multipart.getCount()).thenReturn(1);
        when(multipart.getBodyPart(0)).thenThrow(failure);
        Message message = mock(Message.class);
        when(message.getContent()).thenReturn(multipart);

        IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> reader.firstAttachment(message));

        assertEquals("Unable to load BODYSTRUCTURE", thrown.getMessage());
        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("A MessagingException from a part's disposition becomes IllegalStateException with its message and cause")
    public void messagingExceptionFromPartDispositionBecomesIllegalStateException() throws Exception {
        MessagingException failure = new MessagingException("Unable to load headers");
        BodyPart part = mock(BodyPart.class);
        when(part.getDisposition()).thenThrow(failure);
        Message message = messageWithParts(part);

        IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> reader.firstAttachment(message));

        assertEquals("Unable to load headers", thrown.getMessage());
        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("An IOException from a nested multipart part's content becomes UncheckedIOException with it as cause")
    public void ioExceptionFromNestedPartContentBecomesUncheckedIoException() throws Exception {
        IOException failure = new IOException("Premature end of stream");
        BodyPart part = mock(BodyPart.class);
        when(part.isMimeType("multipart/*")).thenReturn(true);
        when(part.getContent()).thenThrow(failure);
        Message message = messageWithParts(part);

        UncheckedIOException thrown =
            assertThrows(UncheckedIOException.class, () -> reader.firstAttachment(message));

        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("A MessagingException from the attachment's input stream becomes IllegalStateException with its message and cause")
    public void messagingExceptionFromAttachmentStreamBecomesIllegalStateException() throws Exception {
        MessagingException failure = new MessagingException("No content");
        BodyPart part = mock(BodyPart.class);
        when(part.getDisposition()).thenReturn(Part.ATTACHMENT);
        when(part.getInputStream()).thenThrow(failure);
        Message message = messageWithParts(part);

        IllegalStateException thrown =
            assertThrows(IllegalStateException.class, () -> reader.firstAttachment(message));

        assertEquals("No content", thrown.getMessage());
        assertSame(failure, thrown.getCause());
    }

    @Test
    @DisplayName("An IOException from the attachment's input stream becomes UncheckedIOException with it as cause")
    public void ioExceptionFromAttachmentStreamBecomesUncheckedIoException() throws Exception {
        IOException failure = new IOException("Error in encoded stream");
        BodyPart part = mock(BodyPart.class);
        when(part.getDisposition()).thenReturn(Part.ATTACHMENT);
        when(part.getInputStream()).thenThrow(failure);
        Message message = messageWithParts(part);

        UncheckedIOException thrown =
            assertThrows(UncheckedIOException.class, () -> reader.firstAttachment(message));

        assertSame(failure, thrown.getCause());
    }

    /**
     * Returns a part of type {@code application/octet-stream} holding {@code data}, with
     * {@code Content-Disposition: attachment; filename=<fileName>} and base64 transfer encoding.
     */
    private static MimeBodyPart octetStreamAttachment(String fileName, byte[] data) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(data, OCTET_STREAM)));
        part.setHeader("Content-Transfer-Encoding", "base64");
        part.setFileName(fileName);
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }

    /** Returns an inline {@code text/plain; charset=UTF-8} part with no disposition and no file name. */
    private static MimeBodyPart textBody(String text) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setText(text, "UTF-8");
        return part;
    }

    /** Returns a {@code multipart/alternative} of a {@code text/plain} and a {@code text/html} body. */
    private static MimeMultipart alternativeBodies() throws MessagingException {
        MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>Orders</p>", "UTF-8", "html");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(textBody("Orders"));
        alternative.addBodyPart(html);
        return alternative;
    }

    /** Returns a body part whose content is {@code multipart}. */
    private static MimeBodyPart nested(MimeMultipart multipart) throws MessagingException {
        MimeBodyPart part = new MimeBodyPart();
        part.setContent(multipart);
        return part;
    }

    /** Returns a message with subject {@code orders} whose content is {@code content}. */
    private static MimeMessage messageWith(MimeMultipart content) throws MessagingException {
        MimeMessage message = new MimeMessage(SESSION);
        message.setSubject("orders");
        message.setContent(content);
        return message;
    }

    /** Returns {@code message} written to bytes and parsed back into a new {@link MimeMessage}. */
    private static MimeMessage roundTrip(MimeMessage message) throws MessagingException, IOException {
        return parse(serialize(message));
    }

    /** Returns the RFC 822 bytes {@link MimeMessage#writeTo(java.io.OutputStream)} writes for {@code message}. */
    private static byte[] serialize(MimeMessage message) throws MessagingException, IOException {
        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        return out.toByteArray();
    }

    /** Returns the {@link MimeMessage} parsed from {@code bytes} on {@link #SESSION}. */
    private static MimeMessage parse(byte[] bytes) throws MessagingException {
        return new MimeMessage(SESSION, new ByteArrayInputStream(bytes));
    }

    /** Returns a mocked {@link Message} whose content is a mocked {@link Multipart} of {@code parts}. */
    private static Message messageWithParts(BodyPart... parts) throws MessagingException, IOException {
        Multipart multipart = mock(Multipart.class);
        when(multipart.getCount()).thenReturn(parts.length);
        for (int i = 0; i < parts.length; i++) {
            when(multipart.getBodyPart(i)).thenReturn(parts[i]);
        }
        Message message = mock(Message.class);
        when(message.getContent()).thenReturn(multipart);
        return message;
    }

    /** Returns the UTF-8 bytes of {@code text}. */
    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
