package com.mulesoft.examples.rest_api_with_apikit.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Fixture;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Match;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Position;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Positions;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Team;
import com.mulesoft.examples.rest_api_with_apikit.model.response.Teams;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * Unit tests of {@link LeagueResponseMapper}, run without a Spring context. Expected dates are
 * rendered in the test with {@link SimpleDateFormat} in the JVM default time zone.
 */
public class LeagueResponseMapperTest {

    private static final String DATE_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ";

    private static final Pattern DATE_SHAPE =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{4}$");

    private static final long MATCH_TIME = 1389553200000L;

    private static final long NEXT_DAY_TIME = MATCH_TIME + 86_400_000L;

    private static final List<String> MATCH_FIELDS_WITHOUT_SCORES =
            List.of("homeTeam", "awayTeam", "date");

    private static final List<String> MATCH_FIELDS_WITH_SCORES =
            List.of("homeTeam", "awayTeam", "date", "homeTeamScore", "awayTeamScore");

    private static final List<String> POSITION_FIELDS = List.of("position", "team", "points",
            "matchesPlayed", "matchesWon", "matchesDraw", "matchesLost", "goalsInFavor",
            "goalsAgainst");

    private final LeagueResponseMapper mapper = new LeagueResponseMapper();

    private final ObjectMapper reader = new ObjectMapper();

    /**
     * Asserts toJson(Teams) writes the teams wrapper in compact form and leaves out the key of a
     * team whose matches value was never set.
     */
    @Test
    public void toJsonTeamsWritesCompactTeamsWrapperWithoutNullMatches() throws Exception {
        Teams teams = new Teams();
        teams.setTeams(List.of(team("BAR", "Barcelona", "Barcelona", "Camp Nou")));

        String json = mapper.toJson(teams);

        assertEquals("{\"teams\":[{\"id\":\"BAR\",\"name\":\"Barcelona\",\"homeCity\":\"Barcelona\","
                + "\"stadium\":\"Camp Nou\"}]}", json);
        JsonNode element = reader.readTree(json).get("teams").get(0);
        assertFalse(element.has("matches"));
    }

    /**
     * Asserts toJson(Team) writes the team properties in declaration order, ending with the
     * matches count as a JSON integer.
     */
    @Test
    public void toJsonTeamWritesFieldsInDeclarationOrderWithMatches() throws Exception {
        Team team = team("BAR", "Barcelona", "Barcelona", "Camp Nou");
        team.setMatches(24);

        JsonNode root = reader.readTree(mapper.toJson(team));

        assertEquals(List.of("id", "name", "homeCity", "stadium", "matches"), fieldNames(root));
        assertEquals("BAR", root.get("id").textValue());
        assertEquals("Barcelona", root.get("name").textValue());
        assertEquals("Barcelona", root.get("homeCity").textValue());
        assertEquals("Camp Nou", root.get("stadium").textValue());
        assertTrue(root.get("matches").isInt());
        assertEquals(24, root.get("matches").intValue());
    }

    /**
     * Asserts toJson(Match) writes neither score key when both scores are null.
     */
    @Test
    public void toJsonMatchOmitsNullScores() throws Exception {
        String json = mapper.toJson(match("BAR", "RMA", new Date(MATCH_TIME), null, null));

        JsonNode root = reader.readTree(json);

        assertEquals(MATCH_FIELDS_WITHOUT_SCORES, fieldNames(root));
        assertEquals("BAR", root.get("homeTeam").textValue());
        assertEquals("RMA", root.get("awayTeam").textValue());
        assertFalse(json.contains("homeTeamScore"));
        assertFalse(json.contains("awayTeamScore"));
    }

    /**
     * Asserts toJson(Match) writes both scores as JSON integers after the date, a score of 0
     * included.
     */
    @Test
    public void toJsonMatchIncludesScoresWhenSet() throws Exception {
        JsonNode root = reader.readTree(mapper.toJson(match("BAR", "RMA", new Date(MATCH_TIME), 3, 0)));

        assertEquals(MATCH_FIELDS_WITH_SCORES, fieldNames(root));
        assertTrue(root.get("homeTeamScore").isInt());
        assertEquals(3, root.get("homeTeamScore").intValue());
        assertTrue(root.get("awayTeamScore").isInt());
        assertEquals(0, root.get("awayTeamScore").intValue());
    }

