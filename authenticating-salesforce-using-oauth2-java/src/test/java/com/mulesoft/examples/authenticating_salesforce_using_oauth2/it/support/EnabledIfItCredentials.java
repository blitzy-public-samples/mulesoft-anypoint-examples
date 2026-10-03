package com.mulesoft.examples.authenticating_salesforce_using_oauth2.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Enables the annotated test class only when {@code application-it.yml} on the test classpath supplies
 * every key named in {@link #keys()} with a value other than {@code TODO}; see D-021.
 *
 * <p>Usage: {@code @EnabledIfItCredentials(keys = {"sfdc.key", "sfdc.secret", "sfdc.user", "sfdc.password"})}
 * on an {@code *IT} class. {@link ItCredentialsCondition} evaluates the keys at class level.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /** Dotted property names that {@code application-it.yml} must supply. */
    String[] keys();
}
