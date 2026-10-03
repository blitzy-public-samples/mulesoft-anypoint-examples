package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.client;

import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamAuthenticationException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamRateLimitException;
import com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.exception.UpstreamUnavailableException;
import com.workday.bsvc.GetCustomersRequestType;
import com.workday.bsvc.GetCustomersResponseType;
import com.workday.bsvc.ObjectFactory;
import com.workday.bsvc.PutCustomerRequestType;
import com.workday.bsvc.PutCustomerResponseType;
import jakarta.xml.bind.JAXBElement;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import javax.xml.namespace.QName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.WebServiceTransportException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

/**
 * Calls the Workday Revenue_Management v35.0 operations Put_Customer and Get_Customers and classifies
 * their failures (D-018, D-020).
 *
 * <p>{@link #putCustomer(PutCustomerRequestType)} replaces
 * {@code wd-connector:invoke type="Revenue_Management||Put_Customer"} of {@code add-customer-flow}
 * [adding-a-new-customer-to-workday-revenue-management/src/main/app/add_a_new_customer.xml:42].
 * {@link #getCustomers(GetCustomersRequestType)} replaces the {@code Revenue_Management||Get_Customers}
 * read-back of the MUnit test {@code Test}
 * [adding-a-new-customer-to-workday-revenue-management/src/test/munit/integration-test.xml:62-82].
 *
 * <p>Each operation marshals its request as the document/literal global element
 * ({@code urn:com.workday/bsvc} {@code Put_Customer_Request} or {@code Get_Customers_Request}) through
 * the injected {@link WebServiceTemplate} and unwraps the response element into its JAXB type. The
 * request goes to the template's default URI with the SOAP 1.1 default {@code SOAPAction: ""} and
 * without a {@code Workday_Common_Header} (D-018, D-078). The template supplies the marshaller, the
 * WS-Security UsernameToken, the message sender and its timeouts; this class sets no URI, timeout,
 * interceptor or credential.
 *
 * <p>Every call runs through {@link #execute(String, Supplier)}, which applies these rules in order
 * (D-020, D-406, D-407):
 * <ol>
 *   <li>Authentication failure: HTTP 401, or a SOAP fault whose fault-code local part ends with
 *       {@code authenticationError} or whose fault string contains {@code invalid username or password}
 *       (case-insensitive). The call is re-issued once; a second authentication failure is thrown as
 *       {@link UpstreamAuthenticationException}, and any other second failure goes through rules 2 to 4.</li>
 *   <li>HTTP 429: thrown as {@link UpstreamRateLimitException} with the raw {@code Retry-After} value;
 *       the call is not re-issued.</li>
 *   <li>Timeout or connectivity failure ({@link SocketTimeoutException}, {@link HttpTimeoutException},
 *       {@link ConnectException}, {@link UnknownHostException} or a {@link WebServiceIOException} other
 *       than a {@link WebServiceTransportException} in the cause chain): thrown as
 *       {@link UpstreamUnavailableException}; the call is not re-issued.</li>
 *   <li>Any other failure, {@code Validation_Fault} and {@code Processing_Fault} SOAP faults and
 *       {@link WebServiceTransportException} HTTP error statuses included: rethrown unchanged.</li>
 * </ol>
 * Each {@code Upstream*} exception is logged once at ERROR with the operation and
 * {@code upstreamSystem=Workday}. Successful calls are not logged.
 *
 * <p>The class is the project's Workday {@code client/} boundary, mocked directly by service tests
 * (D-408). A {@code null} argument fails with {@link NullPointerException} before anything is sent
 * (D-409). Instances hold no mutable state and are safe for concurrent use.
 *
 * <p>Example:
 * <pre>{@code
 * PutCustomerResponseType response = workdayRevenueClient.putCustomer(request);
 * String descriptor = response.getCustomerReference().getDescriptor();
 * }</pre>
 */
@Component
public class WorkdayRevenueClient {

    /** The {@code upstreamSystem} value of every {@code Upstream*} exception and log line. */
    static final String UPSTREAM_SYSTEM = "Workday";

    /** Operation name of the Revenue_Management {@code Put_Customer} call. */
    static final String PUT_CUSTOMER = "Put_Customer";

