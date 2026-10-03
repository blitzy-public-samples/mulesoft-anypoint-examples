package com.mulesoft.examples.legacy_modernization.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ordermgmt.Address;
import org.ordermgmt.Order;
import org.ordermgmt.OrderItem;
import org.ordermgmt.ShippingOrder;
import org.ordermgmt.ShippingOrderConfirmation;
import org.slf4j.LoggerFactory;

import com.mulesoft.examples.legacy_modernization.mapper.ShippingOrderCsvMapper;

/**
 * Unit tests of {@link ShippingOrderFileWriter}, the {@code async} scope of flow
 * {@code Fulfillment_LegacySystemModernization} [legacy-modernization/src/main/app/FufillmentWebService.xml:9-36],
 * run on instances built with {@code new}, with no application context; {@code write} runs on the
 * test thread.
 *
 * <p>The writer's output directory is {@code Output} under a JUnit temporary directory, passed as a
 * {@code String} (D-549), and its clock is fixed at {@code 2014-07-02T12:19:28.123Z} in UTC. The
 * confirmation carries the values of the request [legacy-modernization/src/test/resources/message.xml].
 *
 * <p>The cases cover:
 *
 * <ul>
 *   <li>the file name {@code ShippingOrder-<dd-MM-yy_HH-mm-ss.SSS>.txt} for the clock's time;</li>
 *   <li>creation of the missing output directory;</li>
 *   <li>the file content: the mapper's CSV text as UTF-8 bytes;</li>
 *   <li>replacement of an existing file of the same name;</li>
 *   <li>a mapper failure: one ERROR event, no file, nothing thrown to the caller (D-550).</li>
 * </ul>
 *
 * <p>These tests cover the {@code service} package under the JaCoCo LINE covered ratio rule of at
 * least 0.80 (D-049).
 */
class ShippingOrderFileWriterTest {

    /** Name of the file written at the fixed clock time. */
    private static final String EXPECTED_FILE = "ShippingOrder-02-07-14_12-19-28.123.txt";

    /** The DW-15 CSV text of the message.xml confirmation: the header and three data records. */
    private static final String EXPECTED_CSV = "MSKU,QTY,BillingAddressName,BillingAddressStreet,"
            + "BillingAddrCity,BillingAddrState,BillingAddrCountry,BillingAddrZipCode,"
            + "ShippingAddrName,ShippingAddrStreet,ShippingAddrCity,ShippingAddrState,"
            + "ShippingAddrCountry,ShippingAddrZipCode,ShippingId\n"
            + "1234,500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "6789,1500,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n"
            + "9998,5000,Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,"
            + "Mulesoft,77 Geary St Level 4,San Francisco,CA,USA,94108,1234\n";

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2014-07-02T12:19:28.123Z"), ZoneOffset.UTC);

    private final ShippingOrderCsvMapper mapper = new ShippingOrderCsvMapper();

    private ShippingOrderFileWriter writer;

    /** Creates the writer over the real mapper, the fixed clock and the directory {@code <dir>/Output}. */
    @BeforeEach
    void setUp() {
        writer = new ShippingOrderFileWriter(mapper, clock, dir.resolve("Output").toString());
    }

    /** The file name for the clock's time is {@code ShippingOrder-02-07-14_12-19-28.123.txt}. */
    @Test
    void fileNameUsesClock() {
        assertThat(writer.fileName(LocalDateTime.now(clock))).isEqualTo(EXPECTED_FILE);
    }

    /** {@code write} creates the absent output directory. */
    @Test
    void createsMissingDirectory() {
        Path output = dir.resolve("Output");
        assertThat(output).doesNotExist();

        writer.write(confirmation());

        assertThat(output).isDirectory();
    }

