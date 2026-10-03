package com.mulesoft.examples.content_based_routing.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of {@link LanguageRoutingService}, the port of flow {@code content-based-routingFlow} and
 * sub-flow {@code replyInDefaultLanguage} [content-based-routing/src/main/app/content-based-routing.xml:4-26];
 * coverage floor D-049.
 *
 * <p>The service is instantiated directly, with no Spring context and no mocks (D-403). A Logback
 * {@link ListAppender} attached to the service's logger for the duration of each test records the INFO lines
 * of the loggers at content-based-routing.xml:20 and :23; the tests assert their text, order, count, level
 * and logger name.
 */
class LanguageRoutingServiceTest {

    /** Name of the SLF4J logger of {@link LanguageRoutingService}. */
    private static final String SERVICE_LOGGER_NAME =
            "com.mulesoft.examples.content_based_routing.service.LanguageRoutingService";

    /** Message of the sub-flow logger [content-based-routing.xml:23], trailing space included. */
    private static final String DEFAULT_LANGUAGE_LOG = "No language specified. Using English as a default. ";

    /** Message of the flow logger [content-based-routing.xml:20]; the arguments are the reply and the language. */
    private static final String REPLY_LOG_FORMAT = "The reply \"%s\" means \"hello\" in %s.";

    private LanguageRoutingService service;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    /**
     * Creates the service and attaches a started {@link ListAppender} to its logger, with the logger's level
     * set to INFO. The logger's previous level, {@code null} when it inherits one, is kept for
     * {@link #tearDown()}.
     */
    @BeforeEach
    void setUp() {
        service = new LanguageRoutingService();
        logger = (Logger) LoggerFactory.getLogger(LanguageRoutingService.class);
        previousLevel = logger.getLevel();
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
        logger.setLevel(previousLevel);
    }

    /**
     * The favicon filter [content-based-routing.xml:7] rejects the String payload {@code /favicon.ico}: the
     * result is empty and neither logger writes a line.
     */
    @Test
    void faviconStringPayloadIsFilteredWithoutLogging() {
        Optional<String> result = service.contentBasedRoutingFlow("/favicon.ico", "Spanish");

        assertThat(result).isEmpty();
        assertThat(appender.list).isEmpty();
    }

    /**
     * A {@code byte[]} payload holding the bytes of {@code /favicon.ico} is not equal to the String the filter
     * [content-based-routing.xml:7] compares against and passes; without a language the {@code otherwise}
     * branch [:16-17] replies {@code Hello!}.
     */
    @Test
    void faviconBytePayloadIsNotFiltered() {
        Optional<String> result =
                service.contentBasedRoutingFlow("/favicon.ico".getBytes(StandardCharsets.UTF_8), null);

        assertThat(result).contains("Hello!");
        assertInfoEvents(DEFAULT_LANGUAGE_LOG, String.format(REPLY_LOG_FORMAT, "Hello!", "English"));
    }

    /**
     * A {@code null} payload passes the favicon filter [content-based-routing.xml:7]; {@code French} replies
     * {@code Bonjour!} [:13-14].
     */
    @Test
    void nullPayloadIsNotFiltered() {
        Optional<String> result = service.contentBasedRoutingFlow(null, "French");

        assertThat(result).contains("Bonjour!");
        assertInfoEvents(String.format(REPLY_LOG_FORMAT, "Bonjour!", "French"));
    }

    /**
     * The first {@code when} [content-based-routing.xml:10-11]: {@code Spanish} replies {@code Hola!} and the
     * flow logger [:20] writes the reply with the language {@code Spanish}.
     */
    @Test
    void spanishRepliesHola() {
        Optional<String> result = service.contentBasedRoutingFlow("", "Spanish");

        assertThat(result).contains("Hola!");
        assertInfoEvents(String.format(REPLY_LOG_FORMAT, "Hola!", "Spanish"));
    }

    /**
     * The second {@code when} [content-based-routing.xml:13-14]: {@code French} replies {@code Bonjour!} and
     * the flow logger [:20] writes the reply with the language {@code French}.
     */
    @Test
    void frenchRepliesBonjour() {
        Optional<String> result = service.contentBasedRoutingFlow("", "French");

        assertThat(result).contains("Bonjour!");
        assertInfoEvents(String.format(REPLY_LOG_FORMAT, "Bonjour!", "French"));
    }

    /**
     * The {@code otherwise} branch [content-based-routing.xml:16-17]: an absent language, the empty string, a
     * differently cased {@code spanish} and an unlisted {@code German} each run sub-flow
     * {@code replyInDefaultLanguage} [:22-26], which logs the default-language line and replies
     * {@code Hello!}; the flow logger [:20] then writes the reply with the language {@code English}.
     *
     * @param language the {@code language} query parameter, or {@code null} when absent
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "spanish", "German"})
    void otherLanguagesUseEnglishDefault(String language) {
        Optional<String> result = service.contentBasedRoutingFlow("", language);

        assertThat(result).contains("Hello!");
        assertInfoEvents(DEFAULT_LANGUAGE_LOG, String.format(REPLY_LOG_FORMAT, "Hello!", "English"));
    }

    /**
     * Sub-flow {@code replyInDefaultLanguage} [content-based-routing.xml:22-26] called directly: it logs the
     * default-language line [:23], sets the {@code language} flow variable to {@code English} [:24] and
     * replies {@code Hello!} [:25].
     */
    @Test
    void replyInDefaultLanguageSetsEnglishAndRepliesHello() {
        Map<String, String> flowVars = new HashMap<>();

        String reply = service.replyInDefaultLanguage(flowVars);

        assertThat(reply).isEqualTo("Hello!");
        assertThat(flowVars).containsEntry("language", "English").hasSize(1);
        assertInfoEvents(DEFAULT_LANGUAGE_LOG);
    }

    /**
     * Asserts that the captured events carry exactly {@code messages}, in order, each at INFO on the service's
     * logger.
     *
     * @param messages the expected formatted messages, in the order they were logged
     */
    private void assertInfoEvents(String... messages) {
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .containsExactly(messages);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel)
                .containsOnly(Level.INFO);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLoggerName)
                .containsOnly(SERVICE_LOGGER_NAME);
    }
}
