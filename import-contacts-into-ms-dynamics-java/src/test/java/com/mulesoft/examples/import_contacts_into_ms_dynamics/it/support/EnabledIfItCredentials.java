package com.mulesoft.examples.import_contacts_into_ms_dynamics.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The annotated test class runs only when {@code application-it.yml} on the test classpath holds every
 * key listed in {@link #keys()} with a value other than blank or {@code TODO} (D-021).
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * {@return the dotted property names, such as {@code dynamics.service-url}, that
     * {@code application-it.yml} must hold}
     */
    String[] keys();
}
