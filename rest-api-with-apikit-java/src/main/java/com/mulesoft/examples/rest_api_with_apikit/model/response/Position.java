/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model.response;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row of the league standings returned by {@code GET /api/positions}.
 *
 * <p>Jackson writes the nine properties in field order: {@code position}, {@code team},
 * {@code points}, {@code matchesPlayed}, {@code matchesWon}, {@code matchesDraw},
 * {@code matchesLost}, {@code goalsInFavor}, {@code goalsAgainst}. A {@code null} team is written
 * as {@code null}. The class carries no JAXB annotations (D-043).
 */
@JsonAutoDetect
public class Position {

    private int position;
    private String team;
    private int points;
    private int matchesPlayed;
    private int matchesWon;
    private int matchesDraw;
    private int matchesLost;
    private int goalsInFavor;
    private int goalsAgainst;

    @JsonProperty
    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    @JsonProperty
    public String getTeam() {
        return team;
    }

    public void setTeam(String team) {
        this.team = team;
    }

    @JsonProperty
    public int getPoints() {
        return points;
    }

    public void setPoints(int points) {
        this.points = points;
    }

    @JsonProperty
    public int getMatchesPlayed() {
        return matchesPlayed;
    }

    public void setMatchesPlayed(int matchesPlayed) {
        this.matchesPlayed = matchesPlayed;
    }

    @JsonProperty
    public int getMatchesWon() {
        return matchesWon;
    }

    public void setMatchesWon(int matchesWon) {
        this.matchesWon = matchesWon;
    }

    @JsonProperty
    public int getMatchesDraw() {
        return matchesDraw;
    }

    public void setMatchesDraw(int matchesDraw) {
        this.matchesDraw = matchesDraw;
    }

    @JsonProperty
    public int getMatchesLost() {
        return matchesLost;
    }

    public void setMatchesLost(int matchesLost) {
        this.matchesLost = matchesLost;
    }

    @JsonProperty
    public int getGoalsInFavor() {
        return goalsInFavor;
    }

    public void setGoalsInFavor(int goalsInFavor) {
        this.goalsInFavor = goalsInFavor;
    }

    @JsonProperty
    public int getGoalsAgainst() {
        return goalsAgainst;
    }

    public void setGoalsAgainst(int goalsAgainst) {
        this.goalsAgainst = goalsAgainst;
    }
}
