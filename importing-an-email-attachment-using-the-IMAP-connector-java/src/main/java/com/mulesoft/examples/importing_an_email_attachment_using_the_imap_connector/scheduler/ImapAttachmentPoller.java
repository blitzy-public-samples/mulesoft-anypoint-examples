package com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.scheduler;

import java.util.ArrayList;
import java.util.List;

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

import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.config.MailStoreProperties;
import com.mulesoft.examples.importing_an_email_attachment_using_the_imap_connector.service.AttachmentToXmlService;

/**
 * Message source of flow {@code imap-to-csvFlow1}
 * [importing-an-email-attachment-using-the-IMAP-connector/src/main/app/imap-to-xml.xml:9-33]: polls the
 * IMAPS {@code INBOX} with a fixed delay of {@code imap.check-frequency} milliseconds, flags each new
 * message DELETED, expunges, and passes a detached copy of each to
 * {@link AttachmentToXmlService#imapToCsvFlow1(Message)}. Reproduces {@code imaps:connector}
 * ({@code imap-to-xml.xml:5}) and {@code imaps:inbound-endpoint} ({@code imap-to-xml.xml:10}); Jakarta
 * Mail per D-063, with no Mule type or pre-Jakarta mail API (D-050) and no code shared with the POP3
 * example (D-004).
 *
 * <p>Each call of {@link #imapToCsvFlow1()} is one poll, in this order (D-676):
 * <ol>
 *   <li><b>Connect.</b> Opens a Jakarta Mail {@link Session} over
 *       {@link MailStoreProperties#sessionProperties()}, which carries the {@code imap.response-timeout}
 *       connect and read timeouts (D-286), and connects its {@code imaps} {@link Store} to
 *       {@code imap.host}:{@code imap.port} as {@link MailStoreProperties#decodedUser()} with
 *       {@code imap.password} (D-285). No {@code Authenticator} is installed.</li>
 *   <li><b>Open.</b> Opens the folder {@code INBOX} read-write.</li>
 *   <li><b>Collect.</b> Visits the messages in folder order and skips each one already flagged
 *       {@link Flags.Flag#DELETED} or {@link Flags.Flag#SEEN}. Every other message is first copied into
 *       a detached in-memory {@link MimeMessage}, then flagged {@link Flags.Flag#DELETED}; a message whose
 *       copy fails is never flagged and stays in the {@code INBOX}.</li>
 *   <li><b>Close.</b> Closes the folder with expunge, which removes every message flagged in step 3,
 *       then closes the store. A failed close is logged at ERROR and changes nothing else.</li>
 *   <li><b>Process.</b> After the store is closed, passes each copy, in collection order, to
 *       {@link AttachmentToXmlService#imapToCsvFlow1(Message)}, one call per copy. A
 *       {@link RuntimeException} of that call, {@code NoAttachmentException} included, is logged at
 *       ERROR as {@code Failed to process message in flow imap-to-csvFlow1} with the exception, and the
 *       next copy is processed. The failed message is not processed again: its original is already
 *       expunged. This reproduces Mule's {@code deleteReadMessages} default of {@code true}, which
 *       flags a mail DELETED before it is routed.</li>
 * </ol>
 *
 * <p>Connection and store failures:
 * <ul>
 *   <li>A failure to connect, to open the folder or to list its messages is logged at ERROR as
 *       {@code Failed to poll IMAPS inbox <imap.host>} with the exception. Whatever is open is closed,
 *       nothing is processed, and the next scheduled poll connects again.</li>
 *   <li>A failure while one message is copied or flagged is logged with the same line and ends the
 *       collection; the messages collected before it are expunged and processed as above.</li>
 *   <li>No exception leaves {@link #imapToCsvFlow1()}. Log lines name {@code imap.host} only and never
 *       print the {@link MailStoreProperties} record (D-287).</li>
 * </ul>
 *
 * <p>Scheduling: the next poll starts {@code imap.check-frequency} milliseconds after the previous poll
 * has returned, and two polls never run at the same time; the first poll runs when the scheduler starts.
 * The bean is registered only when {@code polling.enabled} is {@code true} or absent (D-093).
 *
 * <p>The class holds no mutable state: its fields are the logger and the two collaborators, and every
 * {@link Store}, {@link Folder} and copy list is local to one poll.
 *
 * <pre>{@code
 * ImapAttachmentPoller poller = new ImapAttachmentPoller(mailStoreProperties, attachmentToXmlService);
 * poller.imapToCsvFlow1();  // one poll: each new INBOX mail is expunged, then its copy is processed
 * }</pre>
 */
@Component
@ConditionalOnProperty(name = "polling.enabled", havingValue = "true", matchIfMissing = true)
public class ImapAttachmentPoller {

    /** Writes the ERROR lines of a failed poll, a failed close and a failed message. */
    private static final Logger log = LoggerFactory.getLogger(ImapAttachmentPoller.class);

