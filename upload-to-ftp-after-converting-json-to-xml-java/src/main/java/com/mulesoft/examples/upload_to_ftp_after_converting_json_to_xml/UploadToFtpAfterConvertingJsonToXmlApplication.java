package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the Spring Boot application that converts posted JSON employees to XML and uploads the result to FTP. */
@SpringBootApplication
public class UploadToFtpAfterConvertingJsonToXmlApplication {

    public static void main(String[] args) {
        SpringApplication.run(UploadToFtpAfterConvertingJsonToXmlApplication.class, args);
    }
}
