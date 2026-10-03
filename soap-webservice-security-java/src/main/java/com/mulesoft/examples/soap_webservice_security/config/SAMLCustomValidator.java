/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.soap_webservice_security.config;

import org.apache.wss4j.common.ext.WSSecurityException;
import org.apache.wss4j.common.saml.OpenSAMLUtil;
import org.apache.wss4j.common.saml.SamlAssertionWrapper;
import org.apache.wss4j.dom.handler.RequestData;
import org.apache.wss4j.dom.validate.Credential;
import org.apache.wss4j.dom.validate.SamlAssertionValidator;

/**
 * SAML 2.0 token validator of the Greeter {@code saml} and {@code signedsaml} service paths (D-030).
 *
 * <p>{@link #validate(Credential, RequestData)} first runs every check of the WSS4J
 * {@link SamlAssertionValidator}, which rejects a missing credential or assertion, an assertion without a
 * standard subject confirmation method, an assertion whose conditions, authentication statements, one-time-use
 * condition or schema validation fail, and a signed assertion whose signature is not trusted. The assertion is
 * then accepted only when all of the following hold, checked in this order:
 * <ol>
 *   <li>the issuer is {@code self};</li>
 *   <li>the assertion is a SAML 2.0 assertion;</li>
 *   <li>the first subject confirmation method is present and is sender-vouches;</li>
 *   <li>the subject {@code NameID} value is {@code AllowGreetingServices}.</li>
 * </ol>
 * The first check that fails throws {@link WSSecurityException} with
 * {@link WSSecurityException.ErrorCode#FAILURE} and the message key {@code invalidSAMLsecurity}; the remaining
 * checks are not evaluated.
 *
 * <p>The class adds no fields to {@link SamlAssertionValidator}; its configuration (TTL, future TTL, required
 * subject confirmation method, standard subject confirmation method requirement, bearer signature requirement)
 * is the inherited one, with the WSS4J defaults unless a setter changes it. It carries no Spring stereotype and
 * is registered as a bean by the WS-Security configuration of the project.
 *
 * <pre>{@code
 * Credential credential = new Credential();
 * credential.setSamlAssertion(new SamlAssertionWrapper(assertionElement));
 * Credential validated = new SAMLCustomValidator().validate(credential, requestData);
 * // issuer "self", SAML 2.0, sender-vouches, NameID "AllowGreetingServices": returns the inherited result
 * // issuer "www.example.com": throws WSSecurityException(FAILURE, "invalidSAMLsecurity")
 * }</pre>
 */
public class SAMLCustomValidator extends SamlAssertionValidator {

    /**
     * Validates the SAML assertion carried by {@code credential} with the inherited WSS4J checks and then with
     * the issuer, SAML version, subject confirmation method and subject {@code NameID} checks listed on the class.
     *
     * @param credential the credential holding the {@link SamlAssertionWrapper} read from the
     *                   {@code wsse:Security} header
     * @param data       the WSS4J request data of the message being processed
     * @return the credential returned by {@link SamlAssertionValidator#validate(Credential, RequestData)}
     * @throws WSSecurityException with {@link WSSecurityException.ErrorCode#FAILURE} and the message key
     *                             {@code invalidSAMLsecurity} when a check of this class fails, or the exception
     *                             thrown by the inherited validation
     */
    @Override
    public Credential validate(Credential credential, RequestData data) throws WSSecurityException {
        Credential returnedCredential = super.validate(credential, data);
        //
        // Do some custom validation on the assertion
        //
        SamlAssertionWrapper assertion = credential.getSamlAssertion();
        if (!"self".equals(assertion.getIssuerString())) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        if (assertion.getSaml2() == null) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        String confirmationMethod = assertion.getConfirmationMethods().get(0);
        if (confirmationMethod == null) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }
        if (!OpenSAMLUtil.isMethodSenderVouches(confirmationMethod)) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        if (!"AllowGreetingServices".equals(assertion.getSaml2().getSubject().getNameID().getValue())) {
            throw new WSSecurityException(WSSecurityException.ErrorCode.FAILURE, "invalidSAMLsecurity");
        }

        return returnedCredential;
    }
}
