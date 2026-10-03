package com.mulesoft.examples.mule_expression_language_basics.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mulesoft.examples.mule_expression_language_basics.config.MelProperties;

/**
 * Writes one new file named {@code <random UUID>.dat} under {@code mel.output-path} per call,
 * creating the directory when it is missing; replaces the file outbound endpoints
 * {@code <file:outbound-endpoint path="Path_of_your_choice" responseTimeout="10000"/>} of
 * {@code docs-greetingFlow3}, {@code docs-greetingFlow4}, {@code docs-greetingFlow5} and
 * {@code greetingFlow6} [mule-expression-language-basics/src/main/app/greeting.xml:35,57,69,81]
 * (D-631).
 *
 * <p>Behaviour of each {@link #write(byte[])} call:
 *
 * <ul>
 *   <li>the directory is {@link MelProperties#outputPath()} as bound, resolved against the JVM
 *       working directory when relative; it and its missing parents are created;</li>
 *   <li>the file name is {@link UUID#randomUUID()} in its 36-character lowercase form followed by
 *       {@code .dat}, for example {@code 3f1c2a9e-5b7d-4e8a-9c0f-1a2b3c4d5e6f.dat};</li>
 *   <li>the file is opened with {@link StandardOpenOption#CREATE_NEW}: an existing file is never
 *       overwritten or appended to;</li>
 *   <li>the bytes are written unchanged: no character set conversion, no added line separator.</li>
 * </ul>
 *
 * <p>Each call writes synchronously on the calling thread and logs nothing (D-631). The class holds
 * no mutable state; concurrent calls write distinct files into the same directory.
 *
 * <pre>{@code
 * GreetingFileWriter writer = new GreetingFileWriter(new MelProperties("Path_of_your_choice"));
 * Path written = writer.write("Mule, 1, false".getBytes(StandardCharsets.UTF_8));
 * // written: Path_of_your_choice/<random UUID>.dat, content "Mule, 1, false"
 * }</pre>
 */
@Component
public class GreetingFileWriter {

    private final MelProperties properties;

    /**
     * Creates a writer for the directory bound to {@code mel.output-path}.
     *
     * @param properties the {@code mel.*} configuration properties
     */
    public GreetingFileWriter(MelProperties properties) {
        this.properties = properties;
    }

    /**
     * Writes {@code content} unchanged to a new file and returns its path; an I/O failure is
     * thrown as {@link UncheckedIOException}.
     *
     * <p>The returned path is the configured directory resolved with the generated file name, and
     * is relative when {@code mel.output-path} is relative. A failure to create the directory, for
     * example when {@code mel.output-path} names an existing regular file, or to create or write the
     * file, is thrown as {@link UncheckedIOException} wrapping the {@link IOException}, with its
     * message. A {@code null} {@code content} raises {@link NullPointerException} and creates no
     * file.
     *
     * @param content the bytes to write, already encoded by the caller
     * @return the path of the file written
     * @throws UncheckedIOException when the directory or the file cannot be created or written
     */
    public Path write(byte[] content) {
        Path dir = Path.of(properties.outputPath());
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(UUID.randomUUID() + ".dat");
            Files.write(file, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException(e.getMessage(), e);
        }
    }
}
