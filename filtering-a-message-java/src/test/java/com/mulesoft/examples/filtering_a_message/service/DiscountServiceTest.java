package com.mulesoft.examples.filtering_a_message.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mulesoft.examples.filtering_a_message.mapper.DiscountRequestMapper;
import com.mulesoft.examples.filtering_a_message.model.InboundHttpRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of {@link DiscountService#filteringFlow1(InboundHttpRequest)}, the port of flow
 * {@code filteringFlow1} [filtering-a-message/src/main/app/filtering.xml:4-19] (D-437), and of its
 * and-filter helpers {@code payloadTypeAccepted} (filtering.xml:10) and {@code methodAccepted}
 * (filtering.xml:11), with JUnit 5 and Mockito and no Spring application context (D-057, D-562).
 *
 * <p>Each request is internally consistent: its {@code Content-Length} is the body length, or
 * {@code -1} for a chunked or {@code null} body.
 *
 * <p>The service is built in three ways:
 * <ul>
 *   <li>on Mockito mocks of {@link DiscountRequestMapper} and {@link FreeMembershipDiscountFilter},
 *       for the and-filter of filtering.xml:9-12 through {@code filteringFlow1} and through its two
 *       helpers called directly, the order of the flow steps and the propagation of collaborator
 *       exceptions; a Mockito spy of this service records the order in which the and-filter
 *       evaluates its two filters;</li>
 *   <li>on the real collaborators, for the granted and rejected outcomes of the original request
 *       bodies {@code original/message.json} and {@code original/message1.json} and for the
 *       exceptions raised by malformed, {@code null} and incomplete JSON documents;</li>
 *   <li>on the real mapper and a Mockito spy of the real filter, for the map the JSON-to-map step
 *       of filtering.xml:14 hands to the custom filter.</li>
 * </ul>
 * A Logback {@link ListAppender} attached to the service's logger for the duration of each test
 * records the INFO line of the logger step at filtering.xml:17. These tests cover the
 * {@code service} package under the JaCoCo LINE covered ratio rule of at least 0.80 (D-049).
 *
 * <p>The class and its test methods are public (D-133).
 */
@ExtendWith(MockitoExtension.class)
public class DiscountServiceTest {

    /** The set-payload literal of filtering.xml:16, the text of a granted reply. */
    private static final String GRANTED_TEXT = "the discount was granted.";

    /** A JSON request body that the custom filter grants: 2000 purchases over 12 months, membership free. */
    private static final String GRANTED_JSON = "{\"purchases\": 2000, \"months\": 12, \"membership\": \"free\"}";

    /** A JSON request body that the custom filter rejects: 100 purchases over 6 months, membership free. */
    private static final String DENIED_JSON = "{\"purchases\": 100, \"months\": 6, \"membership\": \"free\"}";

    /** Mocked JSON-to-map step of filtering.xml:14. */
    @Mock
    private DiscountRequestMapper mapper;

    /** Mocked custom filter step of filtering.xml:15. */
    @Mock
    private FreeMembershipDiscountFilter filter;

    /** The service under test, built on {@link #mapper} and {@link #filter}. */
    private DiscountService service;

    /** The service under test, built on a real mapper and a real filter. */
    private DiscountService realService;

    /** The Logback logger of {@link DiscountService}. */
    private Logger logger;

    /** The level of {@link #logger} before the test, {@code null} when it inherits one. */
    private Level previousLevel;

    /** Records the events {@link #logger} writes during the test. */
    private ListAppender<ILoggingEvent> appender;

    /**
     * Creates both services and attaches a started {@link ListAppender} to the service's logger, with
     * the logger's level set to INFO.
     */
    @BeforeEach
    void setUp() {
        service = new DiscountService(mapper, filter);
        realService = new DiscountService(new DiscountRequestMapper(), new FreeMembershipDiscountFilter());
        logger = (Logger) LoggerFactory.getLogger(DiscountService.class);
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
     * Builds a request whose {@code Content-Length} is the body length, or {@code -1} for a
     * {@code null} body.
     *
     * @param method the request method
     * @param transferEncoding the {@code Transfer-Encoding} value, or {@code null} when absent
     * @param contentType the {@code Content-Type} value, or {@code null} when absent
     * @param body the body bytes
     * @return the bound request
     */
    private static InboundHttpRequest request(String method, String transferEncoding, String contentType,
            byte[] body) {
        return new InboundHttpRequest(method, body == null ? -1 : body.length, transferEncoding, contentType, body);
    }

    /**
     * Builds a request with an explicit {@code Content-Length}, {@code -1} for a chunked body.
     *
     * @param method the request method
     * @param contentLength the {@code Content-Length} value, or {@code -1} when absent
     * @param transferEncoding the {@code Transfer-Encoding} value, or {@code null} when absent
     * @param contentType the {@code Content-Type} value, or {@code null} when absent
     * @param body the body bytes
     * @return the bound request
     */
    private static InboundHttpRequest request(String method, long contentLength, String transferEncoding,
            String contentType, byte[] body) {
        return new InboundHttpRequest(method, contentLength, transferEncoding, contentType, body);
    }

    /**
     * Builds the order map {@code {purchases=2000, months=12, membership=free}} of
     * {@link #GRANTED_JSON}, with {@link Integer} numbers.
     *
     * @return a new mutable map
     */
    private static Map<String, Object> grantedOrder() {
        return new HashMap<>(Map.of("purchases", 2000, "months", 12, "membership", "free"));
    }

    /**
     * Builds the order map {@code {purchases=100, months=6, membership=free}} of
     * {@link #DENIED_JSON}, with {@link Integer} numbers.
     *
     * @return a new mutable map
     */
    private static Map<String, Object> deniedOrder() {
        return new HashMap<>(Map.of("purchases", 100, "months", 6, "membership", "free"));
    }

    /**
     * Builds a {@code POST} request with {@code Content-Type: application/json}, no
     * {@code Transfer-Encoding} and the UTF-8 bytes of {@code json} as its body.
     *
     * @param json the body text
     * @return the bound request
     */
    private static InboundHttpRequest jsonPost(String json) {
        return request("POST", null, "application/json", json.getBytes(UTF_8));
    }

    /**
     * Reads a test resource.
     *
     * @param path the classpath resource path
     * @return the resource bytes
     * @throws IOException when the resource cannot be read
     */
    private static byte[] resource(String path) throws IOException {
        try (InputStream in = DiscountServiceTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path);
            return in.readAllBytes();
        }
    }

    /**
     * Stubs the mocked mapper to return a new map for {@code body} and the mocked filter to accept
     * that map.
     *
     * @param body the body the mapper receives
     */
    private void stubGranted(byte[] body) {
        Map<String, Object> order = new HashMap<>();
        when(mapper.toMap(body)).thenReturn(order);
        when(filter.accept(order)).thenReturn(true);
    }

    /**
     * Asserts the service logger wrote exactly one event: {@link #GRANTED_TEXT} at INFO under the
     * logger name of {@link DiscountService}.
     */
    private void assertGrantedLogged() {
        List<ILoggingEvent> events = appender.list;
        assertEquals(1, events.size(), "logged events");
        ILoggingEvent event = events.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals(GRANTED_TEXT, event.getFormattedMessage());
        assertEquals(DiscountService.class.getName(), event.getLoggerName());
    }

    /** Asserts the service logger wrote no event. */
    private void assertNothingLogged() {
        assertTrue(appender.list.isEmpty(), "logged events: " + appender.list);
    }

    /**
     * Asserts a {@code POST} with an empty body fails the payload type filter of filtering.xml:10:
     * the result is empty, the mapper and the custom filter are never called and nothing is logged.
     */
    @Test
    public void emptyBodyIsFilteredWithoutRunningMapperOrFilter() {
        Optional<String> result = service.filteringFlow1(request("POST", null, "application/json", new byte[0]));

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts a {@code POST} with a {@code null} body fails the payload type filter of filtering.xml:10:
     * the result is empty, the mapper and the custom filter are never called and nothing is logged.
     */
    @Test
    public void nullBodyIsFilteredWithoutRunningMapperOrFilter() {
        Optional<String> result = service.filteringFlow1(request("POST", null, "application/json", null));

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts a non-empty JSON {@code POST} whose {@code Transfer-Encoding}, trimmed, is
     * {@code chunked} in any letter case, with {@code Content-Length} {@code -1}, fails the payload
     * type filter of filtering.xml:10: the result is empty and the mapper and the custom filter are
     * never called.
     *
     * @param transferEncoding the {@code Transfer-Encoding} value
     */
    @ParameterizedTest
    @ValueSource(strings = {"chunked", "CHUNKED", "Chunked", " chunked "})
    public void chunkedTransferEncodingIsFilteredInAnyLetterCase(String transferEncoding) {
        Optional<String> result = service.filteringFlow1(
                request("POST", -1, transferEncoding, "application/json", GRANTED_JSON.getBytes(UTF_8)));

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts a non-empty JSON {@code POST} with no {@code Transfer-Encoding}, or one other than
     * {@code chunked}, passes the payload type filter of filtering.xml:10 and reaches the mapper and
     * the custom filter, which grant it.
     *
     * @param transferEncoding the {@code Transfer-Encoding} value, or {@code null} when absent
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"identity", "gzip"})
    public void absentOrNonChunkedTransferEncodingPasses(String transferEncoding) {
        byte[] body = GRANTED_JSON.getBytes(UTF_8);
        stubGranted(body);

        Optional<String> result = service.filteringFlow1(request("POST", transferEncoding, "application/json", body));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        assertGrantedLogged();
    }

    /**
     * Asserts a non-empty {@code POST} whose {@code Content-Type} base type (the text before the
     * first {@code ;}, trimmed, in any letter case) starts with {@code multipart/} fails the payload
     * type filter of filtering.xml:10: the result is empty and the mapper and the custom filter are
     * never called.
     *
     * @param contentType the {@code Content-Type} value
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "multipart/form-data; boundary=----boundary",
        "multipart/mixed",
        "MULTIPART/Related",
        "  multipart/alternative ; charset=UTF-8"
    })
    public void multipartContentTypeIsFiltered(String contentType) {
        Optional<String> result = service.filteringFlow1(
                request("POST", null, contentType, GRANTED_JSON.getBytes(UTF_8)));

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts a non-empty {@code POST} whose {@code Content-Type} base type (the text before the
     * first {@code ;}, trimmed, in any letter case) equals {@code application/x-www-form-urlencoded}
     * fails the payload type filter of filtering.xml:10: the result is empty and the mapper and the
     * custom filter are never called.
     *
     * @param contentType the {@code Content-Type} value
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "application/x-www-form-urlencoded",
        "application/x-www-form-urlencoded; charset=UTF-8",
        " Application/X-WWW-Form-Urlencoded "
    })
    public void formUrlencodedContentTypeIsFiltered(String contentType) {
        Optional<String> result = service.filteringFlow1(
                request("POST", null, contentType, GRANTED_JSON.getBytes(UTF_8)));

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts a non-empty {@code POST} with no {@code Content-Type}, or one whose base type is
     * neither {@code multipart/*} nor {@code application/x-www-form-urlencoded}, passes the payload
     * type filter of filtering.xml:10 and is granted. The base type {@code multipart} without a slash
     * passes (D-437).
     *
     * @param contentType the {@code Content-Type} value, or {@code null} when absent
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
        "application/json",
        "application/json; charset=UTF-8",
        "text/plain",
        "application/octet-stream",
        "multipart"
    })
    public void absentOrStreamContentTypePasses(String contentType) {
        byte[] body = GRANTED_JSON.getBytes(UTF_8);
        stubGranted(body);

        Optional<String> result = service.filteringFlow1(request("POST", null, contentType, body));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        assertGrantedLogged();
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) rejects a JSON {@code POST} whose
     * {@code Content-Length} is {@code 0} and whose body is empty.
     */
    @Test
    public void payloadTypeRejectsEmptyBody() {
        assertFalse(service.payloadTypeAccepted(request("POST", null, "application/json", new byte[0])));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) rejects a JSON body sent with
     * {@code Transfer-Encoding: chunked} and {@code Content-Length} {@code -1}.
     */
    @Test
    public void payloadTypeRejectsChunkedTransferEncoding() {
        assertFalse(service.payloadTypeAccepted(
                request("POST", -1, "chunked", "application/json", GRANTED_JSON.getBytes(UTF_8))));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) rejects a JSON body sent with
     * {@code Transfer-Encoding: CHUNKED} and {@code Content-Length} {@code -1}.
     */
    @Test
    public void payloadTypeRejectsUpperCaseChunkedTransferEncoding() {
        assertFalse(service.payloadTypeAccepted(
                request("POST", -1, "CHUNKED", "application/json", GRANTED_JSON.getBytes(UTF_8))));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) rejects a non-empty body with
     * {@code Content-Type: multipart/form-data; boundary=x}.
     */
    @Test
    public void payloadTypeRejectsMultipartFormData() {
        assertFalse(service.payloadTypeAccepted(
                request("POST", null, "multipart/form-data; boundary=x", GRANTED_JSON.getBytes(UTF_8))));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) rejects a non-empty body with
     * {@code Content-Type: application/x-www-form-urlencoded; charset=UTF-8}.
     */
    @Test
    public void payloadTypeRejectsFormUrlencoded() {
        assertFalse(service.payloadTypeAccepted(request("POST", null,
                "application/x-www-form-urlencoded; charset=UTF-8", "purchases=2000".getBytes(UTF_8))));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) accepts a non-empty body with
     * {@code Content-Type: application/json}.
     */
    @Test
    public void payloadTypeAcceptsJsonBody() {
        assertTrue(service.payloadTypeAccepted(
                request("POST", null, "application/json", GRANTED_JSON.getBytes(UTF_8))));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) accepts a non-empty body with no
     * {@code Content-Type}.
     */
    @Test
    public void payloadTypeAcceptsBodyWithoutContentType() {
        assertTrue(service.payloadTypeAccepted(request("POST", null, null, GRANTED_JSON.getBytes(UTF_8))));
    }

    /**
     * Asserts {@code payloadTypeAccepted} (filtering.xml:10) accepts a non-empty body with
     * {@code Content-Type: text/plain}.
     */
    @Test
    public void payloadTypeAcceptsTextPlainBody() {
        assertTrue(service.payloadTypeAccepted(request("POST", null, "text/plain", GRANTED_JSON.getBytes(UTF_8))));
    }

    /**
     * Asserts a non-empty JSON request whose method is {@code POST} in any letter case passes the
     * message property filter {@code http.method=post} with {@code caseSensitive="false"} of
     * filtering.xml:11 and is granted.
     *
     * @param method the request method
     */
    @ParameterizedTest
    @ValueSource(strings = {"POST", "post", "Post", "pOsT"})
    public void postInAnyLetterCasePasses(String method) {
        byte[] body = GRANTED_JSON.getBytes(UTF_8);
        stubGranted(body);

        Optional<String> result = service.filteringFlow1(request(method, null, "application/json", body));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        assertGrantedLogged();
    }

    /**
     * Asserts {@code methodAccepted} (filtering.xml:11, {@code http.method=post} with
     * {@code caseSensitive="false"}) accepts the methods {@code post}, {@code POST} and {@code Post}.
     */
    @Test
    public void methodAcceptsPostIgnoringCase() {
        byte[] body = GRANTED_JSON.getBytes(UTF_8);

        assertTrue(service.methodAccepted(request("post", null, "application/json", body)));
        assertTrue(service.methodAccepted(request("POST", null, "application/json", body)));
        assertTrue(service.methodAccepted(request("Post", null, "application/json", body)));
    }


    /**
     * Asserts a non-empty JSON request whose method is not {@code POST}, or is {@code null}, fails the
     * message property filter of filtering.xml:11 (D-043, D-437): the result is empty, the mapper and
     * the custom filter are never called and nothing is logged.
     *
     * @param method the request method, or {@code null}
     */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"GET", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "TRACE"})
    public void nonPostMethodIsFilteredWithoutRunningMapperOrFilter(String method) {
        Optional<String> result = service.filteringFlow1(
                request(method, null, "application/json", GRANTED_JSON.getBytes(UTF_8)));

        assertEquals(Optional.empty(), result);
        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts the and-filter of filtering.xml:9-12 evaluates the payload type filter first and stops
     * there when it rejects: for a {@code GET} with an empty body, {@code payloadTypeAccepted} runs,
     * {@code methodAccepted} never runs and the result is empty.
     */
    @Test
    public void andFilterStopsAtRejectingPayloadTypeFilter() {
        DiscountService spied = spy(service);
        InboundHttpRequest request = request("GET", null, "application/json", new byte[0]);

        Optional<String> result = spied.filteringFlow1(request);

        assertEquals(Optional.empty(), result);
        verify(spied).payloadTypeAccepted(request);
        verify(spied, never()).methodAccepted(request);
        verifyNoInteractions(mapper, filter);
    }

    /**
     * Asserts the and-filter of filtering.xml:9-12 evaluates the message property filter after an
     * accepting payload type filter: for a {@code GET} with a JSON body, {@code payloadTypeAccepted}
     * and then {@code methodAccepted} run, each once, and the result is empty.
     */
    @Test
    public void andFilterEvaluatesMethodFilterAfterAcceptingPayloadTypeFilter() {
        DiscountService spied = spy(service);
        InboundHttpRequest request = request("GET", null, "application/json", GRANTED_JSON.getBytes(UTF_8));

        Optional<String> result = spied.filteringFlow1(request);

        assertEquals(Optional.empty(), result);
        InOrder evaluation = inOrder(spied);
        evaluation.verify(spied).payloadTypeAccepted(request);
        evaluation.verify(spied).methodAccepted(request);
        verifyNoInteractions(mapper, filter);
    }

    /**
     * Asserts an accepted request runs the mapper of filtering.xml:14 on the request body and then
     * the custom filter of filtering.xml:15 on the map the mapper returned, each once and with the
     * same instances; the accepting filter yields {@code Optional[the discount was granted.]}
     * (filtering.xml:16), logged once at INFO (filtering.xml:17).
     */
    @Test
    public void acceptedRequestRunsMapperThenFilterAndReturnsGrantedText() {
        byte[] body = GRANTED_JSON.getBytes(UTF_8);
        Map<String, Object> order = grantedOrder();
        when(mapper.toMap(body)).thenReturn(order);
        when(filter.accept(order)).thenReturn(true);

        Optional<String> result = service.filteringFlow1(request("POST", null, "application/json", body));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        InOrder steps = inOrder(mapper, filter);
        steps.verify(mapper).toMap(same(body));
        steps.verify(filter).accept(same(order));
        steps.verifyNoMoreInteractions();
        assertGrantedLogged();
    }

    /**
     * Asserts a JSON {@code POST} of the denied body (100 purchases, 6 months, membership free), whose
     * map the custom filter of filtering.xml:15 rejects, ends the flow with an empty result after one
     * mapper call and one filter call, with the same instances, and nothing is logged.
     */
    @Test
    public void customFilterRejectionReturnsEmptyWithoutLogging() {
        byte[] body = DENIED_JSON.getBytes(UTF_8);
        Map<String, Object> order = deniedOrder();
        when(mapper.toMap(body)).thenReturn(order);
        when(filter.accept(order)).thenReturn(false);

        Optional<String> result = service.filteringFlow1(request("POST", null, "application/json", body));

        assertEquals(Optional.empty(), result);
        verify(mapper).toMap(same(body));
        verify(filter).accept(same(order));
        assertNothingLogged();
    }

    /**
     * Asserts the {@link UncheckedIOException} the mapper throws reaches the caller as the same
     * instance, the custom filter is never called and nothing is logged (D-437).
     */
    @Test
    public void mapperExceptionPropagatesUnchanged() {
        byte[] body = GRANTED_JSON.getBytes(UTF_8);
        UncheckedIOException failure = new UncheckedIOException(new IOException("bad json"));
        when(mapper.toMap(body)).thenThrow(failure);
        InboundHttpRequest request = request("POST", null, "application/json", body);

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> service.filteringFlow1(request));

        assertSame(failure, thrown);
        verifyNoInteractions(filter);
        assertNothingLogged();
    }

    /**
     * Asserts the {@link NullPointerException} the custom filter throws for a map without
     * {@code membership} reaches the caller as the same instance and nothing is logged (D-437).
     */
    @Test
    public void filterNullPointerExceptionPropagatesUnchanged() {
        byte[] body = "{\"purchases\": 2000, \"months\": 12}".getBytes(UTF_8);
        Map<String, Object> order = new HashMap<>(Map.of("purchases", 2000, "months", 12));
        NullPointerException failure = new NullPointerException("membership");
        when(mapper.toMap(body)).thenReturn(order);
        when(filter.accept(order)).thenThrow(failure);
        InboundHttpRequest request = request("POST", null, "application/json", body);

        NullPointerException thrown = assertThrows(NullPointerException.class, () -> service.filteringFlow1(request));

        assertSame(failure, thrown);
        assertNothingLogged();
    }

    /**
     * Asserts the {@link NumberFormatException} the custom filter throws for a map whose
     * {@code months} is {@code "abc"} reaches the caller as the same instance and nothing is logged
     * (D-437).
     */
    @Test
    public void filterExceptionPropagatesUnchanged() {
        byte[] body = "{\"purchases\": 2000, \"months\": \"abc\", \"membership\": \"free\"}".getBytes(UTF_8);
        Map<String, Object> order = new HashMap<>(Map.of("purchases", 2000, "months", "abc", "membership", "free"));
        NumberFormatException failure = new NumberFormatException("For input string: \"abc\"");
        when(mapper.toMap(body)).thenReturn(order);
        when(filter.accept(order)).thenThrow(failure);
        InboundHttpRequest request = request("POST", null, "application/json", body);

        NumberFormatException thrown = assertThrows(NumberFormatException.class, () -> service.filteringFlow1(request));

        assertSame(failure, thrown);
        assertNothingLogged();
    }

    /**
     * Asserts a {@code null} request throws {@link NullPointerException} and neither the mapper nor
     * the custom filter is called.
     */
    @Test
    public void nullRequestThrowsNullPointerException() {
        assertThrows(NullPointerException.class, () -> service.filteringFlow1(null));

        verifyNoInteractions(mapper, filter);
        assertNothingLogged();
    }

    /**
     * Asserts the original request body {@code original/message.json} (2000 purchases, 12 months,
     * membership free), sent as a JSON {@code POST} to the service on real collaborators, yields
     * {@code Optional[the discount was granted.]}, logged once at INFO.
     *
     * @throws IOException when the test resource cannot be read
     */
    @Test
    public void originalMessageIsGranted() throws IOException {
        byte[] body = resource("/original/message.json");

        Optional<String> result = realService.filteringFlow1(request("POST", null, "application/json", body));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        assertGrantedLogged();
    }

    /**
     * Asserts the original request body {@code original/message1.json} (100 purchases, 6 months,
     * membership free), sent as a JSON {@code POST} to the service on real collaborators, is rejected
     * by the custom filter: the result is empty and nothing is logged.
     *
     * @throws IOException when the test resource cannot be read
     */
    @Test
    public void originalMessage1IsFilteredByCustomFilter() throws IOException {
        byte[] body = resource("/original/message1.json");

        Optional<String> result = realService.filteringFlow1(request("POST", null, "application/json", body));

        assertEquals(Optional.empty(), result);
        assertNothingLogged();
    }

    /**
     * Asserts the bytes of {@code original/message.json} reach the custom filter as a {@link HashMap}
     * equal to {@code {purchases=2000, months=12, membership=free}}, with {@link Integer} numbers, when
     * the real mapper converts them (filtering.xml:14).
     *
     * @throws IOException when the test resource cannot be read
     */
    @Test
    public void originalMessageIsConvertedToTheHashMapTheFilterReads() throws IOException {
        FreeMembershipDiscountFilter filterSpy = spy(new FreeMembershipDiscountFilter());
        DiscountService wired = new DiscountService(new DiscountRequestMapper(), filterSpy);
        Map<String, Object> expected = Map.of("purchases", 2000, "months", 12, "membership", "free");

        Optional<String> result = wired.filteringFlow1(
                request("POST", null, "application/json", resource("/original/message.json")));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        verify(filterSpy).accept(argThat(order -> order.getClass() == HashMap.class && order.equals(expected)));
    }

    /**
     * Asserts a JSON body whose {@code months} and {@code purchases} are the strings {@code "12"} and
     * {@code "2000"}, with membership free, is converted and granted by the service on real
     * collaborators.
     */
    @Test
    public void jsonStringNumbersAreParsedAndGranted() {
        Optional<String> result = realService.filteringFlow1(
                jsonPost("{\"purchases\": \"2000\", \"months\": \"12\", \"membership\": \"free\"}"));

        assertEquals(Optional.of(GRANTED_TEXT), result);
        assertGrantedLogged();
    }

    /**
     * Asserts a body that is not a JSON object (a malformed document or an array root) makes the real
     * mapper throw {@link UncheckedIOException} with an {@link IOException} cause, which reaches the
     * caller; nothing is logged.
     *
     * @param json the body text
     */
    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"purchases\": 2000,", "not json", "[2000, 12, \"free\"]"})
    public void nonObjectJsonBodyThrowsUncheckedIOException(String json) {
        InboundHttpRequest request = jsonPost(json);

        UncheckedIOException thrown =
                assertThrows(UncheckedIOException.class, () -> realService.filteringFlow1(request));

        assertNotNull(thrown.getCause());
        assertNothingLogged();
    }

    /**
     * Asserts the JSON document {@code null} makes the real mapper return {@code null}, on which the
     * real custom filter throws {@link NullPointerException}, which reaches the caller; nothing is
     * logged.
     */
    @Test
    public void jsonNullDocumentThrowsNullPointerException() {
        InboundHttpRequest request = jsonPost("null");

        assertThrows(NullPointerException.class, () -> realService.filteringFlow1(request));

        assertNothingLogged();
    }

    /**
     * Asserts a JSON object that lacks {@code membership}, {@code months} or {@code purchases}, or
     * holds {@code null} for {@code membership}, makes the real custom filter throw
     * {@link NullPointerException}, which reaches the caller; nothing is logged.
     *
     * @param json the body text
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"purchases\": 2000, \"months\": 12}",
        "{\"purchases\": 2000, \"membership\": \"free\"}",
        "{\"months\": 12, \"membership\": \"free\"}",
        "{\"purchases\": 2000, \"months\": 12, \"membership\": null}",
        "{}"
    })
    public void missingOrNullOrderValueThrowsNullPointerException(String json) {
        InboundHttpRequest request = jsonPost(json);

        assertThrows(NullPointerException.class, () -> realService.filteringFlow1(request));

        assertNothingLogged();
    }

    /**
     * Asserts a JSON object whose {@code months} or {@code purchases} is not an {@code int} literal
     * (a non-numeric string, a decimal, a number out of {@code int} range) makes the real custom
     * filter throw {@link NumberFormatException}, which reaches the caller; nothing is logged.
     *
     * @param json the body text
     */
    @ParameterizedTest
    @ValueSource(strings = {
        "{\"purchases\": 2000, \"months\": \"abc\", \"membership\": \"free\"}",
        "{\"purchases\": 2000, \"months\": 12.0, \"membership\": \"free\"}",
        "{\"purchases\": 3000000000, \"months\": 12, \"membership\": \"free\"}"
    })
    public void nonIntegerMonthsOrPurchasesThrowsNumberFormatException(String json) {
        InboundHttpRequest request = jsonPost(json);

        assertThrows(NumberFormatException.class, () -> realService.filteringFlow1(request));

        assertNothingLogged();
    }
}
