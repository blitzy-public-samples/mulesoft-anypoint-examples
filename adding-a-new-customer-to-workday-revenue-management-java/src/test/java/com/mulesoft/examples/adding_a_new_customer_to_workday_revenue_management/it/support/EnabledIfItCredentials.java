package com.mulesoft.examples.adding_a_new_customer_to_workday_revenue_management.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Annotating a test class registers {@link ItCredentialsCondition}, which disables the class before any
 * Spring application context starts unless the classpath resource {@code application-it.yml} holds a
 * non-blank, non-{@code TODO} value for every key in {@link #keys()}; keys are dotted names such as
 * {@code wday.status.id}. See D-021 in DECISIONS.md.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * The dotted credential keys that must be supplied.
     *
     * @return the dotted credential keys that must be supplied
     */
    String[] keys();
}
