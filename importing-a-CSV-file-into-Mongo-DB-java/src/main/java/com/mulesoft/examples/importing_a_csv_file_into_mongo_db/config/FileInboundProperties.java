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
 * @param path             {@code file.inbound-endpoint.path}: the directory polled for CSV files; a
 *                         relative path resolves against the working directory
 * @param pollingFrequency {@code file.inbound-endpoint.polling-frequency}: the delay in milliseconds
 *                         between the end of one poll and the start of the next
 */
@ConfigurationProperties("file.inbound-endpoint")
public record FileInboundProperties(Path path, long pollingFrequency) {
}
