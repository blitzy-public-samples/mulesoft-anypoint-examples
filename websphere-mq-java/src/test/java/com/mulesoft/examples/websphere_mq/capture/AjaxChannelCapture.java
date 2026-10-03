package com.mulesoft.examples.websphere_mq.capture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.CollectingAlertHandler;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import com.gargoylesoftware.htmlunit.WebResponseData;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlTextInput;
import com.gargoylesoftware.htmlunit.util.NameValuePair;
import com.gargoylesoftware.htmlunit.util.WebConnectionWrapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tier 2A AJAX capture harness for websphere-mq (AAP 0.7.2, D-081).
 *
 * <p>Drives the running original Mule application, never this Java project. Every page is a
 * separate HtmlUnit 2.70.0 browser that loads the original {@code index.html} from
 * {@code capture.base-url} ({@code ajax:connector serverUrl}, websphere-mq.xml:7) and talks
 * Bayeux on {@code /ajax/cometd} through the page's own Mule/Dojo client
 * ({@code mule-resource/js/dojo/dojo.js} and {@code mule-resource/js/mule.js}, index.html:17-18).
 * The scenario methods call {@code mule.subscribe} and {@code mule.unsubscribe} on
 * {@code /services/wmqExample/dequeue} (index.html:25,30) and {@code mule.rpc} on
 * {@code /services/wmqExample/enqueue} without a callback (index.html:55), wait for the messages
 * flow {@code Output} publishes (websphere-mq.xml:24-27) after the 15000 ms {@code MessageProcessor}
 * step (websphere-mq.xml:19), and write one {@code websphere-mq_<scenario>.json} fixture per
 * scenario into {@code capture.out}. Identities without a channel capture are appended to
 * {@code UNCAPTURABLE.txt} in the same directory.
 *
 * <p>The class is disabled unless the system property {@code capture.base-url} is set, and the
 * default surefire includes do not select it. It is not Tier 1 evidence.
 *
 * <p>Run from {@code websphere-mq-java/} on the Java 17 toolchain, against a freshly started
 * original with empty queues {@code in} and {@code out}:
 * <pre>{@code
 * mvn -B test -Dtest=AjaxChannelCapture -Djacoco.skip=true \
 *     -Dcapture.base-url=http://localhost:8086/services/wmqExample \
 *     -Dcapture.out=<absolute path>/websphere-mq-java/src/test/resources/fixtures
 * }</pre>
 */
