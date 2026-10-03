/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.apache.wss4j.common.crypto.Crypto;
import org.apache.wss4j.common.saml.SAMLCallback;
import org.apache.wss4j.common.saml.bean.AuthenticationStatementBean;
import org.apache.wss4j.common.saml.bean.SubjectBean;
import org.apache.wss4j.common.saml.bean.Version;
import org.apache.wss4j.common.saml.builder.SAML2Constants;

/**
 *  Callback handler that populates a SAML 2.0 assertion based on the SAML properties file
 *
 * <p>WSS4J SAML callback of the Greeter {@code samlToken} and {@code samlTokenSigned} clients (D-030). Every
 * {@link SAMLCallback} it handles receives:
 * <ul>
 *   <li>SAML version 2.0;</li>
 *   <li>the subject {@code AllowGreetingServices}, name qualifier {@code www.example.com}, confirmation method
 *       sender-vouches;</li>
 *   <li>one authentication statement with the authentication method {@code Password} and no subject;</li>
 *   <li>the issuer, the issuer key name, the issuer key password and the issuer crypto of this handler;</li>
 *   <li>{@code signAssertion = false}.</li>
 * </ul>
 *
 * <p>The issuer and the issuer key name are the values of the keys {@code org.apache.ws.security.saml.issuer}
 * and {@code org.apache.ws.security.saml.issuer.key.name} of the classpath properties resource passed to the
 * constructor ({@code saml.properties}: issuer {@code self}; {@code wrong-saml.properties}: issuer
 * {@code www.example.com}; both: key name {@code joe}). The issuer key password is the value of the
 * {@code application.yml} key {@code saml.issuer.key.password}, passed to the constructor by its caller (D-012).
 * The class carries no Spring stereotype. Its state is fixed at construction, {@link #handle(Callback[])} changes
 * only the callbacks it is given, and one instance may be shared by concurrent requests.
 *
 * <pre>{@code
 * SAMLCallbackHandler handler = new SAMLCallbackHandler("saml.properties", issuerKeyPassword, issuerCrypto);
 * SAMLCallback callback = new SAMLCallback();
 * handler.handle(new Callback[] {callback});
 * callback.getIssuer();        // "self"
 * callback.getIssuerKeyName(); // "joe"
 * // a handler over "wrong-saml.properties" sets the issuer "www.example.com"
 * }</pre>
 */
public class SAMLCallbackHandler implements CallbackHandler {

    /** Properties key holding the SAML issuer. */
    private static final String ISSUER_KEY = "org.apache.ws.security.saml.issuer";

    /** Properties key holding the alias of the SAML issuer key. */
    private static final String ISSUER_KEY_NAME_KEY = "org.apache.ws.security.saml.issuer.key.name";

    /** Subject {@code NameID} value set on every handled callback: {@code AllowGreetingServices}. */
    private final String subjectName;

    /** Subject {@code NameID} name qualifier set on every handled callback: {@code www.example.com}. */
    private final String subjectQualifier;

    /** Subject confirmation method set on every handled callback: SAML 2.0 sender-vouches. */
    private final String confirmationMethod;

    /** Issuer set on every handled callback, read from {@code org.apache.ws.security.saml.issuer}. */
    private final String issuer;

    /**
     * Issuer key alias set on every handled callback, read from
     * {@code org.apache.ws.security.saml.issuer.key.name}.
     */
    private final String issuerKeyName;

    /** Issuer key password set on every handled callback, as passed to the constructor. */
    private final String issuerKeyPassword;

    /** Issuer crypto set on every handled callback, as passed to the constructor. */
    private final Crypto issuerCrypto;

