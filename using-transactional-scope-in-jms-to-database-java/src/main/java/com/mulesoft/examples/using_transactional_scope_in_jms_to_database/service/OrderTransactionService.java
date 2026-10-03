package com.mulesoft.examples.using_transactional_scope_in_jms_to_database.service;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.config.JmsConfig;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.mapper.OrderXmlMapper;
import com.mulesoft.examples.using_transactional_scope_in_jms_to_database.model.Order;

/**
 * Body of the flow {@code transactionsFlow1}
 * [using-transactional-scope-in-jms-to-database/src/main/app/transactions.xml:6-22] (D-025).
 *
 * <p>{@code listener.OrderListener} receives each message from queue {@code in} [transactions.xml:7] in a
 * session-transacted listener container and calls {@link #transactionsFlow1(String)} with the message text.
 * The method runs these steps in order:
 * <ol>
 *   <li>XML to object [:8-10]: {@link OrderXmlMapper#toOrder(String)} reads the {@code <order>} document into an
 *       {@link Order}.</li>
 *   <li>Logger [:11]: logs {@code XXXXXXX order: } followed by {@link Order#toString()} at INFO, for example
 *       {@code XXXXXXX order: Order [item_id=1, item_units=2, customer_id=1]}.</li>
 *   <li>Database insert [:13-16]: runs
 *       {@code insert into orders(item_id,item_units,customer_id) values (:itemId, :itemUnits, :customerId)}
 *       through the {@code namedParameterJdbcTemplate} bean, binding {@code itemId}, {@code itemUnits} and
 *       {@code customerId} from the order's getters, in that order (D-063).</li>
 *   <li>Component [:17]: calls {@link TestComponent#process(Order)} with the order; it always throws
 *       {@code MyException("exception customized")}.</li>
 *   <li>Outbound endpoint [:18-20]: sends the component's result to the queue named by
 *       {@link JmsConfig.ActiveMqProperties#outboundQueue()} ({@code out}) through the {@code jmsTemplate} bean.
 *       On a listener thread of {@code transactedListenerFactory} the send goes through the listener's
 *       transacted session (D-343).</li>
 * </ol>
 *
 * <p>All five steps run inside one local JDBC transaction of the {@code transactionManager} bean
 * ({@code DataSourceTransactionManager}), which begins when the method is entered, before the XML is read, and
 * stands for {@code ee:multi-transactional action="ALWAYS_BEGIN"} [:12-21] (D-025, D-571). Every exception thrown by a
 * step, checked or unchecked, rolls the transaction back, so the inserted row is removed, and leaves the method
 * unchanged; the listener then rethrows it inside its transacted session and the received message is rolled back
 * and redelivered. The method returns normally only when every step succeeds, and the transaction then commits.
 *
 * <p>The class holds no mutable state; one instance serves every listener thread.
 *
 * <p>Usage, from the transacted listener method:
 * <pre>{@code
 * orderTransactionService.transactionsFlow1(message.getBody(String.class));
 * }</pre>
 */
@Service
public class OrderTransactionService {

    private static final Logger log = LoggerFactory.getLogger(OrderTransactionService.class);

    /**
     * The {@code db:parameterized-query} of transactions.xml:14 with its three MEL expressions
     * {@code #[payload.itemId]}, {@code #[payload.itemUnits]} and {@code #[payload.customerId]} replaced by the
     * named parameters {@code :itemId}, {@code :itemUnits} and {@code :customerId}, in the original order.
     */
    private static final String INSERT_ORDER_SQL =
            "insert into orders(item_id,item_units,customer_id) values (:itemId, :itemUnits, :customerId)";

    /** Reads the message text into an {@link Order}, the XML-to-object transform [transactions.xml:8-10]. */
    private final OrderXmlMapper orderXmlMapper;

    /** Runs {@link #INSERT_ORDER_SQL} on the {@code MySQL_Configuration} data source [transactions.xml:4]. */
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    /** The component step [transactions.xml:17]. */
    private final TestComponent testComponent;

