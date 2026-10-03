package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.csv.CSVException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.CustomerCsvMapper;

/**
 * Unit tests of {@link CustomerImportService#csvToMongodbFlow1(byte[])}, the port of flow
 * {@code csv-to-mongodbFlow1} [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:8-40];
 * MongoDB calls D-411, coverage floor D-049.
 *
 * <p>The service runs over a real {@link CustomerCsvMapper} (DW-12) and a Mockito mock of {@link MongoTemplate},
 * with no Spring context and no MongoDB server; one test replaces the mapper with a mock. A Logback
 * {@link ListAppender} attached to the service's logger for the duration of each test records the INFO lines of
 * the loggers at csv-to-mongodb.xml:23 and :32; the tests assert their text, order, level and logger name.
 */
@ExtendWith(MockitoExtension.class)
class CustomerImportServiceTest {

    /** The collection named at csv-to-mongodb.xml:25, :29 and :36. */
    private static final String CUSTOMERS = "customers";

    /** Header row of the committed {@code input.csv}. */
    private static final String HEADER = "firstname,surname,phone,email";

    /** Record of the committed {@code input.csv}. */
    private static final String JOHN_ROW = "John,Doe,096548763,john.doe@texasComp.com";

    private static final String JANE_ROW = "Jane,Roe,012345678,jane.roe@example.com";

    private static final String MAX_ROW = "Max,Poe,055512345,max.poe@example.com";

    /** {@code toString()} of the document of {@link #JOHN_ROW}. */
    private static final String JOHN_TEXT =
            "{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}";

    /** {@code toString()} of the document of {@link #JANE_ROW}. */
    private static final String JANE_TEXT =
            "{firstname=Jane, surname=Roe, phone=012345678, email=jane.roe@example.com}";

    /** {@code toString()} of the document of {@link #MAX_ROW}. */
    private static final String MAX_TEXT = "{firstname=Max, surname=Poe, phone=055512345, email=max.poe@example.com}";

    /** Message of the logger at csv-to-mongodb.xml:32. */
    private static final String COLLECTION_EXISTS_LOG = "Customer Collection already exists.";

    /** The template the service checks, creates and writes the {@code customers} collection through. */
    @Mock
    MongoTemplate mongoTemplate;

    private CustomerImportService service;

    private Logger logger;

    private Level previousLevel;

    private ListAppender<ILoggingEvent> appender;

    /**
     * Creates the service over a real mapper and the mocked template, and attaches a started {@link ListAppender}
     * to the service's logger, with the logger's level set to INFO. The logger's previous level, {@code null} when
     * it inherits one, is kept for {@link #tearDown()}.
     */
    @BeforeEach
    void setUp() {
        service = new CustomerImportService(new CustomerCsvMapper(), mongoTemplate);
        logger = (Logger) LoggerFactory.getLogger(CustomerImportService.class);
        previousLevel = logger.getLevel();
        logger.setLevel(Level.INFO);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    /** Detaches and stops the appender and restores the logger's previous level. */
    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
    }

