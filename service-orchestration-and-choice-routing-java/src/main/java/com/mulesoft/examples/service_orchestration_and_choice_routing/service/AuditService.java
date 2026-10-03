package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements flow {@code auditService}
 * [service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:130-143]: inserts the
 * {@code order_audits} row of one audit message; an insert failure surfaces as the D-072
 * expression error with the failure as its cause.
 *
 * <p>An audit message is a JMS {@code TextMessage} on the {@code audit} queue whose text is the
 * order id and whose String property {@value #TOTAL_VALUE_PROPERTY} is the total value (D-474).
 * {@code listener.AuditListener} receives it inside the XA transaction of its delivery (D-026)
 * and calls {@link #auditService(String, Object)}.
 *
 * <ul>
 *   <li>The {@code db:insert} "Save OrderSummary" [fulfillment.xml:134-136] runs
 *       {@link #INSERT_AUDIT_SQL} through the {@code namedParameterJdbcTemplate} bean of
 *       {@code config.DerbyConfig}, binding the order id, then the total value, as given (D-473,
 *       D-475). Inside an active JTA transaction the statement joins it; without one it runs in
 *       auto-commit mode.</li>
 *   <li>Every {@link DataAccessException} of the insert is rethrown as an
 *       {@link IllegalStateException} with the message {@value #EXPRESSION_FAILURE_MESSAGE} and
 *       that exception as its cause. This is the outcome of the {@code choice-exception-strategy}
 *       [fulfillment.xml:137-142], whose {@code rollback-exception-strategy} condition
 *       {@code #[sessionVars.totalValue] > 5000} Mule evaluates as
 *       {@value #AUDIT_ROLLBACK_EXPRESSION} (D-072). The {@code catch-exception-strategy} branch
 *       [fulfillment.xml:139-141] is never reached.</li>
 * </ul>
 *
 * <p>The class holds no mutable state and is safe for concurrent use.
 *
 * <pre>{@code
 * AuditService service = new AuditService(namedParameterJdbcTemplate);
 * service.auditService("12", "02550");          // order_audits row ("12", 2550)
 * service.auditService("12", "0255025502550");  // IllegalStateException, cause: Derby 22018
 * }</pre>
 */
@Service
public class AuditService {

    /**
     * Name of the String property of the audit {@code TextMessage} that carries the total value,
     * the session variable {@code totalValue} of fulfillment.xml:31 (D-474).
     */
    public static final String TOTAL_VALUE_PROPERTY = "totalValue";

    /** Name of the {@link #INSERT_AUDIT_SQL} parameter bound to the order id, {@code #[payload.orderId]}. */
    public static final String ORDER_ID_PARAMETER = "orderId";

    /** Name of the {@link #INSERT_AUDIT_SQL} parameter bound to the total value, {@code #[totalValue]}. */
    public static final String TOTAL_VALUE_PARAMETER = "totalValue";

    /**
     * The {@code db:parameterized-query} of fulfillment.xml:135 with its two MEL expressions
     * replaced by the named parameters {@value #ORDER_ID_PARAMETER} and
     * {@value #TOTAL_VALUE_PARAMETER}, in the original order (D-475).
     */
    public static final String INSERT_AUDIT_SQL =
            "insert into order_audits values(default, :" + ORDER_ID_PARAMETER + ", :" + TOTAL_VALUE_PARAMETER + ")";

    /**
     * The expression Mule evaluates for the {@code rollback-exception-strategy} condition
     * {@code #[sessionVars.totalValue] > 5000} of fulfillment.xml:138 (D-072).
     */
    public static final String AUDIT_ROLLBACK_EXPRESSION = "sessionVars.totalValue] > 500";

    /** Message of the exception thrown when the audit insert fails (D-072). */
    public static final String EXPRESSION_FAILURE_MESSAGE =
            "Execution of the expression \"" + AUDIT_ROLLBACK_EXPRESSION + "\" failed.";

    /** Template that runs {@link #INSERT_AUDIT_SQL} on the Derby XA data source. */
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /**
     * Creates the service over the {@code namedParameterJdbcTemplate} bean of
     * {@code config.DerbyConfig} (D-475).
     *
     * @param namedParameterJdbcTemplate the template that runs the audit insert
     * @throws NullPointerException when {@code namedParameterJdbcTemplate} is {@code null}
     */
    public AuditService(NamedParameterJdbcTemplate namedParameterJdbcTemplate) {
        this.namedParameterJdbcTemplate =
                Objects.requireNonNull(namedParameterJdbcTemplate, "namedParameterJdbcTemplate");
    }

    /**
     * Implements flow {@code auditService} [fulfillment.xml:130-143]: inserts the audit row; an
     * insert failure surfaces as the D-072 expression error with the failure as its cause.
     *
     * <p>Runs {@link #INSERT_AUDIT_SQL} with {@value #ORDER_ID_PARAMETER} bound to
     * {@code orderId} and {@value #TOTAL_VALUE_PARAMETER} bound to {@code totalValue}, both
     * unchanged; a {@link String} total value is bound as a String and converted to the
     * {@code INTEGER} column {@code total_value} by Derby (D-473). The statement joins the active
     * transaction, if any, and starts none (D-026).
     *
     * @param orderId    the order id, the text of the audit message; may be {@code null}
     * @param totalValue the total value, the {@value #TOTAL_VALUE_PROPERTY} property of the audit
     *                   message, for example {@code "02550"}; may be {@code null}
     * @throws IllegalStateException when the insert throws a {@link DataAccessException}, for
     *                               example Derby SQLState 22018 for {@code "0255025502550"} or
     *                               42X05 when {@code order_audits} does not exist; the message
     *                               is {@value #EXPRESSION_FAILURE_MESSAGE} and the cause is that
     *                               exception (D-072)
     */
    @Transactional(propagation = Propagation.SUPPORTS)
    public void auditService(String orderId, Object totalValue) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(ORDER_ID_PARAMETER, orderId);
        parameters.put(TOTAL_VALUE_PARAMETER, totalValue);
        try {
            namedParameterJdbcTemplate.update(INSERT_AUDIT_SQL, parameters);
        } catch (DataAccessException e) {
            throw new IllegalStateException(EXPRESSION_FAILURE_MESSAGE, e);
        }
    }
}