    /** Sends the component's result to the outbound queue [transactions.xml:18-20]. */
    private final JmsTemplate jmsTemplate;

    /** The {@code active-mq.*} keys of the connector {@code Active_MQ}; supplies the outbound queue name. */
    private final JmsConfig.ActiveMqProperties activeMqProperties;

    /**
     * Creates the service.
     *
     * @param orderXmlMapper             the mapper that reads the order XML
     * @param namedParameterJdbcTemplate the template that runs the {@code orders} insert, bean
     *                                   {@code namedParameterJdbcTemplate} of {@code config.DataSourceConfig}
     * @param testComponent              the component step
     * @param jmsTemplate                the session-transacted template, bean {@code jmsTemplate} of
     *                                   {@code config.JmsConfig}
     * @param activeMqProperties         the bound {@code active-mq.*} keys
     * @throws NullPointerException when any argument is {@code null}
     */
    public OrderTransactionService(OrderXmlMapper orderXmlMapper,
                                   NamedParameterJdbcTemplate namedParameterJdbcTemplate,
                                   TestComponent testComponent,
                                   JmsTemplate jmsTemplate,
                                   JmsConfig.ActiveMqProperties activeMqProperties) {
        this.orderXmlMapper = Objects.requireNonNull(orderXmlMapper, "orderXmlMapper");
        this.namedParameterJdbcTemplate =
                Objects.requireNonNull(namedParameterJdbcTemplate, "namedParameterJdbcTemplate");
        this.testComponent = Objects.requireNonNull(testComponent, "testComponent");
        this.jmsTemplate = Objects.requireNonNull(jmsTemplate, "jmsTemplate");
        this.activeMqProperties = Objects.requireNonNull(activeMqProperties, "activeMqProperties");
    }

    /**
     * Runs flow {@code transactionsFlow1} for one message: parses the order XML, logs it, inserts it into
     * {@code orders}, invokes {@link TestComponent} and sends its result to the outbound queue, all inside one
     * local database transaction (D-025).
     *
     * <p>Nothing is caught, wrapped or logged on failure: the exception of the failing step propagates as thrown,
     * after the transaction has been rolled back. With the ported {@link TestComponent} every call ends with
     * {@code MyException("exception customized")} after the insert, the insert is rolled back and nothing is sent
     * to {@code out}.
     *
     * @param xml the text of the message received on queue {@code in}, an {@code <order>} document such as
     *            {@code <order><itemId>1</itemId><itemUnits>2</itemUnits><customerId>1</customerId></order>}
     * @throws com.mulesoft.examples.using_transactional_scope_in_jms_to_database.exception.MyException from
     *         {@link TestComponent#process(Order)}, on every call with the ported component
     * @throws IllegalArgumentException      from {@link OrderXmlMapper#toOrder(String)} when the root element is
     *                                       not {@code order} or the text is not well-formed XML
     * @throws java.io.UncheckedIOException  from {@link OrderXmlMapper#toOrder(String)} when a child element is
     *                                       unknown or its value is not an {@code int}
     * @throws NullPointerException          when {@code xml} is {@code null}
     * @throws org.springframework.dao.DataAccessException when the insert fails, for example when the
     *         {@code orders} table does not exist
     * @throws org.springframework.jms.JmsException when the send to the outbound queue fails
     * @throws Exception                     any other exception of a step, unchanged
     */
    @Transactional(rollbackFor = Exception.class)
    public void transactionsFlow1(String xml) throws Exception {
        Order order = orderXmlMapper.toOrder(xml);
        log.info("XXXXXXX order: " + order);
        namedParameterJdbcTemplate.update(INSERT_ORDER_SQL,
                new MapSqlParameterSource()
                        .addValue("itemId", order.getItemId())
                        .addValue("itemUnits", order.getItemUnits())
                        .addValue("customerId", order.getCustomerId()));
        Object result = testComponent.process(order);
        jmsTemplate.convertAndSend(activeMqProperties.outboundQueue(), result);
    }
}