    /**
     * {@code write} leaves exactly one regular file, named for the clock's time, whose bytes are the
     * UTF-8 encoding of the mapper's CSV text and of the expected DW-15 text.
     */
    @Test
    void writesMapperOutputAsUtf8() throws IOException {
        Path output = dir.resolve("Output");

        writer.write(confirmation());

        List<Path> files = listFiles(output);
        assertThat(files).hasSize(1);
        Path file = files.get(0);
        assertThat(file).isRegularFile();
        assertThat(file.getFileName()).hasToString(EXPECTED_FILE);
        byte[] bytes = Files.readAllBytes(file);
        assertThat(bytes).isEqualTo(mapper.toCsv(confirmation()).getBytes(StandardCharsets.UTF_8));
        assertThat(bytes).isEqualTo(EXPECTED_CSV.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code write} replaces the content of an existing file of the same name and adds no other file. */
    @Test
    void replacesExistingFile() throws IOException {
        Path output = Files.createDirectories(dir.resolve("Output"));
        Path file = Files.writeString(output.resolve(EXPECTED_FILE), "stale", StandardCharsets.UTF_8);

        writer.write(confirmation());

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(EXPECTED_CSV);
        assertThat(listFiles(output)).containsExactly(file);
    }

    /**
     * A mapper failure in {@code write} is not thrown to the caller, leaves the output directory
     * absent or empty, and logs exactly one ERROR event under the writer's logger (D-550).
     */
    @Test
    void mapperFailureLogsAndWritesNothing() throws IOException {
        ShippingOrderCsvMapper failingMapper = mock(ShippingOrderCsvMapper.class);
        when(failingMapper.toCsv(any())).thenThrow(new IllegalArgumentException("boom"));
        ShippingOrderFileWriter failingWriter =
                new ShippingOrderFileWriter(failingMapper, clock, dir.resolve("Output").toString());
        Logger logger = (Logger) LoggerFactory.getLogger(ShippingOrderFileWriter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertDoesNotThrow(() -> failingWriter.write(confirmation()));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        Path output = dir.resolve("Output");
        if (Files.exists(output)) {
            assertThat(listFiles(output)).isEmpty();
        }
        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getLevel()).isEqualTo(Level.ERROR);
    }

    /** Returns the entries of {@code directory}, listed with {@link Files#list(Path)}. */
    private static List<Path> listFiles(Path directory) throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.toList();
        }
    }

    /**
     * Returns the received confirmation of the message.xml request: shipping id {@code 1234}, billing
     * and shipping address {@code Mulesoft}, {@code 77 Geary St}, {@code Level 4}, San Francisco, CA,
     * USA, 94108, and the items {@code 1234}/{@code 500}, {@code 6789}/{@code 1500} and
     * {@code 9998}/{@code 5000} in that order.
     */
    private static ShippingOrderConfirmation confirmation() {
        Order order = new Order();
        order.getOrderItem().add(item("1234", 500));
        order.getOrderItem().add(item("6789", 1500));
        order.getOrderItem().add(item("9998", 5000));
        ShippingOrder shippingOrder = new ShippingOrder();
        shippingOrder.setShippingId("1234");
        shippingOrder.setBillingAddress(address());
        shippingOrder.setShippingAddress(address());
        shippingOrder.setOrder(order);
        ShippingOrderConfirmation confirmation = new ShippingOrderConfirmation();
        confirmation.setShippingOrder(shippingOrder);
        confirmation.setOrderReceivedStatus(true);
        return confirmation;
    }

    /** Returns a new message.xml address: {@code Mulesoft}, {@code 77 Geary St}, {@code Level 4}, San Francisco. */
    private static Address address() {
        Address address = new Address();
        address.setName("Mulesoft");
        address.setLine1("77 Geary St");
        address.setLine2("Level 4");
        address.setCity("San Francisco");
        address.setStateOrProvinceCode("CA");
        address.setCountryCode("USA");
        address.setPostalCode("94108");
        return address;
    }

    /** Returns an order item with the given merchant SKU and quantity. */
    private static OrderItem item(String sku, int quantity) {
        OrderItem item = new OrderItem();
        item.setMerchantSKU(sku);
        item.setQuantity(quantity);
        return item;
    }
}
