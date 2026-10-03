package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link FileWatermarkStore}, the file-per-key store of the {@code triggerFlow} watermark
 * (D-036, D-300).
 *
 * <p>Each test constructs the store directly over {@code <tempDir>/data/watermark}, a directory that does
 * not exist when the test starts, with no Spring application context and no mocks. The tests assert that
 * an absent key reads as empty and creates no directory; a written value reads back exactly and is the
 * exact UTF-8 content of {@code <storeDir>/timestamp}; the first write creates the nested directory; a
 * second write replaces the first; a second instance over the same directory reads what the first wrote,
 * and the first reads what the second wrote; two writes leave no temporary file; surrounding whitespace is
 * stripped and blank content reads as empty; content that is not valid UTF-8, a store directory that cannot
 * be created and a move that fails are each thrown as {@link UncheckedIOException} naming the key file,
 * and the failed move leaves no temporary file; the constructor rejects a missing, blank, {@code TODO} or
 * invalid {@code watermark.store-dir} with an {@link IllegalArgumentException}. Together they cover every
 * line of the store except the non-atomic fallback move after {@code AtomicMoveNotSupportedException} and
 * the suppressed failure to delete the temporary file (D-049).
 */
public class FileWatermarkStoreTest {

    /** The key under which {@code triggerFlow} keeps its watermark (D-036). */
    private static final String KEY = "timestamp";

    /** A watermark in the {@code yyyy-MM-dd'T'HH:mm:ss.SSS'Z'} form of SC-04. */
    private static final String VALUE = "2024-03-01T09:00:00.000Z";

    /** A later watermark in the same form as {@link #VALUE}. */
    private static final String NEWER_VALUE = "2024-03-02T10:30:15.123Z";

    @TempDir
    Path tempDir;

    private Path storeDir;

    private FileWatermarkStore store;

    /**
     * Points {@code storeDir} at {@code <tempDir>/data/watermark}, which does not exist yet, and creates a
     * store over it. Package-private lifecycle method: the public members of this class are its test
     * methods only.
     */
    @BeforeEach
    void setUp() {
        storeDir = tempDir.resolve("data").resolve("watermark");
        store = new FileWatermarkStore(storeDir.toString());
    }

    /** Reading a key that was never written yields an empty value and leaves the directory uncreated. */
    @Test
    @DisplayName("read returns empty for an absent key and creates no directory")
    public void readOfAbsentKeyIsEmptyAndCreatesNoDirectory() {
        assertThat(store.read(KEY)).isEmpty();

        assertThat(storeDir).doesNotExist();
        assertThat(tempDir.resolve("data")).doesNotExist();
    }

    /** A written value reads back unchanged and is the exact UTF-8 content of the key file. */
    @Test
    @DisplayName("read returns exactly the written value, which is the UTF-8 content of the key file")
    public void writtenValueRoundTripsExactly() throws IOException {
        store.write(KEY, VALUE);

        assertThat(store.read(KEY)).isEqualTo(Optional.of(VALUE));
        assertThat(Files.readString(storeDir.resolve(KEY), UTF_8)).isEqualTo(VALUE);
    }

    /** The first write creates the missing store directory and its missing parent. */
    @Test
    @DisplayName("the first write creates the missing nested store directory")
    public void firstWriteCreatesNestedStoreDirectory() {
        assertThat(tempDir.resolve("data")).doesNotExist();

        store.write(KEY, VALUE);

        assertThat(Files.isDirectory(storeDir)).isTrue();
        assertThat(storeDir.resolve(KEY)).isRegularFile();
    }

    /** A second write of the same key replaces the first value. */
    @Test
    @DisplayName("a second write replaces the stored value")
    public void secondWriteReplacesStoredValue() {
        store.write(KEY, VALUE);
        store.write(KEY, NEWER_VALUE);

        assertThat(store.read(KEY)).isEqualTo(Optional.of(NEWER_VALUE));
    }

    /**
     * A new instance over the same directory reads the value an earlier instance wrote, and the earlier
     * instance reads the newer value the new instance wrote (D-036).
     */
    @Test
    @DisplayName("a second instance over the same directory reads the first instance's value, and the first reads the second's newer value")
    public void valueSurvivesRestartAndIsNotCached() {
        store.write(KEY, VALUE);

        FileWatermarkStore restarted = new FileWatermarkStore(storeDir.toString());

        assertThat(restarted.read(KEY)).isEqualTo(Optional.of(VALUE));

        restarted.write(KEY, NEWER_VALUE);

        assertThat(store.read(KEY)).isEqualTo(Optional.of(NEWER_VALUE));
    }

