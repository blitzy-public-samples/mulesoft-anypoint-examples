/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model.response;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON body of one team: the payload of {@code GET /teams/{teamId}} and each item of the
 * {@code GET /teams} list.
 * <p>
 * Properties are written as {@code id}, {@code name}, {@code homeCity}, {@code stadium} and
 * {@code matches}. A property whose value is null is left out of the JSON: a team of the
 * {@code GET /teams} list carries no {@code matches} value and has no {@code matches} key
 * (D-166). The class carries Jackson 2 annotations only; the original's XML binding annotations
 * are not carried (D-043).
 */
@JsonAutoDetect
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Team {

    private String id;
    private String name;
    private String homeCity;
    private String stadium;
    private Integer matches;

    @JsonProperty
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @JsonProperty
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @JsonProperty
    public String getHomeCity() {
        return homeCity;
    }

    public void setHomeCity(String homeCity) {
        this.homeCity = homeCity;
    }

    @JsonProperty
    public String getStadium() {
        return stadium;
    }

    public void setStadium(String stadium) {
        this.stadium = stadium;
    }

    @JsonProperty
    public Integer getMatches() {
        return matches;
    }

    public void setMatches(int matches) {
        this.matches = matches;
    }
}
