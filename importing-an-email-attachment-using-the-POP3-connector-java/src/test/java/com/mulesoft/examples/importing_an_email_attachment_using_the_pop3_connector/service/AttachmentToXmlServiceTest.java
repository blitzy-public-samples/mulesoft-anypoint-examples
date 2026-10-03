package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import jakarta.mail.Message;

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

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.exception.NoAttachmentException;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.OrdersXmlMapper;

/**
 * Mockito unit tests of {@link AttachmentToXmlService#popToXmlFlow1(Message)}, the body of flow
 * {@code pop-to-xmlFlow1}
 * [importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml#flow:pop-to-xmlFlow1]:
 * the {@code logger} at {@code pop-to-xml.xml:27} and the {@code file:outbound-endpoint} at
 * {@code pop-to-xml.xml:28}.
 *
 * <p>{@link MailAttachmentReader} and the {@link Message} are Mockito mocks; the reader mock returns
 * the bytes of the classpath copy {@code /input.csv} and receives the message as an argument only.
 * The {@link OrdersXmlMapper} is a real instance created with {@code new}. The output directory is a
 * JUnit temporary directory, and a Logback {@link ListAppender} on the {@code AttachmentToXmlService}
 * logger, set to {@link Level#INFO} for each test, collects the logged events. No Spring application
 * context, mail server or network is used. The case set is D-688.
 *
 * <p>The cases asserted are: the returned path and the exact UTF-8 bytes of {@code orders.xml}; the
 * creation of a missing nested output directory; the truncation and replacement of a longer existing
 * {@code orders.xml}; exactly one INFO event {@code Received: <xml>}; and the unchanged propagation
 * of {@link NoAttachmentException} with no file written.
 *
 * <p>{@link #EXPECTED_XML} and the private helpers belong to this class and are not shared with any
 * other test class (D-004). These tests cover the {@code service} package under the JaCoCo LINE
 * covered ratio rule of at least 0.80 (D-049).
 */
@ExtendWith(MockitoExtension.class)
public class AttachmentToXmlServiceTest {

    /**
     * The DW-14 document for the sample {@code input.csv}: the declaration line, then the
     * {@code orders} element with two {@code order} elements, LF line feeds, two-space indentation
     * and no trailing line feed.
     */
    private static final String EXPECTED_XML =
          "<?xml version='1.0' encoding='UTF-8'?>\n"
        + "<orders>\n"
        + "  <order>\n"
        + "    <orderId>1</orderId>\n"
        + "    <name>aaa</name>\n"
        + "    <units>2.0</units>\n"
        + "    <pricePerUnit>10</pricePerUnit>\n"
        + "  </order>\n"
        + "  <order>\n"
        + "    <orderId>2</orderId>\n"
        + "    <name>bbb</name>\n"
        + "    <units>4.15</units>\n"
        + "    <pricePerUnit>5</pricePerUnit>\n"
        + "  </order>\n"
        + "</orders>";

    /** The name of the written file, the {@code outputPattern} of {@code pop-to-xml.xml:28}. */
    private static final String ORDERS_XML = "orders.xml";

    /** The classpath copy of the sample attachment, 57 bytes with LF line endings. */
    private static final String INPUT_CSV_RESOURCE = "/input.csv";

    /** The first-attachment reader, mocked. */
    @Mock
    private MailAttachmentReader reader;

    /** The received mail message, mocked and never stubbed. */
    @Mock
    private Message message;

    /** The DW-14 mapper, a real instance. */
    private final OrdersXmlMapper mapper = new OrdersXmlMapper();

    /** The output directory of the service under test. */
    @TempDir
    Path tempDir;

    /** The bytes of {@link #INPUT_CSV_RESOURCE}, returned by the reader mock. */
    private byte[] csv;

    /** The logger of {@code AttachmentToXmlService}. */
    private Logger logger;

    /** The level of {@link #logger} before the test; {@code null} when it inherits its level. */
    private Level savedLevel;

    /** The appender collecting the events of {@link #logger}. */
    private ListAppender<ILoggingEvent> appender;

