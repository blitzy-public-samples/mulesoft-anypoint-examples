package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link FileWatermarkStore}, the file-per-key store of the {@code timestamp} watermark of flow
 * {@code triggerFlow} (D-036, D-277).
 *
 * <p>Each test method receives its own {@code @TempDir} directory and constructs the store directly over it with
 * {@code new FileWatermarkStore(dir.toString())}, with no Spring application context and no mocks. Nothing is
 * written outside that directory. The tests cover the store's lines for the service-package coverage floor
 * (D-049).
 */
public class FileWatermarkStoreTest {

    /** The watermark key of {@code triggerFlow}. */
    private static final String KEY = "timestamp";

    /** A watermark value in the {@code LastModifiedDate} form. */
    private static final String EARLIER = "2014-07-04T06:16:54.000Z";

    /** A watermark value later than {@link #EARLIER}, in the same form. */
    private static final String LATER = "2014-07-05T08:00:00.000Z";

    /**
     * Stores a value with one instance and reads it with a second instance over the same directory (D-036).
     */
    @Test
    @DisplayName("salesforce-data-synchronization-using-watermarking-and-batch-processing_watermark-survives-restart")
    public void watermarkSurvivesRestart(@TempDir Path dir) {
        FileWatermarkStore first = new FileWatermarkStore(dir.toString());
        first.store(KEY, LATER);

        FileWatermarkStore second = new FileWatermarkStore(dir.toString());

        assertEquals(Optional.of(LATER), second.retrieve(KEY));
    }

    /** Reads a key that was never stored from an empty directory and gets an empty value; the directory stays empty. */
    @Test
    @DisplayName("retrieve returns empty for an absent key")
    public void retrieveReturnsEmptyForAbsentKey(@TempDir Path dir) throws IOException {
        FileWatermarkStore store = new FileWatermarkStore(dir.toString());

        assertEquals(Optional.empty(), store.retrieve(KEY));
        assertEquals(List.of(), entries(dir));
    }

    /** Constructs a store over a missing directory and reads a key; the directory is not created. */
    @Test
    @DisplayName("the constructor and retrieve over a missing directory create nothing")
    public void constructorAndRetrieveOverMissingDirectoryCreateNothing(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("missing");

        FileWatermarkStore store = new FileWatermarkStore(missing.toString());

        assertTrue(Files.notExists(missing));
        assertEquals(Optional.empty(), store.retrieve(KEY));
        assertTrue(Files.notExists(missing));
        assertEquals(List.of(), entries(dir));
    }

    /** Stores a value over a missing nested directory; the directory and the key file are created. */
    @Test
    @DisplayName("the first store creates the missing nested directory")
    public void firstStoreCreatesMissingNestedDirectory(@TempDir Path dir) {
        Path nested = dir.resolve("a").resolve("b");
        assertTrue(Files.notExists(nested));

        FileWatermarkStore store = new FileWatermarkStore(nested.toString());
        store.store(KEY, EARLIER);

        assertTrue(Files.isDirectory(nested));
        assertTrue(Files.isRegularFile(nested.resolve(KEY)));
        assertEquals(Optional.of(EARLIER), store.retrieve(KEY));
    }

    /** Stores two values under one key; the second replaces the first. */
    @Test
    @DisplayName("a second store replaces the first value")
    public void secondStoreReplacesFirstValue(@TempDir Path dir) {
        FileWatermarkStore store = new FileWatermarkStore(dir.toString());

        store.store(KEY, EARLIER);
        store.store(KEY, LATER);

        assertEquals(Optional.of(LATER), store.retrieve(KEY));
    }

