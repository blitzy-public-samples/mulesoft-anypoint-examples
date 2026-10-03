package com.mulesoft.examples.http_multipart_request.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.http.Part;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

/**
 * Unit tests of {@link FileUploadService}, run with no Spring context; coverage floor D-049.
 *
 * <p>{@link FileUploadService#httpMultipartRequestFlow(Part)} is driven with Mockito mocks of {@link Part}
 * that answer {@code Content-Disposition}, {@code Content-Type} and a fresh content stream per call. The
 * cases check the steps of flow {@code httpMultipartRequestFlow}
 * [http-multipart-request/src/main/app/http-multipart-request.xml:13-29]:
 * <ul>
 *   <li>{@code http-multipart-request.xml:19} the file name taken from the trimmed
 *       {@code Content-Disposition} by the MEL substring of :19-20;</li>
 *   <li>{@code :23} the ERROR log entry carrying the part's {@code Content-Type};</li>
 *   <li>{@code :25} the part content as the payload;</li>
 *   <li>{@code :27} the write of the payload to {@code <outputPath>/<file name>}, which creates missing
 *       parent directories and truncates an existing file;</li>
 *   <li>the payload returned to the caller.</li>
 * </ul>
 * {@link FileUploadService#httpRenderFlow()} is checked against the {@code parse-template} of
 * {@code http-multipart-request.xml:10}, the byte-identical {@code templates/uploadFile.html} (D-056).
 *
 * <p>Every service instance writes under the per-test {@link TempDir} {@code out}. A started
 * {@link ListAppender} is attached to the logger of {@link FileUploadService} for the duration of each test
 * and records its events.
 */
public class FileUploadServiceTest {

    /** Bytes of {@code http-multipart-request/src/test/resources/test.txt}: {@code file uploaded}. */
    private static final byte[] CONTENT = {
        'f', 'i', 'l', 'e', ' ', 'u', 'p', 'l', 'o', 'a', 'd', 'e', 'd'
    };

    /** {@code Content-Disposition} of the part the original test uploads as {@code file}. */
    private static final String CONTENT_DISPOSITION = "form-data; name=\"file\"; filename=\"file\"";

    /** {@code Content-Type} of the part the original test uploads. */
    private static final String OCTET_STREAM = "application/octet-stream";

    /** File name {@code http-multipart-request.xml:19-20} extracts from {@link #CONTENT_DISPOSITION}. */
    private static final String FILE_NAME = "file";

    /** Classpath location of the upload form served by {@code http-multipart-request.xml:10}. */
    private static final String TEMPLATE = "templates/uploadFile.html";

    /** Length in bytes of {@code http-multipart-request/src/main/resources/uploadFile.html} (D-056). */
    private static final int TEMPLATE_LENGTH = 348;

    /** SHA-256 of {@code http-multipart-request/src/main/resources/uploadFile.html} (D-056). */
    private static final String TEMPLATE_SHA256 =
            "3a430878eb779a9090ae667a91cff775007f7efe7766f2a49e7b2c29e310540f";

    /** Per-test output directory of the service under test. */
    @TempDir
    Path out;

    /** Logback logger of {@link FileUploadService}. */
    private Logger logger;

    /** Records the events of {@link #logger} during one test. */
    private ListAppender<ILoggingEvent> appender;

    /** Attaches a started {@link ListAppender} to the logger of {@link FileUploadService}. */
    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(FileUploadService.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Detaches the appender from the logger and stops it. */
    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    /**
     * Checks {@code http-multipart-request.xml:19-27} for the part of the original test: the content is
     * written to {@code out/file}, nothing else is written, and the content is returned.
     */
    @Test
    @DisplayName("writes the part bytes to the file named by Content-Disposition and returns them")
    public void writesPartBytesToFileNamedByContentDisposition() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());

        byte[] returned = service.httpMultipartRequestFlow(part(CONTENT_DISPOSITION, OCTET_STREAM, CONTENT));

