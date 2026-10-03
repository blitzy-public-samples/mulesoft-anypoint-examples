package com.mulesoft.examples.salesforce_to_mysql_db_using_batch_processing.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Persistent store of string values by key: the value of key {@code k} is the UTF-8 content of the file
 * {@code <storeDir>/k}, where {@code storeDir} is the {@code watermark.store-dir} property (D-036, D-300).
 *
 * <p>Source: {@code salesforce-to-MySQL-DB-using-Batch-Processing/src/main/app/salesforce-to-database.xml:63},
 * the {@code watermark} of flow {@code triggerFlow}, whose value Mule 3.8 kept in its persistent user object
 * store.
 *
 * <p>Values persist across application restarts: a new instance over the same directory reads the values an
 * earlier instance wrote. The store holds no default value, selector or update rule;
 * {@code SalesforceToDatabaseBatchJob.triggerFlow} decides when key {@code timestamp} is read and written.
 *
 * <ul>
 *   <li>{@link #read(String)} reads the file on every call; nothing is cached.</li>
 *   <li>{@link #write(String, String)} creates {@code storeDir} when it is missing, writes the value to a
 *       temporary file {@code <key>-<n>.tmp} in {@code storeDir}, and moves that file over
 *       {@code <storeDir>/<key>}. The move is atomic where the file system supports atomic moves.</li>
 *   <li>Every I/O failure is thrown as an {@link UncheckedIOException} that names the file.</li>
 * </ul>
 *
 * <p>The instance holds no mutable state. Concurrent writes of one key each replace the whole file; the
 * last move to complete determines the stored value.
 *
 * <pre>{@code
 * FileWatermarkStore store = new FileWatermarkStore("data/watermark");
 * store.read("timestamp");                                // Optional.empty() before the first write
 * store.write("timestamp", "2015-10-10T10:00:00.000Z");   // creates data/watermark/timestamp
 * store.read("timestamp");                                // Optional[2015-10-10T10:00:00.000Z]
 * }</pre>
 */
@Component
public class FileWatermarkStore {

    private final Path storeDir;

    /**
     * Creates a store over the directory named by {@code storeDir}. The constructor converts the text with
     * {@link Path#of(String, String...)} and accesses no file or directory. No directory is created or
     * modified until the first {@link #write(String, String)}; {@link #read(String)} only inspects and reads
     * files that already exist.
     *
     * @param storeDir the raw value of {@code watermark.store-dir}, the directory that holds one file per key
     * @throws IllegalArgumentException if {@code storeDir} is {@code null}, blank, the placeholder {@code TODO}
     *                                  (ignoring surrounding whitespace), or not a valid path; the message
     *                                  names {@code watermark.store-dir}
     */
    public FileWatermarkStore(@Value("${watermark.store-dir}") String storeDir) {
        if (storeDir == null) {
            throw new IllegalArgumentException(
                    "watermark.store-dir must name the watermark directory, but it is not set");
        }
        if (storeDir.isBlank()) {
            throw new IllegalArgumentException(
                    "watermark.store-dir must name the watermark directory, but it is blank");
        }
        if (storeDir.strip().equals("TODO")) {
            throw new IllegalArgumentException(
                    "watermark.store-dir must name the watermark directory, but it is the placeholder TODO");
        }
        try {
            this.storeDir = Path.of(storeDir);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException("watermark.store-dir is not a valid path: " + e.getMessage(), e);
        }
    }

    /**
     * Reads the value stored under {@code key} from {@code <storeDir>/<key>}; empty when the file is absent,
     * is not a regular file, or holds only whitespace.
     *
     * <p>The file content is decoded as UTF-8 and stripped of leading and trailing whitespace. Each call
     * reads the file.
     *
     * @param key the key whose value is read, for example {@code timestamp}
     * @return the stripped file content, or {@link Optional#empty()} when there is no stored value
     * @throws NullPointerException if {@code key} is {@code null}
     * @throws UncheckedIOException if the file exists but cannot be read or is not valid UTF-8
     */
    public Optional<String> read(String key) {
        Objects.requireNonNull(key, "key");
        Path target = storeDir.resolve(key);
        if (!Files.isRegularFile(target)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(target, StandardCharsets.UTF_8).strip();
            return content.isEmpty() ? Optional.empty() : Optional.of(content);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read watermark " + target, e);
        }
    }

    /**
     * Stores {@code value} under {@code key}: the file {@code <storeDir>/<key>} afterwards holds exactly
     * {@code value} in UTF-8, with no trailing newline, and replaces any earlier value.
     *
     * <p>Creates {@code storeDir} and its missing parents, writes {@code value} to a new temporary file
     * {@code <key>-<n>.tmp} in {@code storeDir}, and moves it over {@code <storeDir>/<key>} with
     * {@link StandardCopyOption#ATOMIC_MOVE} and {@link StandardCopyOption#REPLACE_EXISTING}. When the file
     * system rejects the atomic move with {@link AtomicMoveNotSupportedException}, the move is repeated with
     * {@link StandardCopyOption#REPLACE_EXISTING} only. On any I/O failure the temporary file is deleted, the
     * earlier value stays in place, and no {@code *.tmp} file remains unless its deletion also fails.
     *
     * @param key   the key under which the value is stored, for example {@code timestamp}
     * @param value the value to store
     * @throws NullPointerException if {@code key} or {@code value} is {@code null}
     * @throws UncheckedIOException if the directory, the temporary file or the move fails; a failure to
     *                              delete the temporary file is attached as a suppressed exception
     */
    public void write(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Path target = storeDir.resolve(key);
        Path tmp = null;
        try {
            Files.createDirectories(storeDir);
            tmp = Files.createTempFile(storeDir, key + "-", ".tmp");
            Files.writeString(tmp, value, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException atomicMoveRejected) {
                // Non-atomic replacement on file systems without atomic moves (D-300).
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException cleanupFailure) {
                    e.addSuppressed(cleanupFailure);
                }
            }
            throw new UncheckedIOException("Cannot write watermark " + target, e);
        }
    }
}
