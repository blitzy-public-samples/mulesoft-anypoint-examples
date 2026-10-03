package com.mulesoft.examples.import_leads_into_salesforce.client;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamRateLimitException;
import com.mulesoft.examples.import_leads_into_salesforce.exception.UpstreamUnavailableException;
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
 * Single entry point to the Salesforce partner API (force-partner-api 65.0.0): the only class of this project
 * that calls {@link PartnerConnection}. It replaces the connector operations bound to
 * {@code sfdc:config name="Salesforce"} [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:4-6]:
 * <ul>
 *   <li>{@link #query(String)}: {@code sfdc:query} of {@code LeadExistsStep}
 *       [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:28] and the IT sub-flow
 *       {@code selectLeadFromSalesforce} [import-leads-into-salesforce/src/test/resources/testflows/test-flows.xml:3-5];</li>
 *   <li>{@link #create(SObject[])}: {@code sfdc:create type="Lead"} inside {@code batch:commit size="200"}
 *       [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:33-37];</li>
 *   <li>{@link #delete(String[])}: the IT sub-flow {@code deleteLeadFromSalesforce}
 *       [import-leads-into-salesforce/src/test/resources/testflows/test-flows.xml:6-10].</li>
 * </ul>
 *
 * <p>Every operation runs through {@link #execute(String, SalesforceCall)}, which takes the session from
 * {@link SalesforceSessionProvider} (OAuth2 username-password grant, D-013) and translates a failed partner call
 * into the connector failure modes of D-020, with upstream system {@code Salesforce}:
 * <table>
 *   <caption>Failure classification of {@code execute}, applied in this order</caption>
 *   <tr><th>Failure of the partner call</th><th>Result</th></tr>
 *   <tr><td>{@link ApiFault} {@code INVALID_SESSION_ID}</td>
 *       <td>one {@link SalesforceSessionProvider#reauthenticate()} and one repeat of the call on the new session;
 *       a second {@code INVALID_SESSION_ID} throws {@link UpstreamAuthenticationException}, any other failure of
 *       the repeat is classified by the rows below</td></tr>
 *   <tr><td>{@link ApiFault} {@code REQUEST_LIMIT_EXCEEDED}</td>
 *       <td>{@link UpstreamRateLimitException} with a {@code null} {@code Retry-After}; no repeat</td></tr>
 *   <tr><td>an {@link IOException} anywhere in the cause chain, the thrown exception included (for example a
 *       {@link ConnectionException} wrapping a {@code SocketTimeoutException} or {@code ConnectException})</td>
 *       <td>{@link UpstreamUnavailableException} after exactly one attempt; nothing is re-sent, writes included</td></tr>
 *   <tr><td>any other {@link Exception}: another {@link ApiFault} code such as {@code MALFORMED_QUERY}, another
 *       {@link ConnectionException}, or a {@link RuntimeException} raised by the call</td>
 *       <td>{@link SalesforceOperationException} naming the operation</td></tr>
 * </table>
 * The three {@code Upstream*} exceptions, whether thrown by the session provider or by the call, propagate
 * unchanged, and a {@link java.lang.Error} is never caught. The re-authentication path is the only repeat of a
 * Salesforce call in this project (D-020).
 *
 * <p>Example:
 * <pre>{@code
 * QueryResult found = salesforceClient.query("SELECT Id FROM Lead WHERE Email = 'faucibus@egetmetus.org'");
 * boolean exists = found.getSize() > 0;
 * SaveResult[] saved = salesforceClient.create(leads);      // at most 200 SObjects per call
 * }</pre>
 *
 * <p>Thread safety: the class holds no mutable state; session access is synchronized by
 * {@link SalesforceSessionProvider}.
 */
@Component
public class SalesforceClient {

    /** Upstream system name carried by every {@code Upstream*} exception this class throws (D-020). */
    static final String UPSTREAM_SYSTEM = "Salesforce";

    /** Largest number of SObjects one {@link #create(SObject[])} call accepts, the {@code batch:commit} size. */
    static final int MAX_CREATE_SIZE = 200;

    /** Logger for the re-authentication WARN line and the DEBUG classification lines. */
    private static final Logger LOG = LoggerFactory.getLogger(SalesforceClient.class);

    /** Source of the cached partner session and of its single renewal (D-013, D-020). */
    private final SalesforceSessionProvider sessions;

    /**
     * Creates the client. Contacts no system.
     *
     * @param sessions provider of the partner session
     * @throws NullPointerException when {@code sessions} is {@code null}
     */
    public SalesforceClient(SalesforceSessionProvider sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    /**
     * Runs one partner call on the current session and classifies its failure (D-020).
     *
     * <p>Steps:
     * <ol>
     *   <li>{@link SalesforceSessionProvider#connection()} supplies the session; an {@code Upstream*} exception of
     *       its token request propagates unchanged and no partner call is made.</li>
     *   <li>{@code call} runs once on that session and its result is returned.</li>
     *   <li>On an {@link ApiFault} with {@link ExceptionCode#INVALID_SESSION_ID}, a WARN line
     *       {@code Salesforce session invalid during <operation>; re-authenticating} is logged,
     *       {@link SalesforceSessionProvider#reauthenticate()} is called once and {@code call} runs once more on
     *       the session it returns. A second {@code INVALID_SESSION_ID} throws
     *       {@link UpstreamAuthenticationException} with the message
     *       {@code Salesforce <operation> rejected the session after re-authentication}.</li>
     *   <li>Every other failure, of the first or of the repeated call, is classified without a further call:
     *       {@code REQUEST_LIMIT_EXCEEDED} → {@link UpstreamRateLimitException}
     *       ({@code Salesforce <operation> exceeded the request limit}, {@code Retry-After} {@code null}); an
     *       {@link IOException} in the cause chain → {@link UpstreamUnavailableException}
     *       ({@code Salesforce <operation> failed to reach the service}); anything else →
     *       {@link SalesforceOperationException}.</li>
     * </ol>
     * At most two partner calls are made, and the second only after a re-authentication.
     *
     * @param operation name of the partner operation, used in messages and in
     *                  {@link SalesforceOperationException#getOperation()}, for example {@code query}
     * @param call      the partner call; receives the session to use
     * @param <T>       result type of the call
     * @return the result of the successful call
     * @throws UpstreamAuthenticationException when the session is rejected again after re-authentication, or the
     *                                         token request is rejected
     * @throws UpstreamRateLimitException      when Salesforce reports {@code REQUEST_LIMIT_EXCEEDED}, or the token
     *                                         endpoint answers 429
     * @throws UpstreamUnavailableException    when Salesforce or the token endpoint cannot be reached or does not
     *                                         answer in time
     * @throws SalesforceOperationException    for any other failure of the call
     * @throws NullPointerException            when {@code operation} or {@code call} is {@code null}; no partner
     *                                         call is made
     */
    public <T> T execute(String operation, SalesforceCall<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        PartnerConnection connection = sessions.connection();
        try {
            return call.apply(connection);
        } catch (UpstreamAuthenticationException | UpstreamRateLimitException | UpstreamUnavailableException e) {
            throw e;
        } catch (ApiFault fault) {
            if (fault.getExceptionCode() == ExceptionCode.INVALID_SESSION_ID) {
                return repeatAfterReauthentication(operation, call);
            }
            throw classify(operation, fault);
        } catch (Exception e) {
            throw classify(operation, e);
        }
    }

    /**
     * Returns {@link PartnerConnection#query(String)} for {@code soql}, through {@link #execute} with operation
     * {@code query}. The SOQL text is passed on exactly as given; composing it is the caller's (D-044). Paging
     * through {@code queryMore} is not offered.
     *
     * <p>Callers: {@code CreateLeadsBatchJob.leadExistsStep} with {@code SELECT Id FROM Lead WHERE Email = '<Email>'}
     * [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:28], and the IT with
     * {@code SELECT FirstName,Id,LastName FROM Lead WHERE Email = '<email>'}
     * [import-leads-into-salesforce/src/test/resources/testflows/test-flows.xml:4].
     *
     * @param soql the SOQL query text
     * @return the query result returned by Salesforce
     * @throws UpstreamAuthenticationException see {@link #execute(String, SalesforceCall)}
     * @throws UpstreamRateLimitException      see {@link #execute(String, SalesforceCall)}
     * @throws UpstreamUnavailableException    see {@link #execute(String, SalesforceCall)}
     * @throws SalesforceOperationException    see {@link #execute(String, SalesforceCall)}
     */
    public QueryResult query(String soql) {
        return execute("query", c -> c.query(soql));
    }

    /**
     * Returns {@link PartnerConnection#create(SObject[])} for {@code objects}, through {@link #execute} with
     * operation {@code create}: one {@code sfdc:create type="Lead"} of a {@code batch:commit size="200"} block
     * [import-leads-into-salesforce/src/main/app/import-leads-into-salesforce.xml:33-37].
     *
     * <p>A record that Salesforce rejects is reported in its {@link SaveResult} ({@code isSuccess() == false}) and
     * is returned, not thrown. A failed call is never re-sent, except the single repeat after
     * {@code INVALID_SESSION_ID} (D-020).
     *
     * @param objects the SObjects to create, at most 200
     * @return one {@link SaveResult} per SObject, in request order
     * @throws NullPointerException            when {@code objects} is {@code null}; no partner call is made
     * @throws IllegalArgumentException        when {@code objects} holds more than 200
     *                                         SObjects; no partner call is made
     * @throws UpstreamAuthenticationException see {@link #execute(String, SalesforceCall)}
     * @throws UpstreamRateLimitException      see {@link #execute(String, SalesforceCall)}
     * @throws UpstreamUnavailableException    see {@link #execute(String, SalesforceCall)}
     * @throws SalesforceOperationException    see {@link #execute(String, SalesforceCall)}
     */
    public SaveResult[] create(SObject[] objects) {
        Objects.requireNonNull(objects, "objects");
        if (objects.length > MAX_CREATE_SIZE) {
            throw new IllegalArgumentException(
                    "create accepts at most " + MAX_CREATE_SIZE + " SObjects per call, got " + objects.length);
        }
        return execute("create", c -> c.create(objects));
    }

    /**
     * Returns {@link PartnerConnection#delete(String[])} for {@code ids}, through {@link #execute} with operation
     * {@code delete}. Used by the IT cleanup only, replacing the sub-flow {@code deleteLeadFromSalesforce}
     * [import-leads-into-salesforce/src/test/resources/testflows/test-flows.xml:6-10] (D-058).
     *
     * @param ids record ids to delete
     * @return one {@link DeleteResult} per id, in request order
     * @throws UpstreamAuthenticationException see {@link #execute(String, SalesforceCall)}
     * @throws UpstreamRateLimitException      see {@link #execute(String, SalesforceCall)}
     * @throws UpstreamUnavailableException    see {@link #execute(String, SalesforceCall)}
     * @throws SalesforceOperationException    see {@link #execute(String, SalesforceCall)}
     */
    public DeleteResult[] delete(String[] ids) {
        return execute("delete", c -> c.delete(ids));
    }

    /**
     * Renews the session once and runs {@code call} once more on the renewed session (D-020). An
     * {@code Upstream*} exception of the renewal propagates unchanged and no second partner call is made. A
     * second {@code INVALID_SESSION_ID} throws {@link UpstreamAuthenticationException}; any other failure is
     * classified by {@link #classify(String, Exception)} and never repeated.
     *
     * @param operation name of the partner operation
     * @param call      the partner call that failed with {@code INVALID_SESSION_ID}
     * @param <T>       result type of the call
     * @return the result of the repeated call
     */
    private <T> T repeatAfterReauthentication(String operation, SalesforceCall<T> call) {
        LOG.warn("Salesforce session invalid during {}; re-authenticating", operation);
        PartnerConnection renewed = sessions.reauthenticate();
        try {
            return call.apply(renewed);
        } catch (UpstreamAuthenticationException | UpstreamRateLimitException | UpstreamUnavailableException e) {
            throw e;
        } catch (ApiFault fault) {
            if (fault.getExceptionCode() == ExceptionCode.INVALID_SESSION_ID) {
                throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM,
                        "Salesforce " + operation + " rejected the session after re-authentication", fault);
            }
            throw classify(operation, fault);
        } catch (Exception e) {
            throw classify(operation, e);
        }
    }

    /**
     * Maps a failure that is not repeated to the exception {@link #execute} throws, in this order (D-020):
     * <ol>
     *   <li>{@link ApiFault} with {@link ExceptionCode#REQUEST_LIMIT_EXCEEDED} → {@link UpstreamRateLimitException}
     *       with a {@code null} {@code Retry-After};</li>
     *   <li>an {@link IOException} in the cause chain, {@code failure} included → {@link UpstreamUnavailableException};</li>
     *   <li>anything else → {@link SalesforceOperationException}, whose message is
     *       {@code <exception code>: <exception message>} for an {@link ApiFault} and {@code failure.getMessage()}
     *       otherwise.</li>
     * </ol>
     *
     * @param operation name of the partner operation
     * @param failure   the failure of the partner call
     * @return the exception to throw; {@code failure} is its cause
     */
    private static RuntimeException classify(String operation, Exception failure) {
        if (failure instanceof ApiFault limitFault
                && limitFault.getExceptionCode() == ExceptionCode.REQUEST_LIMIT_EXCEEDED) {
            LOG.debug("Salesforce {} classified as rate limit", operation);
            return new UpstreamRateLimitException(UPSTREAM_SYSTEM,
                    "Salesforce " + operation + " exceeded the request limit", null, limitFault);
        }
        if (hasIoExceptionInCauseChain(failure)) {
            LOG.debug("Salesforce {} classified as unavailable", operation);
            return new UpstreamUnavailableException(UPSTREAM_SYSTEM,
                    "Salesforce " + operation + " failed to reach the service", failure);
        }
        String message = failure instanceof ApiFault fault
                ? fault.getExceptionCode() + ": " + fault.getExceptionMessage()
                : failure.getMessage();
        LOG.debug("Salesforce {} failed: {}", operation, message);
        return new SalesforceOperationException(operation, message, failure);
    }

    /**
     * Returns whether {@code failure} or any exception in its {@link Throwable#getCause()} chain is an
     * {@link IOException}. Each exception of the chain is visited at most once, which ends the walk on a
     * self-referencing or cyclic cause chain.
     *
     * @param failure the exception whose cause chain is inspected
     * @return {@code true} when the chain holds an {@link IOException}
     */
    private static boolean hasIoExceptionInCauseChain(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * One partner call, run by {@link #execute(String, SalesforceCall)} on the session it supplies. The call
     * receives the session as its argument and may throw the partner API's checked {@link ConnectionException};
     * on the single repeat after {@code INVALID_SESSION_ID} it receives the session returned by
     * {@link SalesforceSessionProvider#reauthenticate()}. {@code execute} takes this interface in place of a
     * {@code java.util.function.Supplier} (D-641).
     *
     * <p>Example: {@code salesforceClient.execute("query", c -> c.query(soql))}.
     *
     * @param <T> result type of the call
     */
    @FunctionalInterface
    public interface SalesforceCall<T> {

        /**
         * Runs the partner call on {@code c}.
         *
         * @param c the partner session to call
         * @return the result of the partner call
         * @throws ConnectionException when the partner call fails, including an {@link ApiFault}
         */
        T apply(PartnerConnection c) throws ConnectionException;
    }

    /**
     * Failure of a partner call that none of the connector failure modes of D-020 covers: an {@link ApiFault} code
     * other than {@code INVALID_SESSION_ID} and {@code REQUEST_LIMIT_EXCEEDED} (for example
     * {@code MALFORMED_QUERY}), a {@link ConnectionException} without an {@link IOException} in its cause chain,
     * or a {@link RuntimeException} raised by the call. The partner failure is the cause.
     *
     * <p>{@code CreateLeadsBatchJob} fails the affected record or commit block with it and does not resubmit
     * the call.
     */
    public static class SalesforceOperationException extends RuntimeException {

        /** Serialization version of this exception class. */
        private static final long serialVersionUID = 1L;

        /** Name of the partner operation that failed, for example {@code query}. */
        private final String operation;

        /**
         * Creates the exception for one failed partner operation.
         *
         * @param operation name of the partner operation that failed; returned by {@link #getOperation()}
         * @param message   failure description: {@code <exception code>: <exception message>} for an
         *                  {@link ApiFault}, otherwise the failure's own message; returned by {@link #getMessage()}
         * @param cause     the partner failure; returned by {@link #getCause()}
         */
        public SalesforceOperationException(String operation, String message, Throwable cause) {
            super(message, cause);
            this.operation = operation;
        }

        /**
         * Returns the name of the partner operation that failed.
         *
         * @return the operation name passed to the constructor, for example {@code query}, {@code create} or
         *         {@code delete}
         */
        public String getOperation() {
            return operation;
        }
    }
}