    /**
     * Reads the sample attachment from the classpath, attaches a started {@link ListAppender} to the
     * service's logger and sets that logger to {@link Level#INFO}.
     *
     * @throws IOException if {@code /input.csv} cannot be read
     */
    @BeforeEach
    void setUp() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(INPUT_CSV_RESOURCE)) {
            csv = Objects.requireNonNull(in, "classpath resource " + INPUT_CSV_RESOURCE).readAllBytes();
        }
        logger = (Logger) LoggerFactory.getLogger(AttachmentToXmlService.class);
        savedLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Detaches and stops the appender and restores the logger's previous level. */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(savedLevel);
    }

    /**
     * Returns the service under test, writing {@code orders.xml} into {@code dir}.
     *
     * @param dir the output directory, passed as {@code file.output-path}
     * @return a service over the reader mock and the real mapper
     */
    private AttachmentToXmlService service(Path dir) {
        return new AttachmentToXmlService(reader, mapper, dir.toString(), ORDERS_XML);
    }

    /**
     * Returns the UTF-8 bytes of {@link #EXPECTED_XML}.
     *
     * @return the expected content of {@code orders.xml}
     */
    private static byte[] expectedBytes() {
        return EXPECTED_XML.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The sample attachment is written as {@code orders.xml} in the output directory, with the exact
     * UTF-8 bytes of the DW-14 document, and the returned path is that file.
     *
     * @throws IOException if the written file cannot be read
     */
    @Test
    @DisplayName("writes the DW-14 document to orders.xml and returns its path")
    public void writesOrdersXmlAndReturnsItsPath() throws IOException {
        when(reader.firstAttachment(message)).thenReturn(csv);

        Path written = service(tempDir).popToXmlFlow1(message);

        assertEquals(tempDir.resolve(ORDERS_XML), written);
        assertArrayEquals(expectedBytes(), Files.readAllBytes(written));
    }

    /**
     * An output directory that does not exist, nested two levels below an existing one, is created
     * with its missing parent before {@code orders.xml} is written into it.
     *
     * @throws IOException if the written file cannot be read
     */
    @Test
    @DisplayName("creates a missing nested output directory before writing orders.xml")
    public void createsMissingNestedOutputDirectory() throws IOException {
        Path dir = tempDir.resolve("a").resolve("b");
        assertTrue(Files.notExists(dir));
        when(reader.firstAttachment(message)).thenReturn(csv);

        service(dir).popToXmlFlow1(message);

        Path target = dir.resolve(ORDERS_XML);
        assertTrue(Files.exists(target));
        assertArrayEquals(expectedBytes(), Files.readAllBytes(target));
    }

    /**
     * An existing {@code orders.xml} longer than the new document is truncated and replaced by
     * exactly the new document's bytes.
     *
     * @throws IOException if the existing file cannot be written or the result cannot be read
     */
    @Test
    @DisplayName("truncates and overwrites an existing longer orders.xml")
    public void overwritesExistingOutputFile() throws IOException {
        Path target = tempDir.resolve(ORDERS_XML);
        String stale = "stale".repeat(200);
        Files.writeString(target, stale, StandardCharsets.UTF_8);
        assertTrue(stale.length() > EXPECTED_XML.length());
        when(reader.firstAttachment(message)).thenReturn(csv);

        service(tempDir).popToXmlFlow1(message);

        assertArrayEquals(expectedBytes(), Files.readAllBytes(target));
    }

    /**
     * Exactly one INFO event is logged on the service's logger, and its formatted message is
     * {@code Received: } followed by the DW-14 document.
     */
    @Test
    @DisplayName("logs Received: followed by the document once at INFO")
    public void logsReceivedXmlAtInfo() {
        when(reader.firstAttachment(message)).thenReturn(csv);

        service(tempDir).popToXmlFlow1(message);

        List<ILoggingEvent> infoEvents = appender.list.stream()
                .filter(event -> Level.INFO.equals(event.getLevel()))
                .toList();
        assertEquals(1, infoEvents.size());
        ILoggingEvent event = infoEvents.get(0);
        assertEquals("Received: " + EXPECTED_XML, event.getFormattedMessage());
    }

    /**
     * The {@link NoAttachmentException} thrown by the reader leaves the service as the same instance,
     * and no {@code orders.xml} is written.
     */
    @Test
    @DisplayName("propagates the reader's NoAttachmentException and writes no file")
    public void propagatesNoAttachmentExceptionWithoutWritingFile() {
        NoAttachmentException thrown = new NoAttachmentException("Message has no attachment");
        when(reader.firstAttachment(message)).thenThrow(thrown);

        NoAttachmentException ex = assertThrows(NoAttachmentException.class,
                () -> service(tempDir).popToXmlFlow1(message));

        assertSame(thrown, ex);
        assertTrue(Files.notExists(tempDir.resolve(ORDERS_XML)));
    }
}
