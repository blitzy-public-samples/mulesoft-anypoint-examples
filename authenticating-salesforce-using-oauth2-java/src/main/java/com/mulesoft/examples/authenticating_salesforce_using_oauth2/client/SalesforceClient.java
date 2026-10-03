package com.mulesoft.examples.authenticating_salesforce_using_oauth2.client;

import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamRateLimitException;
import com.mulesoft.examples.authenticating_salesforce_using_oauth2.exception.UpstreamUnavailableException;
import com.sforce.soap.partner.Connector;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.fault.ApiFault;
import com.sforce.soap.partner.fault.ExceptionCode;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.function.ThrowingSupplier;

/**
 * Salesforce partner-API client of the {@code sfdc:query} operation
 * [authenticating-salesforce-using-oauth2/src/main/app/salesforce-oauth.xml:13].
 *
 * <p>Every call runs on a {@link PartnerConnection} whose session id is the access token held by
 * {@link SalesforceOAuthClient} and whose service endpoint is the token's instance URL followed by
 * {@link #SERVICE_PATH} (D-014). The connection is created through the {@link ConnectionFactory} on
 * first use and reused while the access token and instance URL stay the same; a changed token
 * creates a new connection. The client never calls {@code login()} and sets no connect or read
 * timeout on the connection.
 *
 * <p>Each partner-API call goes through one wrapper that classifies its failure (D-020):
 *
 * <ul>
 *   <li>No token held: {@link UpstreamAuthenticationException}; no call is made.</li>
 *   <li>{@link ApiFault} {@code INVALID_SESSION_ID}: {@link SalesforceOAuthClient#refresh()} runs, then
 *       the call is re-issued exactly once. A second {@code INVALID_SESSION_ID} gives
 *       {@link UpstreamAuthenticationException}; any other failure of the re-issued call is classified
 *       by the rules below.</li>
 *   <li>{@link ApiFault} {@code REQUEST_LIMIT_EXCEEDED}: {@link UpstreamRateLimitException} with no
 *       {@code Retry-After} value; the call is not re-issued.</li>
 *   <li>{@link ConnectionException} whose cause chain holds a {@link SocketTimeoutException} or
 *       {@link ConnectException}: {@link UpstreamUnavailableException} after exactly one attempt.</li>
 *   <li>A {@link RuntimeException}, the upstream exceptions of {@link SalesforceOAuthClient} included:
 *       rethrown unchanged.</li>
 *   <li>Any other checked exception, every other {@link ApiFault} included:
 *       {@link IllegalStateException} carrying the fault message, else the exception message, else the
 *       exception class name.</li>
 * </ul>
 *
 * <p>Every upstream exception carries {@link SalesforceOAuthClient#UPSTREAM_SYSTEM}. The client logs no
 * session id and no query result.
 *
 * <pre>{@code
 * List<SObject> contacts = salesforceClient.query("SELECT id,lastname,lastmodifieddate from contact limit 10");
 * }</pre>
 */
@Component
public class SalesforceClient {

    /** Partner-API SOAP path appended to the token's instance URL to form the service endpoint. */
    static final String SERVICE_PATH = "/services/Soap/u/65.0";

    /** Message of the {@link UpstreamAuthenticationException} raised while no access token is held. */
    private static final String NO_TOKEN_MESSAGE = "No Salesforce access token is held";

    /** Operation name of the first page request, as passed to {@link #execute(String, ThrowingSupplier)}. */
    private static final String QUERY = "query";

    /** Operation name of each further page request, as passed to {@link #execute(String, ThrowingSupplier)}. */
    private static final String QUERY_MORE = "queryMore";

    /** Receives the DEBUG entry written before a token refresh and the single re-issued call. */
    private static final Logger log = LoggerFactory.getLogger(SalesforceClient.class);

    /** Source of the access token and instance URL, and of the token refresh (D-014). */
    private final SalesforceOAuthClient oauthClient;

