package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import jakarta.mail.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.mapper.OrdersXmlMapper;

/**
 * Runs flow {@code pop-to-xmlFlow1} of {@code pop-to-xml.xml} (:5-29) for one received mail message:
 * it reads the first attachment, maps it to the {@code orders} XML document, logs the document and
 * writes it to a file.
 *
 * <p><b>Steps</b>, in this order:
 *
 * <ol>
 *   <li>{@link MailAttachmentReader#firstAttachment(Message)} returns the bytes of the first
 *       attachment: the {@code attachments-list} expression and {@code #[payload[0].getContent()]}
 *       (:7-10);</li>
 *   <li>{@link OrdersXmlMapper#toOrdersXml(byte[])} maps those bytes to the XML text: DW-14
 *       (:11-26). The MEL expressions and the DataWeave script are re-implemented in Java
 *       (D-034);</li>
 *   <li>the text is logged at INFO on this class's logger as {@code Received: } followed by the
 *       text: the {@code logger} (:27);</li>
 *   <li>the text is written, unchanged and UTF-8 encoded, to the file named by
 *       {@code file.output-pattern} (default {@code orders.xml}) inside the directory
 *       {@code file.output-path} (default {@code src/test/resources/output}): the
 *       {@code file:outbound-endpoint} (:28). A relative directory resolves against the working
 *       directory of the JVM. Missing directories are created, and an existing file is truncated
 *       and overwritten. The write runs on the calling thread and has no time limit (D-099).</li>
 * </ol>
 *
 * <p><b>Failures.</b> An exception thrown by the reader or the mapper, among them
 * {@link com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.exception.NoAttachmentException
 * NoAttachmentException} for a message without an attachment, propagates unchanged; this class then
 * logs nothing and writes no file. An {@link IOException} raised while creating the directory or
 * writing the file is rethrown as {@link UncheckedIOException} with the original as its cause, after
 * the INFO entry of step 3 has been logged. The caller, {@code scheduler.Pop3AttachmentPoller},
 * flags each message DELETED and expunges it before calling {@link #popToXmlFlow1(Message)}, and
 * logs a failure at ERROR.
 *
 * <p>The message type is the Jakarta Mail API of {@code spring-boot-starter-mail} (D-063). The
 * service holds no mutable state; concurrent calls write to the same file without coordination.
 * This class is the POP3 project's own copy and shares no code with any other project (D-004).
 *
 * <p>Usage:
 *
 * <pre>{@code
 * AttachmentToXmlService service = new AttachmentToXmlService(
 *         new MailAttachmentReader(), new OrdersXmlMapper(), "src/test/resources/output", "orders.xml");
 * Path written = service.popToXmlFlow1(message);   // src/test/resources/output/orders.xml
 * }</pre>
 */
@Service
public class AttachmentToXmlService {

    /** Logger of the flow's {@code Received: <xml>} INFO entry, named after this class. */
    private static final Logger LOG = LoggerFactory.getLogger(AttachmentToXmlService.class);

    /** Reads the first attachment of each message. */
    private final MailAttachmentReader reader;

    /** Maps the attachment bytes to the DW-14 {@code orders} XML text. */
    private final OrdersXmlMapper mapper;

    /** Output directory, the property {@code file.output-path}, as configured. */
    private final String outputPath;

    /** Output file name, the property {@code file.output-pattern}, as configured. */
    private final String outputPattern;

    /**
     * Creates the service.
     *
     * @param reader        reads the first attachment of a received message
     * @param mapper        maps the attachment bytes to the {@code orders} XML text
     * @param outputPath    directory that receives the file, the property {@code file.output-path}
     *                      (default {@code src/test/resources/output}); a relative path resolves
     *                      against the working directory of the JVM when a message is processed
     * @param outputPattern name of the written file, the property {@code file.output-pattern}
     *                      (default {@code orders.xml})
     * @throws NullPointerException if any argument is {@code null}
     */
    public AttachmentToXmlService(MailAttachmentReader reader, OrdersXmlMapper mapper,
            @Value("${file.output-path:src/test/resources/output}") String outputPath,
            @Value("${file.output-pattern:orders.xml}") String outputPattern) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.outputPath = Objects.requireNonNull(outputPath, "outputPath");
        this.outputPattern = Objects.requireNonNull(outputPattern, "outputPattern");
    }

    /**
     * Processes one received mail message as flow {@code pop-to-xmlFlow1} does (pop-to-xml.xml:5-29):
     * reads its first attachment, maps it to the {@code orders} XML text, logs
     * {@code Received: <xml>} at INFO and writes the text as UTF-8 to
     * {@code <file.output-path>/<file.output-pattern>}, creating missing directories and overwriting
     * an existing file.
     *
     * @param message the received mail message; it is passed unchanged to the reader
     * @return the path of the written file: {@code file.output-path} resolved with
     *         {@code file.output-pattern}, relative when {@code file.output-path} is relative
     * @throws com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.exception.NoAttachmentException
     *         if the message has no attachment part, as thrown by the reader; no file is written
     * @throws IllegalStateException if the reader cannot read the message or the mapper cannot write
     *         the XML document, as thrown by them; no file is written
     * @throws IllegalArgumentException if the mapper's CSV reader rejects the attachment's header, as
     *         thrown by the mapper; no file is written
     * @throws UncheckedIOException if the reader or the mapper fails with an I/O error, as thrown by
     *         them, or if the directory cannot be created or the file cannot be written, wrapping the
     *         {@link IOException} as cause
     * @throws java.nio.file.InvalidPathException if {@code file.output-path} or
     *         {@code file.output-pattern} is not a valid path string
     */
    public Path popToXmlFlow1(Message message) {
        byte[] csv = reader.firstAttachment(message);
        String xml = mapper.toOrdersXml(csv);
        LOG.info("Received: {}", xml);
        return write(xml);
    }

    /**
     * Writes {@code xml} unchanged as UTF-8 to {@code outputPattern} inside {@code outputPath},
     * creating the directory and its missing parents first, and truncating an existing file.
     *
     * @param xml the XML text to write
     * @return the path of the written file
     * @throws UncheckedIOException wrapping the {@link IOException} raised while creating the
     *         directory or writing the file
     */
    private Path write(String xml) {
        try {
            Path dir = Path.of(outputPath);
            Files.createDirectories(dir);
            Path target = dir.resolve(outputPattern);
            Files.writeString(target, xml, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