        assertArrayEquals(CONTENT, returned);
        Path written = out.resolve(FILE_NAME);
        assertTrue(Files.isRegularFile(written));
        assertArrayEquals(CONTENT, Files.readAllBytes(written));
        assertEquals(List.of(written), entriesOf(out));
    }

    /**
     * Checks the {@code trim()} of {@code http-multipart-request.xml:19}: leading and trailing spaces of
     * {@code Content-Disposition} do not change the extracted name {@code file}.
     */
    @Test
    @DisplayName("trims Content-Disposition before extracting the file name")
    public void trimsContentDispositionBeforeParsingFilename() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());

        service.httpMultipartRequestFlow(
                part("  form-data; name=\"file\"; filename=\"file\"  ", OCTET_STREAM, CONTENT));

        Path written = out.resolve(FILE_NAME);
        assertTrue(Files.isRegularFile(written));
        assertArrayEquals(CONTENT, Files.readAllBytes(written));
        assertEquals(List.of(written), entriesOf(out));
    }

    /**
     * Checks the substring of {@code http-multipart-request.xml:20} for a header without {@code filename=}:
     * {@code indexOf} returns -1, and {@code form-data; name="file"} (22 characters) gives
     * {@code substring(9, 21)}, the name {@code ; name="file}.
     */
    @Test
    @DisplayName("names the file from index 9 up to the last character when Content-Disposition has no filename=")
    public void headerWithoutFilenameYieldsSubstringFromIndexNine() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());

        service.httpMultipartRequestFlow(part("form-data; name=\"file\"", OCTET_STREAM, CONTENT));

        Path written = out.resolve("; name=\"file");
        assertTrue(Files.isRegularFile(written));
        assertArrayEquals(CONTENT, Files.readAllBytes(written));
        assertEquals(List.of(written), entriesOf(out));
    }

    /**
     * Checks a {@code Content-Disposition} too short for the substring of
     * {@code http-multipart-request.xml:20}: {@code x} gives {@code substring(9, 0)}, which throws
     * {@link StringIndexOutOfBoundsException}. No ERROR event of {@code :23} is logged, the content of
     * {@code :25} is not read and no file of {@code :27} is written.
     */
    @Test
    @DisplayName("throws StringIndexOutOfBoundsException for a too short Content-Disposition before logging or writing")
    public void tooShortContentDispositionThrowsStringIndexOutOfBounds() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());
        Part part = part("x", OCTET_STREAM, CONTENT);

        assertThrows(StringIndexOutOfBoundsException.class, () -> service.httpMultipartRequestFlow(part));

        assertEquals(List.of(), entriesOf(out));
        assertEquals(List.of(), errorEvents());
        verify(part, never()).getInputStream();
    }

    /**
     * Checks a missing part at {@code http-multipart-request.xml:19}: {@code null} throws
     * {@link NullPointerException}, no ERROR event is logged and no file is written.
     */
    @Test
    @DisplayName("throws NullPointerException for a null part")
    public void nullPartThrowsNullPointerException() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());

        assertThrows(NullPointerException.class, () -> service.httpMultipartRequestFlow(null));

        assertEquals(List.of(), entriesOf(out));
        assertEquals(List.of(), errorEvents());
    }

    /**
     * Checks a part without {@code Content-Disposition} at {@code http-multipart-request.xml:19}: the
     * {@code trim()} of {@code null} throws {@link NullPointerException}. No ERROR event of {@code :23} is
     * logged, the content of {@code :25} is not read and no file of {@code :27} is written.
     */
    @Test
    @DisplayName("throws NullPointerException for a part without Content-Disposition before logging or writing")
    public void missingContentDispositionThrowsNullPointerException() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());
        Part part = part(null, OCTET_STREAM, CONTENT);

        assertThrows(NullPointerException.class, () -> service.httpMultipartRequestFlow(part));

        assertEquals(List.of(), entriesOf(out));
        assertEquals(List.of(), errorEvents());
        verify(part, never()).getInputStream();
    }

    /**
     * Checks {@code http-multipart-request.xml:23}: exactly one event is logged, on the logger
     * {@code com.mulesoft.examples.http_multipart_request.service.FileUploadService}, at ERROR, with the
     * part's {@code Content-Type} {@code text/plain} as its whole message. The part is read in the order of
     * the flow: {@code Content-Disposition} (:19), {@code Content-Type} (:23), then the content (:25), and
     * the ERROR event is already recorded when the content is read.
     */
    @Test
    @DisplayName("logs the part Content-Type once at ERROR between the file-name extraction and the content read")
    public void logsContentTypeOnceAtError() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());
        Part part = part(CONTENT_DISPOSITION, "text/plain", CONTENT);
        AtomicInteger errorsBeforeContentRead = new AtomicInteger(-1);
        doAnswer(invocation -> {
            errorsBeforeContentRead.set(errorEvents().size());
            return new ByteArrayInputStream(CONTENT);
        }).when(part).getInputStream();

        service.httpMultipartRequestFlow(part);

        List<ILoggingEvent> errors = errorEvents();
        assertEquals(1, errors.size());
        ILoggingEvent event = errors.get(0);
        assertEquals("text/plain", event.getFormattedMessage());
        assertEquals(FileUploadService.class.getName(), event.getLoggerName());
        assertEquals(1, appender.list.size());
        assertEquals(1, errorsBeforeContentRead.get());
        InOrder order = inOrder(part);
        order.verify(part).getHeader("Content-Disposition");
        order.verify(part).getHeader("Content-Type");
        order.verify(part).getInputStream();
    }

    /**
     * Checks {@code http-multipart-request.xml:23} for a part without {@code Content-Type}: exactly one
     * ERROR event, whose message reads {@code null}; the content is still written to {@code out/file}.
     */
    @Test
    @DisplayName("logs null at ERROR when the part has no Content-Type")
    public void logsNullContentTypeAtError() throws IOException {
        FileUploadService service = new FileUploadService(out.toString());

        service.httpMultipartRequestFlow(part(CONTENT_DISPOSITION, null, CONTENT));

        List<ILoggingEvent> errors = errorEvents();
        assertEquals(1, errors.size());
        assertEquals("null", String.valueOf(errors.get(0).getFormattedMessage()));
        assertArrayEquals(CONTENT, Files.readAllBytes(out.resolve(FILE_NAME)));
    }

    /**
     * Checks the write of {@code http-multipart-request.xml:27} over an existing, longer {@code out/file}:
     * afterwards the file holds exactly the uploaded bytes and has their length.
     */
    @Test
    @DisplayName("overwrites and truncates an existing file of the same name")
    public void overwritesAndTruncatesExistingFile() throws IOException {
        byte[] previous = "previous content that is much longer".getBytes(StandardCharsets.US_ASCII);
        assertTrue(previous.length > CONTENT.length);
        Path existing = out.resolve(FILE_NAME);
        Files.write(existing, previous);
        FileUploadService service = new FileUploadService(out.toString());

        service.httpMultipartRequestFlow(part(CONTENT_DISPOSITION, OCTET_STREAM, CONTENT));

        assertArrayEquals(CONTENT, Files.readAllBytes(existing));
        assertEquals((long) CONTENT.length, Files.size(existing));
    }

    /**
     * Checks the write of {@code http-multipart-request.xml:27} with an output path {@code out/a/b} that does
     * not exist: the directories are created and {@code out/a/b/file} holds the content.
     */
    @Test
    @DisplayName("creates the missing parent directories of the output path")
    public void createsMissingParentDirectories() throws IOException {
        Path outputPath = out.resolve("a/b");
        assertFalse(Files.exists(out.resolve("a")));
        FileUploadService service = new FileUploadService(outputPath.toString());

        service.httpMultipartRequestFlow(part(CONTENT_DISPOSITION, OCTET_STREAM, CONTENT));

        Path written = out.resolve("a").resolve("b").resolve(FILE_NAME);
        assertTrue(Files.isRegularFile(written));
        assertArrayEquals(CONTENT, Files.readAllBytes(written));
    }

    /**
     * Checks {@code http-multipart-request.xml:10}: {@link FileUploadService#httpRenderFlow()} returns the
     * bytes of the classpath resource {@code templates/uploadFile.html} unchanged, 348 bytes with the SHA-256
     * of the original {@code uploadFile.html} (D-056).
     */
    @Test
    @DisplayName("httpRenderFlow returns the 348 bytes of templates/uploadFile.html unchanged")
    public void httpRenderFlowReturnsUploadFileTemplateBytes() throws IOException, NoSuchAlgorithmException {
        byte[] expected;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(TEMPLATE)) {
            assertNotNull(in, TEMPLATE + " on the test classpath");
            expected = in.readAllBytes();
        }

        byte[] actual = new FileUploadService(out.toString()).httpRenderFlow();

        assertArrayEquals(expected, actual);
        assertEquals(TEMPLATE_LENGTH, actual.length);
        assertEquals(TEMPLATE_SHA256,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual)));
    }

    /**
     * Returns a Mockito mock of {@link Part} that answers {@code getHeader("Content-Disposition")} with
     * {@code contentDisposition}, {@code getHeader("Content-Type")} with {@code contentType}, and each
     * {@link Part#getInputStream()} call with a fresh {@link ByteArrayInputStream} over {@code content}.
     */
    private Part part(String contentDisposition, String contentType, byte[] content) throws IOException {
        Part p = Mockito.mock(Part.class);
        when(p.getHeader("Content-Disposition")).thenReturn(contentDisposition);
        when(p.getHeader("Content-Type")).thenReturn(contentType);
        when(p.getInputStream()).thenAnswer(invocation -> new ByteArrayInputStream(content));
        return p;
    }

    /** Returns the ERROR events recorded by {@link #appender}, in logging order. */
    private List<ILoggingEvent> errorEvents() {
        return appender.list.stream().filter(e -> Level.ERROR.equals(e.getLevel())).toList();
    }

    /** Returns the entries of {@code dir}, files and directories, sorted by path. */
    private static List<Path> entriesOf(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        }
    }
}