    /** Creates a {@link PartnerConnection} from a prepared {@link ConnectorConfig}. */
    private final ConnectionFactory connectionFactory;

    /** Connection created for {@link #cachedAccessToken} and {@link #cachedInstanceUrl}; guarded by {@code this}. */
    private PartnerConnection cachedConnection;

    /** Access token the cached connection was created with; guarded by {@code this}. */
    private String cachedAccessToken;

    /** Instance URL the cached connection was created with; guarded by {@code this}. */
    private String cachedInstanceUrl;

    /**
     * Creates the client with connections built by {@link Connector#newConnection(ConnectorConfig)}.
     *
     * @param oauthClient the holder of the Salesforce token
     * @throws NullPointerException if {@code oauthClient} is {@code null}
     */
    @Autowired
    public SalesforceClient(SalesforceOAuthClient oauthClient) {
        this(oauthClient, Connector::newConnection);
    }

    /**
     * Creates the client with connections built by the given factory.
     *
     * @param oauthClient       the holder of the Salesforce token
     * @param connectionFactory creates a connection from a prepared {@link ConnectorConfig}
     * @throws NullPointerException if an argument is {@code null}
     */
    SalesforceClient(SalesforceOAuthClient oauthClient, ConnectionFactory connectionFactory) {
        this.oauthClient = Objects.requireNonNull(oauthClient, "oauthClient");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    /**
     * Runs a SOQL query and returns every record of every result page
     * [salesforce-oauth.xml:13 {@code sfdc:query}].
     *
     * <p>Sends {@code query(soql)}, then {@code queryMore(<query locator>)} while the last page is not
     * done, and returns the records of all pages in the order received; a page without records adds
     * none. Each request runs through the D-020 classification described on the class.
     *
     * @param soql the SOQL text, sent unchanged
     * @return a new mutable list of the records, empty when the query matches none
     * @throws NullPointerException            if {@code soql} is {@code null}
     * @throws UpstreamAuthenticationException if no token is held, or the session is still rejected
     *                                         after one token refresh
     * @throws UpstreamRateLimitException      if Salesforce answers {@code REQUEST_LIMIT_EXCEEDED}
     * @throws UpstreamUnavailableException    if a request times out or cannot connect
     * @throws IllegalStateException           for any other partner-API failure, or a request that
     *                                         returns no result
     */
    public List<SObject> query(String soql) {
        Objects.requireNonNull(soql, "soql");
        QueryResult page = requireResult(QUERY, execute(QUERY, () -> connection().query(soql)));
        List<SObject> records = new ArrayList<>();
        addRecords(records, page);
        while (!page.isDone()) {
            String locator = page.getQueryLocator();
            page = requireResult(QUERY_MORE, execute(QUERY_MORE, () -> connection().queryMore(locator)));
            addRecords(records, page);
        }
        return records;
    }

    /**
     * Runs one partner-API call and classifies its failure (D-020).
     *
     * <ol>
     *   <li>Without a token held, {@link UpstreamAuthenticationException} is thrown and {@code call} is
     *       not invoked.</li>
     *   <li>{@code call} runs once and its result is returned.</li>
     *   <li>An {@link ApiFault} {@code INVALID_SESSION_ID} runs {@link SalesforceOAuthClient#refresh()},
     *       whose exceptions propagate unchanged, and then {@code call} once more. A second
     *       {@code INVALID_SESSION_ID} gives {@link UpstreamAuthenticationException} with the fault as
     *       its cause.</li>
     *   <li>Every other failure of either invocation gives the exception
     *       {@link #classify(String, Exception)} returns.</li>
     * </ol>
     *
     * @param operation the partner-API operation name used in exception messages
     * @param call      the partner-API call; it obtains its connection when invoked
     * @param <T>       the result type of the call
     * @return the result of the call
     */
    private <T> T execute(String operation, ThrowingSupplier<T> call) {
        if (!oauthClient.hasToken()) {
            throw new UpstreamAuthenticationException(NO_TOKEN_MESSAGE, SalesforceOAuthClient.UPSTREAM_SYSTEM);
        }
        try {
            return call.getWithException();
        } catch (ApiFault fault) {
            if (fault.getExceptionCode() != ExceptionCode.INVALID_SESSION_ID) {
                throw classify(operation, fault);
            }
            log.debug("Salesforce {} answered INVALID_SESSION_ID; refreshing the token and re-issuing the call once",
                    operation);
        } catch (Exception ex) {
            throw classify(operation, ex);
        }
        oauthClient.refresh();
        try {
            return call.getWithException();
        } catch (ApiFault fault) {
            if (fault.getExceptionCode() == ExceptionCode.INVALID_SESSION_ID) {
                throw new UpstreamAuthenticationException(
                        "Salesforce rejected the session after re-authentication during " + operation,
                        SalesforceOAuthClient.UPSTREAM_SYSTEM, fault);
            }
            throw classify(operation, fault);
        } catch (Exception ex) {
            throw classify(operation, ex);
        }
    }

    /**
     * Returns the exception to throw for a failed partner-API call that is not re-issued (D-020).
     *
     * <ul>
     *   <li>{@link ApiFault} {@code REQUEST_LIMIT_EXCEEDED}: {@link UpstreamRateLimitException} with a
     *       {@code null} {@code Retry-After} value and the fault as its cause.</li>
     *   <li>Any other {@link ApiFault}: {@link IllegalStateException}.</li>
     *   <li>{@link ConnectionException} whose cause chain holds a {@link SocketTimeoutException} or
     *       {@link ConnectException}: {@link UpstreamUnavailableException} with the failure as its
     *       cause.</li>
     *   <li>{@link RuntimeException}: the failure itself.</li>
     *   <li>Any other checked exception: {@link IllegalStateException}; an {@link InterruptedException}
     *       also restores the interrupt status of the current thread.</li>
     * </ul>
     *
     * <p>Each {@link IllegalStateException} carries the text {@link #failureMessage(Exception)} returns
     * and the failure as its cause.
     *
     * @param operation the partner-API operation name used in exception messages
     * @param failure   the failure of the call
     * @return the exception to throw
     */
    private static RuntimeException classify(String operation, Exception failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof ApiFault fault) {
            if (fault.getExceptionCode() == ExceptionCode.REQUEST_LIMIT_EXCEEDED) {
                return new UpstreamRateLimitException("Salesforce request limit exceeded during " + operation,
                        SalesforceOAuthClient.UPSTREAM_SYSTEM, null, fault);
            }
            return new IllegalStateException(failureMessage(fault), fault);
        }
        if (failure instanceof ConnectionException && isTimeoutOrConnectFailure(failure)) {
            return new UpstreamUnavailableException("Salesforce " + operation + " timed out or could not connect",
                    SalesforceOAuthClient.UPSTREAM_SYSTEM, failure);
        }
        if (failure instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return new IllegalStateException(failureMessage(failure), failure);
    }

    /**
     * Returns the text of a failure that is not an upstream failure mode.
     *
     * @param failure the failure of the call
     * @return {@link ApiFault#getExceptionMessage()} for a fault when it is not blank, else
     *         {@link Exception#getMessage()} when it is not blank, else the failure's class name
     */
    private static String failureMessage(Exception failure) {
        if (failure instanceof ApiFault fault && !isBlank(fault.getExceptionMessage())) {
            return fault.getExceptionMessage();
        }
        if (!isBlank(failure.getMessage())) {
            return failure.getMessage();
        }
        return failure.getClass().getName();
    }

    /**
     * Returns whether a failure's cause chain, the failure included, holds a socket timeout or a
     * refused connection (D-020).
     *
     * <p>The chain is walked once per distinct throwable and ends at the first repeated one.
     *
     * @param failure the failure of the call
     * @return {@code true} when the chain holds a {@link SocketTimeoutException} or
     *         {@link ConnectException}
     */
    private static boolean isTimeoutOrConnectFailure(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && seen.add(current)) {
            if (current instanceof SocketTimeoutException || current instanceof ConnectException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * Returns the connection for the token currently held, creating and caching a new one when the
     * access token or instance URL differs from the cached connection's (D-014).
     *
     * <p>A new connection is created from a {@link ConnectorConfig} carrying the access token as session
     * id, {@code <instanceUrl>/services/Soap/u/65.0} as both service and auth endpoint, and manual login.
     * No login request is sent and no timeout is set.
     *
     * @return the connection to run a partner-API call on
     * @throws UpstreamAuthenticationException if no token is held
     * @throws IllegalStateException           if the factory returns no connection
     * @throws ConnectionException             if the factory fails to create the connection
     */
    private synchronized PartnerConnection connection() throws ConnectionException {
        SalesforceOAuthClient.Token token = oauthClient.currentToken();
        if (token == null) {
            throw new UpstreamAuthenticationException(NO_TOKEN_MESSAGE, SalesforceOAuthClient.UPSTREAM_SYSTEM);
        }
        if (cachedConnection != null
                && Objects.equals(token.accessToken(), cachedAccessToken)
                && Objects.equals(token.instanceUrl(), cachedInstanceUrl)) {
            return cachedConnection;
        }
        ConnectorConfig config = new ConnectorConfig();
        String endpoint = token.instanceUrl() + SERVICE_PATH;
        config.setSessionId(token.accessToken());
        config.setServiceEndpoint(endpoint);
        config.setAuthEndpoint(endpoint);
        config.setManualLogin(true);
        PartnerConnection connection = connectionFactory.create(config);
        if (connection == null) {
            throw new IllegalStateException("Salesforce connection factory returned no connection");
        }
        cachedConnection = connection;
        cachedAccessToken = token.accessToken();
        cachedInstanceUrl = token.instanceUrl();
        return connection;
    }

    /**
     * Returns a page returned by a partner-API call, rejecting a missing one.
     *
     * @param operation the partner-API operation name used in the exception message
     * @param page      the page returned by the call
     * @return {@code page}
     * @throws IllegalStateException if {@code page} is {@code null}
     */
    private static QueryResult requireResult(String operation, QueryResult page) {
        if (page == null) {
            throw new IllegalStateException("Salesforce " + operation + " returned no result");
        }
        return page;
    }

    /**
     * Appends the records of a result page in order; a page whose records are {@code null} adds none.
     *
     * @param records the list receiving the records
     * @param page    the result page
     */
    private static void addRecords(List<SObject> records, QueryResult page) {
        SObject[] pageRecords = page.getRecords();
        if (pageRecords != null) {
            Collections.addAll(records, pageRecords);
        }
    }

    /**
     * Returns whether a value is {@code null}, empty or whitespace only.
     *
     * @param value the value
     * @return {@code true} when {@code value} is {@code null} or blank
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Creates the partner-API connection that every call of {@link SalesforceClient} runs on (D-020).
     *
     * <p>{@link SalesforceClient} passes a {@link ConnectorConfig} carrying the access token as session
     * id, the service and auth endpoint {@code <instanceUrl>/services/Soap/u/65.0} and manual login. The
     * public constructor uses {@link Connector#newConnection(ConnectorConfig)}.
     *
     * <pre>{@code
     * SalesforceClient.ConnectionFactory factory = Connector::newConnection;
     * }</pre>
     */
    @FunctionalInterface
    public interface ConnectionFactory {

        /**
         * Creates a connection for the given configuration.
         *
         * @param config the prepared connector configuration
         * @return the connection, never {@code null}
         * @throws ConnectionException if the connection cannot be created
         */
        PartnerConnection create(ConnectorConfig config) throws ConnectionException;
    }
}
