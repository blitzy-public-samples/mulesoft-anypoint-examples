/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.mapper;

import java.io.UncheckedIOException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Fixture;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Positions;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Team;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;
import org.springframework.stereotype.Component;

/**
 * Writes the league response bodies as JSON.
 * <p>
 * Every method serializes its argument with a new {@link ObjectMapper} in its default
 * configuration and returns the compact JSON text. Property names, property order, the omission
 * of null properties and the {@code date} format come from the annotations of the
 * {@code model.response} classes and from {@link JsonDateSerializer}. No XML rendering is
 * provided (D-043). A serialization failure is thrown as an {@link UncheckedIOException} whose
 * cause is the {@link JsonProcessingException}.
 */
@Component
public class LeagueResponseMapper {

    /**
     * Writes the teams list as JSON with a default ObjectMapper, as {@code {"teams":[...]}}.
     *
     * @param teams the teams wrapper to write
     * @return the compact JSON text
     * @throws UncheckedIOException when Jackson cannot serialize the value
     */
    public String toJson(Teams teams) {
        return write(teams);
    }

    /**
     * Writes one team as a JSON object with a default ObjectMapper.
     *
     * @param team the team to write
     * @return the compact JSON text
     * @throws UncheckedIOException when Jackson cannot serialize the value
     */
    public String toJson(Team team) {
        return write(team);
    }

    /**
     * Writes the match list as JSON with a default ObjectMapper, as {@code {"fixture":[...]}}.
     *
     * @param fixture the fixture wrapper to write
     * @return the compact JSON text
     * @throws UncheckedIOException when Jackson cannot serialize the value
     */
    public String toJson(Fixture fixture) {
        return write(fixture);
    }

    /**
     * Writes one match as a JSON object with a default ObjectMapper.
     *
     * @param match the match to write
     * @return the compact JSON text
     * @throws UncheckedIOException when Jackson cannot serialize the value
     */
    public String toJson(Match match) {
        return write(match);
    }

    /**
     * Writes the standings as JSON with a default ObjectMapper, as {@code {"positions":[...]}}.
     *
     * @param positions the standings wrapper to write
     * @return the compact JSON text
     * @throws UncheckedIOException when Jackson cannot serialize the value
     */
    public String toJson(Positions positions) {
        return write(positions);
    }

    private String write(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
