/*
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.authenticating_salesforce_using_oauth2;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Starts the authenticating-salesforce-using-oauth2 application: the Salesforce OAuth2 web-server flow
 * followed by the contact query. Every {@code @ConfigurationProperties} type in this package and its
 * subpackages, among them the {@code config.SalesforceOAuthProperties} record of the {@code sfdc.*} keys,
 * is registered and bound by the properties scan (D-301).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class AuthenticatingSalesforceUsingOauth2Application {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(AuthenticatingSalesforceUsingOauth2Application.class, args);
    }
}
