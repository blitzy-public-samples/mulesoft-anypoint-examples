package com.mulesoft.examples.http_multipart_request.service;

import jakarta.servlet.http.Part;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Serves the upload form and stores uploaded multipart files.
 *
 * <p>Implements flow {@code httpRenderFlow}
 * [http-multipart-request/src/main/app/http-multipart-request.xml:6-11] as
 * {@link #httpRenderFlow()} and flow {@code httpMultipartRequestFlow}
 * [http-multipart-request/src/main/app/http-multipart-request.xml:13-29] as
 * {@link #httpMultipartRequestFlow(Part)}. {@code UploadController} calls them for
 * {@code GET /uploadFile} and {@code POST /uploadFile}.
 *
 * <p>Uploaded files are written to the directory bound to {@code file.outbound-endpoint.path},
 * {@code /tmp} by default (D-161). The class holds no state besides its logger and that
 * directory, and concurrent calls do not affect each other except through files of the same name.
 */
@Service
public class FileUploadService {

    private static final Logger LOG = LoggerFactory.getLogger(FileUploadService.class);

    private final String outputPath;

    /**
     * Creates the service writing uploads under {@code outputPath}.
     *
     * @param outputPath the directory uploads are written to, bound to
     *                   {@code file.outbound-endpoint.path} [http-multipart-request.xml:27]
     */
    public FileUploadService(@Value("${file.outbound-endpoint.path}") String outputPath) {
        this.outputPath = outputPath;
    }

    /**
     * Returns the bytes of {@code templates/uploadFile.html}.
     *
     * <p>Implements flow {@code httpRenderFlow} [http-multipart-request.xml:6-11]: the
     * {@code parse-template} of {@code uploadFile.html}. The template holds no {@code #[...]}
     * expression, and its bytes are returned unchanged, with no decoding and no line-ending
     * conversion (D-056).
     *
     * @return the template's bytes
     * @throws UncheckedIOException if the template cannot be read from the classpath
     */
    public byte[] httpRenderFlow() {
        try (InputStream in = new ClassPathResource("templates/uploadFile.html").getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Extracts the file name from the part's {@code Content-Disposition}, logs its
     * {@code Content-Type} at ERROR, writes the content to
     * {@code <file.outbound-endpoint.path>/<filename>} replacing an existing file, and returns the
     * content (D-376).
     *
     * <p>Implements flow {@code httpMultipartRequestFlow} [http-multipart-request.xml:13-29]:
     * <ul>
     *   <li>:19-20 the file name is the text of the trimmed {@code Content-Disposition} from one
     *       character after {@code filename=} up to, not including, its last character.
     *       {@code form-data; name="file"; filename="test.txt"} gives {@code test.txt}. A header
     *       without {@code filename=} gives the text from index 9 up to its last character:
     *       {@code form-data; name="file"} gives {@code ; name="file}.</li>
     *   <li>:23 the part's {@code Content-Type} header is logged at ERROR; an absent header logs
     *       {@code null}.</li>
     *   <li>:25 the part's content is read whole.</li>
     *   <li>:27 the content is written to the file of that name under
     *       {@code file.outbound-endpoint.path}. Missing parent directories are created, and an
     *       existing file is truncated and overwritten, never appended to.</li>
     *   <li>:28 the content is returned.</li>
     * </ul>
     *
     * <p>No exception is caught. Every failure reaches the caller unchanged.
     *
     * @param file the multipart part named {@code file}
     * @return the part's content
     * @throws IOException if the part cannot be read or the file cannot be written
     * @throws NullPointerException if {@code file} or its {@code Content-Disposition} header is
     *                              absent
     * @throws StringIndexOutOfBoundsException if the {@code Content-Disposition} header is too
     *                                         short for the extraction
     */
    public byte[] httpMultipartRequestFlow(Part file) throws IOException {
        // :19-20 expression-component: flowVars['attachmentFileName'] from Content-Disposition.
        String contentDispositionHeaderValue = file.getHeader("Content-Disposition").trim();
        String attachmentFileName = contentDispositionHeaderValue.substring(
                contentDispositionHeaderValue.indexOf("filename=") + "filename=".length() + 1,
                contentDispositionHeaderValue.length() - 1);

        // :23 logger level="ERROR" with the part's Content-Type header.
        LOG.error(String.valueOf(file.getHeader("Content-Type")));

        // :25 set-payload: the part's content.
        byte[] payload;
        try (InputStream in = file.getInputStream()) {
            payload = in.readAllBytes();
        }

        // :27 file:outbound-endpoint outputPattern="#[attachmentFileName]" under file.outbound-endpoint.path.
        Path path = new File(outputPath, attachmentFileName).toPath();
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.write(path, payload,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

        // :28 the payload, the part's content, is the response.
        return payload;
    }
}
