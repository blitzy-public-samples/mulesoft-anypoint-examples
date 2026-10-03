package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.error.ErrorMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config.FtpProperties;

/**
 * Spring Boot entry point of the upload-to-ftp-after-converting-json-to-xml example.
 *
 * <p>Boot's error MVC auto-configuration is excluded: the context registers no error controller, no container
 * error page and no {@code /error} mapping, and {@code /error} is answered like every other unmatched path (D-624,
 * D-563). {@link FtpProperties} is registered as the binding of the {@code ftp.*} keys (D-450).
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
@EnableConfigurationProperties(FtpProperties.class)
public class UploadToFtpAfterConvertingJsonToXmlApplication {

    /**
     * Runs the application.
     *
     * @param args command-line arguments, passed unchanged to {@link SpringApplication#run(Class, String...)}
     */
    public static void main(String[] args) {
        SpringApplication.run(UploadToFtpAfterConvertingJsonToXmlApplication.class, args);
    }
}
