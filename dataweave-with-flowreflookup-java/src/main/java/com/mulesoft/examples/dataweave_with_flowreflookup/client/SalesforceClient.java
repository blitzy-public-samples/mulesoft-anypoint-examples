package com.mulesoft.examples.dataweave_with_flowreflookup.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamRateLimitException;
import com.mulesoft.examples.dataweave_with_flowreflookup.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.DeleteResult;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Issues the Salesforce partner API calls of the account import and classifies their failures
 * (D-020). Every call runs through {@link #execute(String, PartnerCall)} on the session that
 * {@link SalesforceSessionProvider} supplies (D-013).
 *
 * <p>Operations and the Mule elements they replace:
 * <ul>
 *   <li>{@link #create(String, List)}: {@code sfdc:create type="Account"} with
 *       {@code sfdc:objects ref="#[payload]"}
 *       [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:21-23];</li>
 *   <li>{@link #query(String)}: the IT sub-flow {@code selectAccountFromSalesforce},
 *       {@code sfdc:query-single}
 *       [dataweave-with-flowreflookup/src/test/resources/testflows/test-flows.xml:4-6];</li>
 *   <li>{@link #delete(List)}: the IT sub-flow {@code deleteAccountFromSalesforce},
 *       {@code sfdc:delete}
 *       [dataweave-with-flowreflookup/src/test/resources/testflows/test-flows.xml:7-11].</li>
 * </ul>
 *
 * <p>Failure classification of one partner call (D-020), each upstream exception carrying the
 * upstream system {@code Salesforce}:
 * <ul>
 *   <li>{@link ApiFault} {@code INVALID_SESSION_ID} on the first attempt: one
 *       {@link SalesforceSessionProvider#reauthenticate()} and exactly one re-issue of the call on
 *       the returned connection; {@code INVALID_SESSION_ID} on the re-issued attempt:
 *       {@link UpstreamAuthenticationException};</li>
 *   <li>{@link ApiFault} {@code REQUEST_LIMIT_EXCEEDED} on either attempt:
 *       {@link UpstreamRateLimitException} without a {@code Retry-After} value;</li>
 *   <li>a {@link ConnectionException} with an {@link IOException} (a timeout, a refused connection
 *       or any other I/O failure) in its cause chain: {@link UpstreamUnavailableException}, with
 *       no further attempt; a failed {@code create} is never re-sent (D-622);</li>
 *   <li>any other {@link ConnectionException} or {@link ApiFault}: {@link IllegalStateException}
 *       carrying the vendor message (D-622).</li>
 * </ul>
 * Exceptions thrown by {@link SalesforceSessionProvider#connection()} and
 * {@link SalesforceSessionProvider#reauthenticate()}, and every {@link RuntimeException} thrown
 * by a partner call, propagate unchanged. No other attempt is ever made: there is no retry loop,
 * no back-off and no sleep.
 *
 * <p>A {@code null} argument raises {@link NullPointerException} before any session is requested
 * (D-623).
 *
 * <p>Log lines name the operation, record counts and fault codes only; they never carry field
 * values, record ids, the SOQL text, session ids, tokens or credentials (D-623).
 *
 * <pre>{@code
 * List<SaveResult> saved = salesforceClient.create("Account", accounts);  // one create call
 * List<SObject> found = salesforceClient.query(
 *         "SELECT Id,Region__c FROM Account WHERE Name = 'Universal Exports'");
 * salesforceClient.delete(List.of(found.get(0).getId()));                 // one delete call
 * }</pre>
 *
 * <p>Thread safety: the class holds no mutable state; session caching and its synchronization
 * belong to {@link SalesforceSessionProvider}. Two concurrent calls whose sessions are both
 * rejected each re-authenticate once.
 */
@Component
public class SalesforceClient {

    /** Upstream system name carried by every upstream exception this class throws. */
    private static final String UPSTREAM = "Salesforce";

    /** Category of the DEBUG record-count lines, the DEBUG fault-code line and the WARN session line. */
    private static final Logger LOG = LoggerFactory.getLogger(SalesforceClient.class);

    /** Supplies the cached partner session and the re-authenticated one. */
    private final SalesforceSessionProvider provider;

    /**
     * Creates the client. Sends no request.
     *
     * @param provider source of the partner API session (D-013)
     * @throws NullPointerException when {@code provider} is {@code null}
     */
    public SalesforceClient(SalesforceSessionProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    /**
     * One partner API call made on a given connection. {@link #execute(String, PartnerCall)}
     * invokes it once, and a second time on the re-authenticated connection only after an
     * {@code INVALID_SESSION_ID} fault of the first invocation (D-020, D-618).
     *
     * <pre>{@code
     * SaveResult[] results = salesforceClient.execute("create", c -> c.create(sobjects));
     * }</pre>
     *
     * @param <T> result type of the call
     */
    @FunctionalInterface
    public interface PartnerCall<T> {

        /**
         * Performs the call on {@code connection}.
         *
         * @param connection the partner session to call
         * @return the vendor result
         * @throws ConnectionException when the partner API reports a fault or the transport fails
         */
        T call(PartnerConnection connection) throws ConnectionException;
    }

    /**
     * Runs {@code call} on the cached partner session and classifies its failure (D-020).
     *
     * <p>Sequence:
     * <ol>
     *   <li>{@link SalesforceSessionProvider#connection()} supplies the session; its exceptions
     *       propagate unchanged and no call is made;</li>
     *   <li>the call runs once; its result is returned;</li>
     *   <li>an {@link ApiFault} {@code INVALID_SESSION_ID} logs a WARN line naming
     *       {@code operation}, then {@link SalesforceSessionProvider#reauthenticate()} supplies a
     *       new session, whose exceptions propagate unchanged, and the call runs exactly once more
     *       on it;</li>
     *   <li>every other failure of either attempt is classified as the class description lists.</li>
     * </ol>
     *
     * @param operation name of the partner operation, used in log lines and exception messages,
     *                  for example {@code create}
     * @param call      the partner call to run
     * @param <T>       result type of the call
     * @return the result of the successful attempt
     * @throws UpstreamAuthenticationException when the session is rejected again after
     *                                         re-authentication, or when the provider fails to
     *                                         authenticate
     * @throws UpstreamRateLimitException      when Salesforce answers {@code REQUEST_LIMIT_EXCEEDED},
     *                                         or the provider's token request is rate limited
     * @throws UpstreamUnavailableException    when the attempt fails with an I/O cause, or the
     *                                         provider cannot reach the token endpoint
     * @throws IllegalStateException           for any other fault or {@link ConnectionException},
     *                                         with message {@code Salesforce <operation> failed: <detail>}
     * @throws NullPointerException            when {@code operation} or {@code call} is {@code null};
     *                                         no session is requested
     */
    public <T> T execute(String operation, PartnerCall<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        PartnerConnection connection = provider.connection();
        try {
            return call.call(connection);
        } catch (ApiFault fault) {
            if (fault.getExceptionCode() != ExceptionCode.INVALID_SESSION_ID) {
                throw classify(operation, fault);
            }
            LOG.warn("Salesforce session rejected during {}; re-authenticating", operation);
            return reissue(operation, call);
        } catch (ConnectionException failure) {
            throw classify(operation, failure);
        }
    }

    /**
     * Re-authenticates once and runs {@code call} exactly once on the new session (D-020). A
     * second {@code INVALID_SESSION_ID} becomes {@link UpstreamAuthenticationException}; every
     * other failure is classified by {@link #classify(String, ConnectionException)}.
     */
    private <T> T reissue(String operation, PartnerCall<T> call) {
        PartnerConnection renewed = provider.reauthenticate();
        try {
            return call.call(renewed);
        } catch (ApiFault fault) {
            if (fault.getExceptionCode() == ExceptionCode.INVALID_SESSION_ID) {
                LOG.debug("Salesforce {} fault {} after re-authentication", operation, fault.getExceptionCode());
                throw new UpstreamAuthenticationException(
                        "Salesforce session rejected after re-authentication", UPSTREAM, fault);
            }
            throw classify(operation, fault);
        } catch (ConnectionException failure) {
            throw classify(operation, failure);
        }
    }

    /**
     * Maps a failed attempt to the exception the caller receives, checking in this order: an
     * {@code REQUEST_LIMIT_EXCEEDED} fault, an {@link IOException} in the cause chain, anything
     * else (D-020, D-622). Returns the exception; the caller throws it.
     */
    private static RuntimeException classify(String operation, ConnectionException failure) {
        if (failure instanceof ApiFault fault) {
            LOG.debug("Salesforce {} fault {}", operation, fault.getExceptionCode());
            if (fault.getExceptionCode() == ExceptionCode.REQUEST_LIMIT_EXCEEDED) {
                return new UpstreamRateLimitException(
                        "Salesforce request limit exceeded during " + operation, UPSTREAM, null, fault);
            }
        }
        if (hasIoCause(failure)) {
            return new UpstreamUnavailableException(
                    "Salesforce " + operation + " failed: upstream unreachable", UPSTREAM, failure);
        }
        return new IllegalStateException("Salesforce " + operation + " failed: " + detail(failure), failure);
    }

    /**
     * Returns the vendor message of an {@link ApiFault} when it has one, otherwise
     * {@link Throwable#getMessage()} of {@code failure}, which may be {@code null}.
     */
    private static String detail(ConnectionException failure) {
        if (failure instanceof ApiFault fault && fault.getExceptionMessage() != null) {
            return fault.getExceptionMessage();
        }
        return failure.getMessage();
    }

    /**
     * Returns {@code true} when {@code failure} or a throwable in its {@link Throwable#getCause()}
     * chain is an {@link IOException}. Each throwable is visited at most once, which ends the walk
     * on a cyclic chain.
     */
    private static boolean hasIoCause(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates one sObject of {@code type} per record with a single
     * {@link PartnerConnection#create(SObject[])} call, replacing {@code sfdc:create}
     * [dataweave-with-flowreflookup/src/main/app/dataweave-with-flowref.xml:21-23].
     *
     * <p>Each map becomes one {@link SObject}, in list order, with {@link SObject#setType(String)}
     * set to {@code type}. Its entries are read in the map's iteration order: a non-null value is
     * set with {@code setField(name, value)}, and the name of a null value is added to
     * {@link SObject#setFieldsToNull(String[])}, which is always set, to an empty array when no
     * value is null (D-619). All records go in one call, without chunking; an empty list sends one
     * call with an empty array (D-620). The input maps are not modified. The call runs through
     * {@link #execute(String, PartnerCall)} with operation {@code create}.
     *
     * <pre>{@code
     * Map<String, Object> account = new LinkedHashMap<>();
     * account.put("Name", "Best Widgets");
     * account.put("BillingPostalCode", null);
     * salesforceClient.create("Account", List.of(account));
     * // one SObject: type Account, field Name, fieldsToNull [BillingPostalCode]
     * }</pre>
     *
     * @param type    sObject type of every record, {@code Account} for the account import
     * @param records field maps, one per sObject
     * @return one {@link SaveResult} per record, in the order Salesforce returned them; a record
     *         Salesforce rejected has {@code isSuccess() == false} and its {@code getErrors()}, and
     *         is returned, not thrown or logged
     * @throws NullPointerException when {@code type}, {@code records} or one of its records is
     *                              {@code null}; no call is made
     * @throws UpstreamAuthenticationException as {@link #execute(String, PartnerCall)} throws it
     * @throws UpstreamRateLimitException      as {@link #execute(String, PartnerCall)} throws it
     * @throws UpstreamUnavailableException    as {@link #execute(String, PartnerCall)} throws it;
     *                                         the records are not re-sent
     * @throws IllegalStateException           as {@link #execute(String, PartnerCall)} throws it
     */
    public List<SaveResult> create(String type, List<Map<String, Object>> records) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(records, "records");
        SObject[] sobjects = new SObject[records.size()];
        int index = 0;
        for (Map<String, Object> record : records) {
            int position = index;
            Objects.requireNonNull(record, () -> "record " + position + " is null");
            sobjects[index++] = toSObject(type, record);
        }
        SaveResult[] results = execute("create", c -> c.create(sobjects));
        LOG.debug("Salesforce {} sent {} records", "create", sobjects.length);
        return asList(results);
    }

    /**
     * Runs {@code soql} with {@link PartnerConnection#query(String)} and then
     * {@link PartnerConnection#queryMore(String)} with each page's query locator until a page is
     * done, replacing {@code sfdc:query-single}
     * [dataweave-with-flowreflookup/src/test/resources/testflows/test-flows.xml:5]. The SOQL text
     * is sent unchanged.
     *
     * <p>The whole paging sequence runs inside one {@link #execute(String, PartnerCall)} with
     * operation {@code query}; after a session re-authentication the sequence starts again with
     * {@code query} (D-621).
     *
     * <pre>{@code
     * List<SObject> accounts = salesforceClient.query(
     *         "SELECT Id,Region__c FROM Account WHERE Name = 'Universal Exports'");
     * accounts.get(0).getField("Region__c"); // "South East"
     * }</pre>
     *
     * @param soql the SOQL query
     * @return a new list of the records of every page, in page order and record order
     * @throws NullPointerException when {@code soql} is {@code null}; no call is made
     * @throws UpstreamAuthenticationException as {@link #execute(String, PartnerCall)} throws it
     * @throws UpstreamRateLimitException      as {@link #execute(String, PartnerCall)} throws it
     * @throws UpstreamUnavailableException    as {@link #execute(String, PartnerCall)} throws it
     * @throws IllegalStateException           as {@link #execute(String, PartnerCall)} throws it,
     *                                         for example for a malformed query
     */
    public List<SObject> query(String soql) {
        Objects.requireNonNull(soql, "soql");
        List<SObject> records = execute("query", c -> {
            List<SObject> collected = new ArrayList<>();
            QueryResult page = c.query(soql);
            addRecords(collected, page);
            while (!page.isDone()) {
                page = c.queryMore(page.getQueryLocator());
                addRecords(collected, page);
            }
            return collected;
        });
        LOG.debug("Salesforce {} returned {} records", "query", records.size());
        return records;
    }

    /**
     * Deletes the records with the given ids with a single {@link PartnerConnection#delete(String[])}
     * call, replacing {@code sfdc:delete}
     * [dataweave-with-flowreflookup/src/test/resources/testflows/test-flows.xml:8-10]. The ids are
     * sent unchanged and in list order. Used by the live IT for cleanup only.
     *
     * @param ids ids of the records to delete
     * @return one {@link DeleteResult} per id, in the order Salesforce returned them; a failed
     *         deletion has {@code isSuccess() == false} and is returned, not thrown
     * @throws NullPointerException when {@code ids} is {@code null}; no call is made
     * @throws UpstreamAuthenticationException as {@link #execute(String, PartnerCall)} throws it
     * @throws UpstreamRateLimitException      as {@link #execute(String, PartnerCall)} throws it
     * @throws UpstreamUnavailableException    as {@link #execute(String, PartnerCall)} throws it
     * @throws IllegalStateException           as {@link #execute(String, PartnerCall)} throws it
     */
    public List<DeleteResult> delete(List<String> ids) {
        Objects.requireNonNull(ids, "ids");
        String[] idArray = ids.toArray(new String[0]);
        DeleteResult[] results = execute("delete", c -> c.delete(idArray));
        LOG.debug("Salesforce {} sent {} records", "delete", idArray.length);
        return asList(results);
    }

    /**
     * Builds the {@link SObject} of one record: {@code type}, then each non-null entry as a field
     * and each null entry's name in {@code fieldsToNull}, in the map's iteration order (D-619).
     */
    private static SObject toSObject(String type, Map<String, Object> record) {
        SObject sobject = new SObject();
        sobject.setType(type);
        List<String> fieldsToNull = new ArrayList<>();
        for (Map.Entry<String, Object> field : record.entrySet()) {
            if (field.getValue() == null) {
                fieldsToNull.add(field.getKey());
            } else {
                sobject.setField(field.getKey(), field.getValue());
            }
        }
        sobject.setFieldsToNull(fieldsToNull.toArray(new String[0]));
        return sobject;
    }

    /** Appends the records of {@code page} to {@code collected}; a page without records adds none (D-623). */
    private static void addRecords(List<SObject> collected, QueryResult page) {
        SObject[] records = page.getRecords();
        if (records != null) {
            collected.addAll(Arrays.asList(records));
        }
    }

    /**
     * Returns {@link Arrays#asList(Object[])} of {@code results}, or an empty list when the vendor
     * returned no array (D-623).
     */
    private static <R> List<R> asList(R[] results) {
        return results == null ? List.of() : Arrays.asList(results);
    }
}
