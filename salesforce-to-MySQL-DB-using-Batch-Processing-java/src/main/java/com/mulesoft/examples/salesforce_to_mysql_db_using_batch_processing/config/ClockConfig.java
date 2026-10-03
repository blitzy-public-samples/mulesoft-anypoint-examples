package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the UTC system clock read by the watermark default of {@code SalesforceToDatabaseBatchJob}
 * (SC-04, D-036).
 *
 * <p>Source: {@code SC-04 salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:63},
 * the {@code default-expression} of the {@code triggerFlow} watermark, which reads the current time as
 * {@code System.currentTimeMillis()}.
 *
 * <p>The bean is named {@code clock}. A test pins the current time by declaring a differently named
 * {@code @Primary} {@link Clock} bean or a {@code @MockBean} of type {@link Clock}:
 *
 * <pre>
 * &#64;TestConfiguration
 * static class FixedClock {
 *     &#64;Bean
 *     &#64;Primary
 *     Clock fixedClock() {
 *         return Clock.fixed(Instant.parse("2015-10-10T10:00:10Z"), ZoneOffset.UTC);
 *     }
 * }
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    /**
     * Returns the system clock in the UTC zone.
     *
     * @return {@link Clock#systemUTC()}
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