@EnabledIfSystemProperty(named = "capture.base-url", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AjaxChannelCapture {

    /** Example folder name; prefix of every scenario identity. */
    private static final String EXAMPLE = "websphere-mq";

    /** RPC channel of flow {@code Input} (websphere-mq.xml:8-14). */
    private static final String ENQUEUE = "/services/wmqExample/enqueue";

    /** Publish channel of flow {@code Output} (websphere-mq.xml:24-27). */
    private static final String DEQUEUE = "/services/wmqExample/dequeue";

    /** Upper bound for the first successful {@code /meta/handshake} reply of a page. */
    private static final long HANDSHAKE_TIMEOUT_MS = 30_000;

    /** Upper bound for a {@code /meta/subscribe} or {@code /meta/unsubscribe} confirmation. */
    private static final long META_CONFIRM_TIMEOUT_MS = 10_000;

    /** Waiting window per expected message on a recording page. */
    private static final long PER_CALLBACK_TIMEOUT_MS = 60_000;

    /** Extra wait after the expected messages, before the final read of every page. */
    private static final long SETTLE_MS = 5_000;

    /** Wait after the three pre-subscription enqueues: three 15 s processings plus 15 s. */
    private static final long CACHE_FILL_WAIT_MS = 60_000;

    /** Wait after the enqueue sent with no subscriber in the dropped scenario. */
    private static final long DROP_WAIT_MS = 30_000;

    /** Polling interval of every wait. */
    private static final long POLL_INTERVAL_MS = 250;

    /** Longest interval between two progress lines during a wait. */
    private static final long PROGRESS_INTERVAL_MS = 15_000;

    private static final String BASE_URL_PROPERTY = "capture.base-url";
    private static final String OUT_PROPERTY = "capture.out";
    private static final String UNCAPTURABLE_FILE = "UNCAPTURABLE.txt";

    /** Separator between identity and missing input in UNCAPTURABLE.txt: space, U+2014, space. */
    private static final String DASH = " \u2014 ";

    /** Body tag of the original page (websphere-mq/src/main/app/docroot/index.html:66). */
    private static final String BODY_TAG = "<body onload=\"init();\" onunload=\"dispose();\">";

    private static final String HANDSHAKE_REASON =
            "Bayeux handshake on /ajax/cometd did not complete under HtmlUnit 2.70.0";

    private static final String DOCROOT_SCENARIO = "docroot-entry";
    private static final String DOCROOT_REASON =
            "not an AJAX channel; captured by the HTTP procedure of AAP 0.7.2";

    /** Channel identities with no capture, written after a successful handshake. */
    private static final List<Map.Entry<String, String>> FIXED_UNCAPTURABLE = List.of(
            Map.entry("process-failure-rolled-back",
                    "a failure inside the MessageProcessor transaction on queue in; no page input causes one"),
            Map.entry("dequeue-cache-overflow",
                    "501 enqueues before the first subscription since startup, each held 15 s by MessageProcessor,"
                            + " beyond one page session's timeouts"),
            Map.entry("unsubscribe",
                    "mule.unsubscribe publishes no channel message for the page to record"));

    /** The nine channel scenarios of SCENARIOS.txt, written with the handshake reason on handshake failure. */
    private static final List<String> CHANNEL_SCENARIOS = List.of(
            "enqueue",
            "process-appends",
            "process-failure-rolled-back",
            "dequeue-live-two-subscribers",
            "dequeue-cached-before-subscribe",
            "dequeue-cache-overflow",
            "dequeue-dropped-without-subscriber",
            "unsubscribe",
            "page-enqueue-and-receive");

    /** Recorder installed on every page after the handshake; each received message becomes {@code {t, v}}. */
    private static final String RECORDER_SCRIPT = """
            window.__captured = [];
            window.__recorder = function (message) {
              var d = message ? message.data : undefined;
              if (typeof d === 'string') { window.__captured.push({t: 'string', v: d}); }
              else { window.__captured.push({t: 'json', v: JSON.stringify(d === undefined ? null : d)}); }
            };
            """;

    /** Fixture writer: insertion-ordered object nodes, indented output, UTF-8 bytes. */
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** Identities listed in UNCAPTURABLE.txt, read at session start and extended by this run. */
    private final Set<String> listedUncapturable;

    /** Identities this run appended to UNCAPTURABLE.txt. */
    private final Set<String> writtenThisRun;

    /** Top-level fixture file names in {@code capture.out}, without {@code .json}. */
    private final Set<String> fixtureStems;

    /** Pages opened and not yet closed; {@link #closeSession()} closes what remains. */
    private final List<CapturePage> openPages;

    private URI baseUri;
    private Path outDir;
    private boolean channelsCapturable;

    /** Page 1 of {@link #dequeueCachedBeforeSubscribe()}, opened and handshaken by {@link #openSession()}. */
    private CapturePage cachePage1;

    /** Creates the harness with empty session state; {@link #openSession()} fills it. */
    AjaxChannelCapture() {
        this.listedUncapturable = new LinkedHashSet<>();
        this.writtenThisRun = new LinkedHashSet<>();
        this.fixtureStems = new HashSet<>();
        this.openPages = new ArrayList<>();
    }

    /**
     * Checks {@code capture.base-url} (localhost or 127.0.0.1 only) and {@code capture.out}, reads the
     * identities already in UNCAPTURABLE.txt and the existing fixture names, then opens page 1 of
     * {@link #dequeueCachedBeforeSubscribe()} with its body handlers removed and waits for its Bayeux
     * handshake. A page that does not load with HTTP 200 fails the class and writes nothing. A handshake
     * that does not complete lists the nine channel identities with {@value #HANDSHAKE_REASON}; a
     * completed handshake lists the three fixed identities. Both list {@code websphere-mq_docroot-entry}
     * while its fixture is absent.
     */
    @BeforeAll
    void openSession() throws IOException, InterruptedException {
        String baseValue = System.getProperty(BASE_URL_PROPERTY);
        baseUri = parseLocalBaseUrl(baseValue);
        String outValue = System.getProperty(OUT_PROPERTY);
        if (outValue == null || outValue.isBlank()) {
            throw new IllegalStateException(
                    "capture.out must name the fixtures directory, e.g. websphere-mq-java/src/test/resources/fixtures");
        }
        outDir = Paths.get(outValue.trim()).toAbsolutePath().normalize();
        if (Files.exists(outDir) && !Files.isDirectory(outDir)) {
            throw new IllegalStateException("capture.out is not a directory: " + outDir);
        }
        readUncapturable();
        readFixtureStems();

        System.out.println("[capture] " + EXAMPLE + ": run against a freshly started original runtime with empty"
                + " queues in and out; dequeue-cached-before-subscribe runs first");

        CapturePage probe = newPage(1, false);
        boolean handshake;
        try {
            handshake = probe.open();
        } catch (IOException e) {
            closePages(probe);
            throw new IllegalStateException("original runtime not reachable at " + baseUri
                    + ": no HTTP status (" + e + ")", e);
        } catch (RuntimeException e) {
            closePages(probe);
            throw e;
        }

        // The output directory is created only once the original page has loaded.
        Files.createDirectories(outDir);
        if (handshake) {
            channelsCapturable = true;
            cachePage1 = probe;
            System.out.println("[capture] " + EXAMPLE + ": Bayeux handshake completed on page 1");
            for (Map.Entry<String, String> fixed : FIXED_UNCAPTURABLE) {
                appendUncapturable(identity(fixed.getKey()), fixed.getValue());
            }
        } else {
            channelsCapturable = false;
            closePages(probe);
            System.out.println("[capture] " + EXAMPLE + ": " + HANDSHAKE_REASON + "; no fixture is written");
            for (String scenario : CHANNEL_SCENARIOS) {
                appendUncapturable(identity(scenario), HANDSHAKE_REASON);
            }
        }
        if (appendUncapturable(identity(DOCROOT_SCENARIO), DOCROOT_REASON)) {
            System.out.println("[capture] " + identity(DOCROOT_SCENARIO) + " listed as uncapturable; remove that"
                    + " line from UNCAPTURABLE.txt when its HTTP fixture is captured");
        }
    }

    /** Closes every page still open: recorder unsubscribed, {@code dispose()} run where kept, browser closed. */
    @AfterAll
    void closeSession() {
        cachePage1 = null;
        closePages(openPages.toArray(new CapturePage[0]));
    }

    /**
     * {@code websphere-mq_dequeue-cached-before-subscribe}: page 1 from {@link #openSession()}, never
     * subscribed, enqueues {@code dequeue-cached-before-subscribe-1} to {@code -3}; after
     * {@value #CACHE_FILL_WAIT_MS} ms page 2 loads and subscribes, the first subscription to the dequeue
     * channel since runtime startup, and page 1 enqueues {@code -4}. Page 2 is expected to receive four
     * messages: the three cached ones and the live one (AAP 0.3.8, {@code cacheMessages="true"},
     * websphere-mq.xml:26).
     */
    @Test
    @Order(1)
    public void dequeueCachedBeforeSubscribe() throws Exception {
        String scenario = "dequeue-cached-before-subscribe";
        String identity = identity(scenario);
        CapturePage page1 = cachePage1;
        cachePage1 = null;
        CapturePage page2 = null;
        List<String> setup = new ArrayList<>();
        ObjectNode trigger;
        Map<CapturePage, List<Map.Entry<String, String>>> received;
        try {
            assumeCapturable(identity);
            setup.add(loadPageEntry(page1));
            for (int n = 1; n <= 3; n++) {
                String data = payload(scenario, n);
                page1.rpc(data);
                setup.add(enqueueEntry(page1, data));
            }
            pause(identity, CACHE_FILL_WAIT_MS);
            setup.add("wait ms=" + CACHE_FILL_WAIT_MS);
            page2 = openPage(identity, 2, false);
            setup.add(loadPageEntry(page2));
            page2.subscribe();
            setup.add(subscribeEntry(page2));

            String probe = payload(scenario, 4);
            Map<CapturePage, Integer> expected = new LinkedHashMap<>();
            expected.put(page2, 4);
            long sentAt = System.nanoTime();
            page1.rpc(probe);
            trigger = triggerNode(page1, probe, "mule.rpc");
            received = awaitDeliveries(identity, sentAt, expected);
        } finally {
            closePages(page1, page2);
        }
        System.out.println("[capture] " + identity + ": received " + values(received));
        writeFixture(identity, setup, trigger, outputs(received));
    }

    /**
     * {@code websphere-mq_enqueue}: one page with body handlers removed subscribes the recorder to the
     * dequeue channel and enqueues {@code enqueue-1} through {@code mule.rpc}; one message is expected.
     */
    @Test
    @Order(2)
    public void enqueue() throws Exception {
        captureSingleSubscriber("enqueue");
    }

    /**
     * {@code websphere-mq_process-appends}: the {@link #enqueue()} sequence with {@code process-appends-1}.
     * The expected arrival {@code process-appends-1 - processed} (websphere-mq.xml:19) is printed beside
     * what was received; the fixture records what was received.
     */
    @Test
    @Order(3)
    public void processAppends() throws Exception {
        List<String> received = captureSingleSubscriber("process-appends");
        System.out.println("[capture] " + identity("process-appends") + ": expected arrival "
                + payload("process-appends", 1) + " - processed (websphere-mq.xml:19); received " + received);
    }

    /**
     * {@code websphere-mq_dequeue-live-two-subscribers}: two separate browsers, each with body handlers
     * removed, subscribe the recorder to the dequeue channel; page 1 enqueues
     * {@code dequeue-live-two-subscribers-1}. One message is expected on each page.
     */
    @Test
    @Order(4)
    public void dequeueLiveTwoSubscribers() throws Exception {
        String scenario = "dequeue-live-two-subscribers";
        String identity = identity(scenario);
        CapturePage page1 = null;
        CapturePage page2 = null;
        List<String> setup = new ArrayList<>();
        ObjectNode trigger;
        Map<CapturePage, List<Map.Entry<String, String>>> received;
        try {
            assumeCapturable(identity);
            page1 = openPage(identity, 1, false);
            setup.add(loadPageEntry(page1));
            page1.subscribe();
            setup.add(subscribeEntry(page1));
            page2 = openPage(identity, 2, false);
            setup.add(loadPageEntry(page2));
            page2.subscribe();
            setup.add(subscribeEntry(page2));

            String data = payload(scenario, 1);
            Map<CapturePage, Integer> expected = new LinkedHashMap<>();
            expected.put(page1, 1);
            expected.put(page2, 1);
            long sentAt = System.nanoTime();
            page1.rpc(data);
            trigger = triggerNode(page1, data, "mule.rpc");
            received = awaitDeliveries(identity, sentAt, expected);
        } finally {
            closePages(page1, page2);
        }
        System.out.println("[capture] " + identity + ": received " + values(received));
        writeFixture(identity, setup, trigger, outputs(received));
    }

    /**
     * {@code websphere-mq_dequeue-dropped-without-subscriber}: one page subscribes and unsubscribes the
     * recorder, enqueues {@code dequeue-dropped-without-subscriber-1} with no subscriber, waits
     * {@value #DROP_WAIT_MS} ms, subscribes again and enqueues the probe {@code -2}. A received list of
     * {@code [-2 - processed]} shows {@code -1} dropped; {@code [-1 - processed, -2 - processed]} shows it
     * held and delivered.
     */
    @Test
    @Order(5)
    public void dequeueDroppedWithoutSubscriber() throws Exception {
        String scenario = "dequeue-dropped-without-subscriber";
        String identity = identity(scenario);
        CapturePage page1 = null;
        List<String> setup = new ArrayList<>();
        ObjectNode trigger;
        Map<CapturePage, List<Map.Entry<String, String>>> received;
        try {
            assumeCapturable(identity);
            page1 = openPage(identity, 1, false);
            setup.add(loadPageEntry(page1));
            page1.subscribe();
            setup.add(subscribeEntry(page1));
            page1.unsubscribe();
            setup.add(unsubscribeEntry(page1));
            String unobserved = payload(scenario, 1);
            page1.rpc(unobserved);
            setup.add(enqueueEntry(page1, unobserved));
            pause(identity, DROP_WAIT_MS);
            setup.add("wait ms=" + DROP_WAIT_MS);
            page1.subscribe();
            setup.add(subscribeEntry(page1));

            String probe = payload(scenario, 2);
            Map<CapturePage, Integer> expected = new LinkedHashMap<>();
            expected.put(page1, 1);
            long sentAt = System.nanoTime();
            page1.rpc(probe);
            trigger = triggerNode(page1, probe, "mule.rpc");
            received = awaitDeliveries(identity, sentAt, expected);
        } finally {
            closePages(page1);
        }
        System.out.println("[capture] " + identity + ": received " + values(received));
        writeFixture(identity, setup, trigger, outputs(received));
    }

    /**
     * {@code websphere-mq_page-enqueue-and-receive}: one page with body handlers kept, whose
     * {@code init()} subscribes {@code dequeueCallback} on load (index.html:25,66), subscribes the
     * recorder, types {@code page-enqueue-and-receive-1} into {@code #phrase} and clicks
     * {@code #sendButton}, the page's own {@code rpcEnqueue()} (index.html:52-60,72-73). One message is
     * expected.
     */
    @Test
    @Order(6)
    public void pageEnqueueAndReceive() throws Exception {
        String scenario = "page-enqueue-and-receive";
        String identity = identity(scenario);
        CapturePage page1 = null;
        List<String> setup = new ArrayList<>();
        ObjectNode trigger;
        Map<CapturePage, List<Map.Entry<String, String>>> received;
        try {
            assumeCapturable(identity);
            page1 = openPage(identity, 1, true);
            setup.add(loadPageEntry(page1));
            page1.subscribe();
            setup.add(subscribeEntry(page1));

            String data = payload(scenario, 1);
            setup.add("fill page=" + page1.index + " element=phrase dataBase64=" + base64(data));
            Map<CapturePage, Integer> expected = new LinkedHashMap<>();
            expected.put(page1, 1);
            long sentAt = System.nanoTime();
            page1.clickSend(data);
            trigger = triggerNode(page1, data, "sendButton");
            received = awaitDeliveries(identity, sentAt, expected);
        } finally {
            closePages(page1);
        }
        System.out.println("[capture] " + identity + ": received " + values(received));
        writeFixture(identity, setup, trigger, outputs(received));
    }

    /**
     * Runs the one-subscriber sequence of {@code enqueue} and {@code process-appends}: load page 1 with
     * body handlers removed, subscribe the recorder, enqueue {@code <scenario>-1} through {@code mule.rpc},
     * wait for one message, write the fixture.
     *
     * @return the received data values of page 1, in arrival order
     */
    private List<String> captureSingleSubscriber(String scenario) throws IOException, InterruptedException {
        String identity = identity(scenario);
        CapturePage page1 = null;
        List<String> setup = new ArrayList<>();
        ObjectNode trigger;
        Map<CapturePage, List<Map.Entry<String, String>>> received;
        try {
            assumeCapturable(identity);
            page1 = openPage(identity, 1, false);
            setup.add(loadPageEntry(page1));
            page1.subscribe();
            setup.add(subscribeEntry(page1));

            String data = payload(scenario, 1);
            Map<CapturePage, Integer> expected = new LinkedHashMap<>();
            expected.put(page1, 1);
            long sentAt = System.nanoTime();
            page1.rpc(data);
            trigger = triggerNode(page1, data, "mule.rpc");
            received = awaitDeliveries(identity, sentAt, expected);
        } finally {
            closePages(page1);
        }
        List<String> receivedValues = values(received);
        System.out.println("[capture] " + identity + ": received " + receivedValues);
        writeFixture(identity, setup, trigger, outputs(received));
        return receivedValues;
    }

    /** Skips the calling scenario when the handshake failed or its identity is listed in UNCAPTURABLE.txt. */
    private void assumeCapturable(String identity) {
        Assumptions.assumeTrue(channelsCapturable, identity + ": " + HANDSHAKE_REASON);
        Assumptions.assumeFalse(alreadyUncapturable(identity),
                identity + " is listed in UNCAPTURABLE.txt; remove the line to recapture");
    }

    /** Whether UNCAPTURABLE.txt lists {@code identity}, from session start or from this run. */
    private boolean alreadyUncapturable(String identity) {
        return listedUncapturable.contains(identity);
    }

    /** Creates a page and registers it with the open pages closed by {@link #closeSession()}. */
    private CapturePage newPage(int index, boolean keepBodyHandlers) {
        CapturePage page = new CapturePage(CapturePage.newBrowser(), index, keepBodyHandlers, baseUri);
        openPages.add(page);
        return page;
    }

    /**
     * Opens a scenario page and waits for its handshake. A page that does not load, or whose handshake does
     * not complete within {@value #HANDSHAKE_TIMEOUT_MS} ms, fails the scenario.
     */
    private CapturePage openPage(String identity, int index, boolean keepBodyHandlers)
            throws IOException, InterruptedException {
        CapturePage page = newPage(index, keepBodyHandlers);
        boolean handshake;
        try {
            handshake = page.open();
        } catch (IOException | RuntimeException e) {
            closePages(page);
            throw e;
        }
        if (!handshake) {
            closePages(page);
            Assertions.fail(identity + ": Bayeux handshake did not complete on page " + index + " within "
                    + HANDSHAKE_TIMEOUT_MS + " ms");
        }
        return page;
    }

    /**
     * Closes each non-null page with {@link CapturePage#closePage()} and removes it from the open pages. A
     * failure while closing one page is printed and the remaining pages are still closed.
     */
    private void closePages(CapturePage... pages) {
        for (CapturePage page : pages) {
            if (page == null) {
                continue;
            }
            try {
                page.closePage();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.err.println("[capture] closing page " + page.index + " interrupted: " + e);
            } catch (IOException | RuntimeException e) {
                System.err.println("[capture] closing page " + page.index + " failed: " + e);
            } finally {
                openPages.remove(page);
            }
        }
    }

    /**
     * Waits for the expected message counts, measured from {@code sentAtNanos}, the moment the trigger was
     * sent. Each page is polled every {@value #POLL_INTERVAL_MS} ms until it holds its expected count or
     * {@value #PER_CALLBACK_TIMEOUT_MS} ms per expected message have elapsed. After a further
     * {@value #SETTLE_MS} ms every page is read once more, which records late and extra deliveries. Received
     * values are recorded, not compared; a recording page that received nothing fails the scenario.
     *
     * @return the received {@code (dataType, data)} items per page, ordered by page number
     */
    private Map<CapturePage, List<Map.Entry<String, String>>> awaitDeliveries(
            String identity, long sentAtNanos, Map<CapturePage, Integer> expected)
            throws IOException, InterruptedException {
        long lastProgress = System.nanoTime();
        for (Map.Entry<CapturePage, Integer> entry : expected.entrySet()) {
            CapturePage page = entry.getKey();
            int want = entry.getValue();
            long windowMs = PER_CALLBACK_TIMEOUT_MS * want;
            int have = page.captured().size();
            while (have < want && msSince(sentAtNanos) < windowMs) {
                if (msSince(lastProgress) >= PROGRESS_INTERVAL_MS) {
                    System.out.println("[capture] " + identity + ": page " + page.index + " holds " + have + " of "
                            + want + " expected after " + msSince(sentAtNanos) + " ms");
                    lastProgress = System.nanoTime();
                }
                Thread.sleep(POLL_INTERVAL_MS);
                have = page.captured().size();
            }
            System.out.println("[capture] " + identity + ": page " + page.index + " holds " + have + " of " + want
                    + " expected after " + msSince(sentAtNanos) + " ms");
        }
        Thread.sleep(SETTLE_MS);

        List<CapturePage> pages = new ArrayList<>(expected.keySet());
        pages.sort(Comparator.comparingInt(page -> page.index));
        Map<CapturePage, List<Map.Entry<String, String>>> received = new LinkedHashMap<>();
        for (CapturePage page : pages) {
            List<Map.Entry<String, String>> items = page.captured();
            if (items.isEmpty()) {
                Assertions.fail(identity + ": no message reached page " + page.index + " within "
                        + (PER_CALLBACK_TIMEOUT_MS * expected.get(page)) + " ms");
            }
            received.put(page, items);
        }
        return received;
    }

    /** Builds the {@code outputs} array: page 1's items in arrival order, then page 2's. */
    private static ArrayNode outputs(Map<CapturePage, List<Map.Entry<String, String>>> received) {
        ArrayNode outputs = MAPPER.createArrayNode();
        for (Map.Entry<CapturePage, List<Map.Entry<String, String>>> entry : received.entrySet()) {
            for (Map.Entry<String, String> item : entry.getValue()) {
                ObjectNode output = outputs.addObject();
                output.put("kind", "ajax");
                output.put("channel", DEQUEUE);
                output.put("dataType", item.getKey());
                output.put("dataBase64", base64(item.getValue()));
                output.put("page", entry.getKey().index);
            }
        }
        return outputs;
    }

    /** The received data values of every page, in the order of {@link #outputs(Map)}. */
    private static List<String> values(Map<CapturePage, List<Map.Entry<String, String>>> received) {
        List<String> values = new ArrayList<>();
        for (List<Map.Entry<String, String>> items : received.values()) {
            for (Map.Entry<String, String> item : items) {
                values.add(item.getValue());
            }
        }
        return values;
    }

    /** Builds the {@code trigger} object of a message sent on the enqueue channel from {@code page}. */
    private static ObjectNode triggerNode(CapturePage page, String data, String via) {
        ObjectNode trigger = MAPPER.createObjectNode();
        trigger.put("kind", "ajax");
        trigger.put("channel", ENQUEUE);
        trigger.put("dataType", "string");
        trigger.put("dataBase64", base64(data));
        trigger.put("page", page.index);
        trigger.put("via", via);
        return trigger;
    }

    private static String loadPageEntry(CapturePage page) {
        return "load-page page=" + page.index + " body-handlers=" + (page.keepBodyHandlers ? "kept" : "removed");
    }

    private static String enqueueEntry(CapturePage page, String data) {
        return "enqueue page=" + page.index + " channel=" + ENQUEUE + " dataBase64=" + base64(data);
    }

    private static String subscribeEntry(CapturePage page) {
        return "subscribe page=" + page.index + " channel=" + DEQUEUE;
    }

    private static String unsubscribeEntry(CapturePage page) {
        return "unsubscribe page=" + page.index + " channel=" + DEQUEUE;
    }

    /** Deterministic ASCII payload {@code <scenario>-<n>}. */
    private static String payload(String scenario, int n) {
        return scenario + "-" + n;
    }

    /** Scenario identity {@code websphere-mq_<scenario>}, as listed in SCENARIOS.txt. */
    private static String identity(String scenario) {
        return EXAMPLE + "_" + scenario;
    }

    /** Base64 of the UTF-8 bytes of {@code value}. */
    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Writes {@code <capture.out>/<identity>.json} with the fields scenario, setup, replay ({@code live}),
     * trigger, outputs and volatile ({@code []}), replacing an earlier capture of the same identity.
     */
    private void writeFixture(String identity, List<String> setup, ObjectNode trigger, ArrayNode outputs)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("scenario", identity);
        ArrayNode setupNode = root.putArray("setup");
        for (String step : setup) {
            setupNode.add(step);
        }
        root.put("replay", "live");
        root.set("trigger", trigger);
        root.set("outputs", outputs);
        root.putArray("volatile");
        Path file = outDir.resolve(identity + ".json");
        Files.write(file, MAPPER.writeValueAsBytes(root));
        fixtureStems.add(identity);
        System.out.println("[capture] wrote " + file);
    }

    /**
     * Appends {@code <identity> — <reason>} to UNCAPTURABLE.txt in UTF-8, preceded by a line feed when the
     * file does not end with one. Identities already listed, already written by this run, or with an
     * existing {@code <identity>.json} are not written.
     *
     * @return whether the line was written
     */
    private boolean appendUncapturable(String identity, String reason) throws IOException {
        if (listedUncapturable.contains(identity) || writtenThisRun.contains(identity)
                || fixtureStems.contains(identity)) {
            return false;
        }
        Path file = outDir.resolve(UNCAPTURABLE_FILE);
        StringBuilder text = new StringBuilder();
        if (Files.isRegularFile(file) && !endsWithLineFeed(file)) {
            text.append('\n');
        }
        text.append(identity).append(DASH).append(reason).append('\n');
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        writtenThisRun.add(identity);
        listedUncapturable.add(identity);
        System.out.println("[capture] listed " + identity + " in " + file);
        return true;
    }

    /** Whether {@code file} is empty or its last byte is a line feed. */
    private static boolean endsWithLineFeed(Path file) throws IOException {
        byte[] content = Files.readAllBytes(file);
        return content.length == 0 || content[content.length - 1] == '\n';
    }

    /**
     * Reads the identities of {@code <capture.out>/UNCAPTURABLE.txt}, if present: per non-blank line, the
     * trimmed text before {@code " — "}, or the whole trimmed line without one.
     */
    private void readUncapturable() throws IOException {
        Path file = outDir.resolve(UNCAPTURABLE_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int dash = line.indexOf(DASH);
            listedUncapturable.add(dash >= 0 ? line.substring(0, dash).trim() : trimmed);
        }
    }

    /** Reads the names, without {@code .json}, of the top-level fixture files; subfolders are not read. */
    private void readFixtureStems() throws IOException {
        if (!Files.isDirectory(outDir)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(outDir, "*.json")) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry)) {
                    String name = entry.getFileName().toString();
                    fixtureStems.add(name.substring(0, name.length() - ".json".length()));
                }
            }
        }
    }

    /** Parses {@code capture.base-url}; its host must be {@code localhost} or {@code 127.0.0.1}. */
    private static URI parseLocalBaseUrl(String value) {
        URI uri;
        try {
            uri = new URI(value == null ? "" : value.trim());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("capture.base-url must point to localhost: " + value, e);
        }
        String host = uri.getHost();
        if (host == null || !("localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host))) {
            throw new IllegalStateException("capture.base-url must point to localhost: " + value);
        }
        return uri;
    }

    /** Sleeps {@code ms} milliseconds, printing progress at most every {@value #PROGRESS_INTERVAL_MS} ms. */
    private static void pause(String identity, long ms) throws InterruptedException {
        System.out.println("[capture] " + identity + ": waiting " + ms + " ms");
        long start = System.nanoTime();
        long remaining = ms;
        while (remaining > 0) {
            Thread.sleep(Math.min(remaining, PROGRESS_INTERVAL_MS));
            remaining = ms - msSince(start);
            if (remaining > 0) {
                System.out.println("[capture] " + identity + ": waited " + msSince(start) + " of " + ms + " ms");
            }
        }
    }

    /** Milliseconds elapsed since {@code startNanos}, a {@link System#nanoTime()} reading. */
    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Polls {@code condition} every {@value #POLL_INTERVAL_MS} ms for at most {@code timeoutMs} ms. */
    private static boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws InterruptedException {
        long start = System.nanoTime();
        while (!condition.getAsBoolean()) {
            if (msSince(start) >= timeoutMs) {
                return condition.getAsBoolean();
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        return true;
    }

    /**
     * One HtmlUnit browser holding one original page (D-081). As the connection of its own client it
     * refuses every host and port other than those of {@code capture.base-url}, counts the successful
     * {@code /meta/handshake}, {@code /meta/subscribe} and {@code /meta/unsubscribe} replies served on
     * {@code /ajax/cometd}, and removes the body handlers from the served index.html for pages recorded
     * with {@code body-handlers=removed} (index.html:66). No other response byte is changed and no script
     * is added to the page; the recorder is installed through {@code executeJavaScript} after the
     * handshake.
     */
    private static final class CapturePage extends WebConnectionWrapper {

        private final WebClient client;
        private final boolean keepBodyHandlers;
        private final URI baseUri;
        private final String baseHost;
        private final int basePort;
        private final String basePath;
        private final int index;
        private final AtomicInteger handshakeOk = new AtomicInteger();
        private final AtomicInteger subscribeOk = new AtomicInteger();
        private final AtomicInteger unsubscribeOk = new AtomicInteger();
        private final List<String> alerts = Collections.synchronizedList(new ArrayList<>());
        private HtmlPage page;
        private boolean subscribed;
        private boolean closed;

        /**
         * Wraps the connection of {@code client}, which then sends every request through this page, and
         * registers a {@link CollectingAlertHandler} whose alerts are printed.
         */
        private CapturePage(WebClient client, int index, boolean keepBodyHandlers, URI baseUri) {
            super(client);
            this.client = client;
            this.index = index;
            this.keepBodyHandlers = keepBodyHandlers;
            this.baseUri = baseUri;
            this.baseHost = baseUri.getHost();
            this.basePort = baseUri.getPort() != -1 ? baseUri.getPort()
                    : ("https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80);
            this.basePath = baseUri.getRawPath() == null ? "" : baseUri.getRawPath();
            client.setAlertHandler(new CollectingAlertHandler(alerts));
        }

        /**
         * A Chrome-profile browser with JavaScript enabled, CSS disabled, and script errors and failing
         * status codes reported through the page instead of thrown. The default AJAX controller keeps the
         * page's long-poll requests asynchronous.
         */
        private static WebClient newBrowser() {
            WebClient client = new WebClient(BrowserVersion.CHROME);
            client.getOptions().setJavaScriptEnabled(true);
            client.getOptions().setThrowExceptionOnScriptError(false);
            client.getOptions().setThrowExceptionOnFailingStatusCode(false);
            client.getOptions().setCssEnabled(false);
            return client;
        }

        /**
         * Serves every request of the page: refuses requests outside {@code capture.base-url}, counts the
         * Bayeux meta replies, and returns index.html without its body handlers on pages that remove them.
         */
        @Override
        public WebResponse getResponse(WebRequest request) throws IOException {
            URL url = request.getUrl();
            int port = url.getPort() != -1 ? url.getPort() : url.getDefaultPort();
            if (!baseHost.equalsIgnoreCase(url.getHost()) || port != basePort) {
                throw new IOException("blocked request outside capture.base-url: " + url);
            }
            WebResponse response = super.getResponse(request);
            String path = url.getPath() == null ? "" : url.getPath();
            if (path.contains("/ajax/cometd")) {
                countMetaReplies(response);
            }
            String contentType = response.getContentType();
            // Rewrites only 200 text/html documents under the base path; redirects and error pages pass unchanged.
            if (!keepBodyHandlers
                    && response.getStatusCode() == 200
                    && contentType != null
                    && contentType.toLowerCase(Locale.ROOT).startsWith("text/html")
                    && path.startsWith(basePath)) {
                return withoutBodyHandlers(request, response);
            }
            return response;
        }

        /**
         * Counts successful meta replies in a Bayeux response body: a JSON array or object, optionally
         * wrapped in {@code /*} and {@code *}{@code /}. Bodies that are not JSON leave the counters unchanged.
         */
        private void countMetaReplies(WebResponse response) {
            String body = response.getContentAsString(StandardCharsets.UTF_8);
            if (body == null) {
                return;
            }
            String text = body.trim();
            if (text.startsWith("/*")) {
                text = text.substring(2);
            }
            if (text.endsWith("*/")) {
                text = text.substring(0, text.length() - 2);
            }
            JsonNode root;
            try {
                root = MAPPER.readTree(text);
            } catch (IOException notJson) {
                return;
            }
            if (root == null) {
                return;
            }
            if (root.isArray()) {
                for (JsonNode message : root) {
                    countMetaReply(message);
                }
            } else if (root.isObject()) {
                countMetaReply(root);
            }
        }

        /** Increments the counter of a meta channel for one message with {@code "successful": true}. */
        private void countMetaReply(JsonNode message) {
            JsonNode successful = message.get("successful");
            if (!message.isObject() || successful == null || !successful.isBoolean() || !successful.booleanValue()) {
                return;
            }
            switch (message.path("channel").asText("")) {
                case "/meta/handshake" -> handshakeOk.incrementAndGet();
                case "/meta/subscribe" -> subscribeOk.incrementAndGet();
                case "/meta/unsubscribe" -> unsubscribeOk.incrementAndGet();
                default -> {
                    return;
                }
            }
        }

        /**
         * Returns {@code response} with the exact text {@code <body onload="init();" onunload="dispose();">}
         * replaced by {@code <body>}; the body is decoded and re-encoded as ISO-8859-1, which keeps every other
         * byte. The rebuilt response carries the decoded body and omits the Content-Length and
         * Content-Encoding headers.
         *
         * @throws IOException when the served page does not contain that tag
         */
        private WebResponse withoutBodyHandlers(WebRequest request, WebResponse response) throws IOException {
            byte[] original;
            try (InputStream in = response.getContentAsStream()) {
                original = in == null ? new byte[0] : in.readAllBytes();
            }
            String html = new String(original, StandardCharsets.ISO_8859_1);
            if (!html.contains(BODY_TAG)) {
                throw new IOException(
                        "index.html body tag differs from websphere-mq/src/main/app/docroot/index.html:66");
            }
            byte[] rewritten = html.replace(BODY_TAG, "<body>").getBytes(StandardCharsets.ISO_8859_1);
            List<NameValuePair> headers = new ArrayList<>();
            for (NameValuePair header : response.getResponseHeaders()) {
                String name = header.getName();
                if (!"Content-Length".equalsIgnoreCase(name) && !"Content-Encoding".equalsIgnoreCase(name)) {
                    headers.add(header);
                }
            }
            WebResponseData data = new WebResponseData(
                    rewritten, response.getStatusCode(), response.getStatusMessage(), headers);
            long loadTime = response.getLoadTime();
            response.cleanUp();
            return new WebResponse(data, request, loadTime);
        }

        /**
         * Loads {@code capture.base-url}, following its redirect to the docroot, and waits up to
         * {@value #HANDSHAKE_TIMEOUT_MS} ms for a successful {@code /meta/handshake} reply, then installs the
         * recorder.
         *
         * @return {@code false} when {@code typeof mule} is not {@code 'object'} after load or the handshake
         *         reply does not arrive in time
         * @throws IllegalStateException when the final response is not an HTML page with HTTP 200
         * @throws IOException when the original cannot be reached
         */
        boolean open() throws IOException, InterruptedException {
            URL url = baseUri.toURL();
            Page loaded = client.getPage(url);
            int status = loaded.getWebResponse().getStatusCode();
            if (status != 200 || !(loaded instanceof HtmlPage)) {
                throw new IllegalStateException("original runtime not reachable at " + url + ": HTTP " + status
                        + " " + loaded.getWebResponse().getContentType() + " from " + loaded.getUrl());
            }
            page = (HtmlPage) loaded;
            printAlerts();
            Object muleType = page.executeJavaScript("typeof mule").getJavaScriptResult();
            if (!"object".equals(String.valueOf(muleType))) {
                System.out.println("[capture] page " + index + ": typeof mule is " + muleType + " after load");
                return false;
            }
            boolean handshake = waitUntil(() -> handshakeOk.get() > 0, HANDSHAKE_TIMEOUT_MS);
            printAlerts();
            if (!handshake) {
                System.out.println("[capture] page " + index + ": no successful /meta/handshake reply within "
                        + HANDSHAKE_TIMEOUT_MS + " ms");
                return false;
            }
            page.executeJavaScript(RECORDER_SCRIPT);
            System.out.println("[capture] page " + index + ": loaded " + page.getUrl() + " with body handlers "
                    + (keepBodyHandlers ? "kept" : "removed") + ", Bayeux handshake completed");
            return true;
        }

        /** Subscribes the recorder to the dequeue channel and waits for the {@code /meta/subscribe} reply. */
        void subscribe() throws InterruptedException {
            int before = subscribeOk.get();
            page.executeJavaScript("mule.subscribe('" + DEQUEUE + "', window.__recorder);");
            subscribed = true;
            boolean confirmed = waitUntil(() -> subscribeOk.get() > before, META_CONFIRM_TIMEOUT_MS);
            printAlerts();
            System.out.println("[capture] page " + index + ": mule.subscribe " + DEQUEUE
                    + (confirmed ? " confirmed" : " not confirmed within " + META_CONFIRM_TIMEOUT_MS + " ms"));
        }

        /** Unsubscribes the recorder from the dequeue channel and waits for the {@code /meta/unsubscribe} reply. */
        void unsubscribe() throws InterruptedException {
            int before = unsubscribeOk.get();
            page.executeJavaScript("mule.unsubscribe('" + DEQUEUE + "', window.__recorder);");
            subscribed = false;
            boolean confirmed = waitUntil(() -> unsubscribeOk.get() > before, META_CONFIRM_TIMEOUT_MS);
            printAlerts();
            System.out.println("[capture] page " + index + ": mule.unsubscribe " + DEQUEUE
                    + (confirmed ? " confirmed" : " not confirmed within " + META_CONFIRM_TIMEOUT_MS + " ms"));
        }

        /** Calls {@code mule.rpc} on the enqueue channel with {@code data} and no callback, as index.html:55. */
        void rpc(String data) throws IOException {
            page.executeJavaScript("mule.rpc('" + ENQUEUE + "', " + MAPPER.writeValueAsString(data) + ");");
            printAlerts();
            System.out.println("[capture] page " + index + ": mule.rpc " + ENQUEUE + " " + data);
        }

        /** Types {@code data} into {@code #phrase} and clicks {@code #sendButton}, the page's {@code rpcEnqueue()}. */
        void clickSend(String data) throws IOException {
            HtmlTextInput phrase = page.getHtmlElementById("phrase");
            phrase.type(data);
            page.getHtmlElementById("sendButton").click();
            printAlerts();
            System.out.println("[capture] page " + index + ": #sendButton clicked with #phrase " + data);
        }

        /**
         * Reads the recorder's list.
         *
         * @return the received {@code (dataType, data)} items in arrival order
         * @throws IllegalStateException when the page holds no recorder
         */
        List<Map.Entry<String, String>> captured() throws IOException {
            Object result = page.executeJavaScript("JSON.stringify(window.__captured)").getJavaScriptResult();
            printAlerts();
            if (!(result instanceof CharSequence)) {
                throw new IllegalStateException("page " + index + " holds no recorder: " + result);
            }
            List<Map.Entry<String, String>> items = new ArrayList<>();
            for (JsonNode item : MAPPER.readTree(result.toString())) {
                items.add(Map.entry(item.path("t").asText(), item.path("v").asText()));
            }
            return items;
        }

        /**
         * Unsubscribes the recorder if subscribed, runs the page's {@code dispose()} on pages that keep
         * their body handlers, each waiting for its {@code /meta/unsubscribe} reply, and always closes the
         * browser. A second call does nothing.
         */
        void closePage() throws IOException, InterruptedException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (page != null && subscribed) {
                    unsubscribe();
                }
            } finally {
                try {
                    if (page != null && keepBodyHandlers) {
                        int before = unsubscribeOk.get();
                        page.executeJavaScript("dispose();");
                        boolean confirmed = waitUntil(() -> unsubscribeOk.get() > before, META_CONFIRM_TIMEOUT_MS);
                        System.out.println("[capture] page " + index + ": dispose() " + (confirmed
                                ? "confirmed"
                                : "not confirmed within " + META_CONFIRM_TIMEOUT_MS + " ms"));
                    }
                } finally {
                    printAlerts();
                    page = null;
                    client.close();
                }
            }
        }

        /** Prints and clears the alerts the page raised since the last call. */
        private void printAlerts() {
            synchronized (alerts) {
                for (String alert : alerts) {
                    System.out.println("[capture] page " + index + " alert: " + alert);
                }
                alerts.clear();
            }
        }
    }
}

