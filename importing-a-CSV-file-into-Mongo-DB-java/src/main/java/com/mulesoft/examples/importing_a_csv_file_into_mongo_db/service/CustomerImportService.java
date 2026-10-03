package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.service;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import com.mulesoft.examples.importing_a_csv_file_into_mongo_db.mapper.CustomerCsvMapper;

/**
 * Runs flow {@code csv-to-mongodbFlow1}
 * [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:8-40]: maps the CSV to
 * documents, logs them, creates the {@code customers} collection when it is absent, and inserts
 * each document into it.
 *
 * <p>{@code scheduler.CsvFilePoller.csvToMongodbFlow1(Path)} reads each polled file and passes its
 * bytes to {@link #csvToMongodbFlow1(byte[])}. The collection lives in the database the injected
 * {@link MongoTemplate} is bound to, {@code spring.data.mongodb.database}, which takes the value of
 * the {@code database.name} key of {@code mongo:config} [csv-to-mongodb.xml:4]. The MongoDB library
 * is D-063; the mapping of {@code mongo:save-object-from-map} to {@code insert} is D-411.
 *
 * <p>The class holds no mutable state: its fields are the logger and the two injected
 * collaborators, and each call works on its own document list.
 *
 * <p>Example, for the committed {@code input.csv}:
 * <pre>{@code
 * service.csvToMongodbFlow1(
 *         "firstname,surname,phone,email\r\nJohn,Doe,096548763,john.doe@texasComp.com"
 *                 .getBytes(StandardCharsets.UTF_8));
 * // INFO saving objects: [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]
 * // collection absent:  customers is created
 * // collection present: INFO Customer Collection already exists.
 * // customers then holds one more document
 * }</pre>
 */
@Service
public class CustomerImportService {

    /** Writes the INFO lines of the loggers at csv-to-mongodb.xml:23 and :32. */
    private static final Logger log = LoggerFactory.getLogger(CustomerImportService.class);

    /** The collection named at csv-to-mongodb.xml:25, :29 and :36. */
    static final String CUSTOMERS_COLLECTION = "customers";

    /** Maps the CSV bytes to documents (DW-12). */
    private final CustomerCsvMapper mapper;

    /** Checks for, creates and writes to the {@code customers} collection. */
    private final MongoTemplate mongoTemplate;

    /**
     * Creates the service over its two collaborators.
     *
     * @param mapper        the DW-12 mapper that turns the CSV bytes into documents
     * @param mongoTemplate the template bound to the {@code database.name} database
     */
    public CustomerImportService(CustomerCsvMapper mapper, MongoTemplate mongoTemplate) {
        this.mapper = mapper;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * Runs flow {@code csv-to-mongodbFlow1} [csv-to-mongodb.xml:8-40] over the content of one
     * polled CSV file.
     *
     * <p>The steps run in the order of the flow:
     * <ol>
     *   <li>DW-12 (:11-20): {@link CustomerCsvMapper#toDocuments(byte[])} maps {@code csv} to one
     *       document per record, each holding {@code firstname}, {@code surname}, {@code phone}
     *       and {@code email}.</li>
     *   <li>The logger (:23) writes {@code saving objects: <documents>} at INFO, the list rendered
     *       by its {@code toString()}, for example
     *       {@code saving objects: [{firstname=John, surname=Doe, phone=096548763, email=john.doe@texasComp.com}]}.</li>
     *   <li>The enricher (:24-26) sets {@code existsCollection} to whether the {@code customers}
     *       collection exists.</li>
     *   <li>The choice (:27-34): when {@code existsCollection} is false (:28), the
     *       {@code customers} collection is created (:29); otherwise (:31) the logger (:32)
     *       writes {@code Customer Collection already exists.} at INFO.</li>
     *   <li>The foreach (:35-39) inserts each document into {@code customers} (:36-37), one
     *       insert per document, in list order. Each insert adds the generated {@code _id} to the
     *       inserted map.</li>
     * </ol>
     *
     * <p>A file with no records still runs the enricher and the choice, and inserts nothing. Every
     * exception thrown by the mapper or by {@link MongoTemplate} leaves this method unchanged;
     * documents inserted before the failure stay in the collection.
     *
     * @param csv the content of the polled CSV file, header row first
     * @throws NullPointerException if {@code csv} is {@code null}, thrown by the mapper
     * @throws java.io.UncheckedIOException if the CSV is malformed, thrown by the mapper
     * @throws IllegalArgumentException if the CSV header holds an empty column name, thrown by the
     *     mapper
     * @throws org.springframework.dao.DataAccessException if a MongoDB operation fails
     */
    public void csvToMongodbFlow1(byte[] csv) {
        // dw:transform-message "CSV to Map" (:10-21), DW-12 (:11-20).
        List<Map<String, Object>> payload = mapper.toDocuments(csv);

        // logger "Log objects to save" (:23).
        log.info("saving objects: {}", payload);

        // enricher "Save result to flowVars.existsCollection" (:24-26) over mongo:exists-collection (:25).
        boolean existsCollection = mongoTemplate.collectionExists(CUSTOMERS_COLLECTION);

        // choice "Should a collection be created?" (:27-34).
        if (!existsCollection) {
            // when #[!flowVars['existsCollection']] (:28): mongo:create-collection (:29).
            mongoTemplate.createCollection(CUSTOMERS_COLLECTION);
        } else {
            // otherwise (:31): logger "Log that a collection exists" (:32).
            log.info("Customer Collection already exists.");
        }

        // foreach (:35-39): mongo:save-object-from-map with element-attributes #[payload] (:36-37).
        for (Map<String, Object> document : payload) {
            mongoTemplate.insert(document, CUSTOMERS_COLLECTION);
        }
    }
}
