package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.CustomerCsvMapper;

/**
 * Unit tests of {@link CustomerImportService#csvToMongodbFlow1(byte[])}, the port of flow
 * {@code csv-to-mongodbFlow1} [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:8-40].
 *
 * <p>Each test runs the service over a real {@link CustomerCsvMapper} (DW-12) and a Mockito mock of
 * {@link MongoTemplate}, with no Spring context and no MongoDB server (D-593; MongoDB calls D-411). A
 * {@link SnapshotAppender} attached to the service's logger at INFO for the duration of each test records the
 * lines of the loggers at csv-to-mongodb.xml:23 and :32.
 */
class CustomerImportServiceTest {

    /** The collection named at csv-to-mongodb.xml:25, :29 and :36. */
    private static final String CUSTOMERS = "customers";

    /** Header row of the committed {@code input.csv}, ended by CRLF. */
    private static final String HEADER = "firstname,surname,phone,email\r\n";

    /** The header, the record of the committed {@code input.csv} and a second record, each ended by CRLF. */
    private static final String TWO_ROWS = HEADER
            + "John,Doe,096548763,john.doe@texasComp.com\r\n"
            + "Jane,Roe,0123456789,jane.roe@example.com\r\n";

    /** The DW-12 document of the John Doe record. */
    private static final Map<String, Object> JOHN = customer("John", "Doe", "096548763", "john.doe@texasComp.com");

    /** The DW-12 document of the Jane Roe record. */
    private static final Map<String, Object> JANE = customer("Jane", "Roe", "0123456789", "jane.roe@example.com");

    /**
     * Exact line of the logger at csv-to-mongodb.xml:23 for {@link #TWO_ROWS}, spelled out in string literals and
     * not built from {@link #JOHN} and {@link #JANE}.
     */
    private static final String SAVING_JOHN_AND_JANE = "saving objects: ["
            + "{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}, "
            + "{firstname=Jane, surname=Roe, phone=0123456789, email=jane.roe@example.com}]";

    /** Line of the logger at csv-to-mongodb.xml:23 for a file with no records. */
    private static final String SAVING_NOTHING = "saving objects: []";

    /** Line of the logger at csv-to-mongodb.xml:32. */
    private static final String COLLECTION_EXISTS = "Customer Collection already exists.";

    /** The mocked template the service checks, creates and writes the {@code customers} collection through. */
    private MongoTemplate mongoTemplate;

    /** The service under test, over a real {@link CustomerCsvMapper} and {@link #mongoTemplate}. */
    private CustomerImportService service;

    /** The Logback logger of {@link CustomerImportService}. */
    private Logger logger;

    /** The level {@link #logger} had before the test; {@code null} when it inherits one. */
    private Level previousLevel;

    /** The events {@link #logger} received during the test, in order. */
    private SnapshotAppender appender;

