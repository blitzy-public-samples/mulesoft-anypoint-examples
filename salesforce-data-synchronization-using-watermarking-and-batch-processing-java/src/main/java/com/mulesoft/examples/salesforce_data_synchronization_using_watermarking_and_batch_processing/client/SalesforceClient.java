package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.client;

import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.bind.XmlObject;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the partner API calls of the global element {@code sfdc:config name="Salesforce"}
 * [salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml:3]
 * on the session that {@link SalesforceSessionProvider} supplies (D-013), and classifies every failure of
 * those calls (D-020).
 *
 * <p><b>Operations.</b> {@link #query(String)} replaces the connector call
 * {@code <sfdc:query config-ref="Salesforce" query="dsql:SELECT Id,Name,LastModifiedDate FROM Contact WHERE
 * LastModifiedDate > #[flowVars['timestamp']]"/>} [watermarking.xml:19]. It sends the given SOQL text
 * through {@link PartnerConnection#query(String)}, never {@code queryAll}, and reads every further result
 * page through {@link PartnerConnection#queryMore(String)} (D-611). This class sends no other partner API
 * call.
 *
 * <p><b>Failure classification</b> (D-020). Every partner API call runs inside
 * {@link #execute(String, Supplier)}, the only place a failure is classified. Each exception names the
 * upstream system {@code Salesforce}:
 * <ul>
 *   <li>{@link ApiFault} {@link ExceptionCode#INVALID_SESSION_ID} on the first attempt:
 *       {@link SalesforceSessionProvider#refresh()} runs once and the call is invoked exactly once more;
 *       {@code INVALID_SESSION_ID} on that second attempt: {@link UpstreamAuthenticationException};</li>
 *   <li>{@link ApiFault} {@link ExceptionCode#REQUEST_LIMIT_EXCEEDED}: {@link UpstreamRateLimitException}
 *       with no {@code Retry-After} value, after one attempt;</li>
 *   <li>a {@link SocketTimeoutException} or {@link ConnectException} anywhere in the cause chain:
 *       {@link UpstreamUnavailableException}, after one attempt;</li>
 *   <li>any other {@link ConnectionException}: {@link IllegalStateException} with message
 *       {@code Salesforce <operation> failed: <detail>}, the detail being
 *       {@code <exception code>: <exception message>} for an {@link ApiFault} and the exception message
 *       otherwise;</li>
 *   <li>an {@code Upstream*} exception thrown by the session provider: rethrown unchanged; any other
 *       runtime exception: rethrown unchanged.</li>
 * </ul>
 * No other retry, loop or sleep exists.
 *
 * <p><b>Records.</b> Each {@link SObject} becomes a {@link HashMap} holding {@code type} =
 * {@link SObject#getType()} and one entry per child element, keyed by the element's local name; the
 * {@code type} and {@code fieldsToNull} children are skipped. A repeated child name keeps its first
 * non-null value: a non-null value replaces an earlier {@code null}, and a later value never replaces a
 * non-null one. {@link Calendar} and {@link Date} values become {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} text in
 * UTC, {@link String} values stay unchanged, any other non-null value becomes its
 * {@link String#valueOf(Object)} text, and a nil or complex child is stored as {@code null}. On JDK 17 the
 * README record [salesforce-data-synchronization-using-watermarking-and-batch-processing/README.md:36]
 * prints as {@code {LastModifiedDate=2014-07-04T06:16:54.000Z, Id=0032000001DpkrEAAR, type=Contact,
 * Name=Avi123 Green}}.
 *
 * <p>The constructor sends no request: the session provider is called only inside
 * {@link #query(String)}. The class holds no mutable state; concurrent calls share the provider's session.
 */
@Component
public class SalesforceClient {

    private static final Logger log = LoggerFactory.getLogger(SalesforceClient.class);

    private static final String UPSTREAM = "Salesforce";

    private static final String TYPE_ELEMENT = "type";

    private static final String FIELDS_TO_NULL_ELEMENT = "fieldsToNull";

    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final SalesforceSessionProvider sessionProvider;

    /**
     * Creates the client on the given session provider without sending any request.
     *
     * @param sessionProvider supplier of the cached partner connection and of its re-authentication
     */
    public SalesforceClient(SalesforceSessionProvider sessionProvider) {
        this.sessionProvider = Objects.requireNonNull(sessionProvider, "sessionProvider");
    }

    /**
     * Runs one partner API call and classifies its failure (D-020).
     *
     * <p>The call is invoked once. When it fails with {@link ApiFault} {@link ExceptionCode#INVALID_SESSION_ID},
     * {@link SalesforceSessionProvider#refresh()} runs once and the call is invoked exactly once more; an
     * {@code Upstream*} exception thrown by {@code refresh()} propagates unchanged. Every other failure, and
     * every failure of the second invocation, is classified with no further invocation:
     * <ol>
     *   <li>{@code INVALID_SESSION_ID} on the second invocation: {@link UpstreamAuthenticationException}
     *       {@code "Salesforce session invalid after re-authentication"};</li>
     *   <li>{@link ExceptionCode#REQUEST_LIMIT_EXCEEDED}: {@link UpstreamRateLimitException}
     *       {@code "Salesforce request limit exceeded during <operation>"} with no {@code Retry-After}
     *       value;</li>
     *   <li>an {@code Upstream*} exception thrown by the session provider: rethrown unchanged;</li>
     *   <li>a {@link SocketTimeoutException} or {@link ConnectException} anywhere in the cause chain:
     *       {@link UpstreamUnavailableException} {@code "Salesforce <operation> unreachable"};</li>
     *   <li>any other {@link ConnectionException}: {@link IllegalStateException}
     *       {@code "Salesforce <operation> failed: <detail>"};</li>
     *   <li>any other runtime exception: rethrown unchanged.</li>
     * </ol>
     * An exception classified from a partner API failure carries that {@link ConnectionException} as its
     * cause; one classified from a runtime exception carries that runtime exception.
     *
     * @param operation name of the partner API operation, used in exception messages and logs
     * @param call      the partner API call; it is invoked once, or twice after a re-authentication
     * @param <T>       result type of the call
     * @return the result of the successful invocation
     * @throws UpstreamAuthenticationException when the session is rejected again after re-authentication
     * @throws UpstreamRateLimitException      when Salesforce reports its request limit as exceeded
     * @throws UpstreamUnavailableException    when the call times out or cannot connect
     * @throws IllegalStateException           when the call fails in any other partner API way
     */
    public <T> T execute(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        try {
            return call.get();
        } catch (SalesforceCallException failure) {
            ConnectionException cause = failure.connectionException();
            if (!isInvalidSession(cause)) {
                throw classify(operation, cause);
            }
            log.debug("Salesforce {} rejected the session; re-authenticating once", operation);
        } catch (RuntimeException failure) {
            throw classify(operation, failure);
        }
        return invokeAfterRefresh(operation, call);
    }

    /**
     * Runs the SOQL query and returns every matching record, following each result page (D-611).
     *
     * <p>The first page comes from {@link PartnerConnection#query(String)}; while a page reports
     * {@link QueryResult#isDone()} {@code false}, the next page comes from
     * {@link PartnerConnection#queryMore(String)} with that page's query locator. Each page is one
     * {@link #execute(String, Supplier)} call, named {@code query} or {@code queryMore}.
     *
     * @param soql the SOQL text to run
     * @return the converted records of every page in page order; empty, never {@code null}, when nothing
     *         matches
     * @throws UpstreamAuthenticationException when the session is rejected again after re-authentication
     * @throws UpstreamRateLimitException      when Salesforce reports its request limit as exceeded
     * @throws UpstreamUnavailableException    when a page request times out or cannot connect
     * @throws IllegalStateException           when a page request fails in any other partner API way, a page
     *                                         is missing, or a page that is not done carries no query locator
     */
    public List<Map<String, Object>> query(String soql) {
        Objects.requireNonNull(soql, "soql");
        List<Map<String, Object>> records = new ArrayList<>();
        QueryResult page = requirePage("query", execute("query", onConnection(c -> c.query(soql))));
        appendRecords(page, records);
        int pages = 1;
        while (!page.isDone()) {
            String locator = page.getQueryLocator();
            if (locator == null || locator.isBlank()) {
                throw new IllegalStateException(
                        "Salesforce query page " + pages + " is not done and carries no query locator");
            }
            page = requirePage("queryMore", execute("queryMore", onConnection(c -> c.queryMore(locator))));
            appendRecords(page, records);
            pages++;
        }
        log.debug("Salesforce query returned {} records in {} pages", records.size(), pages);
        return records;
    }

    /**
     * Re-authenticates once and invokes the call exactly once more, classifying a second failure with no
     * further invocation.
     */
    private <T> T invokeAfterRefresh(String operation, Supplier<T> call) {
        try {
            sessionProvider.refresh();
            return call.get();
        } catch (SalesforceCallException failure) {
            ConnectionException cause = failure.connectionException();
            if (isInvalidSession(cause)) {
                throw new UpstreamAuthenticationException(
                        "Salesforce session invalid after re-authentication", UPSTREAM, cause);
            }
            throw classify(operation, cause);
        } catch (RuntimeException failure) {
            throw classify(operation, failure);
        }
    }

    /**
     * Maps a partner API failure to the rate-limit, unavailable or unclassified outcome, in that order.
     */
    private static RuntimeException classify(String operation, ConnectionException failure) {
        if (failure instanceof ApiFault limitFault
                && limitFault.getExceptionCode() == ExceptionCode.REQUEST_LIMIT_EXCEEDED) {
            return new UpstreamRateLimitException(
                    "Salesforce request limit exceeded during " + operation, UPSTREAM, null, failure);
        }
        if (hasNetworkCause(failure)) {
            return new UpstreamUnavailableException("Salesforce " + operation + " unreachable", UPSTREAM, failure);
        }
        String detail = failure instanceof ApiFault fault
                ? fault.getExceptionCode() + ": " + fault.getExceptionMessage()
                : failure.getMessage();
        return new IllegalStateException("Salesforce " + operation + " failed: " + detail, failure);
    }

    /**
     * Passes an {@code Upstream*} exception through unchanged, maps a network cause to
     * {@link UpstreamUnavailableException} and returns any other runtime exception unchanged.
     */
    private static RuntimeException classify(String operation, RuntimeException failure) {
        if (failure instanceof UpstreamAuthenticationException
                || failure instanceof UpstreamRateLimitException
                || failure instanceof UpstreamUnavailableException) {
            return failure;
        }
        if (hasNetworkCause(failure)) {
            return new UpstreamUnavailableException("Salesforce " + operation + " unreachable", UPSTREAM, failure);
        }
        return failure;
    }

    private static boolean isInvalidSession(ConnectionException failure) {
        return failure instanceof ApiFault fault && fault.getExceptionCode() == ExceptionCode.INVALID_SESSION_ID;
    }

    /**
     * Reports whether a {@link SocketTimeoutException} or {@link ConnectException} occurs in the cause chain,
     * visiting each throwable at most once.
     */
    private static boolean hasNetworkCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wraps a partner API call into a supplier that obtains the provider's current connection on every
     * invocation and carries a {@link ConnectionException} out as a {@link SalesforceCallException}.
     */
    private <T> Supplier<T> onConnection(ConnectionCall<T> call) {
        return () -> {
            try {
                return call.apply(sessionProvider.connection());
            } catch (ConnectionException failure) {
                throw new SalesforceCallException(failure);
            }
        };
    }

    private static QueryResult requirePage(String operation, QueryResult page) {
        if (page == null) {
            throw new IllegalStateException("Salesforce " + operation + " returned no result");
        }
        return page;
    }

    private static void appendRecords(QueryResult page, List<Map<String, Object>> records) {
        SObject[] pageRecords = page.getRecords();
        if (pageRecords == null) {
            return;
        }
        for (SObject pageRecord : pageRecords) {
            records.add(toMap(pageRecord));
        }
    }

    /**
     * Converts one query record into a {@link HashMap} keyed by child local name, as described on the class.
     */
    private static Map<String, Object> toMap(SObject sObject) {
        Objects.requireNonNull(sObject, "Salesforce query returned a null record");
        Map<String, Object> map = new HashMap<>();
        map.put(TYPE_ELEMENT, sObject.getType());
        Iterator<XmlObject> children = sObject.getChildren();
        while (children.hasNext()) {
            XmlObject child = children.next();
            String name = child.getName().getLocalPart();
            if (TYPE_ELEMENT.equals(name) || FIELDS_TO_NULL_ELEMENT.equals(name)) {
                continue;
            }
            if (map.get(name) == null) {
                map.put(name, convert(child.getValue()));
            }
        }
        return map;
    }

    /**
     * Converts a child value: {@code null} stays {@code null}, a {@link String} stays unchanged, a
     * {@link Calendar} or {@link Date} becomes UTC text with milliseconds, and any other value becomes its
     * {@link String#valueOf(Object)} text.
     */
    private static Object convert(Object value) {
        if (value == null || value instanceof String) {
            return value;
        }
        if (value instanceof Calendar calendar) {
            return FORMAT.format(calendar.toInstant());
        }
        if (value instanceof Date date) {
            return FORMAT.format(Instant.ofEpochMilli(date.getTime()));
        }
        return String.valueOf(value);
    }

    /**
     * One partner API call on a connection.
     *
     * @param <T> result type of the call
     */
    @FunctionalInterface
    private interface ConnectionCall<T> {

        /**
         * Runs the call on the given connection.
         *
         * @param connection the provider's current partner connection
         * @return the call's result
         * @throws ConnectionException when the partner API call fails
         */
        T apply(PartnerConnection connection) throws ConnectionException;
    }

    /**
     * Carries a partner API {@link ConnectionException} out of a {@link Supplier}; it never leaves this class.
     */
    private static final class SalesforceCallException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        SalesforceCallException(ConnectionException cause) {
            super(cause);
        }

        ConnectionException connectionException() {
            return (ConnectionException) getCause();
        }
    }
}
