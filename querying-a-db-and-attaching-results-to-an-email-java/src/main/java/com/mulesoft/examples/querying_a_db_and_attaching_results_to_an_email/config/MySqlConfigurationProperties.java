package com.mulesoft.examples.querying_a_db_and_attaching_results_to_an_email.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JDBC connection settings bound from {@code jdbc.*}; {@code url} is the connection string of the
 * {@code MySQL_Configuration} database configuration (D-012).
 *
 * <p>Source: {@code querying-a-db-and-attaching-results-to-an-email/src/main/app/attachments.xml:5}, the
 * global element {@code db:generic-config} named {@code MySQL_Configuration} with
 * {@code url="${jdbc.url}"}.
 *
 * <p>Binding:
 * <ul>
 *   <li>The record is bound through its canonical constructor and registered by the main class's
 *       {@code @ConfigurationPropertiesScan}; it is not a component.</li>
 *   <li>The record declares no default URL. The committed {@code application.yml} holds the placeholder
 *       {@code TODO} for {@code jdbc.url}, which binds as the text {@code TODO}; an absent key binds
 *       {@code null}. Binding never fails for either value.</li>
 *   <li>Under relaxed binding the environment variable {@code JDBC_URL} sets the same key.</li>
 * </ul>
 *
 * <p>Example: {@code --jdbc.url=jdbc:mysql://localhost:3306/company?user=<user>&password=<password>}
 * binds that exact text, returned unchanged by {@link #url()}.
 *
 * <p>{@code equals}, {@code hashCode} and {@code toString} are the record's generated members;
 * {@code toString} prints the URL as bound, including any user and password the URL carries.
 *
 * @param url JDBC URL from {@code jdbc.url}.
 */
@ConfigurationProperties("jdbc")
public record MySqlConfigurationProperties(String url) {
}
