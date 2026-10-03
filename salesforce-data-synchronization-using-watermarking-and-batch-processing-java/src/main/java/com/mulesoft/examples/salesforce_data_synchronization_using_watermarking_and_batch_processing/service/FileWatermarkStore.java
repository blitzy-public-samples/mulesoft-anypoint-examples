package com.mulesoft.examples.salesforce_data_synchronization_using_watermarking_and_batch_processing.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Persists poll watermark values, one UTF-8 file per key under the configured directory (D-036, D-277).
 *
 * <p>Holds the {@code timestamp} watermark of flow {@code triggerFlow}
 * [salesforce-data-synchronization-using-watermarking-and-batch-processing/src/main/app/watermarking.xml:18]. The
 * directory is the value of {@code watermark.store-dir} (default {@code ./data/watermark}).
 *
 * <p>Layout and behaviour:
 * <ul>
 *   <li>The value of key {@code k} is the whole content of the file {@code <dir>/k}, encoded as UTF-8, with no
 *       byte-order mark and no line terminator.</li>
 *   <li>{@link #store} creates the directory when it is absent, writes the value to a temporary file
 *       {@code <dir>/k<random>.tmp} and moves that file onto {@code <dir>/k} with
 *       {@link StandardCopyOption#REPLACE_EXISTING} and {@link StandardCopyOption#ATOMIC_MOVE}. A reader of
 *       {@code <dir>/k} sees either the previous value or the new one, never a partial write. On POSIX file systems
 *       the file carries the temporary-file permissions (owner read and write only).</li>
 *   <li>{@link #retrieve} reads {@code <dir>/k} on every call; nothing is held in memory. A new instance over the
 *       same directory, for example after an application restart, returns the value an earlier instance stored.</li>
 *   <li>Every {@link IOException} surfaces as an {@link UncheckedIOException} whose message names the file. The
 *       class logs nothing; the caller logs the failure.</li>
 *   <li>Instances hold no mutable state. Calls are not synchronized: concurrent {@link #store} calls for one key
 *       end with the value of the last move.</li>
 * </ul>
 *
 * <p>Example:
 * <pre>{@code
 * FileWatermarkStore store = new FileWatermarkStore(Path.of("./data/watermark"));
 * store.retrieve("timestamp");                            // Optional.empty, directory not created
 * store.store("timestamp", "2014-07-04T06:16:55.000Z");   // ./data/watermark/timestamp holds those 24 bytes
 * store.retrieve("timestamp");                            // Optional[2014-07-04T06:16:55.000Z]
 * }</pre>
 */
@Component
public class FileWatermarkStore {

    /** Directory that holds one file per key, used exactly as given to the constructor. */
    private final Path dir;

    /**
     * Creates a store over {@code dir}. The directory is not accessed until the first {@link #retrieve} or
     * {@link #store} call.
     *
     * @param dir the directory that holds one file per key, bound from {@code watermark.store-dir}
     */
    public FileWatermarkStore(@Value("${watermark.store-dir}") Path dir) {
        this.dir = dir;
    }

    /**
     * Returns the value stored under {@code key}.
     *
     * <p>Reads the file {@code <dir>/<key>} as UTF-8 and returns its content exactly as read, with no trimming and
     * no line-terminator removal. Only that file is read; temporary files in the directory are ignored. When the file
     * does not exist the result is {@link Optional#empty()}, and the directory is neither created nor modified.
     *
     * @param key the watermark key, a plain file name inside the directory, for example {@code timestamp}
     * @return the stored value, or {@link Optional#empty()} when {@code <dir>/<key>} does not exist
     * @throws UncheckedIOException when {@code <dir>/<key>} exists but cannot be read as UTF-8 text, for example
     *                              when it is a directory, is not readable or holds malformed UTF-8
     */
    public Optional<String> retrieve(String key) {
        Path path = dir.resolve(key);
        if (Files.notExists(path)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read watermark file " + path, e);
        }
    }

    /**
     * Stores {@code value} under {@code key}, replacing any previous value.
     *
     * <p>Steps, in order:
     * <ol>
     *   <li>creates {@code <dir>} and its missing parents;</li>
     *   <li>creates a temporary file {@code <key><random>.tmp} in {@code <dir>};</li>
     *   <li>writes {@code value} to it as UTF-8, with no line terminator;</li>
     *   <li>moves it onto {@code <dir>/<key>} with {@link StandardCopyOption#REPLACE_EXISTING} and
     *       {@link StandardCopyOption#ATOMIC_MOVE}.</li>
     * </ol>
     *
     * <p>When step 3 or 4 fails, the temporary file is deleted, a failure of that deletion is added to the original
     * exception as suppressed, and {@code <dir>/<key>} keeps its previous content. After a successful call no
     * temporary file of this call remains in the directory.
     *
     * @param key   the watermark key, a plain file name inside the directory, for example {@code timestamp}
     * @param value the value to store, for example {@code 2014-07-04T06:16:55.000Z}
     * @throws UncheckedIOException when any step fails with an {@link IOException}: for example when {@code <dir>}
     *                              is an existing regular file, when {@code <dir>/<key>} is a directory, when
     *                              {@code value} holds an unpaired surrogate that UTF-8 cannot encode, or when the
     *                              file system does not support an atomic move
     *                              ({@link java.nio.file.AtomicMoveNotSupportedException})
     * @throws NullPointerException when {@code key} or {@code value} is {@code null}; a temporary file created
     *                              before the failure is deleted
     */
    public void store(String key, String value) {
        Path target = dir.resolve(key);
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, key, ".tmp");
            try {
                Files.writeString(temp, value, StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException | RuntimeException e) {
                // Any failure of the write or the move, a null value included, deletes the temporary file (D-277).
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException deleteFailure) {
                    e.addSuppressed(deleteFailure);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write watermark file " + target, e);
        }
    }
}
