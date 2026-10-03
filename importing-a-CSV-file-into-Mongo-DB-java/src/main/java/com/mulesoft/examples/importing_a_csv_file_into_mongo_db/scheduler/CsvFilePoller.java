package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.scheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config.FileInboundProperties;
import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service.CustomerImportService;

/**
 * Message source of flow {@code csv-to-mongodbFlow1}
 * [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:8-40]: the
 * {@code file:inbound-endpoint} at :9, which polls a directory for CSV files and hands the content of
 * each file to {@link CustomerImportService#csvToMongodbFlow1(byte[])}.
 *
 * <p>Behaviour:
 * <ul>
 *   <li>{@link #poll()} polls the directory {@code file.inbound-endpoint.path} every
 *       {@code file.inbound-endpoint.polling-frequency} milliseconds, both bound by
 *       {@link FileInboundProperties}; the interval runs from the end of one poll to the start of the
 *       next, and the first poll runs at startup (D-036, D-103).</li>
 *   <li>Each poll creates the directory when it is absent, lists its regular files (no
 *       subdirectories, no recursion) and passes each of them, in ascending file-name order, to
 *       {@link #csvToMongodbFlow1(Path)}, one file at a time on the scheduler thread (D-575).</li>
 *   <li>{@link #csvToMongodbFlow1(Path)} reads the whole file, passes its bytes to
 *       {@code CustomerImportService.csvToMongodbFlow1}, and deletes the file after the service
 *       returns.</li>
 *   <li>A file whose read, processing or deletion fails stays in place and is read again at the next
 *       poll; the failure is logged at ERROR and no exception leaves the poller (D-036).</li>
 *   <li>The bean is registered only when {@code polling.enabled} is {@code true} or absent
 *       (D-093).</li>
 * </ul>
 *
 * <p>The class holds no mutable state: its fields are the logger and the two injected
 * collaborators.
 *
 * <p>Example, with the poller built directly over a test directory:
 * <pre>{@code
 * CsvFilePoller poller = new CsvFilePoller(service, new FileInboundProperties(tempDir, 1000));
 * poller.poll();                                          // every regular file in tempDir, by name
 * poller.csvToMongodbFlow1(tempDir.resolve("input.csv")); // one file
 * }</pre>
 */
@Component
@ConditionalOnProperty(name = "polling.enabled", havingValue = "true", matchIfMissing = true)
public class CsvFilePoller {

    /** Writes the ERROR lines of a failed poll or a failed file. */
    private static final Logger log = LoggerFactory.getLogger(CsvFilePoller.class);

    /** Runs flow {@code csv-to-mongodbFlow1} over the content of one file. */
    private final CustomerImportService service;

    /** The polled directory and the poll interval of the {@code file:inbound-endpoint}. */
    private final FileInboundProperties properties;

    /**
     * Creates the poller over its two collaborators.
     *
     * @param service    the service that runs flow {@code csv-to-mongodbFlow1} over a file's bytes
     * @param properties the bound {@code file.inbound-endpoint.*} keys: the polled directory and the
     *                   poll interval
     */
    public CsvFilePoller(CustomerImportService service, FileInboundProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    /**
     * Runs one poll of the {@code file:inbound-endpoint} [csv-to-mongodb.xml:9] over
     * {@code file.inbound-endpoint.path}.
     *
     * <p>The poll creates the directory, with any missing parent, when it is absent. It then lists the
     * directory's direct children, keeps the regular files, sorts them by file name in ascending
     * {@link String} order and closes the listing. After that it calls
     * {@link #csvToMongodbFlow1(Path)} for each file in that order, one after the other (D-575).
     *
     * <p>When creating or listing the directory fails, the poll logs
     * {@code Cannot poll input directory <dir>} at ERROR with the exception, processes no file and
     * returns; the next scheduled poll tries again. No exception leaves this method: each file's
     * failure is handled by {@link #csvToMongodbFlow1(Path)}.
     */
    @Scheduled(fixedDelayString = "${file.inbound-endpoint.polling-frequency}")
    public void poll() {
        Path dir = properties.path();
        List<Path> files;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> entries = Files.list(dir)) {
                files = entries
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
            }
        } catch (IOException | RuntimeException e) {
            // Also catches the UncheckedIOException of a listing that fails part-way and any other
            // runtime exception from creating or listing the directory (D-575).
            log.error("Cannot poll input directory {}", dir, e);
            return;
        }
        for (Path file : files) {
            csvToMongodbFlow1(file);
        }
    }

    /**
     * Runs flow {@code csv-to-mongodbFlow1} [csv-to-mongodb.xml:8-40] over one file: reads all of its
     * bytes, passes them to {@link CustomerImportService#csvToMongodbFlow1(byte[])} and, once the
     * service has returned normally, deletes the file. It is the poller's public per-file entry point,
     * called by {@link #poll()} and available for on-demand invocation; the class has no separate
     * {@code processFile(Path)} method.
     *
     * <p>When the read, the service call or the deletion throws any exception, for example a
     * {@link java.nio.file.NoSuchFileException} for a file removed after the listing, or a runtime
     * exception from the CSV mapping or the database, the method logs
     * {@code Failed to process file <name>; the file stays in <directory> for the next poll} at ERROR
     * with the exception and returns normally. The file is then neither moved, renamed nor deleted,
     * and the next poll reads it again (D-036). A file that is already gone when the service returns
     * is not reported.
     *
     * @param file the file to process
     * @throws NullPointerException if {@code file} is {@code null}
     */
    public void csvToMongodbFlow1(Path file) {
        if (file == null) {
            throw new NullPointerException("file must not be null");
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            service.csvToMongodbFlow1(bytes);
            Files.deleteIfExists(file);
        } catch (Exception e) {
            log.error("Failed to process file {}; the file stays in {} for the next poll",
                    file.getFileName(), file.getParent(), e);
        }
    }
}
