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
 * <p>The record declares no defaults: every value comes from the bound keys, and a key that is absent
 * binds {@code null}.
 *
 * @param inbound  the {@code file.inbound.*} settings of the file inbound endpoint
 * @param outbound the {@code file.outbound.*} settings of the two file outbound endpoints
 */
@ConfigurationProperties("file")
public record FileEndpointProperties(Inbound inbound, Outbound outbound) {

    /**
     * The input directory and poll interval of the OrderFlow file inbound endpoint
     * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:8]. The endpoint's
     * {@code responseTimeout} has no component (D-092).
     *
     * @param path             {@code file.inbound.path}: the directory polled for order files, as a
     *                         file-system path relative to the working directory unless absolute
     * @param pollingFrequency {@code file.inbound.polling-frequency}: the delay between two polls; a value
     *                         without a unit suffix is read as milliseconds
     */
    public record Inbound(Path path, @DurationUnit(ChronoUnit.MILLIS) Duration pollingFrequency) {
    }

    /**
     * The output directory and the two output file names of the OrderFlow file outbound endpoints
     * [processing-orders-with-dataweave-and-APIkit/src/main/app/books.xml:39,42]. Both endpoints share
     * {@link #path()}, and their {@code responseTimeout} has no component (D-260).
     *
     * @param path                {@code file.outbound.path}: the directory both output files are written
     *                            to, as a file-system path relative to the working directory unless absolute
     * @param ordersOutputPattern {@code file.outbound.orders-output-pattern}: the file name of the orders
     *                            JSON [books.xml:39]
     * @param reportOutputPattern {@code file.outbound.report-output-pattern}: the file name of the CSV
     *                            report [books.xml:42]
     */
    public record Outbound(Path path, String ordersOutputPattern, String reportOutputPattern) {
    }
}