    /**
     * Stores a value and reads the key file's bytes: they are the UTF-8 bytes of the value, with no byte-order mark
     * and no line terminator, and the key file is the only entry of the directory.
     */
    @Test
    @DisplayName("the key file holds exactly the UTF-8 bytes of the value and no temporary file remains")
    public void keyFileHoldsExactUtf8BytesOfValue(@TempDir Path dir) throws IOException {
        FileWatermarkStore store = new FileWatermarkStore(dir.toString());

        store.store(KEY, LATER);

        assertArrayEquals(LATER.getBytes(UTF_8), Files.readAllBytes(dir.resolve(KEY)));
        assertEquals(List.of(dir.resolve(KEY)), entries(dir));
    }

    /** Stores a value with a non-ASCII character; it reads back unchanged and the file holds its UTF-8 bytes. */
    @Test
    @DisplayName("a non-ASCII value round-trips as UTF-8")
    public void nonAsciiValueRoundTripsAsUtf8(@TempDir Path dir) throws IOException {
        String value = "2014-07-05T08:00:00.000Z-\u00e9";
        FileWatermarkStore store = new FileWatermarkStore(dir.toString());

        store.store(KEY, value);

        assertEquals(Optional.of(value), store.retrieve(KEY));
        assertArrayEquals(value.getBytes(UTF_8), Files.readAllBytes(dir.resolve(KEY)));
    }

    /** Reads a key file written outside the store; the content is returned with its whitespace and line terminator. */
    @Test
    @DisplayName("retrieve returns the key file content without trimming")
    public void retrieveReturnsFileContentWithoutTrimming(@TempDir Path dir) throws IOException {
        String content = " " + LATER + "\n";
        Files.writeString(dir.resolve(KEY), content, UTF_8);

        assertEquals(Optional.of(content), new FileWatermarkStore(dir.toString()).retrieve(KEY));
    }

    /**
     * Stores a value over a directory path that is an existing regular file; the call throws
     * {@link UncheckedIOException} naming the key file, and the regular file keeps its content.
     */
    @Test
    @DisplayName("store throws UncheckedIOException when the directory path is a regular file")
    public void storeThrowsUncheckedIoExceptionWhenDirectoryIsRegularFile(@TempDir Path dir) throws IOException {
        Path blocker = Files.writeString(dir.resolve("blocker"), "x");

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> new FileWatermarkStore(blocker.toString()).store(KEY, "v"));