    /** Operation name of the Revenue_Management {@code Get_Customers} call. */
    static final String GET_CUSTOMERS = "Get_Customers";

    /** HTTP status of an authentication failure reported by {@link WorkdayHttpStatusInterceptor}. */
    private static final int HTTP_UNAUTHORIZED = 401;

    /** HTTP status of a rate-limit response reported by {@link WorkdayHttpStatusInterceptor}. */
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    /** Suffix of the fault-code local part of a Workday authentication fault (D-406). */
    private static final String AUTHENTICATION_FAULT_CODE_SUFFIX = "authenticationError";

    /** Lower-case fault-string fragment of a Workday authentication fault (D-406). */
    private static final String AUTHENTICATION_FAULT_STRING = "invalid username or password";

    /** Maximum number of cause-chain elements inspected by the chain walks. */
    private static final int MAX_CAUSE_DEPTH = 32;

    private static final Logger log = LoggerFactory.getLogger(WorkdayRevenueClient.class);

    private final WebServiceTemplate webServiceTemplate;

    private final ObjectFactory objectFactory = new ObjectFactory();

    /**
     * Creates the client over the project's single Workday {@link WebServiceTemplate}.
     *
     * @param webServiceTemplate the template configured for Revenue_Management v35.0; never {@code null}
     * @throws NullPointerException when {@code webServiceTemplate} is {@code null} (D-409)
     */
    public WorkdayRevenueClient(WebServiceTemplate webServiceTemplate) {
        this.webServiceTemplate = Objects.requireNonNull(webServiceTemplate, "webServiceTemplate");
    }

    /**
     * Sends {@code Put_Customer_Request} and returns the {@code Put_Customer_Response} body
     * [add_a_new_customer.xml:42] (D-018, D-020).
     *
     * @param request the request built by DW-01; never {@code null}
     * @return the unwrapped response, or {@code null} when Workday returns an empty body
     * @throws NullPointerException          when {@code request} is {@code null}; nothing is sent (D-409)
     * @throws UpstreamAuthenticationException when both attempts fail authentication
     * @throws UpstreamRateLimitException    when Workday answers HTTP 429
     * @throws UpstreamUnavailableException  on a timeout or connectivity failure
     * @throws RuntimeException              any other failure, such as a {@link SoapFaultClientException},
     *                                       unchanged
     */
    public PutCustomerResponseType putCustomer(PutCustomerRequestType request) {
        Objects.requireNonNull(request, "request");
        return execute(PUT_CUSTOMER, () -> unwrap(
                webServiceTemplate.marshalSendAndReceive(objectFactory.createPutCustomerRequest(request)),
                PutCustomerResponseType.class));
    }

    /**
     * Sends {@code Get_Customers_Request} and returns the {@code Get_Customers_Response} body
     * [integration-test.xml:62-82] (D-018, D-020).
     *
     * @param request the query, for example by {@code Customer_Reference_ID}; never {@code null}
     * @return the unwrapped response, or {@code null} when Workday returns an empty body
     * @throws NullPointerException          when {@code request} is {@code null}; nothing is sent (D-409)
     * @throws UpstreamAuthenticationException when both attempts fail authentication
     * @throws UpstreamRateLimitException    when Workday answers HTTP 429
     * @throws UpstreamUnavailableException  on a timeout or connectivity failure
     * @throws RuntimeException              any other failure, such as a {@link SoapFaultClientException},
     *                                       unchanged
     */
    public GetCustomersResponseType getCustomers(GetCustomersRequestType request) {
        Objects.requireNonNull(request, "request");
        return execute(GET_CUSTOMERS, () -> unwrap(
                webServiceTemplate.marshalSendAndReceive(objectFactory.createGetCustomersRequest(request)),
                GetCustomersResponseType.class));
    }

