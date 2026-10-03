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
 * Supplies the wrong password for the user "joe".
 */
public class WrongPasswordCallback implements CallbackHandler {

    /** Password set on the callback of the user {@code joe}. */
    private final String wrongPassword;

    /**
     * Creates the callback with the password it supplies for the user {@code joe}.
     *
     * <p>The WS-Security configuration of the project passes the value of the {@code application.yml} key
     * {@code wssecurity.wrong-password} (D-012). The value is stored as given.
     *
     * @param wrongPassword the password set on the {@link WSPasswordCallback} of the user {@code joe}
     */
    public WrongPasswordCallback(String wrongPassword) {
        this.wrongPassword = wrongPassword;
    }

    /**
     * Sets the configured wrong password on the first callback when its identifier is {@code joe}; a callback for
     * any other identifier is left unchanged and the remaining callbacks are not read.
     *
     * <pre>{@code
     * WSPasswordCallback pc = new WSPasswordCallback("joe", WSPasswordCallback.USERNAME_TOKEN);
     * new WrongPasswordCallback(wrongPassword).handle(new Callback[] {pc});
     * // pc.getPassword() equals wrongPassword
     * }</pre>
     *
     * @param callbacks the callbacks of the WS-Security processing; the first element is a {@link WSPasswordCallback}
     * @throws ClassCastException           when the first callback is not a {@link WSPasswordCallback}
     * @throws NullPointerException         when the identifier of the first callback is {@code null}
     * @throws IOException                  not thrown by this implementation; declared by {@link CallbackHandler}
     * @throws UnsupportedCallbackException not thrown by this implementation; declared by {@link CallbackHandler}
     */
    @Override
    public void handle(Callback[] callbacks) throws IOException, UnsupportedCallbackException {
        WSPasswordCallback pc = (WSPasswordCallback) callbacks[0];

        if (pc.getIdentifier().equals("joe")) {
            pc.setPassword(wrongPassword);
        }
    }
}
