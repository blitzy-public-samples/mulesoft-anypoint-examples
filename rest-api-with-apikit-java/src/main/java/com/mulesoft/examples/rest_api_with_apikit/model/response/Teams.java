/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model.response;

import java.util.List;

/**
 * Wrapper of the team list returned by {@code GET /teams}: one {@link Team} per league team
 * matching the optional {@code city} query parameter.
 * <p>
 * Jackson exposes the list as the property {@code teams} through {@link #getTeams()}. The class
 * holds data only: JSON rendering is done by {@code mapper.LeagueResponseMapper}, and no XML
 * binding or XML rendering is carried (D-043).
 */
public class Teams {
    private List<Team> teams;

    /**
     * Returns the team list, written as the JSON property {@code teams}.
     *
     * @return the list held by this wrapper, or {@code null} when none was set
     */
    public List<Team> getTeams() {
        return teams;
    }

    /**
     * Replaces the team list.
     *
     * @param teams the list to hold; stored as given, without copying
     */
    public void setTeams(List<Team> teams) {
        this.teams = teams;
    }

}
