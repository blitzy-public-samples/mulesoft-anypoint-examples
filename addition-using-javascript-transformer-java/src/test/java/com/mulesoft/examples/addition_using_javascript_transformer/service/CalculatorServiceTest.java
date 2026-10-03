package com.mulesoft.examples.addition_using_javascript_transformer.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of {@link CalculatorService}: SC-01 summation, the INFO line of javascript-calculator.xml:14 and the reply text of :15.
 * Decisions: D-034, D-049, D-169.
 *
 * <p>Each test calls the service directly, with no Spring context. The INFO line is read from a
 * {@link ListAppender} attached to the {@code CalculatorService} logger, whose level is INFO
 * during each test and restored afterwards.
 */
public class CalculatorServiceTest {

    private CalculatorService service;

    private ListAppender<ILoggingEvent> appender;

    private Logger serviceLogger;

    private Level previousLevel;

    /** Creates the service and attaches a started list appender to its logger at INFO. */
    @BeforeEach
    public void setUp() {
        service = new CalculatorService();

        serviceLogger = (Logger) LoggerFactory.getLogger(CalculatorService.class);
        previousLevel = serviceLogger.getLevel();
        serviceLogger.setLevel(Level.INFO);

        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
    }

    /** Detaches and stops the list appender and restores the logger's previous level. */
    @AfterEach
    public void tearDown() {
        serviceLogger.detachAppender(appender);
        appender.stop();
        serviceLogger.setLevel(previousLevel);
    }

    /** {@code { "a" : 1, "b": 2 }} sums to {@code 3.0}. */
    @Test
    public void sumOfObjectMembers() {
        assertEquals(3.0, service.sum("{ \"a\" : 1, \"b\": 2 }"));
    }

    /** {@code [1,2,3]} sums to {@code 6.0}. */
    @Test
    public void sumOfArrayElements() {
        assertEquals(6.0, service.sum("[1,2,3]"));
    }

    /** {@code {}} sums to {@code 0.0}. */
    @Test
    public void sumOfEmptyObject() {
        assertEquals(0.0, service.sum("{}"));
    }

    /** {@code []} sums to {@code 0.0}. */
    @Test
    public void sumOfEmptyArray() {
        assertEquals(0.0, service.sum("[]"));
    }

    /**
     * {@code {"b":1e16,"1":1,"2":1}} sums to {@code 1.0000000000000002E16}: keys {@code "1"} and
     * {@code "2"} are added before {@code "b"}. Keys {@code "0"} and {@code "4294967294"} are
     * added first as well; keys {@code ""}, {@code "07"}, {@code "-1"}, {@code "1.5"},
     * {@code "4294967295"} and {@code "12345678901"} are added in document order after
     * {@code "b"}, giving {@code 1.0E16}.
     */
    @Test
    public void sumFollowsForInKeyOrder() {
        assertEquals(1.0000000000000002E16, service.sum("{\"b\":1e16,\"1\":1,\"2\":1}"));

        assertEquals(1.0000000000000002E16, service.sum("{\"b\":1e16,\"4294967294\":1,\"0\":1}"));
        assertEquals(1.0E16, service.sum("{\"b\":1e16,\"\":1,\"07\":1}"));
        assertEquals(1.0E16, service.sum("{\"b\":1e16,\"-1\":1,\"1.5\":1}"));
        assertEquals(1.0E16, service.sum("{\"b\":1e16,\"4294967295\":1,\"12345678901\":1}"));
    }

    /**
     * <code>{ "a" : 1,</code> raises {@link IllegalArgumentException} carrying the parser's exception
     * as its cause.
     */
    @Test
    public void sumRejectsMalformedJson() {
        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.sum("{ \"a\" : 1,"));
        assertNotNull(thrown.getCause());
    }

    /** An empty, a blank and a {@code null} payload each raise {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsBlankInput() {
        assertThrows(IllegalArgumentException.class, () -> service.sum(""));
        assertThrows(IllegalArgumentException.class, () -> service.sum("   "));
        assertThrows(IllegalArgumentException.class, () -> service.sum(null));
    }

    /** {@code {"a":1} 2} and {@code [1][2]} each raise {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsTrailingTokens() {
        assertThrows(IllegalArgumentException.class, () -> service.sum("{\"a\":1} 2"));
        assertThrows(IllegalArgumentException.class, () -> service.sum("[1][2]"));
    }

    /** {@code {"a":"1"}} raises {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsStringMember() {
        assertThrows(IllegalArgumentException.class, () -> service.sum("{\"a\":\"1\"}"));
    }

    /** {@code {"a":true}} raises {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsBooleanMember() {
        assertThrows(IllegalArgumentException.class, () -> service.sum("{\"a\":true}"));
    }

    /** {@code {"a":null}} raises {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsNullMember() {
        assertThrows(IllegalArgumentException.class, () -> service.sum("{\"a\":null}"));
    }

    /** {@code {"a":{"b":1}}} and {@code [[1]]} each raise {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsNestedMember() {
        assertThrows(IllegalArgumentException.class, () -> service.sum("{\"a\":{\"b\":1}}"));
        assertThrows(IllegalArgumentException.class, () -> service.sum("[[1]]"));
    }

    /** {@code 5} raises {@link IllegalArgumentException}. */
    @Test
    public void sumRejectsNumericRoot() {
        assertThrows(IllegalArgumentException.class, () -> service.sum("5"));
    }

    /**
     * {@code { "a" : 1, "b": 2 }} returns {@code Sum is: 3.0.} and logs exactly one INFO event
     * {@code Sum is: 3.0}.
     */
    @Test
    public void flowReturnsSumText() {
        String reply = service.javascriptCalculatorFlow1(
                "{ \"a\" : 1, \"b\": 2 }".getBytes(StandardCharsets.UTF_8));

        assertEquals("Sum is: 3.0.", reply);
        assertEquals(1, appender.list.size());
        ILoggingEvent event = appender.list.get(0);
        assertSame(Level.INFO, event.getLevel());
        assertEquals("Sum is: 3.0", event.getFormattedMessage());
    }

    /** {@code {"a":0.1,"b":0.2}} returns {@code Sum is: 0.30000000000000004.}. */
    @Test
    public void flowKeepsDoubleRendering() {
        String reply = service.javascriptCalculatorFlow1(
                "{\"a\":0.1,\"b\":0.2}".getBytes(StandardCharsets.UTF_8));

        assertEquals("Sum is: 0.30000000000000004.", reply);
    }

    /** A {@code null} body raises {@link IllegalArgumentException}. */
    @Test
    public void flowRejectsNullBody() {
        assertThrows(IllegalArgumentException.class, () -> service.javascriptCalculatorFlow1(null));
    }
}