        assertEquals("Cannot write watermark file " + blocker.resolve(KEY), thrown.getMessage());
        assertEquals("x", Files.readString(blocker, UTF_8));
    }

    /**
     * Reads a key whose path is a directory; the call throws {@link UncheckedIOException} naming the key path.
     */
    @Test
    @DisplayName("retrieve throws UncheckedIOException when the key path is a directory")
    public void retrieveThrowsUncheckedIoExceptionWhenKeyPathIsDirectory(@TempDir Path dir) throws IOException {
        Path keyPath = Files.createDirectory(dir.resolve(KEY));

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> new FileWatermarkStore(dir.toString()).retrieve(KEY));

        assertEquals("Cannot read watermark file " + keyPath, thrown.getMessage());
    }

    /**
     * Reads a key file whose bytes are not valid UTF-8; the call throws {@link UncheckedIOException} naming the
     * key file, with a {@link CharacterCodingException} cause.
     */
    @Test
    @DisplayName("retrieve throws UncheckedIOException when the key file is not valid UTF-8")
    public void retrieveThrowsUncheckedIoExceptionForMalformedUtf8(@TempDir Path dir) throws IOException {
        Path keyFile = Files.write(dir.resolve(KEY), new byte[] {(byte) 0xC3, (byte) 0x28});

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> new FileWatermarkStore(dir.toString()).retrieve(KEY));

        assertEquals("Cannot read watermark file " + keyFile, thrown.getMessage());
        assertInstanceOf(CharacterCodingException.class, thrown.getCause());
    }

    /**
     * Stores a value, then stores a value with an unpaired surrogate that UTF-8 cannot encode; the second call
     * throws {@link UncheckedIOException} naming the key file, the first value is still returned, and no temporary
     * file remains (D-036).
     */
    @Test
    @DisplayName("a failed write keeps the previous value and leaves no temporary file")
    public void failedWriteKeepsPreviousValueAndLeavesNoTemporaryFile(@TempDir Path dir) throws IOException {
        FileWatermarkStore store = new FileWatermarkStore(dir.toString());
        store.store(KEY, EARLIER);

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, () -> store.store(KEY, "\uD800"));

        assertEquals("Cannot write watermark file " + dir.resolve(KEY), thrown.getMessage());
        assertInstanceOf(CharacterCodingException.class, thrown.getCause());
        assertEquals(Optional.of(EARLIER), store.retrieve(KEY));
        assertEquals(List.of(dir.resolve(KEY)), entries(dir));
    }

    /**
     * Stores a value under a key whose path is a directory; the move fails, the call throws
     * {@link UncheckedIOException} naming the key path, and the directory is the only entry left.
     */
    @Test
    @DisplayName("a failed move onto the key path deletes the temporary file")
    public void failedMoveDeletesTemporaryFile(@TempDir Path dir) throws IOException {
        Path keyPath = Files.createDirectory(dir.resolve(KEY));

        UncheckedIOException thrown = assertThrows(UncheckedIOException.class,
                () -> new FileWatermarkStore(dir.toString()).store(KEY, LATER));

        assertEquals("Cannot write watermark file " + keyPath, thrown.getMessage());
        assertEquals(0, thrown.getCause().getSuppressed().length);
        assertTrue(Files.isDirectory(keyPath));
        assertEquals(List.of(keyPath), entries(dir));
    }

    /**
     * Stores a value, then stores {@code null}; the second call throws {@link NullPointerException}, the first value
     * is still returned, and no temporary file remains.
     */
    @Test
    @DisplayName("a null value throws NullPointerException and keeps the previous value")
    public void nullValueThrowsAndKeepsPreviousValue(@TempDir Path dir) throws IOException {
        FileWatermarkStore store = new FileWatermarkStore(dir.toString());
        store.store(KEY, EARLIER);

        assertThrows(NullPointerException.class, () -> store.store(KEY, null));

        assertEquals(Optional.of(EARLIER), store.retrieve(KEY));
        assertEquals(List.of(dir.resolve(KEY)), entries(dir));
    }

    /**
     * Constructs stores over a {@code null}, blank, {@code TODO} and invalid {@code watermark.store-dir}; each is
     * rejected with an {@link IllegalArgumentException} whose message names the property, and nothing is created.
     */
    @Test
    @DisplayName("the constructor rejects a missing, blank, TODO or invalid watermark.store-dir")
    public void constructorRejectsMissingBlankTodoOrInvalidStoreDir(@TempDir Path dir) throws IOException {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> new FileWatermarkStore(null));
        assertEquals("watermark.store-dir must name the watermark directory, but it is not set",
                missing.getMessage());

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new FileWatermarkStore(" \t"));
        assertEquals("watermark.store-dir must name the watermark directory, but it is blank", blank.getMessage());

        IllegalArgumentException todo = assertThrows(IllegalArgumentException.class,
                () -> new FileWatermarkStore(" TODO "));
        assertEquals("watermark.store-dir must name the watermark directory, but it is the placeholder TODO",
                todo.getMessage());

        String invalidText = dir.resolve("watermark") + "\u0000";
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> new FileWatermarkStore(invalidText));
        assertTrue(invalid.getMessage().startsWith("watermark.store-dir is not a valid path: "),
                invalid.getMessage());
        assertInstanceOf(InvalidPathException.class, invalid.getCause());

        assertEquals(List.of(), entries(dir));
    }

    /**
     * Lists the entries of {@code dir} in name order.
     *
     * @param dir an existing directory
     * @return the paths directly inside {@code dir}, sorted
     * @throws IOException when the directory cannot be listed
     */
    private static List<Path> entries(Path dir) throws IOException {
        try (Stream<Path> listing = Files.list(dir)) {
            return listing.sorted().toList();
        }
    }
}

