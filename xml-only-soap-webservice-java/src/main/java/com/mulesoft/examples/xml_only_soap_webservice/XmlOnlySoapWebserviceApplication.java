package com.mulesoft.examples.xml_only_soap_webservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the Spring Boot application that serves the hospital admission SOAP service and its patient and EHR mock services. */
@SpringBootApplication
public class XmlOnlySoapWebserviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(XmlOnlySoapWebserviceApplication.class, args);
    }
}