    /** Store protocol of the {@code imaps:inbound-endpoint} at {@code imap-to-xml.xml:10}. */
    private static final String IMAPS_PROTOCOL = "imaps";

    /** Name of the polled folder. */
    private static final String INBOX = "INBOX";

    /** The bound {@code imap.*} keys: host, port, user, password, poll delay and timeouts. */
    private final MailStoreProperties props;

    /** Runs the body of flow {@code imap-to-csvFlow1} for one received mail. */
    private final AttachmentToXmlService service;

    /**
     * Creates the poller over its two collaborators.
     *
     * @param props   the bound {@code imap.*} keys of the {@code imaps:connector} and
     *                {@code imaps:inbound-endpoint} [imap-to-xml.xml:5,10]
     * @param service the service that runs the body of flow {@code imap-to-csvFlow1} for one mail
     */
    public ImapAttachmentPoller(MailStoreProperties props, AttachmentToXmlService service) {
        this.props = props;
        this.service = service;
    }

    /**
     * Runs one poll of the {@code imaps:inbound-endpoint} [imap-to-xml.xml:10] and the flow
     * {@code imap-to-csvFlow1} for every new {@code INBOX} message, as the class documentation lists:
     * connect, open, collect detached copies while flagging the originals DELETED, close the folder with
     * expunge and the store, then pass each copy to {@link AttachmentToXmlService#imapToCsvFlow1(Message)}.
     *
     * <p>Every failure is logged at ERROR with its exception and none is thrown: a failed poll as
     * {@code Failed to poll IMAPS inbox <imap.host>}, a failed close as
     * {@code Failed to close IMAPS folder INBOX} or {@code Failed to close IMAPS store <imap.host>}, and
     * a failed message as {@code Failed to process message in flow imap-to-csvFlow1}.
     */
    @Scheduled(fixedDelayString = "${imap.check-frequency}")
    public void imapToCsvFlow1() {
        List<MimeMessage> copies = new ArrayList<>();
        Store store = null;
        Folder folder = null;
        try {
            Session session = Session.getInstance(props.sessionProperties());
            store = session.getStore(IMAPS_PROTOCOL);
            store.connect(props.host(), props.port(), props.decodedUser(), props.password());
            folder = store.getFolder(INBOX);
            folder.open(Folder.READ_WRITE);
            collect(folder.getMessages(), copies);
        } catch (MessagingException | RuntimeException e) {
            // Unchecked failures of the mail library are logged like MessagingException (D-676).
            log.error("Failed to poll IMAPS inbox " + props.host(), e);
        } finally {
            closeFolder(folder);
            closeStore(store);
        }
        for (MimeMessage copy : copies) {
            try {
                service.imapToCsvFlow1(copy);
            } catch (RuntimeException e) {
                log.error("Failed to process message in flow imap-to-csvFlow1", e);
            }
        }
    }

    /**
     * Adds a detached copy of each new message to {@code copies} and then flags that message DELETED.
     *
     * <p>Messages are visited in array order. A message already flagged {@link Flags.Flag#DELETED} or
     * {@link Flags.Flag#SEEN} is skipped. The copy is made before the flag is set: when the copy throws,
     * the message keeps its flags, and the exception ends the visit with every earlier copy already in
     * {@code copies}.
     *
     * @param messages the messages of the open {@code INBOX}
     * @param copies   the list that receives the copies, in visit order
     * @throws MessagingException when a flag cannot be read or set, or a message cannot be copied
     */
    private void collect(Message[] messages, List<MimeMessage> copies) throws MessagingException {
        for (Message m : messages) {
            if (m.isSet(Flags.Flag.DELETED) || m.isSet(Flags.Flag.SEEN)) {
                continue;
            }
            copies.add(new MimeMessage((MimeMessage) m));
            m.setFlag(Flags.Flag.DELETED, true);
        }
    }

    /**
     * Closes the folder with expunge when it was opened; a failure is logged at ERROR and not thrown.
     *
     * @param folder the {@code INBOX} folder, or {@code null} when the poll failed before it was obtained
     */
    private void closeFolder(Folder folder) {
        if (folder == null) {
            return;
        }
        try {
            if (folder.isOpen()) {
                folder.close(true);
            }
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to close IMAPS folder " + INBOX, e);
        }
    }

    /**
     * Closes the store when it is connected; a failure is logged at ERROR and not thrown.
     *
     * @param store the {@code imaps} store, or {@code null} when the poll failed before it was obtained
     */
    private void closeStore(Store store) {
        if (store == null) {
            return;
        }
        try {
            if (store.isConnected()) {
                store.close();
            }
        } catch (MessagingException | RuntimeException e) {
            log.error("Failed to close IMAPS store " + props.host(), e);
        }
    }
}
