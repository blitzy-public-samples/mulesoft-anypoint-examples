package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.exception.NoAttachmentException;
import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.OrdersXmlMapper;

/**
 * Mockito unit tests for {@link AttachmentToXmlService#imapToCsvFlow1(Message)}, the body of flow
 * {@code imap-to-csvFlow1} [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:9-33]
 * (D-057, D-049, D-583).
 *
 * <p>{@link MailAttachmentReader} and {@link OrdersXmlMapper} are Mockito mocks, the output directory is a JUnit
 * temporary directory, and no Spring context, mail server or network is used. A Logback {@link ListAppender} on the
 * {@code AttachmentToXmlService} logger collects the {@code Received:} events of the {@code logger} at
 * {@code imap-to-xml.xml:31}; the written file stands for the {@code file:outbound-endpoint} at
 * {@code imap-to-xml.xml:32}.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentToXmlServiceTest {

    /** Name of the written file, the {@code outputPattern} of {@code imap-to-xml.xml:32}. */
    private static final String ORDERS_XML = "orders.xml";

    /** Attachment text returned by the reader mock. */
    private static final String CSV = "csv";

    /** XML document returned by the mapper mock. */
    private static final String XML = "<xml/>";

    /** Mock of the first-attachment reader. */
    @Mock
    private MailAttachmentReader reader;

    /** Mock of the DW-13 mapper. */
    @Mock
    private OrdersXmlMapper mapper;

    /** Output directory of the service under test. */
    @TempDir
    Path dir;

    /** Mail passed through to the reader. */
    private Message msg;

    /** Service under test, writing to {@link #dir}. */
    private AttachmentToXmlService service;

    /** Logger of {@code AttachmentToXmlService}. */
    private Logger logger;

    /** Level of {@link #logger} before the test; may be {@code null}. */
    private Level savedLevel;

    /** Appender collecting the events of {@link #logger}. */
    private ListAppender<ILoggingEvent> appender;

    /** Builds the mail, the service and the log appender. */
    @BeforeEach
    void setUp() {
        msg = new MimeMessage(Session.getInstance(new Properties()));
        service = new AttachmentToXmlService(reader, mapper, dir.toString(), ORDERS_XML);
        logger = (Logger) LoggerFactory.getLogger(AttachmentToXmlService.class);
        savedLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Detaches the appender and restores the logger level. */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(savedLevel);
    }

    /**
     * Returns the collected events whose formatted message starts with {@code Received:}.
     *
     * @return the {@code Received:} events, in logging order
     */
    private List<ILoggingEvent> receivedEvents() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().startsWith("Received:"))
                .toList();
    }

    /**
     * Returns the entries of {@code directory}.
     *
     * @param directory an existing directory
     * @return its entries
     * @throws IOException if the directory cannot be listed
     */
    private static List<Path> entries(Path directory) throws IOException {
        try (Stream<Path> stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    /** The mapped XML is written as {@code orders.xml} and logged once at INFO as {@code Received: <xml>}. */
    @Test
    @DisplayName("writes the mapped XML to orders.xml and logs it once at INFO")
    void writesMappedXmlAndLogsIt() throws IOException {
        when(reader.firstAttachment(msg)).thenReturn(CSV);
        when(mapper.toOrdersXml(CSV)).thenReturn(XML);

        service.imapToCsvFlow1(msg);

        assertArrayEquals(XML.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(dir.resolve(ORDERS_XML)));
        List<ILoggingEvent> received = receivedEvents();
        assertEquals(1, received.size());
        assertEquals(Level.INFO, received.get(0).getLevel());
        assertEquals("Received: " + XML, received.get(0).getFormattedMessage());
        verify(reader).firstAttachment(msg);
        verify(mapper).toOrdersXml(CSV);
        assertEquals(List.of(dir.resolve(ORDERS_XML)), entries(dir));
    }

    /** A document with a non-ASCII character is written as its UTF-8 bytes. */
    @Test
    @DisplayName("writes non-ASCII XML as UTF-8 bytes")
    void writesNonAsciiXmlAsUtf8() throws IOException {
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n<orders>\n  <order>\n    <name>café</name>\n"
                + "  </order>\n</orders>";
        when(reader.firstAttachment(msg)).thenReturn(CSV);
        when(mapper.toOrdersXml(CSV)).thenReturn(xml);

        service.imapToCsvFlow1(msg);

        assertArrayEquals(xml.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(dir.resolve(ORDERS_XML)));
    }

    /** An existing, longer {@code orders.xml} is replaced by exactly the new document. */
    @Test
    @DisplayName("overwrites an existing orders.xml")
    void overwritesExistingFile() throws IOException {
        Files.writeString(dir.resolve(ORDERS_XML), "previous content that is much longer than the new xml",
                StandardCharsets.UTF_8);
        when(reader.firstAttachment(msg)).thenReturn(CSV);
        when(mapper.toOrdersXml(CSV)).thenReturn(XML);

        service.imapToCsvFlow1(msg);

        assertArrayEquals(XML.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(dir.resolve(ORDERS_XML)));
    }

    /** {@link NoAttachmentException} of the reader propagates; the mapper is not called and nothing is written. */
    @Test
    @DisplayName("propagates NoAttachmentException without mapping, logging or writing")
    void propagatesNoAttachmentException() throws IOException {
        when(reader.firstAttachment(msg)).thenThrow(new NoAttachmentException());

        assertThrows(NoAttachmentException.class, () -> service.imapToCsvFlow1(msg));

        verifyNoInteractions(mapper);
        assertTrue(entries(dir).isEmpty());
        assertTrue(receivedEvents().isEmpty());
    }

    /** An exception of the mapper propagates; nothing is logged and nothing is written. */
    @Test
    @DisplayName("propagates a mapper failure without logging or writing")
    void propagatesMapperFailure() throws IOException {
        when(reader.firstAttachment(msg)).thenReturn(CSV);
        when(mapper.toOrdersXml(CSV)).thenThrow(new IllegalArgumentException("bad csv"));

        assertThrows(IllegalArgumentException.class, () -> service.imapToCsvFlow1(msg));

        assertTrue(entries(dir).isEmpty());
        assertTrue(receivedEvents().isEmpty());
    }

    /** An output path naming an existing regular file makes the write fail with {@link UncheckedIOException}. */
    @Test
    @DisplayName("throws UncheckedIOException when the output directory cannot be created")
    void throwsUncheckedIoExceptionOnWriteFailure() throws IOException {
        Path blocker = dir.resolve("blocker");
        Files.writeString(blocker, "x", StandardCharsets.UTF_8);
        AttachmentToXmlService blocked = new AttachmentToXmlService(reader, mapper, blocker.toString(), ORDERS_XML);
        when(reader.firstAttachment(msg)).thenReturn(CSV);
        when(mapper.toOrdersXml(CSV)).thenReturn(XML);

        assertThrows(UncheckedIOException.class, () -> blocked.imapToCsvFlow1(msg));

        assertEquals("x", Files.readString(blocker, StandardCharsets.UTF_8));
    }

    /** A missing output directory and its missing parent are created before the file is written. */
    @Test
    @DisplayName("creates a missing output directory")
    void createsMissingOutputDirectory() throws IOException {
        Path nested = dir.resolve("nested").resolve("output");
        AttachmentToXmlService nestedService = new AttachmentToXmlService(reader, mapper, nested.toString(),
                ORDERS_XML);
        when(reader.firstAttachment(msg)).thenReturn(CSV);
        when(mapper.toOrdersXml(CSV)).thenReturn(XML);

        nestedService.imapToCsvFlow1(msg);

        assertTrue(Files.isDirectory(nested));
        assertArrayEquals(XML.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(nested.resolve(ORDERS_XML)));
    }
}