    /**
     * Collection absent (csv-to-mongodb.xml:28-30): the {@code saving objects:} line lists both documents before
     * the {@code customers} collection is checked, the collection is created, and each record is then inserted
     * into it as its own four-key {@link LinkedHashMap}, in record order. The otherwise logger writes nothing.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: an absent customers collection is created, then two rows are inserted in order")
    void createsCustomersCollectionWhenAbsentThenInsertsEachRowInRecordOrder() {
        List<Integer> logLinesAtCheck = new ArrayList<>();
        List<Integer> logLinesAtInsert = new ArrayList<>();
        List<Map<String, Object>> insertedDocuments = new ArrayList<>();
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenAnswer(invocation -> {
            logLinesAtCheck.add(appender.list.size());
            return false;
        });
        when(mongoTemplate.insert(ArgumentMatchers.<Map<String, Object>>any(), eq(CUSTOMERS)))
                .thenAnswer(invocation -> {
                    logLinesAtInsert.add(appender.list.size());
                    insertedDocuments.add(invocation.getArgument(0));
                    return invocation.getArgument(0);
                });

        service.csvToMongodbFlow1(utf8(HEADER + "\r\n" + JOHN_ROW + "\r\n" + JANE_ROW));

        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).createCollection(CUSTOMERS);
        order.verify(mongoTemplate).insert(john(), CUSTOMERS);
        order.verify(mongoTemplate).insert(jane(), CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(insertedDocuments).containsExactly(john(), jane());
        for (Map<String, Object> document : insertedDocuments) {
            assertThat(document).isInstanceOf(LinkedHashMap.class);
            assertThat(document.keySet()).containsExactly("firstname", "surname", "phone", "email");
        }
        assertThat(loggedLines()).containsExactly("saving objects: [" + JOHN_TEXT + ", " + JANE_TEXT + "]");
        assertThat(logLinesAtCheck).containsExactly(1);
        assertThat(logLinesAtInsert).containsExactly(1, 1);
    }

    /**
     * Collection present (csv-to-mongodb.xml:31-33): the {@code customers} collection is checked and not created,
     * {@code Customer Collection already exists.} is written after the {@code saving objects:} line and before the
     * first insert, and each record is then inserted in record order.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: a present customers collection is logged, not created; two rows are inserted")
    void logsPresentCustomersCollectionAndInsertsEachRowWithoutCreatingIt() {
        List<Integer> logLinesAtCheck = new ArrayList<>();
        List<Integer> logLinesAtInsert = new ArrayList<>();
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenAnswer(invocation -> {
            logLinesAtCheck.add(appender.list.size());
            return true;
        });
        when(mongoTemplate.insert(ArgumentMatchers.<Map<String, Object>>any(), eq(CUSTOMERS)))
                .thenAnswer(invocation -> {
                    logLinesAtInsert.add(appender.list.size());
                    return invocation.getArgument(0);
                });

        service.csvToMongodbFlow1(utf8(HEADER + "\r\n" + JOHN_ROW + "\r\n" + JANE_ROW));

        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).insert(john(), CUSTOMERS);
        order.verify(mongoTemplate).insert(jane(), CUSTOMERS);
        verify(mongoTemplate, never()).createCollection(anyString());
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly(
                "saving objects: [" + JOHN_TEXT + ", " + JANE_TEXT + "]",
                COLLECTION_EXISTS_LOG);
        assertThat(logLinesAtCheck).containsExactly(1);
        assertThat(logLinesAtInsert).containsExactly(2, 2);
    }

    /**
     * The 72-byte committed {@code input.csv}, a header row ended by CRLF and one record, gives one insert of the
     * John Doe document into the created {@code customers} collection, and the {@code saving objects:} line of
     * the service's class Javadoc example.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: the committed input.csv is inserted as the single John Doe document")
    void importsCommittedSampleInputAsOneJohnDoeDocument() throws IOException {
        byte[] sample;
        try (InputStream in = new ClassPathResource("input.csv").getInputStream()) {
            sample = in.readAllBytes();
        }
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(false);

        service.csvToMongodbFlow1(sample);

        assertThat(sample).hasSize(72);
        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).createCollection(CUSTOMERS);
        order.verify(mongoTemplate).insert(john(), CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly("saving objects: [" + JOHN_TEXT + "]");
    }

    /**
     * With a mocked mapper, the content is passed to {@link CustomerCsvMapper#toDocuments(byte[])} as the same
     * array, and each map instance it returns is passed to {@code insert} as the same instance, in list order,
     * after the collection check. The maps and the list hold the same entries afterwards.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: each document instance the mapper returns is inserted as itself, unchanged")
    void insertsEachMapperDocumentInstanceUnchanged() {
        CustomerCsvMapper mapper = mock(CustomerCsvMapper.class);
        CustomerImportService serviceOverMockedMapper = new CustomerImportService(mapper, mongoTemplate);
        byte[] csv = utf8(HEADER + "\r\n" + JOHN_ROW + "\r\n" + JANE_ROW);
        Map<String, Object> first = john();
        Map<String, Object> second = jane();
        List<Map<String, Object>> documents = new ArrayList<>(List.of(first, second));
        when(mapper.toDocuments(csv)).thenReturn(documents);
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(true);

        serviceOverMockedMapper.csvToMongodbFlow1(csv);

        InOrder order = inOrder(mapper, mongoTemplate);
        order.verify(mapper).toDocuments(same(csv));
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).insert(same(first), eq(CUSTOMERS));
        order.verify(mongoTemplate).insert(same(second), eq(CUSTOMERS));
        verifyNoMoreInteractions(mapper, mongoTemplate);
        assertThat(documents).hasSize(2);
        assertThat(documents.get(0)).isSameAs(first).isEqualTo(john());
        assertThat(documents.get(1)).isSameAs(second).isEqualTo(jane());
        assertThat(loggedLines()).containsExactly(
                "saving objects: [" + JOHN_TEXT + ", " + JANE_TEXT + "]",
                COLLECTION_EXISTS_LOG);
    }

    /**
     * A header row with no record still runs the collection check and creates the absent {@code customers}
     * collection, logs an empty list, and inserts nothing.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: a header-only file creates the absent customers collection and inserts nothing")
    void createsAbsentCollectionAndInsertsNothingForHeaderOnlyFile() {
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(false);

        service.csvToMongodbFlow1(utf8(HEADER + "\r\n"));

        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).createCollection(CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly("saving objects: []");
    }

    /**
     * A file of 0 bytes still runs the collection check, logs an empty list and, with the {@code customers}
     * collection present, {@code Customer Collection already exists.}, and neither creates nor inserts.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: a zero-byte file logs the present customers collection and inserts nothing")
    void logsPresentCollectionAndInsertsNothingForZeroByteFile() {
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(true);

        service.csvToMongodbFlow1(new byte[0]);

        verify(mongoTemplate).collectionExists(CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly("saving objects: []", COLLECTION_EXISTS_LOG);
    }

    /**
     * {@code null} content propagates the mapper's {@link NullPointerException}, with message {@code csv}, before
     * any log line or {@link MongoTemplate} call.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: null content propagates the mapper's NullPointerException, no MongoDB call")
    void propagatesMapperNullPointerExceptionForNullContentWithoutMongoDbCalls() {
        Throwable thrown = catchThrowable(() -> service.csvToMongodbFlow1(null));

        assertThat(thrown).isInstanceOf(NullPointerException.class).hasMessage("csv");
        verifyNoInteractions(mongoTemplate);
        assertThat(appender.list).isEmpty();
    }

    /**
     * A quoted value left unterminated propagates the mapper's {@link UncheckedIOException}, whose cause is a
     * {@link CSVException}, before any log line or {@link MongoTemplate} call.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: an unterminated quote propagates UncheckedIOException, no MongoDB call")
    void propagatesMapperUncheckedIoExceptionForUnterminatedQuoteWithoutMongoDbCalls() {
        byte[] csv = utf8(HEADER + "\r\nJohn,\"Doe,096548763,john.doe@texasComp.com");

        Throwable thrown = catchThrowable(() -> service.csvToMongodbFlow1(csv));

        assertThat(thrown).isInstanceOf(UncheckedIOException.class).hasCauseInstanceOf(CSVException.class);
        verifyNoInteractions(mongoTemplate);
        assertThat(appender.list).isEmpty();
    }

    /**
     * A header row with an empty column name propagates the mapper's {@link IllegalArgumentException} before any
     * log line or {@link MongoTemplate} call.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: an empty header name propagates IllegalArgumentException, no MongoDB call")
    void propagatesMapperIllegalArgumentExceptionForEmptyHeaderNameWithoutMongoDbCalls() {
        byte[] csv = utf8("firstname,,phone,email\r\n" + JOHN_ROW);

        Throwable thrown = catchThrowable(() -> service.csvToMongodbFlow1(csv));

        assertThat(thrown).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(mongoTemplate);
        assertThat(appender.list).isEmpty();
    }

    /**
     * A failing {@code collectionExists} call propagates its exception as the same instance, after the
     * {@code saving objects:} line, with no create and no insert.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: a failing collection check propagates its exception, no create and no insert")
    void propagatesCollectionExistsFailureWithoutCreatingOrInserting() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("collectionExists failed");
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.csvToMongodbFlow1(utf8(HEADER + "\r\n" + JOHN_ROW)));

        assertThat(thrown).isSameAs(failure);
        verify(mongoTemplate).collectionExists(CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly("saving objects: [" + JOHN_TEXT + "]");
    }

    /**
     * A failing {@code createCollection} call for the absent {@code customers} collection propagates its exception
     * as the same instance, with no insert.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: a failing collection creation propagates its exception with no insert")
    void propagatesCreateCollectionFailureWithoutInserting() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("createCollection failed");
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(false);
        when(mongoTemplate.createCollection(CUSTOMERS)).thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.csvToMongodbFlow1(utf8(HEADER + "\r\n" + JOHN_ROW)));

        assertThat(thrown).isSameAs(failure);
        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).createCollection(CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly("saving objects: [" + JOHN_TEXT + "]");
    }

    /**
     * Of three records, the first insert succeeds and the second fails: the failure propagates as the same
     * instance, the second record is not retried, and the third record is not inserted.
     */
    @Test
    @DisplayName("csv-to-mongodbFlow1: a failing second insert propagates its exception and the third row is skipped")
    void propagatesInsertFailureAfterEarlierInsertAndSkipsLaterRows() {
        DuplicateKeyException failure = new DuplicateKeyException("insert failed");
        when(mongoTemplate.collectionExists(CUSTOMERS)).thenReturn(true);
        when(mongoTemplate.insert(ArgumentMatchers.<Map<String, Object>>any(), eq(CUSTOMERS)))
                .thenAnswer(invocation -> invocation.getArgument(0))
                .thenThrow(failure);

        Throwable thrown = catchThrowable(() -> service.csvToMongodbFlow1(
                utf8(HEADER + "\r\n" + JOHN_ROW + "\r\n" + JANE_ROW + "\r\n" + MAX_ROW)));

        assertThat(thrown).isSameAs(failure);
        InOrder order = inOrder(mongoTemplate);
        order.verify(mongoTemplate).collectionExists(CUSTOMERS);
        order.verify(mongoTemplate).insert(john(), CUSTOMERS);
        order.verify(mongoTemplate).insert(jane(), CUSTOMERS);
        verifyNoMoreInteractions(mongoTemplate);
        assertThat(loggedLines()).containsExactly(
                "saving objects: [" + JOHN_TEXT + ", " + JANE_TEXT + ", " + MAX_TEXT + "]",
                COLLECTION_EXISTS_LOG);
    }

    /**
     * The formatted messages the service's logger wrote during the test, in order; each line is asserted to be
     * INFO and to come from the logger of {@link CustomerImportService}.
     */
    private List<String> loggedLines() {
        List<String> lines = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getLoggerName()).isEqualTo(CustomerImportService.class.getName());
            lines.add(event.getFormattedMessage());
        }
        return lines;
    }

    /** The DW-12 document of {@link #JOHN_ROW}. */
    private static Map<String, Object> john() {
        return customer("John", "Doe", "096548763", "john.doe@texasComp.com");
    }

    /** The DW-12 document of {@link #JANE_ROW}. */
    private static Map<String, Object> jane() {
        return customer("Jane", "Roe", "012345678", "jane.roe@example.com");
    }

    /** A new mutable document holding the four DW-12 keys in output order. */
    private static Map<String, Object> customer(String firstname, String surname, String phone, String email) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("firstname", firstname);
        document.put("surname", surname);
        document.put("phone", phone);
        document.put("email", email);
        return document;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
