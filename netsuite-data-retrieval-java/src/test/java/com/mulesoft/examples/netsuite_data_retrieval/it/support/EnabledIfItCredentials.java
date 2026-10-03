package com.mulesoft.examples.netsuite_data_retrieval.it.support;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tier 2B credential gate for a live-sandbox test class (D-021).
 *
 * <p>Placing this annotation on a test class registers {@link ItCredentialsCondition}. The condition disables
 * the class unless {@code application-it.yml} on the test classpath holds every key listed in {@link #keys()}
 * with a value that is neither blank nor {@code TODO}. {@code application-it.yml} is the git-ignored copy of
 * the committed {@code application-it.example.yml} (D-012).
 *
 * <p>The condition is evaluated before any Spring context is created: a disabled class starts no application
 * context, and JUnit reports it as skipped with the reason the condition returns.
 *
 * <p>Usage:
 * <pre>
 * &#64;EnabledIfItCredentials(keys = {"netsuite.account", "nets.item.quantity"})
 * class NetsuiteApiIT {
 * }
 * </pre>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(ItCredentialsCondition.class)
public @interface EnabledIfItCredentials {

    /**
     * Dotted property names that {@code application-it.yml} must hold, for example {@code nets.item.quantity}.
     * Each name resolves in flat form ({@code nets.item.quantity: 5}), nested form
     * ({@code nets: {item: {quantity: 5}}}) or a mix of both ({@code nets.item: {quantity: 5}}).
     *
     * @return the property names the annotated class requires
     */
    String[] keys();
}