    /** After two writes the store directory holds the key file and no temporary file. */
    @Test
    @DisplayName("two writes leave only the key file in the store directory")
    public void writesLeaveNoTemporaryFile() throws IOException {
        store.write(KEY, VALUE);
        store.write(KEY, NEWER_VALUE);

        try (Stream<Path> entries = Files.list(storeDir)) {
            assertThat(entries).containsExactly(storeDir.resolve(KEY));
        }
    }

    /** Leading and trailing whitespace of the key file is not part of the value read. */
    @Test
    @DisplayName("read strips leading and trailing whitespace from the key file content")
    public void readStripsSurroundingWhitespace() throws IOException {
        Files.createDirectories(storeDir);
        Files.writeString(storeDir.resolve(KEY), " \t" + VALUE + "\n", UTF_8);

        assertThat(store.read(KEY)).isEqualTo(Optional.of(VALUE));
    }

    /** A key file that holds only whitespace reads as an empty value. */
    @Test
    @DisplayName("read returns empty when the key file holds only whitespace")
    public void readOfWhitespaceOnlyContentIsEmpty() throws IOException {
        Files.createDirectories(storeDir);
        Files.writeString(storeDir.resolve(KEY), " \t\n", UTF_8);

        assertThat(store.read(KEY)).isEmpty();
    }

    /** A key file whose bytes are not valid UTF-8 makes read throw an unchecked exception naming the file. */
    @Test
    @DisplayName("read throws UncheckedIOException naming the key file when its content is not valid UTF-8")
    public void readOfInvalidUtf8ThrowsUncheckedIoException() throws IOException {
        Path keyFile = storeDir.resolve(KEY);
        Files.createDirectories(storeDir);
        Files.write(keyFile, new byte[] {(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> store.read(KEY))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Cannot read watermark " + keyFile)
                .hasCauseInstanceOf(CharacterCodingException.class);
    }

    /**
     * A regular file at the store directory's path makes write throw an unchecked exception naming the key
     * file, with no suppressed exception, and leaves that regular file unchanged.
     */
    @Test
    @DisplayName("write throws UncheckedIOException naming the key file when the store directory cannot be created")
    public void writeThrowsUncheckedIoExceptionWhenStoreDirectoryCannotBeCreated() throws IOException {
        Files.createDirectories(tempDir.resolve("data"));
        Files.writeString(storeDir, "x");

        assertThatThrownBy(() -> store.write(KEY, VALUE))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Cannot write watermark " + storeDir.resolve(KEY))
                .hasCauseInstanceOf(IOException.class)
                .satisfies(thrown -> assertThat(thrown.getCause().getSuppressed()).isEmpty());

        assertThat(storeDir).isRegularFile();
        assertThat(Files.readString(storeDir, UTF_8)).isEqualTo("x");
    }

    /**
     * A directory at the key file's path makes the move fail: write throws an unchecked exception naming the
     * key file, deletes its temporary file, and leaves the directory in place, which read reports as empty.
     */
    @Test
    @DisplayName("write throws UncheckedIOException and deletes its temporary file when the move onto the key path fails")
    public void writeDeletesTemporaryFileWhenMoveFails() throws IOException {
        Path keyPath = storeDir.resolve(KEY);
        Files.createDirectories(keyPath);

        assertThatThrownBy(() -> store.write(KEY, VALUE))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessage("Cannot write watermark " + keyPath)
                .hasCauseInstanceOf(IOException.class)
                .satisfies(thrown -> assertThat(thrown.getCause().getSuppressed()).isEmpty());

        try (Stream<Path> entries = Files.list(storeDir)) {
            assertThat(entries).containsExactly(keyPath);
        }
        assertThat(keyPath).isDirectory();
        assertThat(store.read(KEY)).isEmpty();
    }

    /** A missing, blank or {@code TODO} store directory is rejected with a message naming the property. */
    @Test
    @DisplayName("the constructor rejects a missing, blank or TODO watermark.store-dir")
    public void constructorRejectsMissingBlankOrTodoStoreDir() {
        assertThatThrownBy(() -> new FileWatermarkStore(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("watermark.store-dir must name the watermark directory, but it is not set");
        assertThatThrownBy(() -> new FileWatermarkStore(" \t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("watermark.store-dir must name the watermark directory, but it is blank");
        assertThatThrownBy(() -> new FileWatermarkStore(" TODO "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("watermark.store-dir must name the watermark directory, but it is the placeholder TODO");
    }

    /** Text that {@link Path#of(String, String...)} refuses is rejected with a message naming the property. */
    @Test
    @DisplayName("the constructor rejects a watermark.store-dir that is not a valid path")
    public void constructorRejectsInvalidPath() {
        String invalid = storeDir + "\u0000";

        assertThatThrownBy(() -> new FileWatermarkStore(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("watermark.store-dir is not a valid path: ")
                .hasCauseInstanceOf(InvalidPathException.class);
    }
}
