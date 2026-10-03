package com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.config.MailStoreProperties;
import com.mulesoft.examples.importing_an_email_attachment_using_the_pop3_connector.service.AttachmentToXmlService;

/**
 * Message source of flow {@code pop-to-xmlFlow1}
 * [importing-an-email-attachment-using-the-POP3-connector/src/main/app/pop-to-xml.xml:5-29]: the
 * {@code pop3s:inbound-endpoint} at :6 on the {@code pop3s:connector} at :3. It polls the
 * {@code INBOX} of the POP3S mailbox configured under {@code pop3.*} every
 * {@code pop3.check-frequency} milliseconds and hands each mail to
 * {@link AttachmentToXmlService#popToXmlFlow1(Message)}, which runs the body of
 * {@code pop-to-xmlFlow1}.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #popToXmlFlow1()} runs every {@code pop3.check-frequency} milliseconds (default
 *       {@code 100}, the connector's {@code checkFrequency}); the interval runs from the end of one
 *       poll to the start of the next, and the first poll runs at startup.</li>
 *   <li>Each poll opens a new Jakarta Mail {@link Session} over the {@code pop3s} protocol, with
 *       {@code pop3.host}, {@code pop3.port} and every {@code pop3.session-properties} entry
 *       (D-098), logs in with {@link MailStoreProperties#loginUser()} and
 *       {@link MailStoreProperties#loginPassword()} (D-319), and opens {@code INBOX} read-write.</li>
 *   <li>Each message is copied into a detached in-memory {@link MimeMessage} and flagged
 *       {@link Flags.Flag#DELETED DELETED}. The folder is then closed with expunge and the store is
 *       closed. Only after that are the copies passed to the service, in mailbox order, one at a time
 *       on the calling thread.</li>
 *   <li>Every failure is logged at ERROR with its exception, and no exception leaves the poller. A
 *       failed connection ends the poll, and the next poll connects again (D-099). A mail whose
 *       processing fails is not processed again: it has already been expunged from the mailbox.</li>
 *   <li>The endpoint's {@code responseTimeout} and the connector's {@code validateConnections} have
 *       no counterpart here (D-099).</li>
 *   <li>The bean is registered only when {@code polling.enabled} is {@code true} or absent
 *       (D-093).</li>
 * </ul>
 *
 * <p>The class holds no mutable state: its fields are the logger, the bound {@code pop3.*} settings
 * and the service. Every poll uses its own {@link Session}, {@link Store} and {@link Folder}.
 * Passwords are never logged.
 *
 * <p>Example, with the poller built directly:
 * <pre>{@code
 * Pop3AttachmentPoller poller = new Pop3AttachmentPoller(properties, attachmentToXmlService);
 * poller.popToXmlFlow1();   // one poll: copy, flag DELETED, expunge, then process each copy
 * }</pre>
 */
