package com.mulesoft.examples.processing_orders_with_dataweave_and_apikit.config;

import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

/**
 * File endpoint settings of OrderFlow
 * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:8,39,42], bound by constructor
 * binding from the {@code file.*} keys of {@code application.yml} (D-036).
 *
 * <p>The record declares no defaults: every value comes from the bound keys. The compact constructors
 * validate every component at binding (D-332): they reject an absent {@code file.inbound} or
 * {@code file.outbound} group, an absent or blank directory, an absent, zero or negative poll interval and
 * an absent or blank output file name. Each rejection is an {@link IllegalArgumentException} whose message
 * names the full {@code file.*} key and omits the bound value. The constructors keep each bound directory,
 * relative or absolute, unchanged and do not access the file system.
 *
 * @param inbound  the {@code file.inbound.*} settings of the file inbound endpoint
 * @param outbound the {@code file.outbound.*} settings of the two file outbound endpoints
 */
@ConfigurationProperties("file")
public record FileEndpointProperties(Inbound inbound, Outbound outbound) {

    /**
     * Rejects an absent {@code file.inbound} or {@code file.outbound} group and keeps both unchanged.
     *
     * @param inbound  the bound {@code file.inbound} group
     * @param outbound the bound {@code file.outbound} group
     * @throws IllegalArgumentException naming {@code file.inbound} or {@code file.outbound} when that group
     *                                  is absent
     */
    public FileEndpointProperties {
        requirePresent(inbound, "file.inbound");
        requirePresent(outbound, "file.outbound");
    }

    /**
     * Rejects an absent group.
     *
     * @param group the bound group
     * @param key   the full configuration key of {@code group}
     * @throws IllegalArgumentException naming {@code key} when {@code group} is {@code null}
     */
    private static void requirePresent(Object group, String key) {
        if (group == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
    }

    /**
     * Rejects a directory that is absent or whose path text is empty or blank.
     *
     * @param directory the bound directory
     * @param key       the full configuration key of {@code directory}
     * @throws IllegalArgumentException naming {@code key} when {@code directory} is rejected
     */
    private static void requireDirectory(Path directory, String key) {
        if (directory == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
        if (directory.toString().isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
    }

    /**
     * Rejects a duration that is absent, zero or negative.
     *
     * @param value the bound duration
     * @param key   the full configuration key of {@code value}
     * @throws IllegalArgumentException naming {@code key} when {@code value} is rejected
     */
    private static void requirePositive(Duration value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(key + " must be a positive duration");
        }
    }

    /**
     * Rejects a file name that is absent or blank.
     *
     * @param value the bound file name
     * @param key   the full configuration key of {@code value}
     * @throws IllegalArgumentException naming {@code key} when {@code value} is rejected
     */
    private static void requireText(String value, String key) {
        if (value == null) {
            throw new IllegalArgumentException(key + " must be set");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " must not be blank");
        }
    }

    /**
     * The input directory and poll interval of the OrderFlow file inbound endpoint
     * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:8]. The endpoint's
     * {@code responseTimeout} has no component (D-092). The compact constructor rejects an absent or blank
     * {@code path} and an absent, zero or negative {@code pollingFrequency}, naming the key (D-332).
     *
     * @param path             {@code file.inbound.path}: the directory polled for order files, as a
     *                         file-system path relative to the working directory unless absolute
     * @param pollingFrequency {@code file.inbound.polling-frequency}: the delay between two polls; a value
     *                         without a unit suffix is read as milliseconds
     */
    public record Inbound(Path path, @DurationUnit(ChronoUnit.MILLIS) Duration pollingFrequency) {

        /**
         * Validates both components in declaration order and keeps each value unchanged.
         *
         * @param path             the bound {@code file.inbound.path}
         * @param pollingFrequency the bound {@code file.inbound.polling-frequency}
         * @throws IllegalArgumentException naming {@code file.inbound.path} when it is absent or blank, or
         *                                  {@code file.inbound.polling-frequency} when it is absent, zero
         *                                  or negative
         */
        public Inbound {
            requireDirectory(path, "file.inbound.path");
            requirePositive(pollingFrequency, "file.inbound.polling-frequency");
        }
    }

    /**
     * The output directory and the two output file names of the OrderFlow file outbound endpoints
     * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:39,42]. Both endpoints share
     * {@link #path()}, and their {@code responseTimeout} has no component (D-260). The compact constructor
     * rejects an absent or blank {@code path}, {@code ordersOutputPattern} or {@code reportOutputPattern},
     * naming the key (D-332).
     *
     * @param path                {@code file.outbound.path}: the directory both output files are written
     *                            to, as a file-system path relative to the working directory unless absolute
     * @param ordersOutputPattern {@code file.outbound.orders-output-pattern}: the file name of the orders
     *                            JSON [books.xml:39]
     * @param reportOutputPattern {@code file.outbound.report-output-pattern}: the file name of the CSV
     *                            report [books.xml:42]
     */
    public record Outbound(Path path, String ordersOutputPattern, String reportOutputPattern) {

        /**
         * Validates every component in declaration order and keeps each value unchanged.
         *
         * @param path                the bound {@code file.outbound.path}
         * @param ordersOutputPattern the bound {@code file.outbound.orders-output-pattern}
         * @param reportOutputPattern the bound {@code file.outbound.report-output-pattern}
         * @throws IllegalArgumentException naming {@code file.outbound.path},
         *                                  {@code file.outbound.orders-output-pattern} or
         *                                  {@code file.outbound.report-output-pattern} when it is absent or
         *                                  blank
         */
        public Outbound {
            requireDirectory(path, "file.outbound.path");
            requireText(ordersOutputPattern, "file.outbound.orders-output-pattern");
            requireText(reportOutputPattern, "file.outbound.report-output-pattern");
        }
    }
}
