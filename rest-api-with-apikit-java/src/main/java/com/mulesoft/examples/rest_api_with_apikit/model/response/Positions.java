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
 * The league standings returned by {@code GET /api/positions}. {@code service.LeagueService}
 * fills it with one {@link Position} per team, in standings order, numbered from 1.
 * <p>
 * The list is exposed as the bean property {@code positions}. The class carries no JSON
 * rendering method and no XML binding (D-043); its JSON form is written by
 * {@code mapper.LeagueResponseMapper}.
 */
public class Positions {
    private List<Position> positions;

    /**
     * Returns the standings rows as set, or {@code null} when none have been set.
     *
     * @return the list of standings rows
     */
    public List<Position> getPositions() {
        return positions;
    }

    /**
     * Stores the given list as the standings rows; the list is kept by reference, not copied.
     *
     * @param positions the standings rows, in standings order
     */
    public void setPositions(List<Position> positions) {
        this.positions = positions;
    }
}
