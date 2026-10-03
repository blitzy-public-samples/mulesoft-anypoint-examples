package com.mulesoft.examples.service_orchestration_and_choice_routing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Unit tests of {@link AuditService}: flow {@code auditService}
 * ({@code service-orchestration-and-choice-routing/src/main/app/fulfillment.xml:130-143}), its
 * {@code db:insert} "Save OrderSummary" {@code insert into order_audits values(default,
 * #[payload.orderId], #[totalValue])} [fulfillment.xml:135] and its
 * {@code choice-exception-strategy} [fulfillment.xml:137-142].
 *
 * <p>Each test constructs the service with {@code new AuditService(namedParameterJdbcTemplate)}
 * over a Mockito mock of {@link NamedParameterJdbcTemplate}, with no Spring application context,
 * no database and no transaction, and asserts
 * <ul>
 *   <li>the message property name, the parameter names, the insert statement and the evaluated
 *       rollback expression hold their original values (D-072, D-474, D-475);</li>
 *   <li>one {@code auditService} call runs one
 *       {@link NamedParameterJdbcTemplate#update(String, Map)} with
 *       {@link AuditService#INSERT_AUDIT_SQL} and the order id, then the total value, bound
 *       unchanged (D-473, D-475);</li>
 *   <li>a {@link org.springframework.dao.DataAccessException} of the insert surfaces as an
 *       {@link IllegalStateException} with the expression failure message and that exception as its
 *       cause (D-072).</li>
 * </ul>
 * The transaction rollback of the audit delivery belongs to {@code listener.AuditListener} and is not
 * exercised here. The tests cover every line of {@link AuditService} (D-049).
 */
@ExtendWith(MockitoExtension.class)
public class AuditServiceTest {

    /** Order id of the audit message, the text of the {@code audit} queue message. */
    private static final String ORDER_ID = "12";

    /** Total value of one Samsung item of price 2550, {@code "0"} concatenated with {@code 2550} (D-473). */
    private static final String TOTAL_VALUE = "02550";

    /** Template mock that receives the audit insert. */
    @Mock
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /** Captor of the parameter map of the audit insert. */
    @Captor
    private ArgumentCaptor<Map<String, Object>> parameters;

    /** The unit under test, constructed over {@link #namedParameterJdbcTemplate}. */
    private AuditService service;

    /** Creates the service over the template mock. */
    @BeforeEach
    public void setUp() {
        service = new AuditService(namedParameterJdbcTemplate);
    }

    /**
     * The constants hold the original names: property and parameter {@code totalValue}, parameter
     * {@code orderId}, the insert of fulfillment.xml:135 with its two expressions replaced by
     * {@code :orderId} and {@code :totalValue}, the evaluated expression
     * {@code sessionVars.totalValue] > 500} and its failure message (D-072, D-474, D-475).
     */
    @Test
    @DisplayName("Audit constants hold the original property name, insert statement and evaluated rollback expression")
    public void constantsHoldOriginalNames() {
        assertThat(AuditService.TOTAL_VALUE_PROPERTY).isEqualTo("totalValue");
        assertThat(AuditService.ORDER_ID_PARAMETER).isEqualTo("orderId");
        assertThat(AuditService.TOTAL_VALUE_PARAMETER).isEqualTo("totalValue");
        assertThat(AuditService.INSERT_AUDIT_SQL)
                .isEqualTo("insert into order_audits values(default, :orderId, :totalValue)");
        assertThat(AuditService.AUDIT_ROLLBACK_EXPRESSION).isEqualTo("sessionVars.totalValue] > 500");
        assertThat(AuditService.EXPRESSION_FAILURE_MESSAGE)
                .isEqualTo("Execution of the expression \"sessionVars.totalValue] > 500\" failed.");
    }

    /**
     * {@code auditService("12", "02550")} runs one {@code update(INSERT_AUDIT_SQL, parameters)} whose
     * parameters are {@code orderId = "12"}, then {@code totalValue = "02550"}, and makes no other
     * call on the template (D-475).
     */
    @Test
    @DisplayName("Audit inserts the order id, then the total value, through one parameterized update")
    public void insertsOrderIdThenTotalValue() {
        service.auditService(ORDER_ID, TOTAL_VALUE);

        verify(namedParameterJdbcTemplate).update(eq(AuditService.INSERT_AUDIT_SQL), parameters.capture());
        verifyNoMoreInteractions(namedParameterJdbcTemplate);
        assertThat(parameters.getValue()).containsExactly(
                entry(AuditService.ORDER_ID_PARAMETER, ORDER_ID),
                entry(AuditService.TOTAL_VALUE_PARAMETER, TOTAL_VALUE));
    }

    /**
     * A non-String total value is bound as the same object, unconverted: {@code auditService("12", 159)}
     * binds {@code totalValue} to that {@link Integer} instance (D-473).
     */
    @Test
    @DisplayName("Audit binds the total value as given, without conversion")
    public void passesTotalValueUnchanged() {
        Integer totalValue = 159;

        service.auditService(ORDER_ID, totalValue);

        verify(namedParameterJdbcTemplate).update(eq(AuditService.INSERT_AUDIT_SQL), parameters.capture());
        verifyNoMoreInteractions(namedParameterJdbcTemplate);
        assertThat(parameters.getValue().get(AuditService.TOTAL_VALUE_PARAMETER)).isSameAs(totalValue);
        assertThat(parameters.getValue().get(AuditService.ORDER_ID_PARAMETER)).isEqualTo(ORDER_ID);
    }

    /**
     * An insert failure surfaces as the D-072 expression error: when the update throws
     * {@code DataIntegrityViolationException("insert failed")}, {@code auditService} throws an
     * {@link IllegalStateException} whose message is
     * {@code Execution of the expression "sessionVars.totalValue] > 500" failed.} and whose cause is
     * that same exception.
     */
    @Test
    @DisplayName("Audit insert failure raises the evaluated rollback expression error with the failure as its cause")
    public void insertFailureRaisesExpressionError() {
        DataIntegrityViolationException failure = new DataIntegrityViolationException("insert failed");
        when(namedParameterJdbcTemplate.update(eq(AuditService.INSERT_AUDIT_SQL), anyMap())).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.auditService(ORDER_ID, TOTAL_VALUE));

        assertThat(thrown)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AuditService.EXPRESSION_FAILURE_MESSAGE)
                .hasMessageContaining(AuditService.AUDIT_ROLLBACK_EXPRESSION);
        assertThat(thrown.getCause()).isSameAs(failure);
    }
}
