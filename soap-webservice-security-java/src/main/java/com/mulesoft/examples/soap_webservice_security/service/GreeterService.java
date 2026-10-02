/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.service;

import org.springframework.stereotype.Service;

/**
 * Returns the greeting for the six Greeter service paths under {@code /services}: {@code unsecure},
 * {@code username}, {@code signed}, {@code encrypted}, {@code saml} and {@code signedsaml}.
 *
 * <p>Each flow method implements the Mule flow whose name it carries in camelCase (for example
 * {@code UsernameTokenServiceFlow} as {@link #usernameTokenServiceFlow(String)}) and returns
 * {@link #greet(String)}. The WS-Security policy of each path is applied to the request before the
 * endpoint calls this service (D-030). The class is stateless.
 *
 * <pre>{@code
 * GreeterService service = new GreeterService();
 * service.greet("Mule");                    // "Hello Mule"
 * service.usernameTokenServiceFlow("Mule"); // "Hello Mule"
 * service.greet(null);                      // "Hello null"
 * }</pre>
 */
@Service
public class GreeterService {

    /**
     * Returns {@code "Hello "} followed by the name. The name is neither validated nor trimmed.
     *
     * @param name the name to greet; {@code null} yields {@code "Hello null"}
     * @return {@code "Hello " + name}
     */
    public String greet(String name) {
        return "Hello " + name;
    }

    /** Greeting for {@code /services/unsecure} (no WS-Security). */
    public String unsecureServiceFlow(String name) {
        return greet(name);
    }

    /** Greeting for {@code /services/username} (UsernameToken + Timestamp). */
    public String usernameTokenServiceFlow(String name) {
        return greet(name);
    }

    /** Greeting for {@code /services/signed} (UsernameToken + Signature + Timestamp). */
    public String usernameTokenSignedServiceFlow(String name) {
        return greet(name);
    }

    /** Greeting for {@code /services/encrypted} (UsernameToken + Timestamp + Encrypt). */
    public String usernameTokenEncryptedServiceFlow(String name) {
        return greet(name);
    }

    /** Greeting for {@code /services/saml} (unsigned SAML token + Timestamp). */
    public String samlTokenServiceFlow(String name) {
        return greet(name);
    }

    /** Greeting for {@code /services/signedsaml} (unsigned SAML token + Signature). */
    public String signedSamlTokenServiceFlow(String name) {
        return greet(name);
    }
}
