/**
 * MuleSoft Examples
 * Copyright 2014 MuleSoft, Inc.
 *
 * This product includes software developed at
 * MuleSoft, Inc. (http://www.mulesoft.com/).
 */

package com.mulesoft.examples.rest_api_with_apikit.model;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-memory La Liga league: the teams loaded from the classpath resource {@code teams.json} and the
 * fixture of every home-and-away match between them.
 * <p>
 * {@link #initialize()} loads the teams and builds the fixture. The first round of matches is dated
 * seven and six days before today and carries random scores already applied to the team standings; the
 * second round is dated one day before and one day after today and has no scores. Every match starts at
 * 18:00:00.000 local time.
 * <p>
 * Teams and matches are keyed in {@link HashMap}s: a team by its id, a match by
 * {@code <homeTeamId>:<awayTeamId>}. The lists this class returns are new copies in the maps' iteration
 * order. The class holds mutable state and performs no synchronization.
 */
public class League {

    private static final Logger LOGGER = LoggerFactory.getLogger(League.class);

    private static final int MAX_RANDOM_SCORE = 5;
    private static final int MATCH_HOUR = 18;

    private HashMap<String, Team> teams = new HashMap<String, Team>();
    private HashMap<String, Match> fixture = new HashMap<String, Match>();

    /**
     * Loads the teams of the {@code teams} array in the classpath resource {@code teams.json}, in array
     * order, and then builds the fixture.
     * <p>
     * Any failure (resource missing or unreadable, root without a {@code teams} array, an element that is
     * not an object, a team property that is neither a string nor null) is logged at ERROR as
     * {@code Error initializing league. Cause: } with the exception, and not thrown. Teams added before the
     * failure stay in the league, and the fixture is not built (D-581).
     */
    public void initialize() {
        try (InputStream stream = this.getClass().getClassLoader().getResourceAsStream("teams.json")) {
            JsonNode root = new ObjectMapper().readTree(stream);
            JsonNode teams = root.get("teams");
            if(teams == null || !teams.isArray()) {
                throw new IllegalStateException("teams.json has no \"teams\" array");
            }
            for(JsonNode team : teams) {
                addTeam(team);
            }
            buildFixture();

        } catch (Exception e) {
            LOGGER.error("Error initializing league. Cause: ", e);
        }
    }

    /**
     * Builds one match for every ordered pair of distinct teams. The first match of a pair is a first-round
     * match: it alternates between the two first-round dates, receives a random away score and then a random
     * home score, and its result is applied to both teams. The reverse match is a second-round match: it
     * alternates between the two second-round dates and has no scores.
     */
    private void buildFixture() {
        Date firstRoundFirstDate = getDate(-7);
        Date firstRoundSecondDate = getDate(-6);
        Date secondRoundFirstDate = getDate(-1);
        Date secondRoundSecondDate = getDate(1);
        int firstRoundGames = 0;
        int secondRoundGames = 0;

        for(Team homeTeam : teams.values()) {
            for(Team awayTeam : teams.values()) {
                if(!homeTeam.equals(awayTeam)) {
                    Match match = new Match();
                    match.setHomeTeam(homeTeam);
                    match.setAwayTeam(awayTeam);
                    if(!playedFirstRound(homeTeam, awayTeam)) {
                        match.setDate((firstRoundGames % 2 == 0)? firstRoundFirstDate : firstRoundSecondDate);
                        match.setAwayTeamScore(generateRandomScore());
                        match.setHomeTeamScore(generateRandomScore());
                        match.updateResult();
                        firstRoundGames++;
                    } else {
                        match.setDate((secondRoundGames % 2 == 0)? secondRoundFirstDate : secondRoundSecondDate);
                        secondRoundGames++;
                    }

                    fixture.put(generateFixtureId(homeTeam.getId(), awayTeam.getId()), match);
                }
            }
        }
    }

    /**
     * Returns today shifted by {@code shiftDays} days, at {@code MATCH_HOUR}:00:00.000 in the default time
     * zone.
     */
    private Date getDate(int shiftDays) {
        Calendar date = GregorianCalendar.getInstance();
        date.add(GregorianCalendar.DATE, shiftDays);
        date.set(GregorianCalendar.HOUR_OF_DAY, MATCH_HOUR);
        date.set(GregorianCalendar.MINUTE, 0);
        date.set(GregorianCalendar.SECOND, 0);
        date.set(GregorianCalendar.MILLISECOND, 0);
        return date.getTime();
    }

    /**
     * Returns whether the fixture already holds the reverse match, with {@code awayTeam} at home.
     */
    private boolean playedFirstRound(Team homeTeam, Team awayTeam) {
        return fixture.containsKey(generateFixtureId(awayTeam.getId(), homeTeam.getId()));
    }

    /**
     * Returns the fixture key {@code <homeTeam>:<awayTeam>}.
     */
    private String generateFixtureId(String homeTeam, String awayTeam) {
        return homeTeam + ":" + awayTeam;
    }

    /**
     * Returns the match between the given home and away team ids, or {@code null} when the fixture has none.
     *
     * @param homeTeam id of the home team
     * @param awayTeam id of the away team
     * @return the match, or {@code null}
     */
    public Match getMatch(String homeTeam, String awayTeam) {
        return fixture.get(generateFixtureId(homeTeam, awayTeam));
    }

    /**
     * Returns whether the fixture holds a match between the given home and away team ids.
     *
     * @param homeTeam id of the home team
     * @param awayTeam id of the away team
     * @return {@code true} when the match exists
     */
    public boolean hasMatch(String homeTeam, String awayTeam) {
        return getMatch(homeTeam, awayTeam) != null;
    }

    /**
     * Returns a new list holding every match of the fixture.
     *
     * @return the matches, in fixture iteration order
     */
    public List<Match> getMatches() {
        List<Match> matches = new ArrayList<Match>();
        matches.addAll(fixture.values());
        return matches;
    }

    /**
     * Returns a random score from 0 to {@code MAX_RANDOM_SCORE - 1}, drawn from a new {@link Random}.
     */
    private int generateRandomScore() {
        return new Random().nextInt(MAX_RANDOM_SCORE);
    }

    /**
     * Adds the team read from one element of the {@code teams} array: its {@code id}, {@code name},
     * {@code homeCity} and {@code stadium} properties, each {@code null} when absent or JSON null. The team
     * is keyed by its id and replaces any team with the same id.
     *
     * @throws IllegalStateException when the element is not a JSON object or a property is neither a string
     *                               nor null; no team is added
     */
    private void addTeam(JsonNode jsonObject) {
        if(!jsonObject.isObject()) {
            throw new IllegalStateException("teams.json team entry is not an object: " + jsonObject.getNodeType());
        }
        Team team = new Team();
        team.setId(text(jsonObject, "id"));
        team.setName(text(jsonObject, "name"));
        team.setHomeCity(text(jsonObject, "homeCity"));
        team.setStadium(text(jsonObject, "stadium"));
        this.teams.put(team.getId(), team);
    }

    /**
     * Returns the string value of property {@code key}, or {@code null} when the property is absent or JSON
     * null.
     *
     * @throws IllegalStateException when the property holds a value that is not a string
     */
    private String text(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if(value == null || value.isNull()) {
            return null;
        }
        if(!value.isTextual()) {
            throw new IllegalStateException("teams.json team property \"" + key + "\" is not a string: "
                    + value.getNodeType());
        }
        return value.textValue();
    }

    /**
     * Returns a new list holding every team of the league.
     *
     * @return the teams, in team-map iteration order
     */
    public List<Team> getTeams() {
        List<Team> teams = new ArrayList<Team>();
        teams.addAll(this.teams.values());
        return teams;
    }

    /**
     * Returns a new list of the teams whose home city equals {@code homeCity} (case-sensitive), or every
     * team when {@code homeCity} is {@code null}.
     *
     * @param homeCity home city to match, or {@code null} for every team
     * @return the matching teams, in team-map iteration order
     * @throws NullPointerException when {@code homeCity} is not {@code null} and a team has no home city
     */
    public List<Team> getTeams(String homeCity) {
        List<Team> teams = getTeams();
        if(homeCity == null) {
            return teams;
        }

        List<Team> teamsByCity = new ArrayList<Team>();
        for(Team team : teams) {
            if(team.getHomeCity().equals(homeCity)) {
                teamsByCity.add(team);
            }
        }
        return teamsByCity;
    }

    /**
     * Returns whether the league holds a team with the given id.
     *
     * @param id team id
     * @return {@code true} when the team exists
     */
    public boolean hasTeam(String id) {
        return teams.containsKey(id);
    }

    /**
     * Returns the team with the given id, or {@code null} when the league has none.
     *
     * @param id team id
     * @return the team, or {@code null}
     */
    public Team getTeam(String id) {
        return teams.get(id);
    }

    /**
     * Adds the team keyed by its id, replacing any team with the same id. The fixture is not changed.
     *
     * @param team team to add
     */
    public void addTeam(Team team) {
        teams.put(team.getId(), team);
    }

    /**
     * Removes the team with the given id and every match it plays at home or away. The result of each
     * removed match that has both scores is reverted on both of its teams.
     *
     * @param id id of the team to remove
     */
    public void deleteTeam(String id) {
        List<Match> matches = getMatches();
        for(Match match : matches) {
            if(id.equals(match.getHomeTeam().getId()) || id.equals(match.getAwayTeam().getId())) {
                match.revertResult();
                fixture.remove(generateFixtureId(match.getHomeTeam().getId(), match.getAwayTeam().getId()));
            }
        }
        teams.remove(id);
    }

    /**
     * Returns a new list of every team ordered by points, then goal difference, then goals in favor, each
     * descending. Teams equal on all three keep the order of {@link #getTeams()}.
     *
     * @return the teams in standings order
     */
    public List<Team> orderTeamsByPosition() {
        List<Team> teams = getTeams();
        Collections.sort(teams, new Comparator<Team>() {
            @Override
            public int compare(Team team, Team team2) {
                int byPoints = Integer.valueOf(team2.getPoints()).compareTo(team.getPoints());
                if(byPoints != 0) {
                    return byPoints;
                }

                int byGoalDifference = Integer.valueOf(team2.getGoalsInFavor() - team2.getGoalsAgainst())
                        .compareTo(team.getGoalsInFavor() - team.getGoalsAgainst());

                if(byGoalDifference != 0) {
                    return byGoalDifference;
                }

                return Integer.valueOf(team2.getGoalsInFavor()).compareTo(team.getGoalsInFavor());
            }
        });
        return teams;
    }

}