    /**
     * Asserts toJson(Match) writes the date as the {@code yyyy-MM-dd'T'HH:mm:ssZ} string of
     * JsonDateSerializer in the JVM default time zone, and writes no date key for a null date.
     */
    @Test
    public void toJsonMatchFormatsDateWithJsonDateSerializer() throws Exception {
        Date d = new Date(MATCH_TIME);
        String expected = new SimpleDateFormat(DATE_PATTERN).format(d);

        JsonNode date = reader.readTree(mapper.toJson(match("BAR", "RMA", d, null, null))).get("date");

        assertTrue(date.isTextual());
        assertEquals(expected, date.textValue());
        assertTrue(DATE_SHAPE.matcher(date.textValue()).matches(), date.textValue());

        JsonNode undated = reader.readTree(mapper.toJson(match("BAR", "RMA", null, null, null)));

        assertFalse(undated.has("date"));
    }

    /**
     * Asserts toJson(Fixture) writes the fixture wrapper with its matches in list order, each with
     * its own date and only the scores that are set.
     */
    @Test
    public void toJsonFixtureWritesFixtureWrapper() throws Exception {
        Date first = new Date(MATCH_TIME);
        Date second = new Date(NEXT_DAY_TIME);
        Fixture fixture = new Fixture();
        fixture.setFixture(List.of(
                match("BAR", "RMA", first, null, null),
                match("ATH", "ATL", second, 1, 1)));

        String json = mapper.toJson(fixture);

        assertTrue(json.startsWith("{\"fixture\":["), json);
        JsonNode root = reader.readTree(json);
        assertEquals(List.of("fixture"), fieldNames(root));
        JsonNode matches = root.get("fixture");
        assertTrue(matches.isArray());
        assertEquals(2, matches.size());

        SimpleDateFormat format = new SimpleDateFormat(DATE_PATTERN);
        JsonNode unplayed = matches.get(0);
        assertEquals(MATCH_FIELDS_WITHOUT_SCORES, fieldNames(unplayed));
        assertEquals("BAR", unplayed.get("homeTeam").textValue());
        assertEquals("RMA", unplayed.get("awayTeam").textValue());
        assertEquals(format.format(first), unplayed.get("date").textValue());

        JsonNode played = matches.get(1);
        assertEquals(MATCH_FIELDS_WITH_SCORES, fieldNames(played));
        assertEquals("ATH", played.get("homeTeam").textValue());
        assertEquals("ATL", played.get("awayTeam").textValue());
        assertEquals(format.format(second), played.get("date").textValue());
        assertEquals(1, played.get("homeTeamScore").intValue());
        assertEquals(1, played.get("awayTeamScore").intValue());
    }

    /**
     * Asserts toJson(Positions) writes the positions wrapper with every row in the key order of
     * the RAML positions example and the row values as set.
     */
    @Test
    public void toJsonPositionsWritesPositionsInExampleKeyOrder() throws Exception {
        Positions positions = new Positions();
        positions.setPositions(List.of(
                position(1, "BAR", 36, 12, 10, 0, 2, 15, 6),
                position(2, "RMA", 34, 12, 11, 1, 2, 14, 3)));

        String json = mapper.toJson(positions);

        assertTrue(json.startsWith("{\"positions\":["), json);
        JsonNode root = reader.readTree(json);
        assertEquals(List.of("positions"), fieldNames(root));
        JsonNode rows = root.get("positions");
        assertEquals(2, rows.size());
        for (JsonNode row : rows) {
            assertEquals(POSITION_FIELDS, fieldNames(row));
        }
        assertPositionRow(rows.get(0), 1, "BAR", 36, 12, 10, 0, 2, 15, 6);
        assertPositionRow(rows.get(1), 2, "RMA", 34, 12, 11, 1, 2, 14, 3);
    }

    /**
     * Asserts toJson(Teams) throws UncheckedIOException wrapping the JsonProcessingException
     * raised when the teams getter fails.
     */
    @Test
    public void toJsonTeamsRethrowsSerializationFailureAsUncheckedIOException() {
        Teams teams = new Teams() {
            @Override
            public List<Team> getTeams() {
                throw new IllegalStateException("teams");
            }
        };

        assertSerializationFailure(() -> mapper.toJson(teams), "teams");
    }