    /**
     * Creates the mocked template and the service over a real mapper, sets the service's logger to INFO, keeping
     * its previous level ({@code null} when it inherits one), and attaches a started {@link SnapshotAppender}.
     */
    @BeforeEach
    void setUp() {
        mongoTemplate = Mockito.mock(MongoTemplate.class);
        service = new CustomerImportService(new CustomerCsvMapper(), mongoTemplate);
        logger = (Logger) LoggerFactory.getLogger(CustomerImportService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new SnapshotAppender();
        appender.start();
        logger.addAppender(appender);
    }

    /** Detaches and stops the appender, then restores the logger's previous level, {@code null} included. */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /**
     * Collection absent (csv-to-mongodb.xml:28-30): the collection is checked, then created, then each record is
     * inserted into it in record order; only the {@code saving objects:} line is written.
     */
    @Test
    @DisplayName("csvToMongodbFlow1 creates the customers collection when absent and inserts each record in order")
    void createsCustomersCollectionWhenAbsentAndInsertsEachRecordInOrder() {
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(false);

        service.csvToMongodbFlow1(csv(TWO_ROWS));

        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).createCollection(CUSTOMERS);
        order.verify(mongoTemplate).insert(eq(JOHN), eq(CUSTOMERS));
        order.verify(mongoTemplate).insert(eq(JANE), eq(CUSTOMERS));
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.INFO, SAVING_JOHN_AND_JANE));
        assertThat(appender.list)
                .extracting(ILoggingEvent::getFormattedMessage)
                .doesNotContain(COLLECTION_EXISTS);
    }

    /**
     * Collection present (csv-to-mongodb.xml:31-33): the collection is checked and not created, the otherwise logger
     * writes {@code Customer Collection already exists.} after the {@code saving objects:} line, and each record is
     * inserted in record order.
     */
    @Test
    @DisplayName("csvToMongodbFlow1 logs that the customers collection exists and does not create it")
    void logsExistingCustomersCollectionAndDoesNotCreateIt() {
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(true);

        service.csvToMongodbFlow1(csv(TWO_ROWS));

        verify(mongoTemplate, never()).createCollection(anyString());
        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).insert(eq(JOHN), eq(CUSTOMERS));
        order.verify(mongoTemplate).insert(eq(JANE), eq(CUSTOMERS));
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(
                        tuple(Level.INFO, SAVING_JOHN_AND_JANE),
                        tuple(Level.INFO, COLLECTION_EXISTS));
    }

    /**
     * A header row with no record logs an empty list and inserts nothing, while the collection check and the
     * creation of the absent collection still run (csv-to-mongodb.xml:24-30).
     */
    @Test
    @DisplayName("csvToMongodbFlow1 logs an empty list and inserts nothing for a header-only CSV")
    void logsEmptyListAndInsertsNothingForHeaderOnlyCsv() {
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(false);

        service.csvToMongodbFlow1(csv(HEADER));

        assertThat(appender.list)
                .extracting(ILoggingEvent::getLevel, ILoggingEvent::getFormattedMessage)
                .containsExactly(tuple(Level.INFO, SAVING_NOTHING));
        verify(mongoTemplate, never()).insert(any(Map.class), eq(CUSTOMERS));
        verify(mongoTemplate, never()).insert(anyCollection(), anyString());
        verify(mongoTemplate).collectionExists(CUSTOMERS);
        verify(mongoTemplate).createCollection(CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
    }

    /**
     * A failing insert leaves the method as the same exception instance, and no later record is inserted.
     */
    @Test
    @DisplayName("csvToMongodbFlow1 rethrows an insert failure unchanged")
    void rethrowsInsertFailureUnchanged() {
        RuntimeException failure = new RuntimeException("insert failed");
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(true);
        doThrow(failure).when(mongoTemplate).insert(any(Map.class), eq(CUSTOMERS));

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.csvToMongodbFlow1(csv(TWO_ROWS)));

        assertSame(failure, thrown);
        verify(mongoTemplate).collectionExists(CUSTOMERS);
        verify(mongoTemplate, times(1)).insert(any(Map.class), eq(CUSTOMERS));
        verifyNoMoreInteractions(mongoTemplate);
    }

    /**
     * A new mutable document holding the four DW-12 keys in output order.
     *
     * @param firstname the {@code firstname} value
     * @param surname   the {@code surname} value
     * @param phone     the {@code phone} value
     * @param email     the {@code email} value
     * @return the document with the keys {@code firstname}, {@code surname}, {@code phone} and {@code email}
     */
    private static LinkedHashMap<String, Object> customer(
            String firstname, String surname, String phone, String email) {
        LinkedHashMap<String, Object> document = new LinkedHashMap<>();
        document.put("firstname", firstname);
        document.put("surname", surname);
        document.put("phone", phone);
        document.put("email", email);
        return document;
    }

    /**
     * The CSV content of a polled file.
     *
     * @param text the CSV text, header row first
     * @return the UTF-8 bytes of {@code text}
     */
    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** Captures each event with its message formatted at append time. */
    private static final class SnapshotAppender extends ListAppender<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            event.prepareForDeferredProcessing();
            super.append(event);
        }
    }
}

