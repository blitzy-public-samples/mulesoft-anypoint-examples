package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.mail.Message;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.mapper.OrdersXmlMapper;

/**
 * Flow body of {@code imap-to-csvFlow1}
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:9-33]: first
 * attachment, DW-13 XML, INFO log ({@code :31}), {@code orders.xml} in the output directory
 * ({@code :32}) (D-034, D-063, D-107).
 *
 * <p>{@code scheduler.ImapAttachmentPoller.imapToCsvFlow1()} calls {@link #imapToCsvFlow1(Message)} once
 * per received mail. The output directory is {@code file.output-path} (default
 * {@code src/test/resources/output}) and the file name is {@code file.output-pattern} (default
 * {@code orders.xml}), the {@code path} and {@code outputPattern} attributes of the
 * {@code file:outbound-endpoint} at {@code imap-to-xml.xml:32}; a relative directory resolves against
 * the JVM working directory.
 *
 * <p>The log category is this class: each processed mail yields one INFO event whose formatted message
 * is {@code Received: } followed by the XML document, the {@code logger} message
 * {@code Received: #[message.payloadAs(java.lang.String)]} at {@code imap-to-xml.xml:31}.
 *
 * <p>Instances hold no mutable state: the output location is fixed at construction and both
 * collaborators are stateless. Every call writes to the same file.
 *
 * <pre>{@code
 * AttachmentToXmlService service = new AttachmentToXmlService(
 *         new MailAttachmentReader(), new OrdersXmlMapper(), "src/test/resources/output", "orders.xml");
 * service.imapToCsvFlow1(message);
 * // src/test/resources/output/orders.xml now holds the XML mapped from the first attachment
 * }</pre>
 */
@Service
public class AttachmentToXmlService {

    /** Logger of the {@code Received:} message of {@code imap-to-xml.xml:31}. */
    private static final Logger log = LoggerFactory.getLogger(AttachmentToXmlService.class);

    /** Reader of the first attachment, {@code imap-to-xml.xml:11-15}. */
    private final MailAttachmentReader reader;

    /** Mapper of DW-13, {@code imap-to-xml.xml:17-29}. */
    private final OrdersXmlMapper mapper;

    /**
     * Output directory, the {@code path} of the {@code file:outbound-endpoint} at
     * {@code imap-to-xml.xml:32}.
     */
    private final String outputPath;

    /**
     * Output file name, the {@code outputPattern} of the {@code file:outbound-endpoint} at
     * {@code imap-to-xml.xml:32}.
     */
    private final String outputPattern;

    /**
     * Creates the service over its collaborators and the output location.
     *
     * @param reader        reader of the first attachment of a mail
     * @param mapper        mapper of the CSV attachment text to the DW-13 orders XML document
     * @param outputPath    {@code file.output-path}: the directory {@code orders.xml} is written to,
     *                      absolute or relative to the JVM working directory
     * @param outputPattern {@code file.output-pattern}: the name of the written file
     */
    public AttachmentToXmlService(MailAttachmentReader reader,
                                  OrdersXmlMapper mapper,
                                  @Value("${file.output-path}") String outputPath,
                                  @Value("${file.output-pattern}") String outputPattern) {
        this.reader = reader;
        this.mapper = mapper;
        this.outputPath = outputPath;
        this.outputPattern = outputPattern;
    }

    /**
     * Runs the body of flow {@code imap-to-csvFlow1} for one received mail.
     *
     * <ol>
     *   <li>Reads the text of the first attachment with {@link MailAttachmentReader#firstAttachment(Message)}
     *       ({@code imap-to-xml.xml:11-15}).</li>
     *   <li>Maps it to the orders XML document with {@link OrdersXmlMapper#toOrdersXml(String)} (DW-13,
     *       {@code imap-to-xml.xml:16-30}).</li>
     *   <li>Logs {@code Received: } followed by the document at INFO ({@code imap-to-xml.xml:31}).</li>
     *   <li>Creates the {@code file.output-path} directory and any missing parent, then writes the document
     *       to the {@code file.output-pattern} file inside it, encoded as UTF-8, exactly as the mapper
     *       returned it, with no added trailing newline ({@code imap-to-xml.xml:32}). An existing file of
     *       that name is truncated and replaced; content is never appended (D-582).</li>
     * </ol>
     *
     * <p>Exceptions of the reader and the mapper, among them {@code NoAttachmentException} for a mail
     * without an attachment (D-121), {@link IllegalStateException} and {@link IllegalArgumentException},
     * propagate unchanged; nothing is logged and no file is written or changed when they occur. The
     * caller logs every {@link RuntimeException} of this method at ERROR (D-582).
     *
     * @param message the received mail
     * @throws NullPointerException     when {@code message} is {@code null}, raised by the reader
     * @throws IllegalStateException    when the mail structure or an attachment header cannot be read
     * @throws IllegalArgumentException when the CSV header has an empty column name or a CSV value holds a
     *                                  character XML 1.0 does not allow
     * @throws UncheckedIOException     wrapping the {@link IOException} raised while an attachment is read,
     *                                  while the CSV text is parsed, or while the output directory is
     *                                  created or the file is written, for example when
     *                                  {@code file.output-path} names an existing regular file
     */
    public void imapToCsvFlow1(Message message) {
        String csv = reader.firstAttachment(message);
        String xml = mapper.toOrdersXml(csv);
        log.info("Received: " + xml);
        try {
            Path dir = Path.of(outputPath);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(outputPattern), xml, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