@Component
@ConditionalOnProperty(prefix = "polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Pop3AttachmentPoller {

    /** Writes the DEBUG line of each poll and the ERROR lines of every failure. */
    private static final Logger log = LoggerFactory.getLogger(Pop3AttachmentPoller.class);

    /** Name of the replaced Mule flow, written into every log line. */
    private static final String FLOW_NAME = "pop-to-xmlFlow1";

    /** Jakarta Mail store protocol of the {@code pop3s:inbound-endpoint}. */
    private static final String PROTOCOL = "pop3s";

    /** Mailbox folder read on every poll; the connector sets no other folder. */
    private static final String FOLDER_NAME = "INBOX";

    /** Session property naming the default store protocol. */
    private static final String STORE_PROTOCOL_PROPERTY = "mail.store.protocol";

    /** Session property holding the POP3S server host. */
    private static final String HOST_PROPERTY = "mail.pop3s.host";

    /** Session property holding the POP3S server port. */
    private static final String PORT_PROPERTY = "mail.pop3s.port";

    /** The bound {@code pop3.*} keys: server, login and extra session properties. */
    private final MailStoreProperties properties;

    /** Runs flow {@code pop-to-xmlFlow1} over one received mail. */
    private final AttachmentToXmlService attachmentToXmlService;

    /**
     * Creates the poller over its two collaborators.
     *
     * @param properties             the bound {@code pop3.*} keys: host, port, login, poll interval
     *                               and extra session properties
     * @param attachmentToXmlService the service that runs flow {@code pop-to-xmlFlow1} over one mail
     * @throws NullPointerException if either argument is {@code null}
     */
    public Pop3AttachmentPoller(MailStoreProperties properties, AttachmentToXmlService attachmentToXmlService) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.attachmentToXmlService = Objects.requireNonNull(attachmentToXmlService, "attachmentToXmlService");
    }

    /**
     * Runs one poll of the {@code pop3s:inbound-endpoint} [pop-to-xml.xml:6] and processes every mail
     * it retrieves. The steps, in this order:
     *
     * <ol>
     *   <li><b>Session.</b> A new {@link Properties} receives {@code mail.store.protocol=pop3s},
     *       {@code mail.pop3s.host} from {@code pop3.host} (left out when the key is unset) and
     *       {@code mail.pop3s.port} from {@code pop3.port}; every {@code pop3.session-properties}
     *       entry is then put over them, an entry with a {@code null} key or value being skipped
     *       (D-098). {@link Session#getInstance(Properties)} creates the session from them alone.</li>
     *   <li><b>Connect.</b> The {@code pop3s} store connects to the {@code mail.pop3s.host} and
     *       {@code mail.pop3s.port} of those properties with {@link MailStoreProperties#loginUser()}
     *       and {@link MailStoreProperties#loginPassword()} (D-319), and {@code INBOX} is opened
     *       read-write.</li>
     *   <li><b>Copy and flag.</b> For each message, in mailbox order, a detached in-memory
     *       {@link MimeMessage} copy is collected, and the message is then flagged DELETED, whether
     *       or not the copy succeeded. A failed copy or a failed flag is logged at ERROR with the
     *       message number, and the next message follows.</li>
     *   <li><b>Close.</b> The folder, when it was obtained and is open, is closed with expunge; then
     *       the store, when it was obtained, is closed. Each close that fails is logged at ERROR, and
     *       the other close still runs.</li>
     *   <li><b>Connection failure.</b> A failure while creating the session, connecting, opening the
     *       folder or listing its messages is logged at ERROR with the host and the port, and the
     *       poll ends after step 4; the next scheduled poll connects again (D-099).</li>
     *   <li><b>Process.</b> After step 4, each copy is passed, in order, to
     *       {@link AttachmentToXmlService#popToXmlFlow1(Message)}. An exception it throws, such as
     *       {@code NoAttachmentException} for a mail without an attachment, is logged at ERROR as
     *       {@code Exception while processing message in flow pop-to-xmlFlow1: <message>} with the
     *       exception, and the next copy follows. Copies collected before a failed close are still
     *       processed.</li>
     * </ol>
     *
     * <p>A failed mail is never retried: it is not re-queued, and it was flagged DELETED and expunged
     * before processing. The method declares no checked exception and lets no exception escape.
     */
    @Scheduled(fixedDelayString = "${pop3.check-frequency:100}")
    public void popToXmlFlow1() {
        Properties sessionProperties = sessionProperties();
        // Host and port are read back from the session properties: a pop3.session-properties entry
        // for mail.pop3s.host or mail.pop3s.port replaces pop3.host or pop3.port (D-098).
        String host = sessionProperties.getProperty(HOST_PROPERTY);
        String port = sessionProperties.getProperty(PORT_PROPERTY);
        List<MimeMessage> copies = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            Session session = Session.getInstance(sessionProperties);
            store = session.getStore(PROTOCOL);
            store.connect(host, Integer.parseInt(port), properties.loginUser(), properties.loginPassword());
            folder = store.getFolder(FOLDER_NAME);
            folder.open(Folder.READ_WRITE);
            Message[] messages = folder.getMessages();
            log.debug("Retrieved {} message(s) from the POP3S {} of {}:{} in flow {}",
                    messages.length, FOLDER_NAME, host, port, FLOW_NAME);
            for (Message message : messages) {
                copyAndFlagDeleted(message, copies);
            }
        } catch (MessagingException | RuntimeException e) {
            log.error("Cannot read the POP3S {} of {}:{} in flow {}", FOLDER_NAME, host, port, FLOW_NAME, e);
        } finally {
            closeFolder(folder, host, port);
            closeStore(store, host, port);
        }
        for (MimeMessage copy : copies) {
            process(copy);
        }
    }

    /**
     * Builds the Jakarta Mail session properties of one poll: {@code mail.store.protocol},
     * {@code mail.pop3s.host} (only when {@code pop3.host} is set) and {@code mail.pop3s.port}, then
     * every {@code pop3.session-properties} entry with a non-{@code null} key and value on top.
     *
     * @return a new {@link Properties} holding only those entries
     */
    private Properties sessionProperties() {
        Properties sessionProperties = new Properties();
        sessionProperties.put(STORE_PROTOCOL_PROPERTY, PROTOCOL);
        if (properties.host() != null) {
            sessionProperties.put(HOST_PROPERTY, properties.host());
        }
        sessionProperties.put(PORT_PROPERTY, String.valueOf(properties.port()));
        Map<String, String> extra = properties.sessionProperties();
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    sessionProperties.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return sessionProperties;
    }

    /**
     * Adds a detached in-memory copy of {@code message} to {@code copies}, then flags
     * {@code message} DELETED in its folder. The flag is set whether or not the copy succeeded; each
     * failure is logged at ERROR with the message number.
     *
     * @param message a message of the open {@code INBOX}
     * @param copies  the copies collected by the current poll, in mailbox order
     */
    private static void copyAndFlagDeleted(Message message, List<MimeMessage> copies) {
        int number = message.getMessageNumber();
        try {
            copies.add(new MimeMessage((MimeMessage) message));
        } catch (MessagingException | RuntimeException e) {
            log.error("Cannot copy message {} of the POP3S {} in flow {}; the message is flagged DELETED"
                    + " and not processed", number, FOLDER_NAME, FLOW_NAME, e);
        }
        try {
            message.setFlag(Flags.Flag.DELETED, true);
        } catch (MessagingException | RuntimeException e) {
            log.error("Cannot flag message {} of the POP3S {} DELETED in flow {}", number, FOLDER_NAME, FLOW_NAME, e);
        }
    }

    /**
     * Closes {@code folder} with expunge when it is open. Does nothing when {@code folder} is
     * {@code null} or closed; a failure is logged at ERROR.
     *
     * @param folder the {@code INBOX} of the current poll, or {@code null} when it was not obtained
     * @param host   the POP3S host written into the log line
     * @param port   the POP3S port written into the log line
     */
    private static void closeFolder(Folder folder, String host, String port) {
        if (folder == null) {
            return;
        }
        try {
            if (folder.isOpen()) {
                folder.close(true);
            }
        } catch (MessagingException | RuntimeException e) {
            log.error("Cannot close and expunge the POP3S {} of {}:{} in flow {}",
                    FOLDER_NAME, host, port, FLOW_NAME, e);
        }
    }

    /**
     * Closes {@code store}. Does nothing when {@code store} is {@code null}; a failure is logged at
     * ERROR.
     *
     * @param store the {@code pop3s} store of the current poll, or {@code null} when it was not
     *              obtained
     * @param host  the POP3S host written into the log line
     * @param port  the POP3S port written into the log line
     */
    private static void closeStore(Store store, String host, String port) {
        if (store == null) {
            return;
        }
        try {
            store.close();
        } catch (MessagingException | RuntimeException e) {
            log.error("Cannot close the POP3S store of {}:{} in flow {}", host, port, FLOW_NAME, e);
        }
    }

    /**
     * Passes one copied mail to {@link AttachmentToXmlService#popToXmlFlow1(Message)}. An exception
     * it throws is logged at ERROR with the exception and is not rethrown.
     *
     * @param copy a detached copy of a mail that has already been expunged from the mailbox
     */
    private void process(MimeMessage copy) {
        try {
            attachmentToXmlService.popToXmlFlow1(copy);
        } catch (Exception e) {
            log.error("Exception while processing message in flow {}: {}", FLOW_NAME, e.getMessage(), e);
        }
    }
}
