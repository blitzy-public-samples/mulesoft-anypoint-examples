package com.mulesoft.examples.salesforce_data_retrieval.client;

import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_data_retrieval.exception.UpstreamUnavailableException;
import com.mulesoft.examples.salesforce_data_retrieval.model.SobjectSummary;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.DescribeGlobalResult;
import com.sforce.soap.partner.DescribeGlobalSObjectResult;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.bind.XmlObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issues every Salesforce partner API call through {@code execute}, which classifies failures
 * (D-020). Replaces the connector operations of the global element {@code sfdc:config} named
 * {@code Salesforce} [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:3]:
 * {@code sfdc:describe-global} in flow {@code showFormFlow}
 * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:9] and
 * {@code sfdc:query-all} in flow {@code salesforceDataRetrievalFlow}
 * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:27].
 *
 * <p>The session comes from {@link SalesforceSessionProvider}, which authenticates with the OAuth2
 * username-password grant on the first call (D-013, D-015); the constructor makes no network call.
 * Each partner call runs on the provider's current {@link PartnerConnection} and its failure maps
 * as follows (D-020, D-632):
 * <ul>
 *   <li>{@link ApiFault} {@code INVALID_SESSION_ID}: one {@link SalesforceSessionProvider#refresh()},
 *       then the same call once more on the refreshed connection; any failure of that second call
 *       raises {@link UpstreamAuthenticationException} (502);</li>
 *   <li>{@link ApiFault} {@code REQUEST_LIMIT_EXCEEDED}: {@link UpstreamRateLimitException} (429)
 *       with a {@code null} {@code Retry-After}, after one call;</li>
 *   <li>any other {@link ApiFault}, for example {@code MALFORMED_QUERY} or {@code INVALID_TYPE}:
 *       {@link IllegalStateException} carrying the fault's exception message;</li>
 *   <li>a {@link ConnectionException} whose cause chain holds an {@link IOException} (socket
 *       timeout, connection refused, connection reset): {@link UpstreamUnavailableException} (503),
 *       after one call;</li>
 *   <li>any other {@link ConnectionException}: {@link IllegalStateException}.</li>
 * </ul>
 * The upstream exceptions raised by the session provider pass through unchanged. The single
 * re-issue after {@code refresh()} is the only repeated call in this class.
 *
 * <pre>{@code
 * List<SobjectSummary> sobjects = salesforceClient.describeGlobal();
 * List<Map<String, Object>> users = salesforceClient.queryAll(
 *         "SELECT id, name , email from user where name like '%mule%'");
 * users.get(0).get("Email");   // "kicks+a@mulesoft.com"
 * }</pre>
 */
@Component
public class SalesforceClient {

    private static final Logger log = LoggerFactory.getLogger(SalesforceClient.class);

    private static final String UPSTREAM = "Salesforce";

    private final SalesforceSessionProvider sessionProvider;

    /**
     * One partner API call made on the connection it receives. {@code execute} passes the current
     * connection first and the refreshed connection on the single re-issue (D-020).
     *
     * @param <T> the partner call's result type
     */
    @FunctionalInterface
    interface SalesforceOperation<T> {

        /**
         * Makes the partner call on {@code connection}.
         *
         * @param connection the partner API connection to call
         * @return the call's result
         * @throws ConnectionException when the call fails, an {@link ApiFault} included
         */
        T apply(PartnerConnection connection) throws ConnectionException;
    }

    /**
     * Creates the client over the session provider. No network call is made; the provider
     * authenticates on the first partner call (D-013).
     *
     * @param sessionProvider the source of the partner API connection
     * @throws NullPointerException when {@code sessionProvider} is {@code null}
     */
    public SalesforceClient(SalesforceSessionProvider sessionProvider) {
        this.sessionProvider = Objects.requireNonNull(sessionProvider, "sessionProvider");
    }

    /**
     * Lists the org's sObjects with {@code PartnerConnection.describeGlobal()}, replacing
     * {@code sfdc:describe-global} in flow {@code showFormFlow}
     * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:9].
     *
     * <p>Each {@code DescribeGlobalSObjectResult} becomes {@code SobjectSummary(name, label)} in the
     * order Salesforce returns them; a {@code null} element stays a {@code null} element, and a
     * {@code null} result or {@code null} sObject array gives an empty list (D-632).
     *
     * @return the unmodifiable list of sObject names and labels
     * @throws UpstreamAuthenticationException when the session is rejected after one re-authentication
     * @throws UpstreamRateLimitException when Salesforce reports {@code REQUEST_LIMIT_EXCEEDED}
     * @throws UpstreamUnavailableException when Salesforce cannot be reached
     * @throws IllegalStateException for any other Salesforce fault or connection failure
     */
    public List<SobjectSummary> describeGlobal() {
        DescribeGlobalResult result = execute("describeGlobal", PartnerConnection::describeGlobal);
        DescribeGlobalSObjectResult[] sobjects = result == null ? null : result.getSobjects();
        if (sobjects == null) {
            return List.of();
        }
        List<SobjectSummary> summaries = new ArrayList<>(sobjects.length);
        for (DescribeGlobalSObjectResult sobject : sobjects) {
            summaries.add(sobject == null ? null : new SobjectSummary(sobject.getName(), sobject.getLabel()));
        }
        return Collections.unmodifiableList(summaries);
    }

    /**
     * Runs {@code soql} with {@code PartnerConnection.queryAll(String)}, replacing
     * {@code sfdc:query-all} in flow {@code salesforceDataRetrievalFlow}
     * [salesforce-data-retrieval/src/main/app/salesforce-id-retrieval.xml:27].
     *
     * <p>While a page reports {@code done=false}, the next page is read with
     * {@code PartnerConnection.queryMore(queryLocator)}; every page is its own {@code execute} call
     * with the D-020 classification. Records keep the order Salesforce returns across pages, and a
     * {@code null} records array counts as an empty page.
     *
     * <p>Each {@code SObject} becomes a {@link LinkedHashMap} of its child elements in document
     * order, keyed by the element's local name, with the element's value. When a local name repeats,
     * {@code Id} for example, the map keeps the first value (D-632). A {@code null} record stays a
     * {@code null} element. For example:
     * <pre>{@code
     * {type=User, Id=00520000003LtYCAA0, Name=Mule orgA, Email=kicks+a@mulesoft.com}
     * }</pre>
     *
     * @param soql the SOQL statement, sent as given
     * @return the unmodifiable list of records across every page
     * @throws UpstreamAuthenticationException when the session is rejected after one re-authentication
     * @throws UpstreamRateLimitException when Salesforce reports {@code REQUEST_LIMIT_EXCEEDED}
     * @throws UpstreamUnavailableException when Salesforce cannot be reached
     * @throws IllegalStateException for a malformed query or any other Salesforce fault
     */
    public List<Map<String, Object>> queryAll(String soql) {
        List<Map<String, Object>> records = new ArrayList<>();
        QueryResult page = execute("queryAll", connection -> connection.queryAll(soql));
        appendRecords(page, records);
        while (page != null && !page.isDone()) {
            String locator = page.getQueryLocator();
            page = execute("queryMore", connection -> connection.queryMore(locator));
            appendRecords(page, records);
        }
        return Collections.unmodifiableList(records);
    }

    /**
     * Deletes records created by integration tests (D-058) with
     * {@code PartnerConnection.delete(String[])}, in one call for every id given.
     *
     * <p>Every {@code DeleteResult} with {@code success=false} contributes each of its errors as
     * {@code STATUS_CODE: message}; a failed result without errors contributes
     * {@code no error detail for <id>}. Any contribution raises one {@link IllegalStateException}
     * listing them all, joined by {@code "; "}.
     *
     * @param ids the record ids to delete
     * @throws UpstreamAuthenticationException when the session is rejected after one re-authentication
     * @throws UpstreamRateLimitException when Salesforce reports {@code REQUEST_LIMIT_EXCEEDED}
     * @throws UpstreamUnavailableException when Salesforce cannot be reached
     * @throws IllegalStateException when Salesforce refuses any delete, or for any other fault
     */
    public void delete(String... ids) {
        DeleteResult[] results = execute("delete", connection -> connection.delete(ids));
        if (results == null) {
            return;
        }
        List<String> messages = new ArrayList<>();
        for (DeleteResult result : results) {
            if (result == null || result.isSuccess()) {
                continue;
            }
            com.sforce.soap.partner.Error[] errors = result.getErrors();
            if (errors == null || errors.length == 0) {
                messages.add("no error detail for " + result.getId());
                continue;
            }
            for (com.sforce.soap.partner.Error error : errors) {
                messages.add(error.getStatusCode() + ": " + error.getMessage());
            }
        }
        if (!messages.isEmpty()) {
            throw new IllegalStateException("Salesforce delete failed: " + String.join("; ", messages));
        }
    }

    /**
     * Makes one partner call and classifies its failure (D-020). Re-authenticates once on
     * {@code INVALID_SESSION_ID} and re-issues the call once; a timeout or connection failure is
     * never re-sent (D-020).
     *
     * <ol>
     *   <li>{@link SalesforceSessionProvider#connection()} supplies the connection; its upstream
     *       exceptions and {@link IllegalStateException} pass through unchanged.</li>
     *   <li>{@code call} runs once on that connection and its result is returned.</li>
     *   <li>{@link ApiFault} {@code INVALID_SESSION_ID}: one INFO line, one
     *       {@link SalesforceSessionProvider#refresh()} (its exceptions pass through unchanged), and
     *       {@code call} once more on the refreshed connection. Any {@link ConnectionException} from
     *       that second call, of any fault code or an I/O failure, raises
     *       {@link UpstreamAuthenticationException} with the second failure as cause.</li>
     *   <li>{@link ApiFault} {@code REQUEST_LIMIT_EXCEEDED}: {@link UpstreamRateLimitException} with
     *       a {@code null} {@code Retry-After} (D-632).</li>
     *   <li>Any other {@link ApiFault}: {@link IllegalStateException} with the fault's exception
     *       message, or its {@code toString()} when the message is {@code null}.</li>
     *   <li>A {@link ConnectionException} whose cause chain holds an {@link IOException}:
     *       {@link UpstreamUnavailableException}.</li>
     *   <li>Any other {@link ConnectionException}: {@link IllegalStateException} with its message, or
     *       its {@code toString()} when the message is {@code null}.</li>
     * </ol>
     *
     * <pre>{@code
     * QueryResult page = execute("queryAll", connection -> connection.queryAll(soql));
     * }</pre>
     *
     * @param operation the partner operation name used in exception messages and the log line
     * @param call the partner call
     * @param <T> the partner call's result type
     * @return the result of the first successful call
     * @throws UpstreamAuthenticationException when the re-issued call fails
     * @throws UpstreamRateLimitException on {@code REQUEST_LIMIT_EXCEEDED}
     * @throws UpstreamUnavailableException on an I/O failure
     * @throws IllegalStateException on any other fault or connection failure
     */
    <T> T execute(String operation, SalesforceOperation<T> call) {
        PartnerConnection connection = sessionProvider.connection();
        try {
            return call.apply(connection);
        } catch (ApiFault fault) {
            ExceptionCode code = fault.getExceptionCode();
            if (code == ExceptionCode.INVALID_SESSION_ID) {
                log.info("Salesforce session invalid during {}; re-authenticating once", operation);
                PartnerConnection refreshed = sessionProvider.refresh();
                try {
                    return call.apply(refreshed);
                } catch (ConnectionException second) {
                    throw new UpstreamAuthenticationException(UPSTREAM,
                            "Salesforce rejected the session after re-authentication during " + operation,
                            second);
                }
            }
            if (code == ExceptionCode.REQUEST_LIMIT_EXCEEDED) {
                throw new UpstreamRateLimitException(UPSTREAM,
                        "Salesforce request limit exceeded during " + operation, null, fault);
            }
            throw new IllegalStateException(faultMessage(fault), fault);
        } catch (ConnectionException e) {
            if (hasIoCause(e)) {
                throw new UpstreamUnavailableException(UPSTREAM,
                        "Salesforce unreachable during " + operation + ": " + e.getMessage(), e);
            }
            throw new IllegalStateException(e.getMessage() != null ? e.getMessage() : e.toString(), e);
        }
    }

    /**
     * Appends the records of {@code page} to {@code records} in page order, each converted by
     * {@link #toRecord(SObject)}. A {@code null} page or records array appends nothing.
     */
    private static void appendRecords(QueryResult page, List<Map<String, Object>> records) {
        if (page == null) {
            return;
        }
        SObject[] pageRecords = page.getRecords();
        if (pageRecords == null) {
            return;
        }
        for (SObject record : pageRecords) {
            records.add(record == null ? null : toRecord(record));
        }
    }

    /**
     * Converts the child elements of {@code record} into an insertion-ordered map of local name to
     * value, keeping the first value of a repeated local name (D-632).
     */
    private static Map<String, Object> toRecord(SObject record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        Iterator<XmlObject> children = record.getChildren();
        while (children.hasNext()) {
            XmlObject child = children.next();
            String key = child.getName().getLocalPart();
            if (!fields.containsKey(key)) {
                fields.put(key, child.getValue());
            }
        }
        return fields;
    }

    /**
     * Returns the fault's exception message, or its {@code toString()} when the message is
     * {@code null}.
     */
    private static String faultMessage(ApiFault fault) {
        String message = fault.getExceptionMessage();
        return message != null ? message : fault.toString();
    }

    /**
     * Returns {@code true} when an element of the cause chain of {@code t}, starting at
     * {@code t.getCause()}, is an {@link IOException}. The walk stops at a {@code null} cause or at
     * a cause already visited, a self-reference included.
     */
    private static boolean hasIoCause(Throwable t) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(t);
        Throwable cause = t.getCause();
        while (cause != null && visited.add(cause)) {
            if (cause instanceof IOException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }
}