    /**
     * Runs one Workday call and classifies its failure (D-020, D-406).
     *
     * <p>The supplier runs once. When it throws an authentication failure, a WARN line is logged and
     * the supplier runs a second and last time; this re-issue is the only repeated call of the client.
     * A second authentication failure is logged at ERROR and thrown as
     * {@link UpstreamAuthenticationException} with the second failure as its cause. Every other
     * failure, of the first or the second attempt, is classified by the rate-limit, unavailable and
     * rethrow rules of the class description and is never re-issued.
     *
     * <p>Example:
     * <pre>{@code
     * Object response = client.execute("Get_Customers", () -> template.marshalSendAndReceive(element));
     * }</pre>
     *
     * @param operation the Workday operation name used in log lines and exception messages
     * @param call      the call to run; it runs at most twice and only the authentication path runs it twice
     * @param <T>       the result type of the call
     * @return the result of the first successful attempt
     * @throws NullPointerException          when {@code operation} or {@code call} is {@code null} (D-409)
     * @throws UpstreamAuthenticationException when both attempts fail authentication
     * @throws UpstreamRateLimitException    when the failure carries HTTP 429
     * @throws UpstreamUnavailableException  when the failure is a timeout or connectivity failure
     * @throws RuntimeException              any other failure, unchanged
     */
    public <T> T execute(String operation, Supplier<T> call) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(call, "call");
        try {
            return call.get();
        } catch (RuntimeException first) {
            if (!isAuthenticationFailure(first)) {
                throw classify(operation, first);
            }
            log.warn("Workday authentication failed for {} (upstreamSystem={}); re-issuing the request once",
                    operation, UPSTREAM_SYSTEM);
            try {
                // second and last attempt (D-020)
                return call.get();
            } catch (RuntimeException second) {
                if (isAuthenticationFailure(second)) {
                    log.error("Workday call {} failed: authentication (upstreamSystem={})",
                            operation, UPSTREAM_SYSTEM, second);
                    throw new UpstreamAuthenticationException(UPSTREAM_SYSTEM,
                            "Workday authentication failed for " + operation, second);
                }
                throw classify(operation, second);
            }
        }
    }

    /**
     * Maps a non-authentication failure to the exception {@link #execute(String, Supplier)} throws,
     * applying the rules in this order (D-020, D-407):
     * <ol>
     *   <li>an {@code UpstreamHttpStatusException} with status 429 in the cause chain: logged at ERROR
     *       and returned as {@link UpstreamRateLimitException} with its raw {@code Retry-After} value;</li>
     *   <li>a timeout or connectivity exception in the cause chain, {@code ex} itself included, as
     *       {@link #isUnavailable(Throwable)} tells: logged at ERROR with {@code ex} and returned as
     *       {@link UpstreamUnavailableException} whose message ends with the root-cause message;</li>
     *   <li>anything else: {@code ex} itself, unchanged and not logged.</li>
     * </ol>
     *
     * @param operation the Workday operation name
     * @param ex        the failure of the call
     * @return the exception to throw
     */
    private RuntimeException classify(String operation, RuntimeException ex) {
        WorkdayHttpStatusInterceptor.UpstreamHttpStatusException statusException =
                findCause(ex, WorkdayHttpStatusInterceptor.UpstreamHttpStatusException.class);
        if (statusException != null && statusException.status() == HTTP_TOO_MANY_REQUESTS) {
            log.error("Workday call {} failed: rate limited, Retry-After={} (upstreamSystem={})",
                    operation, statusException.retryAfter(), UPSTREAM_SYSTEM);
            return new UpstreamRateLimitException(UPSTREAM_SYSTEM,
                    "Workday rate limit exceeded for " + operation, statusException.retryAfter(), ex);
        }
        if (isUnavailable(ex)) {
            log.error("Workday call {} failed: unavailable (upstreamSystem={})", operation, UPSTREAM_SYSTEM, ex);
            return new UpstreamUnavailableException(UPSTREAM_SYSTEM,
                    "Workday unavailable for " + operation + ": " + rootCauseMessage(ex), ex);
        }
        return ex;
    }

    /**
     * Tells whether a failure is a Workday authentication failure (D-406): an
     * {@code UpstreamHttpStatusException} with status 401 in the cause chain, or a
     * {@link SoapFaultClientException} in the cause chain whose fault-code local part ends with
     * {@code authenticationError} (Workday's {@code SOAP-ENV:Client.authenticationError}) or whose fault
     * string contains {@code invalid username or password}, compared in lower case.
     *
     * @param ex the failure of the call
     * @return {@code true} for an authentication failure
     */
    private static boolean isAuthenticationFailure(Throwable ex) {
        WorkdayHttpStatusInterceptor.UpstreamHttpStatusException statusException =
                findCause(ex, WorkdayHttpStatusInterceptor.UpstreamHttpStatusException.class);
        if (statusException != null && statusException.status() == HTTP_UNAUTHORIZED) {
            return true;
        }
        SoapFaultClientException fault = findCause(ex, SoapFaultClientException.class);
        if (fault == null) {
            return false;
        }
        QName code = fault.getFaultCode();
        if (code != null && code.getLocalPart().endsWith(AUTHENTICATION_FAULT_CODE_SUFFIX)) {
            return true;
        }
        String reason = fault.getFaultStringOrReason();
        return reason != null && reason.toLowerCase(Locale.ROOT).contains(AUTHENTICATION_FAULT_STRING);
    }

    /**
     * Tells whether the cause chain, {@code ex} itself included, holds a {@link SocketTimeoutException},
     * an {@link HttpTimeoutException} (connect timeouts included), a {@link ConnectException}, an
     * {@link UnknownHostException} or a {@link WebServiceIOException} other than a
     * {@link WebServiceTransportException} (D-020, D-407).
     *
     * @param ex the failure of the call
     * @return {@code true} for a timeout or connectivity failure
     */
    private static boolean isUnavailable(Throwable ex) {
        // WebServiceTransportException (an HTTP error status without a SOAP fault) is excluded here
        // and takes the rethrow rule (D-407).
        WebServiceIOException ioFailure = findCause(ex, WebServiceIOException.class);
        return findCause(ex, SocketTimeoutException.class) != null
                || findCause(ex, HttpTimeoutException.class) != null
                || findCause(ex, ConnectException.class) != null
                || findCause(ex, UnknownHostException.class) != null
                || (ioFailure != null && !(ioFailure instanceof WebServiceTransportException));
    }

    /**
     * Returns the first element of the cause chain, {@code ex} itself included, that is an instance of
     * {@code type}. The walk stops at a self-referencing cause or after 32 elements.
     *
     * @param ex   the start of the chain, or {@code null}
     * @param type the type looked for
     * @param <E>  the type looked for
     * @return the first matching element, or {@code null} when none matches
     */
    private static <E extends Throwable> E findCause(Throwable ex, Class<E> type) {
        Throwable current = ex;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (type.isInstance(current)) {
                return type.cast(current);
            }
            current = nextCause(current);
            depth++;
        }
        return null;
    }

    /**
     * Returns the message of the last element of the cause chain, or that element's simple class name
     * when its message is {@code null} (D-410). The walk stops at a self-referencing cause or after
     * 32 elements.
     *
     * @param ex the start of the chain; never {@code null}
     * @return the root-cause message
     */
    private static String rootCauseMessage(Throwable ex) {
        Throwable root = ex;
        Throwable current = nextCause(ex);
        int depth = 1;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            root = current;
            current = nextCause(current);
            depth++;
        }
        String message = root.getMessage();
        return message != null ? message : root.getClass().getSimpleName();
    }

    /**
     * Returns the cause of {@code current}, or {@code null} when it has none or names itself.
     *
     * @param current a chain element; never {@code null}
     * @return the next chain element, or {@code null} at the end of the chain
     */
    private static Throwable nextCause(Throwable current) {
        Throwable cause = current.getCause();
        return cause == current ? null : cause;
    }

    /**
     * Returns the value of a {@link JAXBElement} response, or the response itself when it is not
     * wrapped, cast to {@code type}; {@code null} stays {@code null}.
     *
     * @param response the object {@link WebServiceTemplate#marshalSendAndReceive(Object)} returned
     * @param type     the expected response type
     * @param <T>      the expected response type
     * @return the typed response, or {@code null}
     * @throws ClassCastException when the response is of another type; it is rethrown unchanged
     */
    private static <T> T unwrap(Object response, Class<T> type) {
        if (response instanceof JAXBElement<?> element) {
            return type.cast(element.getValue());
        }
        return type.cast(response);
    }
}