    /**
     * Asserts toJson(Team) throws UncheckedIOException wrapping the JsonProcessingException
     * raised when the id getter fails.
     */
    @Test
    public void toJsonTeamRethrowsSerializationFailureAsUncheckedIOException() {
        Team team = new Team() {
            @Override
            public String getId() {
                throw new IllegalStateException("id");
            }
        };

        assertSerializationFailure(() -> mapper.toJson(team), "id");
    }

    /**
     * Asserts toJson(Fixture) throws UncheckedIOException wrapping the JsonProcessingException
     * raised when the fixture getter fails.
     */
    @Test
    public void toJsonFixtureRethrowsSerializationFailureAsUncheckedIOException() {
        Fixture fixture = new Fixture() {
            @Override
            public List<Match> getFixture() {
                throw new IllegalStateException("fixture");
            }
        };

        assertSerializationFailure(() -> mapper.toJson(fixture), "fixture");
    }

    /**
     * Asserts toJson(Match) throws UncheckedIOException wrapping the JsonProcessingException
     * raised when the homeTeam getter fails.
     */
    @Test
    public void toJsonMatchRethrowsSerializationFailureAsUncheckedIOException() {
        Match match = new Match() {
            @Override
            public String getHomeTeam() {
                throw new IllegalStateException("homeTeam");
            }
        };

        assertSerializationFailure(() -> mapper.toJson(match), "homeTeam");
    }

    /**
     * Asserts toJson(Positions) throws UncheckedIOException wrapping the JsonProcessingException
     * raised when the positions getter fails.
     */
    @Test
    public void toJsonPositionsRethrowsSerializationFailureAsUncheckedIOException() {
        Positions positions = new Positions() {
            @Override
            public List<Position> getPositions() {
                throw new IllegalStateException("positions");
            }
        };

        assertSerializationFailure(() -> mapper.toJson(positions), "positions");
    }

    private void assertSerializationFailure(Executable call, String getterMessage) {
        UncheckedIOException thrown = assertThrows(UncheckedIOException.class, call);
        JsonProcessingException cause = assertInstanceOf(JsonProcessingException.class, thrown.getCause());
        IllegalStateException failure = assertInstanceOf(IllegalStateException.class, cause.getCause());
        assertEquals(getterMessage, failure.getMessage());
    }

    private void assertPositionRow(JsonNode row, int position, String team, int points,
            int matchesPlayed, int matchesWon, int matchesDraw, int matchesLost, int goalsInFavor,
            int goalsAgainst) {
        assertIntField(row, "position", position);
        assertEquals(team, row.get("team").textValue());
        assertIntField(row, "points", points);
        assertIntField(row, "matchesPlayed", matchesPlayed);
        assertIntField(row, "matchesWon", matchesWon);
        assertIntField(row, "matchesDraw", matchesDraw);
        assertIntField(row, "matchesLost", matchesLost);
        assertIntField(row, "goalsInFavor", goalsInFavor);
        assertIntField(row, "goalsAgainst", goalsAgainst);
    }

    private void assertIntField(JsonNode node, String name, int expected) {
        JsonNode value = node.get(name);
        assertTrue(value.isInt(), name);
        assertEquals(expected, value.intValue(), name);
    }

    private Team team(String id, String name, String homeCity, String stadium) {
        Team team = new Team();
        team.setId(id);
        team.setName(name);
        team.setHomeCity(homeCity);
        team.setStadium(stadium);
        return team;
    }

    private Match match(String homeTeam, String awayTeam, Date date, Integer homeTeamScore,
            Integer awayTeamScore) {
        Match match = new Match();
        match.setHomeTeam(homeTeam);
        match.setAwayTeam(awayTeam);
        match.setDate(date);
        match.setHomeTeamScore(homeTeamScore);
        match.setAwayTeamScore(awayTeamScore);
        return match;
    }

    private Position position(int position, String team, int points, int matchesPlayed,
            int matchesWon, int matchesDraw, int matchesLost, int goalsInFavor, int goalsAgainst) {
        Position row = new Position();
        row.setPosition(position);
        row.setTeam(team);
        row.setPoints(points);
        row.setMatchesPlayed(matchesPlayed);
        row.setMatchesWon(matchesWon);
        row.setMatchesDraw(matchesDraw);
        row.setMatchesLost(matchesLost);
        row.setGoalsInFavor(goalsInFavor);
        row.setGoalsAgainst(goalsAgainst);
        return row;
    }

    private List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
