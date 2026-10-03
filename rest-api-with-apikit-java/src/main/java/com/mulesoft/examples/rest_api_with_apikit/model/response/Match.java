/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model.response;

import java.util.Date;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.mulesoft.examples.rest_api_with_apikit.mapper.JsonDateSerializer;

/**
 * JSON body of one match: the payload of {@code GET /fixture/{homeTeamId}/{awayTeamId}} and each
 * item of the {@code GET /fixture} list.
 * <p>
 * Properties are written in field order as {@code homeTeam}, {@code awayTeam}, {@code date},
 * {@code homeTeamScore} and {@code awayTeamScore}; {@code homeTeam} and {@code awayTeam} hold team
 * ids. A property whose value is null is left out of the JSON: a match not yet played has no
 * {@code homeTeamScore} or {@code awayTeamScore} key, and a match without a date has no
 * {@code date} key. A non-null {@code date} is written by {@link JsonDateSerializer} as a string in
 * the pattern {@code yyyy-MM-dd'T'HH:mm:ssZ} (D-166). The class carries Jackson 2 annotations
 * only; the original's XML binding annotations are not carried (D-043).
 */
@JsonAutoDetect
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Match {

    private String homeTeam;
    private String awayTeam;

    @JsonSerialize(using = JsonDateSerializer.class)
    private Date date;
    private Integer homeTeamScore;
    private Integer awayTeamScore;

    @JsonProperty
    public String getHomeTeam() {
        return homeTeam;
    }

    public void setHomeTeam(String homeTeam) {
        this.homeTeam = homeTeam;
    }

    public String getAwayTeam() {
        return awayTeam;
    }

    public void setAwayTeam(String awayTeam) {
        this.awayTeam = awayTeam;
    }

    public Date getDate() {
        return date;
    }

    public void setDate(Date date) {
        this.date = date;
    }

    public Integer getHomeTeamScore() {
        return homeTeamScore;
    }

    public void setHomeTeamScore(Integer homeTeamScore) {
        this.homeTeamScore = homeTeamScore;
    }

    public Integer getAwayTeamScore() {
        return awayTeamScore;
    }

    public void setAwayTeamScore(Integer awayTeamScore) {
        this.awayTeamScore = awayTeamScore;
    }
}
