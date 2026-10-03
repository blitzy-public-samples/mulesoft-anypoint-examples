package com.mulesoft.examples.mule_expression_language_basics.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mulesoft.examples.mule_expression_language_basics.config.MelProperties;

/**
 * Unit tests of {@link GreetingFileWriter#write(byte[])} (D-631) over real files under a JUnit
 * {@link TempDir}, with no Spring context and no mocks (D-660): a missing output directory is
 * created; each call writes one new {@code <UUID>.dat} file holding the given bytes unchanged; an
 * output path naming an existing regular file raises {@link UncheckedIOException} wrapping the
 * {@link IOException}; a relative output path gives a relative returned path; {@code null} content
 * raises {@link NullPointerException} and creates no file.
 *
 * <p>Every output directory lies under the {@link TempDir}. The {@code service} package
 * line-coverage floor these tests count towards is D-049.
 */
public class GreetingFileWriterTest {

    private static final Pattern FILE_NAME = Pattern.compile("^[0-9a-f-]{36}\\.dat$");

    @TempDir
    Path tmp;

    @Test
    @DisplayName("a missing output directory and its missing parent are created, and the file is written inside it")
    public void writeCreatesMissingDirectory() throws IOException {
        Path dir = tmp.resolve("missing").resolve("Path_of_your_choice");
        assertThat(dir).doesNotExist();
        assertThat(dir.getParent()).doesNotExist();

        Path written = writer(dir).write("x".getBytes(UTF_8));

        assertThat(dir).isDirectory();
        assertThat(written.toAbsolutePath().normalize().getParent())
                .isEqualTo(dir.toAbsolutePath().normalize());
        assertThat(written).isRegularFile().hasBinaryContent("x".getBytes(UTF_8));
    }

    @Test
    @DisplayName("two calls write two distinct <UUID>.dat files, each holding its bytes unchanged")
    public void writeCreatesDistinctUuidFilesWithUnchangedBytes() throws IOException {
        Path dir = tmp.resolve("Path_of_your_choice");
        GreetingFileWriter writer = writer(dir);
        byte[] first = "Mule, 1, false".getBytes(UTF_8);
        byte[] second = "Mule,1,false\n".getBytes(UTF_8);

        Path firstFile = writer.write(first);
        Path secondFile = writer.write(second);

        assertThat(firstFile).isNotEqualTo(secondFile);
        for (Path file : List.of(firstFile, secondFile)) {
            String name = file.getFileName().toString();
            assertThat(name).matches(FILE_NAME);
            String stem = name.substring(0, name.length() - ".dat".length());
            assertThat(UUID.fromString(stem).toString()).isEqualTo(stem);
        }
        assertThat(Files.readAllBytes(firstFile)).isEqualTo(first);
        assertThat(Files.readAllBytes(secondFile)).isEqualTo(second);
        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries.map(Path::getFileName))
                    .containsExactlyInAnyOrder(firstFile.getFileName(), secondFile.getFileName());
        }
    }

    @Test
    @DisplayName("an output path naming an existing regular file raises UncheckedIOException wrapping the IOException")
    public void writeThrowsUncheckedIOExceptionWhenOutputPathIsRegularFile() throws IOException {
        byte[] existing = "taken".getBytes(UTF_8);
        Path occupied = Files.write(tmp.resolve("Path_of_your_choice"), existing);

        assertThatThrownBy(() -> writer(occupied).write("x".getBytes(UTF_8)))
                .isInstanceOf(UncheckedIOException.class)
                .hasCauseInstanceOf(IOException.class)
                .satisfies(e -> assertThat(e).hasMessage(e.getCause().getMessage()));
        assertThat(occupied).isRegularFile().hasBinaryContent(existing);
    }

    @Test
    @DisplayName("a relative output path gives a relative returned path, resolved against the working directory")
    public void writeReturnsRelativePathForRelativeOutputPath() throws IOException {
        Path target = tmp.toRealPath().resolve("Path_of_your_choice");
        Path relativeDir = Path.of("").toAbsolutePath().toRealPath().relativize(target);
        assertThat(relativeDir).isRelative();

        Path written = writer(relativeDir).write("x".getBytes(UTF_8));

        assertThat(written).isRelative();
        assertThat(written.getParent()).isEqualTo(relativeDir);
        assertThat(target.resolve(written.getFileName()))
                .isRegularFile()
                .hasBinaryContent("x".getBytes(UTF_8));
        try (Stream<Path> entries = Files.list(target)) {
            assertThat(entries.map(Path::getFileName)).containsExactly(written.getFileName());
        }
    }

    @Test
    @DisplayName("null content raises NullPointerException and creates no file")
    public void writeNullThrowsNullPointerExceptionAndCreatesNoFile() throws IOException {
        Path dir = Files.createDirectory(tmp.resolve("Path_of_your_choice"));

        assertThatThrownBy(() -> writer(dir).write(null)).isInstanceOf(NullPointerException.class);
        try (Stream<Path> entries = Files.list(dir)) {
            assertThat(entries).isEmpty();
        }
    }

    /** Returns a writer whose {@code mel.output-path} is the text of {@code dir}. */
    private static GreetingFileWriter writer(Path dir) {
        return new GreetingFileWriter(new MelProperties(dir.toString()));
    }
}
