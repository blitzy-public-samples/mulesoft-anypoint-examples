/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.config;

import com.mulesoft.examples.exposing_a_restful_resource_using_the_http_connector.model.Person;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the person store and the person id counter, the singleton beans {@code personDataStore}
 * and {@code personId} that {@code http-restful-resource.xml} registers at lines 7 and 8.
 *
 * <p>Each bean is named after its method and created once per application context.
 * {@code PersonService} injects both by name: the store holds every person {@code POST /person}
 * accepts, under the id the counter hands out, and {@code GET /person} and
 * {@code GET /person/{personId}} read from it.
 */
@Configuration
public class PersonStoreConfig {

    /**
     * Returns the singleton map of stored persons keyed by person id, bean
     * {@code personDataStore} [http-restful-resource.xml:7].
     *
     * <p>The map is a synchronized view, {@link Collections#synchronizedMap(Map)}, over an empty
     * {@link HashMap} (D-577). Its contents and its iteration order are those of the wrapped
     * {@code HashMap}: it accepts a {@code null} person, and for the ids the counter hands out,
     * {@code 1} upwards, {@code values()} yields the persons in ascending id order while every
     * stored id is below 65,536. Every single call, such as {@code put}, {@code get} or
     * {@code containsKey}, runs while holding the map's monitor; a caller that iterates
     * {@code values()}, {@code keySet()} or {@code entrySet()} holds that monitor for the whole
     * iteration:
     *
     * <pre>{@code
     * List<Person> copy;
     * synchronized (personDataStore) {
     *     copy = new ArrayList<>(personDataStore.values());
     * }
     * }</pre>
     *
     * @return a new, empty, synchronized {@code Map<Integer, Person>} backed by a {@code HashMap}
     */
    @Bean
    public Map<Integer, Person> personDataStore() {
        return Collections.synchronizedMap(new HashMap<>());
    }

    /**
     * Returns the singleton person id counter, bean {@code personId} [http-restful-resource.xml:8].
     *
     * <p>The counter starts at {@code 0}, so the first {@link AtomicInteger#incrementAndGet()}
     * returns {@code 1} and each later call returns the next integer.
     *
     * @return a new {@link AtomicInteger} with the value {@code 0}
     */
    @Bean
    public AtomicInteger personId() {
        return new AtomicInteger();
    }
}
