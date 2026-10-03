package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the watched directory and the poll interval in milliseconds of the {@code file:inbound-endpoint}
 * [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:9] from the
 * {@code file.inbound-endpoint.*} keys of {@code application.yml}, by constructor binding.
 *
 * <p>The record declares no defaults: every value comes from the bound keys. The endpoint's
 * {@code responseTimeout} has no component (D-103).
 *
 * <p>The canonical constructor rejects a {@code null} or blank path, a path that is the placeholder
 * {@code TODO}, and a poll interval that is not positive, with an {@link IllegalArgumentException}
 * whose message starts with the offending key, which fails the binding at startup. It stores the path
 * as bound, and neither reads nor creates anything on disk (D-363).
 *
 * @param path             {@code file.inbound-endpoint.path}: the directory polled for CSV files; a
 *                         relative path resolves against the working directory
 * @param pollingFrequency {@code file.inbound-endpoint.polling-frequency}: the delay in milliseconds
 *                         between the end of one poll and the start of the next
 */
@ConfigurationProperties("file.inbound-endpoint")
public record FileInboundProperties(Path path, long pollingFrequency) {

    /**
     * Checks both bound values and keeps them unchanged.
     *
     * @param path             {@code file.inbound-endpoint.path}: the directory polled for CSV files
     * @param pollingFrequency {@code file.inbound-endpoint.polling-frequency}: the poll interval in
     *                         milliseconds
     * @throws IllegalArgumentException if {@code path} is {@code null}, blank or, after stripping
     *                                  surrounding white space, the placeholder {@code TODO}, or if
     *                                  {@code pollingFrequency} is zero or negative; the message
     *                                  starts with the offending key
     */
    public FileInboundProperties {
        if (path == null || path.toString().isBlank()) {
            throw new IllegalArgumentException(
                    "file.inbound-endpoint.path must name the directory to poll");
        }
        if (path.toString().strip().equals("TODO")) {
            throw new IllegalArgumentException("file.inbound-endpoint.path holds the placeholder TODO;"
                    + " it must name the directory to poll");
        }
        if (pollingFrequency <= 0) {
            throw new IllegalArgumentException("file.inbound-endpoint.polling-frequency must be a positive"
                    + " number of milliseconds, but is " + pollingFrequency);
        }
    }
}
