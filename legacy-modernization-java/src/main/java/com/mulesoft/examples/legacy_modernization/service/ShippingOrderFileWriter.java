package com.mulesoft.examples.legacy_modernization.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;

import org.ordermgmt.ShippingOrderConfirmation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.legacy_modernization.mapper.ShippingOrderCsvMapper;

/**
 * Writes a shipping order confirmation as a CSV text file into the legacy fulfillment directory.
 *
 * <p>Port of the {@code async} scope of flow {@code Fulfillment_LegacySystemModernization}
 * [legacy-modernization/src/main/app/FufillmentWebService.xml:9-36]:
 *
 * <ul>
 *   <li>DW-15 CSV mapping [:10-33] is {@link ShippingOrderCsvMapper#toCsv(ShippingOrderConfirmation)};</li>
 *   <li>{@code byte-array-to-string-transformer} [:34] is the mapper's {@code String} result,
 *       written as UTF-8 text;</li>
 *   <li>{@code file:outbound-endpoint path="src/main/resources/Output"
 *       outputPattern="ShippingOrder-#[server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')].txt"}
 *       [:35] is {@link #write(ShippingOrderConfirmation)} with {@link #fileName(TemporalAccessor)}.
 *       The directory is the property {@code file.legacy-fulfillment.path}, resolved against the
 *       working directory. A file of the same name is overwritten. No time limit applies to the
 *       write (D-105).</li>
 * </ul>
 *
 * <p>{@link #write(ShippingOrderConfirmation)}, called on the Spring bean, runs on the
 * {@code legacyFulfillmentExecutor} executor (D-060, D-313); the caller returns without waiting
 * for the file. The time in the file name comes from the injected {@link Clock}.
 *
 * <p>Usage:
 *
 * <pre>{@code
 * ShippingOrderFileWriter writer = new ShippingOrderFileWriter(
 *         new ShippingOrderCsvMapper(), Clock.systemDefaultZone(), "src/main/resources/Output");
 * writer.write(confirmation);   // src/main/resources/Output/ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt
 * }</pre>
 */
@Service
public class ShippingOrderFileWriter {

    private static final Logger LOG = LoggerFactory.getLogger(ShippingOrderFileWriter.class);

    /** Time pattern of the file name: the MEL {@code server.dateTime.format('dd-MM-yy_HH-mm-ss.SSS')}. */
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("dd-MM-yy_HH-mm-ss.SSS");

    private final ShippingOrderCsvMapper mapper;

    private final Clock clock;

    private final Path directory;

    /**
     * Creates the writer. The directory is bound as a {@code String} and converted with
     * {@link Path#of(String, String...)} (D-549).
     *
     * @param mapper    maps the confirmation to CSV text
     * @param clock     source of the time in the file name
     * @param directory output directory, the property {@code file.legacy-fulfillment.path}; a
     *                  relative path resolves against the working directory
     */
    public ShippingOrderFileWriter(ShippingOrderCsvMapper mapper, Clock clock,
            @Value("${file.legacy-fulfillment.path}") String directory) {
        this.mapper = mapper;
        this.clock = clock;
        this.directory = Path.of(directory);
    }

    /**
     * Returns the output file name for the given time: {@code ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt}
     * [legacy-modernization/src/main/app/FufillmentWebService.xml:35].
     *
     * <p>Example: 2 July 2014, 14:19:28.123 gives {@code ShippingOrder-02-07-14_14-19-28.123.txt}.
     *
     * @param time a date-time carrying day, month, year, hour, minute, second and nano-of-second
     * @return the file name
     * @throws java.time.DateTimeException if {@code time} lacks one of those fields
     */
    public String fileName(TemporalAccessor time) {
        return "ShippingOrder-" + FILE_TIMESTAMP.format(time) + ".txt";
    }

    /**
     * Maps the confirmation to CSV text and writes it, as UTF-8, to the file named by
     * {@link #fileName(TemporalAccessor)} for the current time of the clock, inside the output
     * directory [legacy-modernization/src/main/app/FufillmentWebService.xml:10-35].
     *
     * <p>Called on the Spring bean, runs on the {@code legacyFulfillmentExecutor} executor (D-060);
     * called on an instance built with {@code new}, runs on the calling thread. The output directory
     * and its missing parents are created; an existing file of the same name is overwritten. A failure
     * of the mapping or of the write is logged at ERROR, writes no file and is not passed to the
     * caller (D-550). The confirmation is only read.
     *
     * @param confirmation the confirmation returned to the SOAP caller
     */
    @Async("legacyFulfillmentExecutor")
    public void write(ShippingOrderConfirmation confirmation) {
        try {
            String csv = mapper.toCsv(confirmation);
            Files.createDirectories(directory);
            Path file = directory.resolve(fileName(ZonedDateTime.now(clock)));
            Files.writeString(file, csv, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            LOG.debug("Wrote the legacy fulfillment file {}", file);
        } catch (Exception e) {
            LOG.error("Failed to write the legacy fulfillment file to {}", directory, e);
        }
    }
}
