package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code ftp.*} keys of the FTP outbound endpoint (host, port, credentials, remote path, output file
 * name, response timeout).
 *
 * <p>Source: the {@code host}, {@code port}, {@code user}, {@code password}, {@code path},
 * {@code outputPattern} and {@code responseTimeout} attributes of the {@code ftp:outbound-endpoint} of flow
 * {@code main} [upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:28]. The
 * {@code ftp:connector} it references [upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:3]
 * sets no attribute and binds no key.
 *
 * <p>Every value comes from configuration; the record declares no default (D-450). The committed
 * {@code application.yml} holds the endpoint's original non-secret values and placeholders for the host and
 * the credentials. Credentials are supplied by configuration (D-012): the {@code local} profile
 * ({@code application-local.yml}) and the {@code test} profile ({@code application-test.yml}) set them.
 *
 * <p>{@code @EnableConfigurationProperties(FtpProperties.class)} on
 * {@code UploadToFtpAfterConvertingJsonToXmlApplication} registers the record, which Spring Boot binds through
 * its canonical constructor. Relaxed binding maps {@code ftp.output-pattern} to {@code outputPattern} and
 * {@code ftp.response-timeout} to {@code responseTimeout}; the environment variables {@code FTP_HOST},
 * {@code FTP_PORT}, {@code FTP_USER}, {@code FTP_PASSWORD}, {@code FTP_PATH}, {@code FTP_OUTPUT_PATTERN} and
 * {@code FTP_RESPONSE_TIMEOUT} set the same keys. A {@code String} component whose key is absent binds
 * {@code null}, and an {@code int} component whose key is absent binds {@code 0}. Values bind as written, with
 * no validation or trimming. Instances are immutable, and the generated {@code toString()} prints every
 * component, {@code password} included (D-450).
 *
 * <p>Consumers: {@code client.FtpUploadClient} reads {@link #host()}, {@link #port()}, {@link #user()},
 * {@link #password()} and {@link #responseTimeout()}; {@code service.FtpUploadService} reads {@link #path()}
 * and {@link #outputPattern()}.
 *
 * @param host            FTP server host name the upload connects to ({@code ftp.host})
 * @param port            FTP server control port ({@code ftp.port})
 * @param user            user name the upload logs in with ({@code ftp.user})
 * @param password        password the upload logs in with ({@code ftp.password})
 * @param path            remote directory the file is written to ({@code ftp.path})
 * @param outputPattern   file name written to the remote path ({@code ftp.output-pattern})
 * @param responseTimeout the endpoint's response timeout in milliseconds ({@code ftp.response-timeout})
 */
@ConfigurationProperties("ftp")
public record FtpProperties(String host, int port, String user, String password,
                            String path, String outputPattern, int responseTimeout) {
}
