package com.mulesoft.examples.dataweave_with_flowreflookup.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Runs the annotated test class only when the classpath resource {@code application-it.yml} defines every
 * dotted key listed in {@link #keys()} with a value that is not blank and not {@code TODO}.
 *
 * <p>When the resource is absent, or any listed key is missing, blank or {@code TODO}, JUnit reports the
 * class as skipped before any extension, Spring's included, creates a context. The check is performed by
 * {@link ItCredentialsCondition}, which this annotation registers through {@link ExtendWith}. The annotation
 * applies to test classes only.
 *
 * <p>Usage:
 * <pre>
 * &#64;SpringBootTest
 * &#64;ActiveProfiles("it")
 * &#64;EnabledIfItCredentials(keys = {"sfdc.user", "sfdc.password", "sfdc.securityToken", "sfdc.key",
 *         "sfdc.secret", "sfdc.login-url"})
 * class DataWeaveWithFlowRefIT {
 * }
 * </pre>
 *
 * <p>See D-021 in {@code DECISIONS.md}.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted property names that {@code application-it.yml} must define, such as {@code sfdc.user}. A nested
     * YAML mapping ({@code sfdc:} followed by {@code user:}) resolves to the same dotted name as the literal
     * key {@code sfdc.user}.
     *
     * @return the dotted property names the resource must define
     */
    String[] keys();
}
