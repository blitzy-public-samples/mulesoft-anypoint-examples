package com.mulesoft.examples.sending_json_data_to_a_jms_queue.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;

/**
 * Unit tests of {@link SalesPublisher#jsonToJmsFlow(byte[], java.nio.charset.Charset)}, the body of
 * the flow {@code json-to-jmsFlow} [sending-json-data-to-a-jms-queue/src/main/app/json-to-jms.xml:5-11]:
 * the byte-array-to-string step (:8), the send to queue {@code sales} (:9) and the INFO logger (:10).
 * Decisions: D-049, D-080, D-273.
 *
 * <p>No Spring context starts. The {@link JmsTemplate} is a Mockito mock, the publisher is built with
 * the queue name {@code sales}, and the INFO line is read from a {@link ListAppender} attached to the
 * {@code SalesPublisher} logger, whose level is INFO during each test and restored afterwards. The
 * request body is the copied original fixture {@code /original/message.json}, read as raw bytes and
 * never parsed. The HTTP side of the flow is tested by {@code controller.SalesControllerIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
public class SalesPublisherTest {

    private static final String QUEUE = "sales";

    private static final String MESSAGE_JSON = "/original/message.json";

    private static final int MESSAGE_JSON_LENGTH = 73;

    @Mock
    private JmsTemplate jmsTemplate;

    private SalesPublisher publisher;

    private ListAppender<ILoggingEvent> appender;

    private Logger publisherLogger;

    private Level previousLevel;

    /**
     * Builds the publisher on the mocked template with queue {@code sales}, sets the publisher's logger
     * to INFO and attaches a started list appender to it.
     */
    @BeforeEach
    void setUp() {
        publisher = new SalesPublisher(jmsTemplate, QUEUE);

        publisherLogger = (Logger) LoggerFactory.getLogger(SalesPublisher.class);
        previousLevel = publisherLogger.getLevel();
        publisherLogger.setLevel(Level.INFO);

        appender = new ListAppender<>();
        appender.start();
        publisherLogger.addAppender(appender);
    }

    /** Detaches and stops the list appender and restores the logger's previous level. */
    @AfterEach
    void tearDown() {
        publisherLogger.detachAppender(appender);
        appender.stop();
        publisherLogger.setLevel(previousLevel);
    }

    /**
     * Reads the copied original request body {@code /original/message.json} from the test classpath as
     * raw bytes.
     *
     * @return the 73 bytes of the fixture, unchanged
     * @throws IOException when the resource cannot be read
     */
    private static byte[] readMessageJson() throws IOException {
        try (InputStream stream = SalesPublisherTest.class.getResourceAsStream(MESSAGE_JSON)) {
            assertNotNull(stream, "test resource " + MESSAGE_JSON + " is missing");
            byte[] bytes = stream.readAllBytes();
            assertEquals(MESSAGE_JSON_LENGTH, bytes.length, "byte length of " + MESSAGE_JSON);
            return bytes;
        }
    }

    /**
     * The {@code message.json} bytes decoded as UTF-8 are sent once to queue {@code sales}, with no
     * other template call, and returned unchanged.
     */
    @Test
    @DisplayName("sends the decoded body once to queue sales and returns it")
    public void sendsOnceAndReturnsText() throws IOException {
        byte[] bytes = readMessageJson();
        String expected = new String(bytes, StandardCharsets.UTF_8);

        String result = publisher.jsonToJmsFlow(bytes, StandardCharsets.UTF_8);

        verify(jmsTemplate, times(1)).convertAndSend("sales", expected);
        verifyNoMoreInteractions(jmsTemplate);
        assertEquals(expected, result);
    }

    /**
     * The bytes {@code 63 61 66 E9} decoded with ISO-8859-1 are sent and returned as
     * {@code caf\u00e9}.
     */
    @Test
    @DisplayName("decodes the body with the given charset before sending it")
    public void decodesWithGivenCharset() {
        byte[] bytes = {0x63, 0x61, 0x66, (byte) 0xE9};

        String result = publisher.jsonToJmsFlow(bytes, StandardCharsets.ISO_8859_1);

        verify(jmsTemplate, times(1)).convertAndSend("sales", "caf\u00e9");
        verifyNoMoreInteractions(jmsTemplate);
        assertEquals("caf\u00e9", result);
    }

    /** An empty body is sent once to queue {@code sales} as the empty text and returned as {@code ""}. */
    @Test
    @DisplayName("sends an empty body as empty text and returns it")
    public void emptyBodySendsEmptyText() {
        String result = publisher.jsonToJmsFlow(new byte[0], StandardCharsets.UTF_8);

        verify(jmsTemplate, times(1)).convertAndSend("sales", "");
        verifyNoMoreInteractions(jmsTemplate);
        assertEquals("", result);
    }

    /**
     * An {@link UncategorizedJmsException} from the send reaches the caller as the same instance after
     * exactly one send attempt, and no INFO line is written (D-273).
     */
    @Test
    @DisplayName("propagates a send failure unchanged after one attempt and logs no INFO line")
    public void sendFailurePropagatesAfterOneAttempt() {
        UncategorizedJmsException failure = new UncategorizedJmsException("down");
        doThrow(failure).when(jmsTemplate).convertAndSend(eq("sales"), ArgumentMatchers.<Object>any());

        UncategorizedJmsException thrown = assertThrows(UncategorizedJmsException.class,
                () -> publisher.jsonToJmsFlow(readMessageJson(), StandardCharsets.UTF_8));

        assertSame(failure, thrown);
        verify(jmsTemplate, times(1)).convertAndSend(eq("sales"), ArgumentMatchers.<Object>any());
        verifyNoMoreInteractions(jmsTemplate);
        assertEquals(0, appender.list.stream().filter(event -> event.getLevel() == Level.INFO).count(),
                "INFO events after a failed send");
    }

    /**
     * A successful send writes exactly one event on the {@code SalesPublisher} logger: one INFO line
     * whose formatted message contains the decoded {@code message.json} text (json-to-jms.xml:10,
     * D-273).
     */
    @Test
    @DisplayName("writes exactly one INFO line containing the sent text")
    public void logsOneInfoLine() throws IOException {
        byte[] bytes = readMessageJson();
        String expected = new String(bytes, StandardCharsets.UTF_8);

        publisher.jsonToJmsFlow(bytes, StandardCharsets.UTF_8);

        assertEquals(1, appender.list.size(), "events on the SalesPublisher logger");
        long matching = appender.list.stream()
                .filter(event -> event.getLevel() == Level.INFO)
                .filter(event -> event.getFormattedMessage().contains(expected))
                .count();
        assertEquals(1, matching, "INFO events containing the sent text");
        ILoggingEvent event = appender.list.get(0);
        assertEquals(SalesPublisher.class.getName(), event.getLoggerName());
        assertTrue(event.getFormattedMessage().contains(QUEUE), "INFO line names the queue");
    }
}
