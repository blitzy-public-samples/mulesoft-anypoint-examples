package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code database.*} keys of the {@code mongo:config} element
 * [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:4]: database name, user and
 * password, by constructor binding from {@code application.yml}.
 *
 * <p>The record declares no defaults: every value comes from the bound keys, and a key that is absent
 * binds {@code null}. In the committed {@code application.yml} all three keys hold placeholder values
 * that the user replaces (D-012).
 *
 * @param name     {@code database.name}: the MongoDB database the imported customers are written to
 *                 (the element's {@code database} attribute)
 * @param user     {@code database.user}: the MongoDB user name (the element's {@code username}
 *                 attribute)
 * @param password {@code database.password}: the MongoDB password (the element's {@code password}
 *                 attribute)
 */
@ConfigurationProperties("database")
public record MongoProperties(String name, String user, String password) {
}
