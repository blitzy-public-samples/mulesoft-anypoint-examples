package com.mulesoft.examples.sending_a_csv_file_through_email_using_smtp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Directory polled for CSV files and the delay between polls in milliseconds, bound from
 * {@code file.input-path} (default {@code src/main/resources/input}, relative to the working directory) and
 * {@code file.polling-frequency} (default 1000) (D-036, D-111).
 *
 * <p>The record replaces the {@code file:inbound-endpoint} of flow {@code csv-to-smtpFlow}
 * [sending-a-csv-file-through-email-using-smtp/src/main/app/csv-to-smtp.xml:5] and is read by
 * {@code scheduler.CsvFilePoller}. The endpoint's {@code responseTimeout} has no component (D-092).
 *
 * <p>Both values bind by constructor binding; an absent key binds its default. Every value binds as written:
 * the path is neither resolved, normalised nor made absolute, nothing on disk is read or created, and no value
 * is rejected beyond the conversion of {@code file.polling-frequency} to a number (D-392). Instances are
 * immutable.
 *
 * <pre>{@code
 * file:
 *   input-path: /data/orders
 *   polling-frequency: 250
 * }</pre>
 * binds {@code inputPath()} {@code "/data/orders"} and {@code pollingFrequency()} {@code 250}. The
 * environment variables {@code FILE_INPUT_PATH} and {@code FILE_POLLING_FREQUENCY} bind the same keys.
 *
 * @param inputPath        {@code file.input-path}: the directory polled for CSV files, as written; a relative
 *                         path resolves against the working directory of the JVM
 * @param pollingFrequency {@code file.polling-frequency}: the delay in milliseconds between the end of one poll
 *                         and the start of the next
 */
@ConfigurationProperties(prefix = "file")
public record FilePollProperties(
        @DefaultValue("src/main/resources/input") String inputPath,
        @DefaultValue("1000") long pollingFrequency) {
}
