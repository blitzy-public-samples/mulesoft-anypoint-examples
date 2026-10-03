package com.mulesoft.examples.jms_message_rollback_and_redelivery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Listener-side redelivery policy of the {@code JMSRedeliver} flow, bound from the {@code redelivery-policy.*}
 * keys of {@code application.yml} (D-024).
 *
 * <p>Source: the ActiveMQ {@code RedeliveryPolicy} bean {@code redeliveryPolicy}
 * [jms-message-rollback-and-redelivery/src/main/app/jms-redelivery.xml:6-12]. Its five properties keep their
 * names and values: {@code maximumRedeliveries} 5, {@code initialRedeliveryDelay} 5000,
 * {@code redeliveryDelay} 2000, {@code useExponentialBackOff} false and {@code backOffMultiplier} 2.
 *
 * <p>The record computes two values from the {@code JMSXDeliveryCount} of a delivery, where 1 is the first
 * delivery and 2 the first redelivery:
 * <ul>
 *   <li>{@link #delayBeforeDelivery(int)}: the wait in milliseconds before that delivery is processed;</li>
 *   <li>{@link #ceilingReached(int)}: whether no further redelivery is permitted after that delivery.</li>
 * </ul>
 * With the values of {@code application.yml}, delivery 1 is processed at once, delivery 2 after 5000 ms and
 * deliveries 3 to 6 after 2000 ms each, and the ceiling is reached on delivery 6: the first delivery plus five
 * redeliveries.
 *
 * <p>Registration: {@code @ConfigurationPropertiesScan} on {@code JmsMessageRollbackAndRedeliveryApplication}
 * registers one bean of this record, bound through its canonical constructor (D-255). Relaxed binding maps each
 * kebab-case key to the component of the same name. Usage by a consumer of the bean:
 * <pre>
 * int deliveryCount = message.getIntProperty("JMSXDeliveryCount");
 * long waitMillis = redeliveryDelayPolicy.delayBeforeDelivery(deliveryCount);
 * boolean lastDelivery = redeliveryDelayPolicy.ceilingReached(deliveryCount);
 * </pre>
 *
 * @param maximumRedeliveries    the number of redeliveries permitted after the first delivery; a negative value
 *                               permits any number; key {@code redelivery-policy.maximum-redeliveries}
 * @param initialRedeliveryDelay the delay in milliseconds before the first redelivery, delivery 2; key
 *                               {@code redelivery-policy.initial-redelivery-delay}
 * @param redeliveryDelay        the delay in milliseconds before each redelivery from delivery 3 on when
 *                               exponential back-off does not apply; key {@code redelivery-policy.redelivery-delay}
 * @param useExponentialBackOff  whether the delays from delivery 3 on grow by {@code backOffMultiplier}; key
 *                               {@code redelivery-policy.use-exponential-back-off}
 * @param backOffMultiplier      the factor applied to the previous delay from delivery 3 on, when
 *                               {@code useExponentialBackOff} is set and the factor is greater than 1; key
 *                               {@code redelivery-policy.back-off-multiplier}
 */
@ConfigurationProperties(prefix = "redelivery-policy")
public record RedeliveryDelayPolicy(int maximumRedeliveries,
                                    long initialRedeliveryDelay,
                                    long redeliveryDelay,
                                    boolean useExponentialBackOff,
                                    double backOffMultiplier) {

    /**
     * Returns the delay in milliseconds before the delivery with the given {@code JMSXDeliveryCount} is
     * processed (D-024):
     * <ul>
     *   <li>{@code deliveryCount} 1 or lower, the first delivery: {@code 0};</li>
     *   <li>{@code deliveryCount} 2, the first redelivery: {@code initialRedeliveryDelay};</li>
     *   <li>{@code deliveryCount} 3 or higher, with {@code useExponentialBackOff} set and
     *       {@code backOffMultiplier} greater than 1: {@code initialRedeliveryDelay} multiplied by
     *       {@code backOffMultiplier} once for each delivery from 3 to {@code deliveryCount}, the product
     *       truncated to a {@code long} after each multiplication;</li>
     *   <li>{@code deliveryCount} 3 or higher otherwise: {@code redeliveryDelay}.</li>
     * </ul>
     * No upper bound applies to the result. A product beyond the {@code long} range is held at
     * {@link Long#MAX_VALUE} by the truncation. The result depends only on the record components and
     * {@code deliveryCount}.
     *
     * <p>Examples for deliveries 1 to 4: {@code new RedeliveryDelayPolicy(5, 5000, 2000, false, 2)} returns 0,
     * 5000, 2000 and 2000; {@code new RedeliveryDelayPolicy(5, 5000, 2000, true, 2)} returns 0, 5000, 10000 and
     * 20000; {@code new RedeliveryDelayPolicy(5, 5000, 2000, true, 1)} returns 0, 5000, 2000 and 2000.
     *
     * @param deliveryCount the {@code JMSXDeliveryCount} of the delivery about to be processed; 1 is the first
     *                      delivery
     * @return the delay in milliseconds before that delivery is processed
     */
    public long delayBeforeDelivery(int deliveryCount) {
        if (deliveryCount <= 1) {
            return 0L;
        }
        if (deliveryCount == 2) {
            return initialRedeliveryDelay;
        }
        if (useExponentialBackOff && backOffMultiplier > 1) {
            long delay = initialRedeliveryDelay;
            // One multiplication per delivery from 3 to deliveryCount inclusive; the long counter ends the loop
            // at deliveryCount = Integer.MAX_VALUE as well.
            for (long delivery = 3; delivery <= deliveryCount; delivery++) {
                delay = (long) (delay * backOffMultiplier);
            }
            return delay;
        }
        return redeliveryDelay;
    }

    /**
     * Tells whether no further redelivery is permitted after the delivery with the given
     * {@code JMSXDeliveryCount} (D-024): {@code true} when {@code maximumRedeliveries} is 0 or higher and
     * {@code deliveryCount} is greater than {@code maximumRedeliveries}, otherwise {@code false}. A negative
     * {@code maximumRedeliveries} permits any number of redeliveries and always yields {@code false}.
     *
     * <p>With {@code maximumRedeliveries} 5, {@code ceilingReached(5)} returns {@code false} and
     * {@code ceilingReached(6)} returns {@code true}: a message is delivered at most six times, the first
     * delivery plus five redeliveries.
     *
     * @param deliveryCount the {@code JMSXDeliveryCount} of the delivery being processed; 1 is the first delivery
     * @return {@code true} when no further redelivery is permitted after that delivery, {@code false} when a
     *         further redelivery is permitted
     */
    public boolean ceilingReached(int deliveryCount) {
        return maximumRedeliveries >= 0 && deliveryCount > maximumRedeliveries;
    }
}
