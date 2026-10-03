package com.mulesoft.examples.service_orchestration_and_choice_routing.capture;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gargoylesoftware.htmlunit.BrowserVersion;
import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.ScriptResult;
import com.gargoylesoftware.htmlunit.WebClient;
import com.gargoylesoftware.htmlunit.html.HtmlPage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Tier 2A capture harness of D-081 for the AJAX channels of the original Mule 3.8
 * service-orchestration-and-choice-routing application.
 *
 * <p>The harness loads the original page at the URL in the system property {@code capture.base-url}
 * with HtmlUnit (JavaScript on, CSS off), waits until the page's own Bayeux client is ready, and calls
 * {@code mule.rpc} through that client on the three channels the original
 * {@code docroot/index.html:119,160,166} calls: {@code /orders/request}, {@code /orders/soap} and
 * {@code /orders/manufacturers}. Each callback receives one message whose {@code data} is the reply
 * payload, or the exception message when the flow fails (D-077).
 *
 * <p>The five channel scenarios run once each, in the order of {@code fixtures/SCENARIOS.txt}, on the
 * same page. For each scenario the harness writes, into the directory named by the system property
 * {@code capture.out}, either the fixture {@code <identity>.json} or one {@code UNCAPTURABLE.txt}
 * line {@code <identity> — <reason>}; the latest outcome of an identity replaces any earlier one.
 * A fixture holds the request data as an {@code ajax} trigger and the reply data as one {@code ajax}
 * output, both base64 encoded. The output {@code dataType} is {@code string} when the received
 * {@code data} is a string, and {@code json} otherwise, with the bytes of the in-page
 * {@code JSON.stringify(data)} (D-081).
 *
 * <p>An unreachable page, or a Bayeux client that does not become ready under HtmlUnit, records all five
 * scenarios in {@code UNCAPTURABLE.txt}. The test fails only when {@code capture.out} is missing, the
 * classpath resource {@code original/message.xml} is missing, {@code capture.base-url} is not a URL or
 * does not answer an HTML page, or a file cannot be written.
 *
 * <p>Run command, from {@code service-orchestration-and-choice-routing-java/} on the Java 17 toolchain:
 * <pre>
 * mvn -B test -Dtest=AjaxChannelCapture -Dcapture.base-url=http://localhost:8090/orders \
 *     -Dcapture.out=&lt;fixtures directory&gt; -Djacoco.skip=true
 * </pre>
 * {@code -Djacoco.skip=true} turns off the coverage check for this single-class run. Without
 * {@code capture.base-url} the class is skipped.
 */
@EnabledIfSystemProperty(named = "capture.base-url", matches = ".+")
public class AjaxChannelCapture {

    /** Prefix of every scenario identity of this example. */
    private static final String PREFIX = "service-orchestration-and-choice-routing_";

    /** Longest wait for one {@code mule.rpc} callback. */
    private static final long CALLBACK_TIMEOUT_MS = 60_000;

    /** Longest wait for the page to load, and then for its Bayeux client to become ready. */
    private static final long READY_TIMEOUT_MS = 60_000;

    /** Interval of every poll of the page. */
    private static final long POLL_MS = 250;

    /** Interval between two attempts to load the page. */
    private static final long PAGE_RETRY_MS = 2_000;

    /** File name of the uncapturable-identity list inside {@code capture.out}. */
    private static final String UNCAPTURABLE = "UNCAPTURABLE.txt";

    /** Separator between identity and reason in {@code UNCAPTURABLE.txt}: space, U+2014 EM DASH, space. */
    private static final String SEPARATOR = " \u2014 ";

    /** Classpath resource holding the original SOAP request {@code message.xml}. */
    private static final String MESSAGE_RESOURCE = "/original/message.xml";

    /** Writes the fixtures and the JavaScript string literals, and parses the callback results. */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Standard base64 encoder, with padding and without line breaks. */
    private static final Base64.Encoder BASE64 = Base64.getEncoder();

