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
 * Wrapper of the match list returned by {@code GET /fixture}: one {@link Match} per league match.
 * <p>
 * Jackson exposes the list as the property {@code fixture} through {@link #getFixture()}. The class
 * holds data only: JSON rendering is done by {@code mapper.LeagueResponseMapper}, and no XML
 * binding or XML rendering is carried (D-043).
 */
public class Fixture {
    private List<Match> fixture;

    /**
     * Returns the match list, written as the JSON property {@code fixture}.
     *
     * @return the list held by this wrapper, or {@code null} when none was set
     */
    public List<Match> getFixture() {
        return fixture;
    }

    /**
     * Replaces the match list.
     *
     * @param fixture the list to hold; stored as given, without copying
     */
    public void setFixture(List<Match> fixture) {
        this.fixture = fixture;
    }

}
