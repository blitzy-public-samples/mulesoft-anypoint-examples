package com.mulesoft.examples.soap_webservice_security.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GreeterService}: {@link GreeterService#greet(String)} and the six flow methods
 * {@code unsecureServiceFlow}, {@code usernameTokenServiceFlow}, {@code usernameTokenSignedServiceFlow},
 * {@code usernameTokenEncryptedServiceFlow}, {@code samlTokenServiceFlow} and
 * {@code signedSamlTokenServiceFlow}.
 *
 * <p>The service is instantiated directly, with no Spring context and no mocks (D-377). These tests count
 * toward the service coverage rule (D-049).
 */
public class GreeterServiceTest {

    private static final String NAME = "Mule";

    private static final String GREETING = "Hello Mule";

    private GreeterService service;

    @BeforeEach
    public void setUp() {
        service = new GreeterService();
    }

    @Test
    public void greetReturnsHelloName() {
        // greet prefixes the name with "Hello ", the reply the original IT expects for "Mule".
        assertEquals(GREETING, service.greet(NAME));
    }

    @Test
    public void greetWithNullNameReturnsHelloNull() {
        // greet concatenates a null name as the text "null".
        assertEquals("Hello null", service.greet(null));
    }

    @Test
    public void unsecureServiceFlowReturnsGreeting() {
        // unsecureServiceFlow (flow UnsecureServiceFlow) returns "Hello " + name.
        assertEquals(GREETING, service.unsecureServiceFlow(NAME));
    }

    @Test
    public void usernameTokenServiceFlowReturnsGreeting() {
        // usernameTokenServiceFlow (flow UsernameTokenServiceFlow) returns "Hello " + name.
        assertEquals(GREETING, service.usernameTokenServiceFlow(NAME));
    }

    @Test
    public void usernameTokenSignedServiceFlowReturnsGreeting() {
        // usernameTokenSignedServiceFlow (flow UsernameTokenSignedServiceFlow) returns "Hello " + name.
        assertEquals(GREETING, service.usernameTokenSignedServiceFlow(NAME));
    }

    @Test
    public void usernameTokenEncryptedServiceFlowReturnsGreeting() {
        // usernameTokenEncryptedServiceFlow (flow UsernameTokenEncryptedServiceFlow) returns "Hello " + name.
        assertEquals(GREETING, service.usernameTokenEncryptedServiceFlow(NAME));
    }

    @Test
    public void samlTokenServiceFlowReturnsGreeting() {
        // samlTokenServiceFlow (flow SamlTokenServiceFlow) returns "Hello " + name.
        assertEquals(GREETING, service.samlTokenServiceFlow(NAME));
    }

    @Test
    public void signedSamlTokenServiceFlowReturnsGreeting() {
        // signedSamlTokenServiceFlow (flow SignedSamlTokenServiceFlow) returns "Hello " + name.
        assertEquals(GREETING, service.signedSamlTokenServiceFlow(NAME));
    }
}