    /** Progress log of the capture run. */
    private static final System.Logger LOG = System.getLogger(AjaxChannelCapture.class.getName());

    /**
     * Order request of {@code order-request}: the text the page's {@code JSON.stringify(order)} produces
     * for order 12 of John Doe, Main Street 123, with one Samsung {@code s-1} {@code AX02} of quantity 1.
     */
    private static final String ORDER_JSON = "{\"orderId\":\"12\",\"customer\":{\"firstName\":\"John\","
            + "\"lastName\":\"Doe\",\"address\":\"Main Street 123\"},\"orderItems\":[{\"item\":"
            + "{\"manufacturer\":\"Samsung\",\"name\":\"s-1\",\"productId\":\"AX02\",\"quantity\":\"1\"}}]}";

    /** SOAP request of {@code order-proxy-fault}: an operation the order service does not declare. */
    private static final String FAULT_ENVELOPE = "<soapenv:Envelope"
            + " xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\""
            + " xmlns:ord=\"http://orders.se.mulesoft.com/\">"
            + "<soapenv:Header/><soapenv:Body><ord:unknownOperation/></soapenv:Body></soapenv:Envelope>";

    /** Request data of {@code order-request-failure-reply}. */
    private static final String NOT_JSON = "not json";

    /**
     * True once {@code mule.rpc} exists and, where the Dojo cometd object exposes {@code clientId},
     * once that client id is set.
     */
    private static final String READY_PREDICATE = "(typeof mule !== 'undefined' && typeof mule.rpc === 'function'"
            + " && (typeof dojox === 'undefined' || !dojox.cometd || !('clientId' in dojox.cometd)"
            + " || !!dojox.cometd.clientId))";

    /**
     * Sends one RPC. {@code %1$s} is the window key, {@code %2$s} the channel literal and {@code %3$s} the
     * data literal. The callback stores the type and the text of the message's {@code data} under the
     * window key; the script returns {@code sent}, or {@code error: <exception>} when {@code mule.rpc}
     * throws.
     */
    private static final String RPC_SCRIPT = """
            (function () {
              window.%1$s = undefined;
              try {
                mule.rpc(%2$s, %3$s, function (m) {
                  var d = (m === undefined || m === null) ? undefined : m.data;
                  window.%1$s = { type: typeof d, text: (typeof d === 'string') ? d : JSON.stringify(d) };
                });
                return 'sent';
              } catch (e) { return 'error: ' + e; }
            })()
            """;

    /** Reads the stored callback result of window key {@code %1$s} as JSON text, or null before the callback. */
    private static final String POLL_SCRIPT = "(window.%1$s === undefined) ? null : JSON.stringify(window.%1$s)";

    /**
     * Captures the five AJAX channel scenarios from the running original into {@code capture.out}.
     * A missing {@code capture.out} or {@code original/message.xml}, or a {@code capture.base-url} that is
     * not a URL or not an HTML page, fails the test through {@link Assertions#fail(String)}.
     *
     * @throws Exception when a file cannot be read or written, or a wait is interrupted
     */
    @Test
    public void captureChannels() throws Exception {
        String out = System.getProperty("capture.out");
        if (out == null || out.isBlank()) {
            Assertions.fail("capture.out must name the fixtures directory");
        }
        String baseUrl = System.getProperty("capture.base-url");
        List<Scenario> scenarios = scenarios(readMessageXml());
        Path outDir = Paths.get(out);
        Files.createDirectories(outDir);

        try (WebClient client = new WebClient(BrowserVersion.CHROME)) {
            client.getOptions().setJavaScriptEnabled(true);
            client.getOptions().setCssEnabled(false);
            client.getOptions().setThrowExceptionOnScriptError(false);

            HtmlPage page = openPage(client, baseUrl, outDir, scenarios);
            if (page == null) {
                return;
            }
            if (!awaitClient(client, page)) {
                recordAll(outDir, scenarios, "page Bayeux client did not complete its handshake under HtmlUnit within "
                        + seconds(READY_TIMEOUT_MS) + " s");
                return;
            }
            int captured = 0;
            for (int index = 0; index < scenarios.size(); index++) {
                if (capture(client, page, outDir, index, scenarios.get(index))) {
                    captured++;
                }
            }
            LOG.log(System.Logger.Level.INFO, "captured {0} of {1} AJAX channel scenarios into {2}",
                    captured, scenarios.size(), outDir);
        }
    }

