package com.mulesoft.examples.login_form_using_the_http_connector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the application that serves the HTML login form and checks submitted credentials. */
@SpringBootApplication
public class LoginFormUsingTheHttpConnectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoginFormUsingTheHttpConnectorApplication.class, args);
    }
}