    /**
     * Creates the handler with the fixed subject and with the issuer data of {@code samlPropertiesResource}.
     *
     * @param samlPropertiesResource classpath location of the SAML properties resource, for example
     *                               {@code saml.properties} or {@code wrong-saml.properties}; a leading
     *                               {@code /} is ignored
     * @param issuerKeyPassword      the password of the issuer key, set on every handled callback; may be
     *                               {@code null}
     * @param issuerCrypto           the crypto holding the issuer key, set on every handled callback; may be
     *                               {@code null}
     * @throws IllegalArgumentException when {@code samlPropertiesResource} is {@code null} or blank
     * @throws IllegalStateException    when the resource is not on the classpath, cannot be read, or has no
     *                                  non-blank value for {@code org.apache.ws.security.saml.issuer} or
     *                                  {@code org.apache.ws.security.saml.issuer.key.name}; the message names
     *                                  the resource
     */
    public SAMLCallbackHandler(String samlPropertiesResource, String issuerKeyPassword, Crypto issuerCrypto) {
        subjectName = "AllowGreetingServices";
        subjectQualifier = "www.example.com";
        confirmationMethod = SAML2Constants.CONF_SENDER_VOUCHES;

        Properties samlProperties = loadProperties(samlPropertiesResource);
        this.issuer = requiredProperty(samlProperties, ISSUER_KEY, samlPropertiesResource);
        this.issuerKeyName = requiredProperty(samlProperties, ISSUER_KEY_NAME_KEY, samlPropertiesResource);
        this.issuerKeyPassword = issuerKeyPassword;
        this.issuerCrypto = issuerCrypto;
    }

    /**
     * Populates every callback of the array, in order, with the SAML 2.0 data listed on the class.
     *
     * @param callbacks the callbacks passed by WSS4J; every element is a {@link SAMLCallback}
     * @throws IOException                  never thrown by this implementation; declared by
     *                                      {@link CallbackHandler#handle(Callback[])}
     * @throws UnsupportedCallbackException with the message {@code Unrecognized Callback} for the first element
     *                                      that is not a {@link SAMLCallback}; the elements before it are
     *                                      already populated
     */
    @Override
    public void handle(Callback[] callbacks)
            throws IOException, UnsupportedCallbackException
    {
        for (int i = 0; i < callbacks.length; i++) {
            if (callbacks[i] instanceof SAMLCallback) {
                SAMLCallback callback = (SAMLCallback) callbacks[i];
                callback.setSamlVersion(Version.SAML_20);
                SubjectBean subjectBean =
                        new SubjectBean(
                                subjectName, subjectQualifier, confirmationMethod
                        );
                callback.setSubject(subjectBean);
                createAndSetStatement(null, callback);

                callback.setIssuer(issuer);
                callback.setIssuerKeyName(issuerKeyName);
                callback.setIssuerKeyPassword(issuerKeyPassword);
                callback.setIssuerCrypto(issuerCrypto);
                callback.setSignAssertion(false);
            } else {
                throw new UnsupportedCallbackException(callbacks[i], "Unrecognized Callback");
            }
        }
    }

    /**
     * Sets on {@code callback} a single authentication statement with the authentication method
     * {@code Password}, carrying {@code subjectBean} when it is not {@code null}.
     *
     * @param subjectBean the subject of the statement, or {@code null} for a statement without subject
     * @param callback    the callback receiving the statement
     */
    private void createAndSetStatement(SubjectBean subjectBean, SAMLCallback callback) {
        AuthenticationStatementBean authBean = new AuthenticationStatementBean();
        if (subjectBean != null) {
            authBean.setSubject(subjectBean);
        }
        authBean.setAuthenticationMethod("Password");
        callback.setAuthenticationStatementData(Collections.singletonList(authBean));
    }

    /**
     * Reads the classpath properties resource {@code resource} with the class loader of this class.
     *
     * @param resource the classpath location; a leading {@code /} is ignored
     * @return the properties of the resource
     * @throws IllegalArgumentException when {@code resource} is {@code null} or blank
     * @throws IllegalStateException    when the resource is not on the classpath or cannot be read
     */
    private static Properties loadProperties(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new IllegalArgumentException("The SAML properties resource must not be null or blank");
        }
        String location = resource.startsWith("/") ? resource.substring(1) : resource;
        ClassLoader classLoader = SAMLCallbackHandler.class.getClassLoader();

        try (InputStream in = classLoader.getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("Cannot find " + resource + " on the classpath");
            }
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("Cannot read " + resource + " from the classpath", ex);
        }
    }

    /**
     * Returns the trimmed value of {@code key} in {@code properties}.
     *
     * @param properties the properties read from {@code resource}
     * @param key        the key to read
     * @param resource   the classpath location the properties were read from, named in the exception message
     * @return the trimmed, non-blank value
     * @throws IllegalStateException when the key is absent or its value is blank
     */
    private static String requiredProperty(Properties properties, String key, String resource) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing property " + key + " in " + resource);
        }
        return value.trim();
    }
}