    /**
     * The five channel scenarios, in the order of {@code fixtures/SCENARIOS.txt}.
     *
     * @param messageXml the text of {@code original/message.xml}, the request of {@code order-proxy}
     * @return the scenarios, each with its channel and its request data
     */
    private static List<Scenario> scenarios(String messageXml) {
        List<Scenario> scenarios = new ArrayList<>();
        scenarios.add(new Scenario("order-request", "/orders/request", ORDER_JSON));
        scenarios.add(new Scenario("order-proxy", "/orders/soap", messageXml));
        scenarios.add(new Scenario("order-proxy-fault", "/orders/soap", FAULT_ENVELOPE));
        scenarios.add(new Scenario("manufacturers", "/orders/manufacturers", ""));
        scenarios.add(new Scenario("order-request-failure-reply", "/orders/request", NOT_JSON));
        return List.copyOf(scenarios);
    }

    /**
     * Reads the classpath resource {@code /original/message.xml} as UTF-8 text, unchanged.
     *
     * @return the full text of the resource
     * @throws IOException when the resource cannot be read
     */
    private static String readMessageXml() throws IOException {
        try (InputStream in = AjaxChannelCapture.class.getResourceAsStream(MESSAGE_RESOURCE)) {
            if (in == null) {
                return Assertions.fail("original/message.xml not on the test classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Loads the original page at {@code baseUrl}, following redirects, and retries every
     * {@value #PAGE_RETRY_MS} ms on an I/O error or a failing HTTP status until {@value #READY_TIMEOUT_MS} ms
     * have passed. When the page never loads, every scenario is recorded as uncapturable with the last failure.
     *
     * @param client    the HtmlUnit client
     * @param baseUrl   the value of {@code capture.base-url}, used as given
     * @param outDir    the {@code capture.out} directory
     * @param scenarios the scenarios recorded as uncapturable when the page never loads
     * @return the loaded page, or null when it never loaded
     * @throws IOException          when {@code UNCAPTURABLE.txt} cannot be written
     * @throws InterruptedException when the wait between two attempts is interrupted
     */
    private static HtmlPage openPage(WebClient client, String baseUrl, Path outDir, List<Scenario> scenarios)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS);
        Exception failure;
        while (true) {
            Page loaded;
            try {
                loaded = client.getPage(baseUrl);
            } catch (MalformedURLException e) {
                // A capture.base-url that is not a URL fails the run; it is not retried.
                return Assertions.fail("capture.base-url is not a valid URL: " + baseUrl, e);
            } catch (IOException | FailingHttpStatusCodeException e) {
                failure = e;
                long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                if (remaining <= 0) {
                    break;
                }
                LOG.log(System.Logger.Level.INFO, "original page {0} not loaded yet: {1}", baseUrl, e.toString());
                Thread.sleep(Math.min(PAGE_RETRY_MS, remaining));
                continue;
            }
            if (loaded instanceof HtmlPage html) {
                return html;
            }
            // A page at capture.base-url that is not HTML fails the run.
            return Assertions.fail("capture.base-url " + baseUrl + " answered "
                    + loaded.getWebResponse().getContentType() + ", not an HTML page");
        }
        recordAll(outDir, scenarios, "original page " + baseUrl + " not reachable: "
                + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        return null;
    }

    /**
     * Polls the page every {@value #POLL_MS} ms, for at most {@value #READY_TIMEOUT_MS} ms, until the
     * readiness predicate is true: {@code mule.rpc} is a function and, where the Dojo cometd object exposes
     * {@code clientId}, the Bayeux handshake has set it.
     *
     * @param client the HtmlUnit client
     * @param page   the loaded original page
     * @return true once the predicate holds, false when the time ran out
     * @throws InterruptedException when a poll is interrupted
     */
    private static boolean awaitClient(WebClient client, HtmlPage page) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READY_TIMEOUT_MS);
        do {
            pause(client);
            if (Boolean.TRUE.equals(result(page.executeJavaScript(READY_PREDICATE)))) {
                return true;
            }
        } while (System.nanoTime() - deadline < 0);
        return false;
    }

    /**
     * Sends the scenario's RPC once through the page's {@code mule.rpc}, waits for its callback, and records
     * the outcome: a fixture, or an {@code UNCAPTURABLE.txt} line when {@code mule.rpc} throws, no callback
     * arrives within {@value #CALLBACK_TIMEOUT_MS} ms, or the message carries no {@code data}.
     *
     * @param client   the HtmlUnit client
     * @param page     the ready original page
     * @param outDir   the {@code capture.out} directory
     * @param index    the scenario's position, which names its window key {@code __capture_<index>}
     * @param scenario the scenario
     * @return true when a fixture was written
     * @throws IOException          when a file cannot be written or the callback result cannot be parsed
     * @throws InterruptedException when a poll is interrupted
     */
    private static boolean capture(WebClient client, HtmlPage page, Path outDir, int index, Scenario scenario)
            throws IOException, InterruptedException {
        String key = "__capture_" + index;
        Object sent = result(page.executeJavaScript(
                RPC_SCRIPT.formatted(key, jsLiteral(scenario.channel()), jsLiteral(scenario.data()))));
        String sendResult = String.valueOf(sent);
        if (!"sent".equals(sendResult)) {
            recordUncapturable(outDir, scenario.identity(), "mule.rpc threw: " + sendResult);
            return false;
        }

        String reply = awaitReply(client, page, key);
        if (reply == null) {
            recordUncapturable(outDir, scenario.identity(),
                    "no reply on " + scenario.channel() + " within " + seconds(CALLBACK_TIMEOUT_MS) + " s");
            return false;
        }

        JsonNode message = MAPPER.readTree(reply);
        JsonNode type = message.get("type");
        JsonNode text = message.get("text");
        if (type == null || "undefined".equals(type.asText()) || text == null || !text.isTextual()) {
            recordUncapturable(outDir, scenario.identity(), "reply on " + scenario.channel() + " carried no data");
            return false;
        }
        String dataType = "string".equals(type.asText()) ? "string" : "json";
        writeFixture(outDir, scenario, dataType, text.asText().getBytes(StandardCharsets.UTF_8));
        removeUncapturable(outDir, scenario.identity());
        LOG.log(System.Logger.Level.INFO, "captured {0} ({1} data on {2})",
                scenario.identity(), dataType, scenario.channel());
        return true;
    }

    /**
     * Polls window key {@code key} every {@value #POLL_MS} ms, for at most {@value #CALLBACK_TIMEOUT_MS} ms.
     *
     * @param client the HtmlUnit client
     * @param page   the original page
     * @param key    the scenario's window key
     * @return the JSON text {@code {"type":…,"text":…}} the callback stored, or null when no callback ran in time
     * @throws InterruptedException when a poll is interrupted
     */
    private static String awaitReply(WebClient client, HtmlPage page, String key) throws InterruptedException {
        String script = POLL_SCRIPT.formatted(key);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CALLBACK_TIMEOUT_MS);
        do {
            pause(client);
            Object stored = result(page.executeJavaScript(script));
            if (stored instanceof CharSequence) {
                return stored.toString();
            }
        } while (System.nanoTime() - deadline < 0);
        return null;
    }

    /**
     * Lets background JavaScript run for up to {@value #POLL_MS} ms, then sleeps for the rest of that
     * interval when it returned sooner. Each call lasts at least {@value #POLL_MS} ms.
     *
     * @param client the HtmlUnit client
     * @throws InterruptedException when the sleep is interrupted
     */
    private static void pause(WebClient client) throws InterruptedException {
        long started = System.nanoTime();
        client.waitForBackgroundJavaScript(POLL_MS);
        long rest = POLL_MS - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        if (rest > 0) {
            Thread.sleep(rest);
        }
    }

    /**
     * The JavaScript value of an executed script.
     *
     * @param result the script result, possibly null
     * @return the value, with a JavaScript string as a Java {@link String}; null for a null result
     */
    private static Object result(ScriptResult result) {
        Object value = result == null ? null : result.getJavaScriptResult();
        return value instanceof CharSequence ? value.toString() : value;
    }

    /**
     * A JavaScript string literal for {@code s}: its JSON string form, with U+2028 and U+2029 written as
     * escape sequences.
     *
     * @param s the text
     * @return the quoted, escaped literal
     * @throws IOException when Jackson cannot write the string
     */
    private static String jsLiteral(String s) throws IOException {
        return MAPPER.writeValueAsString(s).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }


    /**
     * Writes {@code <identity>.json} into {@code outDir}, creating or truncating it, as pretty-printed UTF-8
     * JSON followed by one {@code \n}. The keys are, in order: {@code scenario}, {@code setup} (empty),
     * {@code replay} ({@code embedded}), {@code trigger} (an {@code ajax} trigger with the request data as a
     * {@code string}), {@code outputs} (one {@code ajax} output with the reply data) and {@code volatile}
     * (empty). Both {@code channel} values are the RPC channel.
     *
     * @param outDir   the {@code capture.out} directory
     * @param scenario the captured scenario
     * @param dataType {@code string} or {@code json}
     * @param reply    the reply data bytes
     * @throws IOException when the file cannot be written
     */
    private static void writeFixture(Path outDir, Scenario scenario, String dataType, byte[] reply)
            throws IOException {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("scenario", scenario.identity());
        root.putArray("setup");
        root.put("replay", "embedded");

        ObjectNode trigger = root.putObject("trigger");
        trigger.put("kind", "ajax");
        trigger.put("channel", scenario.channel());
        trigger.put("dataType", "string");
        trigger.put("dataBase64", BASE64.encodeToString(scenario.data().getBytes(StandardCharsets.UTF_8)));

        ArrayNode outputs = root.putArray("outputs");
        ObjectNode output = outputs.addObject();
        output.put("kind", "ajax");
        output.put("channel", scenario.channel());
        output.put("dataType", dataType);
        output.put("dataBase64", BASE64.encodeToString(reply));

        root.putArray("volatile");

        byte[] json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
        byte[] content = Arrays.copyOf(json, json.length + 1);
        content[json.length] = '\n';
        Files.write(fixturePath(outDir, scenario.identity()), content);
    }

    /**
     * Records every scenario as uncapturable with the same reason.
     *
     * @param outDir    the {@code capture.out} directory
     * @param scenarios the scenarios
     * @param reason    the reason
     * @throws IOException when {@code UNCAPTURABLE.txt} cannot be written
     */
    private static void recordAll(Path outDir, List<Scenario> scenarios, String reason) throws IOException {
        for (Scenario scenario : scenarios) {
            recordUncapturable(outDir, scenario.identity(), reason);
        }
    }

    /**
     * Replaces the {@code UNCAPTURABLE.txt} line of {@code identity} with {@code <identity> — <reason>} and
     * deletes {@code <identity>.json} when it exists. Every run of CR and LF characters in {@code reason}
     * becomes one space. The lines of other identities stay unchanged; the file is written as UTF-8 with
     * {@code \n} after every line, and is created when absent.
     *
     * @param outDir   the {@code capture.out} directory
     * @param identity the scenario identity
     * @param reason   the reason the scenario has no fixture
     * @throws IOException when a file cannot be read, written or deleted
     */
    private static void recordUncapturable(Path outDir, String identity, String reason) throws IOException {
        Path file = outDir.resolve(UNCAPTURABLE);
        String oneLineReason = reason.replaceAll("[\\r\\n]+", " ");
        List<String> lines = otherLines(readLines(file), identity);
        lines.add(identity + SEPARATOR + oneLineReason);
        writeLines(file, lines);
        Files.deleteIfExists(fixturePath(outDir, identity));
        LOG.log(System.Logger.Level.WARNING, "uncapturable {0}: {1}", identity, oneLineReason);
    }

    /**
     * Removes the {@code UNCAPTURABLE.txt} line of {@code identity}, keeping every other line unchanged.
     * Does nothing when the file does not exist or holds no line of {@code identity}.
     *
     * @param outDir   the {@code capture.out} directory
     * @param identity the scenario identity
     * @throws IOException when the file cannot be read or written
     */
    private static void removeUncapturable(Path outDir, String identity) throws IOException {
        Path file = outDir.resolve(UNCAPTURABLE);
        if (!Files.exists(file)) {
            return;
        }
        List<String> lines = readLines(file);
        List<String> retained = otherLines(lines, identity);
        if (retained.size() != lines.size()) {
            writeLines(file, retained);
        }
    }

    /**
     * The UTF-8 lines of {@code file}.
     *
     * @param file the {@code UNCAPTURABLE.txt} file
     * @return its lines; empty when the file does not exist
     * @throws IOException when the file cannot be read
     */
    private static List<String> readLines(Path file) throws IOException {
        return Files.exists(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
    }

    /**
     * The lines that do not start with {@code identity + " "}, in their order.
     *
     * @param lines    the lines of {@code UNCAPTURABLE.txt}
     * @param identity the scenario identity
     * @return a mutable list of the retained lines
     */
    private static List<String> otherLines(List<String> lines, String identity) {
        String own = identity + " ";
        List<String> retained = new ArrayList<>();
        for (String line : lines) {
            if (!line.startsWith(own)) {
                retained.add(line);
            }
        }
        return retained;
    }

    /**
     * Writes {@code lines} to {@code file} as UTF-8, each followed by {@code \n}, creating or truncating it.
     *
     * @param file  the target file
     * @param lines the lines
     * @throws IOException when the file cannot be written
     */
    private static void writeLines(Path file, List<String> lines) throws IOException {
        StringBuilder text = new StringBuilder();
        for (String line : lines) {
            text.append(line).append('\n');
        }
        Files.write(file, text.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The fixture file of {@code identity}.
     *
     * @param outDir   the {@code capture.out} directory
     * @param identity the scenario identity
     * @return {@code <outDir>/<identity>.json}
     */
    private static Path fixturePath(Path outDir, String identity) {
        return outDir.resolve(identity + ".json");
    }

    /**
     * Whole seconds of a millisecond duration.
     *
     * @param millis the duration in milliseconds
     * @return the duration in seconds
     */
    private static long seconds(long millis) {
        return TimeUnit.MILLISECONDS.toSeconds(millis);
    }

    /**
     * One AJAX channel scenario.
     *
     * @param suffix  the scenario name after {@link #PREFIX}
     * @param channel the RPC channel, which is also the fixture's output channel
     * @param data    the request data, sent unchanged
     */
    private record Scenario(String suffix, String channel, String data) {

        /**
         * The scenario identity.
         *
         * @return {@code PREFIX + suffix}
         */
        private String identity() {
            return PREFIX + suffix;
        }
    }
}
