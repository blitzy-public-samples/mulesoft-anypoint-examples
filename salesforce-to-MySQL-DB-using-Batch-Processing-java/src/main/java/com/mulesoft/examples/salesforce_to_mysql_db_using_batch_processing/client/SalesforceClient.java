package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.client;

import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamRateLimitException;
import com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Salesforce Partner API client of this project: the only class that sends partner calls, and the
 * place where their failures are classified into the connector failure modes of D-020. Together with
 * {@link SalesforceSessionProvider} it replaces the {@code sfdc:config} element of
 * {@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:4-6}.
 *
 * <p><b>Operations.</b>
 * <ul>
 *   <li>{@link #query(String)} runs the SOQL text of {@code sfdc:query}
 *       {@code SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > <timestamp>}
 *       [salesforce-to-database.xml:64] unchanged and follows {@code queryMore} until the result is done;</li>
 *   <li>{@link #create(String, List)} sends {@code create} calls of at most {@value #MAX_RECORDS_PER_CALL}
 *       SObjects, replacing the {@code sfdc:upsert} of sub-flow {@code insertSalesforceContactSubFlow}
 *       [salesforce-to-MySQL-DB-using-Batch-Processing/src/test/resources/testflows/test-flows.xml:3-12];</li>
 *   <li>{@link #delete(List)} sends {@code delete} calls of at most {@value #MAX_RECORDS_PER_CALL} ids and logs
 *       each result, replacing sub-flow {@code deleteContactFromSalesforce} [test-flows.xml:25-30].</li>
 * </ul>
 *
 * <p><b>Session.</b> Every SOAP request obtains its {@link PartnerConnection} from
 * {@link SalesforceSessionProvider#connection()} when the request is sent; the provider holds the
 * OAuth2 username-password session (D-013). Creating this bean sends nothing.
 *
 * <p><b>Failure classification (D-020).</b> Each SOAP request (the {@code query}, each {@code queryMore}
 * page, each {@code create} or {@code delete} chunk) runs through {@link #execute(String, Supplier)}, which
 * applies these rules in order:
 * <ol>
 *   <li>an {@link UpstreamAuthenticationException}, {@link UpstreamRateLimitException} or
 *       {@link UpstreamUnavailableException} raised by the token request of the session provider propagates
 *       unchanged;</li>
 *   <li>an {@link ApiFault} with {@link ExceptionCode#INVALID_SESSION_ID}: the first time, the session is
 *       invalidated and the same request is sent once more on a new session; the second time,
 *       {@link UpstreamAuthenticationException};</li>
 *   <li>an {@link ApiFault} with {@link ExceptionCode#REQUEST_LIMIT_EXCEEDED}, or an HTTP 429 response status
 *       recorded by the session provider (D-530) → {@link UpstreamRateLimitException} carrying the recorded
 *       {@code Retry-After} value, or none;</li>
 *   <li>a {@link SocketTimeoutException} or {@link ConnectException} anywhere in the cause chain, including a
 *       force-wsc {@link ConnectionException} wrapping one → {@link UpstreamUnavailableException};</li>
 *   <li>any other failure propagates unchanged; a checked {@link ConnectionException} (for example a
 *       {@code MalformedQueryFault}) arrives wrapped in {@link SalesforceCallException}.</li>
 * </ol>
 * The re-sent request of rule 2 is the only repetition; no other request is sent twice, and a
 * {@code create} chunk is sent again only after Salesforce rejected its session.
 *
 * <p>Usage:
 * <pre>{@code
 * List<Map<String, Object>> rows = salesforceClient.query(
 *         "SELECT Email,FirstName,LastModifiedDate,LastName FROM Contact WHERE LastModifiedDate > 2026-01-01T00:00:00.000Z");
 * String email = (String) rows.get(0).get("Email");
 * }</pre>
 */
@Component
public class SalesforceClient {

    private static final Logger LOG = LoggerFactory.getLogger(SalesforceClient.class);

    /** Maximum number of SObjects per {@code create} call and of ids per {@code delete} call. */
    static final int MAX_RECORDS_PER_CALL = 200;

    /** Attempts per request in {@link #execute(String, Supplier)}: the first and the re-authenticated one (D-020). */
    private static final int MAX_ATTEMPTS = 2;

    /** HTTP status classified as a rate limit (D-020, D-530). */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** Keyword that opens the SELECT list of a SOQL query, matched ignoring case. */
    private static final String SELECT_TOKEN = "SELECT ";

    /** Keyword that closes the SELECT list of a SOQL query, matched ignoring case. */
    private static final String FROM_TOKEN = " FROM ";

    private final SalesforceSessionProvider sessionProvider;

    /**
     * Creates the client over the session provider of this project.
     *
     * @param sessionProvider source of the Partner API connection (D-013)
     */
    public SalesforceClient(SalesforceSessionProvider sessionProvider) {
        this.sessionProvider = Objects.requireNonNull(sessionProvider, "sessionProvider");
    }

    /**
     * Runs one Salesforce request and classifies its failure (D-020).
     *
     * <p>Each attempt first removes the SOAP response status recorded for the current thread
     * ({@link SalesforceSessionProvider#clearLastResponseStatus()}), then returns {@code call.get()}. On a
     * {@link RuntimeException} the failure examined is the cause of a {@link SalesforceCallException}, or the
     * exception itself, and the rules of the class description apply in order. Only an
     * {@link ExceptionCode#INVALID_SESSION_ID} fault on the first attempt leads to a second attempt, after a WARN
     * line {@code Salesforce session invalid during <operation>; re-authenticating} and
     * {@link SalesforceSessionProvider#invalidate()}; at most two attempts are made. The classified exceptions are
     * thrown without a log line of this class.
     *
     * <pre>{@code
     * QueryResult result = salesforceClient.execute("query", () -> partnerCall());
     * }</pre>
     *
     * @param operation name of the request, used in log lines and exception messages (for example {@code query})
     * @param call      the request; each invocation sends it once
     * @param <T>       result type of the request
     * @return the value returned by {@code call}
     * @throws UpstreamAuthenticationException the session was rejected again after re-authentication, or the
     *                                         token request was rejected
     * @throws UpstreamRateLimitException      Salesforce answered {@code REQUEST_LIMIT_EXCEEDED} or HTTP 429
     * @throws UpstreamUnavailableException    the request timed out or could not connect
     * @throws SalesforceCallException         any other checked partner API failure, unchanged
     * @throws RuntimeException                any other runtime failure of {@code call}, unchanged
     */
    public <T> T execute(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        for (int attempt = 1; ; attempt++) {
            sessionProvider.clearLastResponseStatus();
            try {
                return call.get();
            } catch (UpstreamAuthenticationException | UpstreamRateLimitException | UpstreamUnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                Throwable cause = e instanceof SalesforceCallException ? e.getCause() : e;
                // HTTP status and Retry-After of the failed SOAP response, recorded by the session provider (D-530).
                Optional<SalesforceSessionProvider.ResponseStatus> status = sessionProvider.lastResponseStatus();

                if (isApiFault(cause, ExceptionCode.INVALID_SESSION_ID)) {
                    if (attempt < MAX_ATTEMPTS) {
                        LOG.warn("Salesforce session invalid during {}; re-authenticating", operation);
                        sessionProvider.invalidate();
                        continue;
                    }
                    throw new UpstreamAuthenticationException(
                            "Salesforce authentication failed after re-authentication during " + operation, cause);
                }

                if (isApiFault(cause, ExceptionCode.REQUEST_LIMIT_EXCEEDED)
                        || status.filter(s -> s.status() == HTTP_TOO_MANY_REQUESTS).isPresent()) {
                    String retryAfter = status.map(SalesforceSessionProvider.ResponseStatus::retryAfter).orElse(null);
                    String message = "Salesforce rate limit reached during " + operation;
                    throw retryAfter == null
                            ? new UpstreamRateLimitException(message, cause)
                            : new UpstreamRateLimitException(message, retryAfter, cause);
                }

                if (hasTimeoutOrConnectCause(cause)) {
                    throw new UpstreamUnavailableException("Salesforce unreachable during " + operation, cause);
                }

                throw e;
            }
        }
    }

    /**
     * Runs a SOQL query and returns every record of every result page, replacing the {@code sfdc:query} of
     * {@code triggerFlow} [salesforce-to-database.xml:64].
     *
     * <p>The field list between the first {@code SELECT } and the following {@code  FROM } (both ignoring case)
     * is read once; each name is trimmed. The query is sent unchanged with {@code query}; while the result is
     * not done, {@code queryMore} is sent with its query locator. Each request runs through
     * {@link #execute(String, Supplier)} with the operation name {@code query} or {@code queryMore}.
     *
     * <p>Each record becomes a {@link LinkedHashMap} with one entry per SELECT-list name, in SELECT-list order,
     * holding {@code SObject.getField(name)} as the Partner API returns it: a {@code String} for a simple
     * field, {@code null} for a nil or absent field. Records keep the order of the result pages.
     *
     * <pre>{@code
     * List<Map<String, Object>> rows = salesforceClient.query("SELECT Email,FirstName FROM Contact");
     * // each row: {Email=..., FirstName=...}
     * }</pre>
     *
     * @param soql the SOQL text, sent as given
     * @return the records in result order; an empty list when the query matched none
     * @throws NullPointerException     {@code soql} is {@code null}
     * @throws IllegalArgumentException {@code soql} holds no {@code SELECT ... FROM} field list
     * @throws IllegalStateException    a result page is missing, or a page that is not done carries no query locator
     * @see #execute(String, Supplier) for the failures of each request
     */
    public List<Map<String, Object>> query(String soql) {
        List<String> fields = selectFields(soql);
        List<Map<String, Object>> rows = new ArrayList<>();

        QueryResult page = requirePage(execute("query", soap(connection -> connection.query(soql))), "query");
        addRows(page, fields, rows);
        while (!page.isDone()) {
            String locator = page.getQueryLocator();
            if (locator == null || locator.isBlank()) {
                throw new IllegalStateException("Salesforce query result is not done and carries no query locator");
            }
            page = requirePage(execute("queryMore", soap(connection -> connection.queryMore(locator))), "queryMore");
            addRows(page, fields, rows);
        }
        LOG.debug("Salesforce query returned {} records", rows.size());
        return rows;
    }

    /**
     * Creates SObjects of one type and returns their ids, replacing the {@code sfdc:upsert} (type
     * {@code Contact}, no {@code Id} field) of sub-flow {@code insertSalesforceContactSubFlow}
     * [test-flows.xml:3-12].
     *
     * <p>The records are sent in input order, in {@code create} calls of at most
     * {@value #MAX_RECORDS_PER_CALL} SObjects. Each SObject gets {@code setType(type)} and one
     * {@code setField(key, value)} per map entry, in the map's iteration order. Each call runs through
     * {@link #execute(String, Supplier)} with the operation name {@code create}. After a call whose results
     * hold a failure, no further call is sent.
     *
     * <pre>{@code
     * List<String> ids = salesforceClient.create("Contact",
     *         List.of(Map.of("LastName", "Doe", "Email", "doe@example.com")));
     * }</pre>
     *
     * @param type    the SObject type, for example {@code Contact}
     * @param records the field maps, one per SObject
     * @return the created ids in input order; an empty list, with no call sent, when {@code records} is empty
     * @throws NullPointerException  {@code type} or {@code records} is {@code null}, or a record is {@code null}
     * @throws IllegalStateException a {@code SaveResult} reports a failure, naming each failed record's input
     *                               index and its {@code statusCode: message} errors, or a call returned a
     *                               number of results different from the number of SObjects sent
     * @see #execute(String, Supplier) for the failures of each call
     */
    public List<String> create(String type, List<Map<String, Object>> records) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(records, "records");
        List<String> ids = new ArrayList<>(records.size());
        for (int offset = 0; offset < records.size(); offset += MAX_RECORDS_PER_CALL) {
            int end = Math.min(records.size(), offset + MAX_RECORDS_PER_CALL);
            SObject[] chunk = toSObjects(type, records.subList(offset, end), offset);
            SaveResult[] results = execute("create", soap(connection -> connection.create(chunk)));
            requireResultCount("create", results, chunk.length);

            List<String> failures = new ArrayList<>();
            for (int i = 0; i < results.length; i++) {
                SaveResult result = results[i];
                if (result != null && result.isSuccess()) {
                    ids.add(result.getId());
                } else {
                    failures.add((offset + i) + " " + describeErrors(result == null ? null : result.getErrors()));
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Salesforce create failed: " + String.join("; ", failures));
            }
        }
        LOG.debug("Salesforce create of {} {} records returned {} ids", records.size(), type, ids.size());
        return ids;
    }

    /**
     * Deletes records by id, replacing sub-flow {@code deleteContactFromSalesforce} [test-flows.xml:25-30].
     *
     * <p>The ids are sent in input order, in {@code delete} calls of at most {@value #MAX_RECORDS_PER_CALL}
     * ids. Each call runs through {@link #execute(String, Supplier)} with the operation name {@code delete}.
     * Every {@code DeleteResult} is logged at INFO with its id, its success flag and its errors. After a call
     * whose results hold a failure, no further call is sent.
     *
     * <pre>{@code
     * salesforceClient.delete(List.of(contactId));
     * }</pre>
     *
     * @param ids the record ids; an empty list sends no call
     * @throws NullPointerException  {@code ids} is {@code null}
     * @throws IllegalStateException a {@code DeleteResult} reports a failure, naming each failed id and its
     *                               {@code statusCode: message} errors, or a call returned a number of
     *                               results different from the number of ids sent
     * @see #execute(String, Supplier) for the failures of each call
     */
    public void delete(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        for (int offset = 0; offset < ids.size(); offset += MAX_RECORDS_PER_CALL) {
            int end = Math.min(ids.size(), offset + MAX_RECORDS_PER_CALL);
            String[] chunk = ids.subList(offset, end).toArray(new String[0]);
            DeleteResult[] results = execute("delete", soap(connection -> connection.delete(chunk)));
            requireResultCount("delete", results, chunk.length);

            List<String> failures = new ArrayList<>();
            for (int i = 0; i < results.length; i++) {
                DeleteResult result = results[i];
                String id = result != null && result.getId() != null ? result.getId() : chunk[i];
                boolean success = result != null && result.isSuccess();
                List<String> errors = errorTexts(result == null ? null : result.getErrors());
                LOG.info("Salesforce delete result: id={}, success={}, errors={}", id, success, errors);
                if (!success) {
                    failures.add(id + " " + describeErrors(result == null ? null : result.getErrors()));
                }
            }
            if (!failures.isEmpty()) {
                throw new IllegalStateException("Salesforce delete failed: " + String.join("; ", failures));
            }
        }
    }

    /**
     * Returns a supplier that sends one partner call: on each invocation it obtains the connection from
     * {@link SalesforceSessionProvider#connection()} and applies {@code call} to it. A
     * {@link ConnectionException} of the call is rethrown wrapped in {@link SalesforceCallException}; runtime
     * exceptions of the provider or the call propagate unchanged.
     */
    private <T> Supplier<T> soap(ConnectionCall<T> call) {
        return () -> {
            PartnerConnection connection = sessionProvider.connection();
            try {
                return call.call(connection);
            } catch (ConnectionException e) {
                throw new SalesforceCallException(e);
            }
        };
    }

    /**
     * Returns the trimmed names between the first {@code SELECT } and the following {@code  FROM } of a SOQL
     * text, both matched ignoring case, in their SOQL order; the list text is split on {@code ,}.
     *
     * @throws NullPointerException     {@code soql} is {@code null}
     * @throws IllegalArgumentException {@code soql} holds no such list, or the list text is blank
     */
    private static List<String> selectFields(String soql) {
        Objects.requireNonNull(soql, "soql");
        int select = indexOfIgnoreCase(soql, SELECT_TOKEN, 0);
        int listStart = select + SELECT_TOKEN.length();
        int from = select < 0 ? -1 : indexOfIgnoreCase(soql, FROM_TOKEN, listStart);
        String list = from < 0 ? "" : soql.substring(listStart, from);
        if (list.isBlank()) {
            throw new IllegalArgumentException("SOQL query has no SELECT ... FROM field list: " + soql);
        }
        List<String> fields = new ArrayList<>();
        for (String part : list.split(",", -1)) {
            fields.add(part.trim());
        }
        return List.copyOf(fields);
    }

    /** Returns the first index of {@code token} in {@code text} at or after {@code from}, ignoring case; -1 if absent. */
    private static int indexOfIgnoreCase(String text, String token, int from) {
        for (int index = Math.max(0, from); index + token.length() <= text.length(); index++) {
            if (text.regionMatches(true, index, token, 0, token.length())) {
                return index;
            }
        }
        return -1;
    }

    /**
     * Appends one row per record of {@code page} to {@code rows}; a {@code null} records array adds nothing.
     *
     * @throws IllegalStateException the records array holds a {@code null} element
     */
    private static void addRows(QueryResult page, List<String> fields, List<Map<String, Object>> rows) {
        SObject[] records = page.getRecords();
        if (records == null) {
            return;
        }
        for (SObject record : records) {
            if (record == null) {
                throw new IllegalStateException("Salesforce query result holds a null record");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (String field : fields) {
                row.put(field, record.getField(field));
            }
            rows.add(row);
        }
    }

    /**
     * Returns {@code page}.
     *
     * @throws IllegalStateException {@code page} is {@code null}
     */
    private static QueryResult requirePage(QueryResult page, String operation) {
        if (page == null) {
            throw new IllegalStateException("Salesforce " + operation + " returned no result");
        }
        return page;
    }

    /**
     * Builds the SObjects of one {@code create} call: {@code setType(type)}, then {@code setField} per entry.
     *
     * @param offset input index of the first record of {@code records}, used in error messages
     * @throws NullPointerException a record is {@code null}
     */
    private static SObject[] toSObjects(String type, List<Map<String, Object>> records, int offset) {
        SObject[] sObjects = new SObject[records.size()];
        for (int i = 0; i < sObjects.length; i++) {
            int index = offset + i;
            Map<String, Object> record = Objects.requireNonNull(records.get(i), () -> "Salesforce create record " + index + " is null");
            SObject sObject = new SObject();
            sObject.setType(type);
            for (Map.Entry<String, Object> field : record.entrySet()) {
                sObject.setField(field.getKey(), field.getValue());
            }
            sObjects[i] = sObject;
        }
        return sObjects;
    }

    /**
     * Checks that a {@code create} or {@code delete} call returned one result per element sent.
     *
     * @throws IllegalStateException {@code results} is {@code null} or has another length
     */
    private static void requireResultCount(String operation, Object[] results, int sent) {
        int received = results == null ? 0 : results.length;
        if (results == null || received != sent) {
            throw new IllegalStateException("Salesforce " + operation + " returned " + received
                    + " results for " + sent + " elements sent");
        }
    }

    /** Returns one {@code statusCode: message} text per error; an empty list for {@code null} or no errors. */
    private static List<String> errorTexts(com.sforce.soap.partner.Error[] errors) {
        if (errors == null || errors.length == 0) {
            return List.of();
        }
        List<String> texts = new ArrayList<>(errors.length);
        for (com.sforce.soap.partner.Error error : errors) {
            texts.add(error == null ? "null" : error.getStatusCode() + ": " + error.getMessage());
        }
        return texts;
    }

    /** Returns the {@link #errorTexts} joined by {@code ", "}, or {@code no error details} when there are none. */
    private static String describeErrors(com.sforce.soap.partner.Error[] errors) {
        List<String> texts = errorTexts(errors);
        return texts.isEmpty() ? "no error details" : String.join(", ", texts);
    }

    /** Reports whether {@code failure} is an {@link ApiFault} whose exception code is {@code code}. */
    private static boolean isApiFault(Throwable failure, ExceptionCode code) {
        return failure instanceof ApiFault fault && fault.getExceptionCode() == code;
    }

    /**
     * Reports whether the cause chain of {@code failure}, {@code failure} included, holds a
     * {@link SocketTimeoutException} or a {@link ConnectException}; each throwable is visited once.
     */
    private static boolean hasTimeoutOrConnectCause(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable t = failure; t != null && seen.add(t); t = t.getCause()) {
            if (t instanceof SocketTimeoutException || t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unchecked carrier of a checked force-wsc {@link ConnectionException} raised by a partner call (D-020).
     * {@link #getCause()} returns that exception, for example an {@link ApiFault} subclass such as
     * {@code MalformedQueryFault}. {@link SalesforceClient#execute(String, Supplier)} classifies its cause
     * and rethrows it unchanged when no failure mode applies.
     */
    public static final class SalesforceCallException extends RuntimeException {

        /** Serialization version of this class. */
        private static final long serialVersionUID = 1L;

        /**
         * Wraps a partner call failure; the message names the cause type and its message.
         *
         * @param cause the checked failure of the partner call, returned by {@link #getCause()}
         * @throws NullPointerException {@code cause} is {@code null}
         */
        public SalesforceCallException(ConnectionException cause) {
            super(describe(Objects.requireNonNull(cause, "cause")), cause);
        }

        private static String describe(ConnectionException cause) {
            String detail = cause.getMessage();
            return "Salesforce partner call failed: " + cause.getClass().getSimpleName()
                    + (detail == null || detail.isBlank() ? "" : ": " + detail);
        }
    }

    /**
     * One partner call on a {@link PartnerConnection}, allowed to throw the checked force-wsc
     * {@link ConnectionException} (D-020).
     *
     * @param <T> result type of the call
     */
    @FunctionalInterface
    private interface ConnectionCall<T> {

        /**
         * Sends the call on {@code connection}.
         *
         * @param connection the Partner API connection of the current attempt
         * @return the call's result
         * @throws ConnectionException the call failed, including SOAP faults ({@link ApiFault})
         */
        T call(PartnerConnection connection) throws ConnectionException;
    }
}

