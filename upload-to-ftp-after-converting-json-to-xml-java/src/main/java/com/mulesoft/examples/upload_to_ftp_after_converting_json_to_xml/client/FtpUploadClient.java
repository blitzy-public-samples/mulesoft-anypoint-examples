package com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.upload_to_ftp_after_converting_json_to_xml.config.FtpProperties;

/**
 * Uploads bytes to the configured FTP server.
 *
 * <p>Replaces the {@code ftp:outbound-endpoint} of flow {@code main}
 * [upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:28] and the {@code ftp:connector}
 * {@code FTP} it references [upload-to-ftp-after-converting-json-to-xml/src/main/app/upload-to-ftp.xml:3] with an
 * Apache Commons Net {@link FTPClient} (D-063). The server address, the credentials and the timeout come from
 * {@link FtpProperties} (D-012): {@code ftp.host}, {@code ftp.port}, {@code ftp.user}, {@code ftp.password} and
 * {@code ftp.response-timeout}. The remote directory and the file name are arguments of {@link #upload}.
 *
 * <p>Every call of {@link #upload} opens its own FTP session and closes it before returning; the session settings
 * are those of D-635. The instance holds only the immutable {@link FtpProperties}, and concurrent calls run in
 * separate sessions. Nothing is logged and nothing is retried.
 */
@Component
public class FtpUploadClient {

    private final FtpProperties properties;

    /**
     * Creates the client over the bound {@code ftp.*} properties.
     *
     * @param properties the FTP server address, credentials and response timeout
     */
    public FtpUploadClient(FtpProperties properties) {
        this.properties = properties;
    }

    /**
     * Stores {@code content} as {@code fileName} in {@code directory} on the FTP server, replacing a file of that
     * name (D-635).
     *
     * <p>A new {@link FTPClient} runs these steps in order:
     * <ol>
     *   <li>sets {@code ftp.response-timeout} milliseconds as the connect timeout, the control-connection read
     *       timeout and the data-connection read timeout;</li>
     *   <li>connects to {@code ftp.host} on {@code ftp.port} and requires a positive completion reply;</li>
     *   <li>logs in with {@code ftp.user} and {@code ftp.password};</li>
     *   <li>enters local passive mode;</li>
     *   <li>sets the binary file type;</li>
     *   <li>changes the working directory to {@code directory}, passed unchanged;</li>
     *   <li>stores {@code content} under {@code fileName}, used literally.</li>
     * </ol>
     * After success and after failure alike, the client then sends {@code QUIT} while the control connection is
     * open and closes the connection; a failure of either is not reported and never replaces the exception of a
     * failed step.
     *
     * @param directory the remote working directory the file is stored in
     * @param fileName  the remote file name
     * @param content   the bytes to store, not {@code null}
     * @throws UncheckedIOException     when the server's connect reply is not a positive completion, or the server
     *                                  refuses the login, the binary file type, the directory change or the store:
     *                                  the message of the exception and of its {@link IOException} cause is the
     *                                  server's last reply, trimmed, or empty when there is none. Also when a
     *                                  Commons Net call throws an {@link IOException}, for example
     *                                  {@link java.net.ConnectException}, {@link java.net.UnknownHostException},
     *                                  {@link java.net.SocketTimeoutException} or
     *                                  {@link org.apache.commons.net.ftp.FTPConnectionClosedException}: that
     *                                  exception is the cause and its message is the message
     * @throws NullPointerException     when {@code content} is {@code null}, after the directory change and before
     *                                  the store
     * @throws IllegalArgumentException when {@code ftp.host} is {@code null} or {@code ftp.response-timeout} is
     *                                  negative
     */
    public void upload(String directory, String fileName, byte[] content) {
        int timeout = properties.responseTimeout();
        FTPClient ftp = new FTPClient();
        ftp.setConnectTimeout(timeout);
        ftp.setDefaultTimeout(timeout);
        ftp.setDataTimeout(Duration.ofMillis(timeout));
        try {
            ftp.connect(properties.host(), properties.port());
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                throw failure(ftp);
            }
            if (!ftp.login(properties.user(), properties.password())) {
                throw failure(ftp);
            }
            ftp.enterLocalPassiveMode();
            if (!ftp.setFileType(FTP.BINARY_FILE_TYPE)) {
                throw failure(ftp);
            }
            if (!ftp.changeWorkingDirectory(directory)) {
                throw failure(ftp);
            }
            try (InputStream in = new ByteArrayInputStream(content)) {
                if (!ftp.storeFile(fileName, in)) {
                    throw failure(ftp);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        } finally {
            close(ftp);
        }
    }

    /**
     * Builds the exception for a negative server reply: its message, and the message of its {@link IOException}
     * cause, is {@link #reply(FTPClient)}.
     *
     * @param ftp the client whose last reply was negative
     * @return the exception to throw
     */
    private static UncheckedIOException failure(FTPClient ftp) {
        String reply = reply(ftp);
        return new UncheckedIOException(reply, new IOException(reply));
    }

    /**
     * Returns the server's last reply with surrounding whitespace removed, or an empty string when there is none.
     *
     * @param ftp the client whose last reply is read
     * @return the trimmed reply text, never {@code null}
     */
    private static String reply(FTPClient ftp) {
        String reply = ftp.getReplyString();
        return reply == null ? "" : reply.trim();
    }

    /**
     * Sends {@code QUIT} when the control connection is open, then closes the connection, a socket left by a
     * connect attempt that did not complete included. Neither step reports a failure.
     *
     * @param ftp the client to close
     */
    private static void close(FTPClient ftp) {
        if (ftp.isConnected()) {
            try {
                ftp.logout();
            } catch (IOException ignored) {
                // A failed QUIT is discarded.
            }
        }
        try {
            ftp.disconnect();
        } catch (IOException ignored) {
            // A failure while closing the connection is discarded.
        }
    }
}
