/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.ext.WSPasswordCallback;

/**
 * WSS4J password callback of the Greeter {@code username}, {@code signed} and {@code encrypted} service paths
 * (D-030). Supplies the configured passwords for the users {@code joe} and {@code stan}.
 *
 * <p>The two passwords are the values of the {@code application.yml} keys {@code wssecurity.users.joe} and
 * {@code wssecurity.users.stan}, passed to the constructor by the WS-Security configuration of the project
 * (D-012). The class carries no Spring stereotype and holds no state other than the two passwords.
 *
 * <pre>{@code
 * PasswordCallback handler = new PasswordCallback(joePassword, stanPassword);
 * WSPasswordCallback pc = new WSPasswordCallback("joe", WSPasswordCallback.USERNAME_TOKEN);
 * handler.handle(new Callback[] {pc});
 * pc.getPassword(); // joePassword
 * // identifier "stan": stanPassword; any other identifier: the password stays unset
 * }</pre>
 */
public class PasswordCallback implements CallbackHandler {

    /** Password set for the identifier {@code joe}. */
    private final String joePassword;

    /** Password set for the identifier {@code stan}. */
    private final String stanPassword;

    /**
     * Creates the callback with the passwords of the two users.
     *
     * @param joePassword  the password set for the identifier {@code joe}
     * @param stanPassword the password set for the identifier {@code stan}
     */
    public PasswordCallback(String joePassword, String stanPassword) {
        this.joePassword = joePassword;
        this.stanPassword = stanPassword;
    }

    /**
     * Sets the password of the first callback, which is a {@link WSPasswordCallback}: {@code joePassword} for
     * the identifier {@code joe}, {@code stanPassword} for the identifier {@code stan}. For any other identifier
     * the callback is left unchanged. The remaining callbacks of the array are not read.
     *
     * @param callbacks the callbacks passed by WSS4J; the first element is a {@link WSPasswordCallback}
     * @throws IOException                  never thrown by this implementation; declared by
     *                                      {@link CallbackHandler#handle(Callback[])}
     * @throws UnsupportedCallbackException never thrown by this implementation; declared by
     *                                      {@link CallbackHandler#handle(Callback[])}
     * @throws ClassCastException           when the first callback is not a {@link WSPasswordCallback}
     * @throws NullPointerException         when the identifier of the first callback is {@code null}
     */
    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        WSPasswordCallback pc = (WSPasswordCallback) callbacks[0];

        if (pc.getIdentifier().equals("joe")) {
            pc.setPassword(joePassword);
        } else if (pc.getIdentifier().equals("stan")) {
            pc.setPassword(stanPassword);
        }
    }
}
