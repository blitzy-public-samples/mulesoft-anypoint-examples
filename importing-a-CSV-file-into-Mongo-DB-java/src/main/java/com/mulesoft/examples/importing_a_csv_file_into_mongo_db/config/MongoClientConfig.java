package com.mulesoft.examples.importing_a_csv_file_into_mongo_db.config;

import org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import com.mongodb.MongoCredential;

/**
 * Applies the {@code username} and {@code password} of the {@code mongo:config} element
 * [importing-a-CSV-file-into-Mongo-DB/src/main/app/csv-to-mongodb.xml:4] to the MongoDB client that
 * Spring Boot auto-configures, through one {@link MongoClientSettingsBuilderCustomizer} (D-516).
 *
 * <p>The values come from {@link MongoProperties}: {@code database.user}, {@code database.password}
 * and, as the authentication database, {@code database.name}. The element's {@code database}
 * attribute itself is the {@code spring.data.mongodb.database} key of {@code application.yml}, which
 * takes the value of {@code database.name}. The committed {@code application.yml} holds placeholder
 * values for all three keys (D-012).
 *
 * <p>The class defines no {@code MongoClient}, database factory, template, connection string or
 * connection details bean, and sets no host, port, authentication mechanism, pool size or timeout:
 * those stay the ones of Boot's auto-configured client, which reaches {@code localhost:27017} when no
 * {@code spring.data.mongodb} address key is set, as the element without host and port did. The
 * element's {@code mongo:connection-pooling-profile} (:5) has no counterpart (D-280).
 *
 * <p>Example: with {@code database.name=customers} and a set {@code database.user}, the client
 * authenticates as that user against the {@code customers} database; with
 * {@code database.user=""}, the value of {@code application-test.yml}, it connects without a
 * credential (D-039).
 */
@Configuration
public class MongoClientConfig {

    /**
     * Adds a MongoDB credential for {@code database.user} against the {@code database.name}
     * authentication database when the user is set; adds none otherwise (D-039, D-516).
     *
     * <p>The returned customizer reads {@code properties} each time it is applied:
     * <ul>
     *   <li>{@code database.user} has text: it sets
     *       {@link MongoCredential#createCredential(String, String, char[])} with the user exactly as
     *       bound, {@code database.name} as the source database and the characters of
     *       {@code database.password}, or no characters when the password is {@code null}. The
     *       credential names no mechanism, and the driver negotiates it with the server. A
     *       {@code null} {@code database.name} makes the driver reject the credential with an
     *       {@link IllegalArgumentException} when the customizer is applied.</li>
     *   <li>{@code database.user} is {@code null}, empty or white space only: it adds no credential
     *       and leaves the builder's credential unchanged.</li>
     * </ul>
     *
     * <p>Boot applies every {@link MongoClientSettingsBuilderCustomizer} bean to the settings builder
     * of its client. Its own customizer applies the connection string built from the
     * {@code spring.data.mongodb} keys; while those keys carry no user, as in the committed
     * {@code application.yml}, the credential set here stays in place whichever of the two runs
     * first.
     *
     * @param properties the bound {@code database.*} keys of {@code mongo:config}
     * @return the customizer that adds the credential to the client settings builder
     */
    @Bean
    public MongoClientSettingsBuilderCustomizer mongoCredentialsCustomizer(MongoProperties properties) {
        return builder -> {
            if (StringUtils.hasText(properties.user())) {
                builder.credential(MongoCredential.createCredential(
                        properties.user(), properties.name(), passwordChars(properties.password())));
            }
        };
    }

    /**
     * Returns the characters of {@code password}, or an empty array when it is {@code null}.
     *
     * @param password the bound {@code database.password}, possibly {@code null}
     * @return a new array holding the password's characters
     */
    private static char[] passwordChars(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }
}
