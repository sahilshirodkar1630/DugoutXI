package com.sahilprojects.bettingApp.contest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestLeagues;
import com.sahilprojects.bettingApp.TestLeagues.TestLeague;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Importing a league's fixture list in one go: checked row by row, and all or nothing. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FixtureImportTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private final Instant day8 = Instant.now().plus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);

    @Test
    void aDryRunChecksEveryRowAndCreatesNothing() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);

        JsonNode report = read(importRows(league.organizer(), league, true,
                row("MI", "CSK", day8),
                row("Mumbai Indians", "rcb", day8.plus(1, ChronoUnit.DAYS)),
                row("XYZ", "CSK", day8),
                row("MI", "MI", day8),
                row("MI", "CSK", Instant.now().plus(2, ChronoUnit.DAYS)),
                row("CSK", "MI", day8))
                .andExpect(status().isOk()));

        assertThat(report.get("ok").asBoolean()).isFalse();
        assertThat(report.get("created").asInt()).isZero();
        List<String> errors = new ArrayList<>();
        report.get("rows").forEach(r -> errors.add(r.get("error").isNull() ? null : r.get("error").asText()));
        assertThat(errors.get(0)).isNull();
        assertThat(errors.get(1)).isNull();
        assertThat(errors.get(2)).contains("\"XYZ\" is not a team");
        assertThat(errors.get(3)).contains("two different teams");
        assertThat(errors.get(4)).contains("after the league's payment deadline");
        assertThat(errors.get(5)).contains("already in the league");
        assertThat(contestCount(league)).isZero();
    }

    @Test
    void aCleanListCreatesEveryMatchWithNumbersInOrder() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);

        read(importRows(league.organizer(), league, false,
                row("MI", "CSK", day8),
                rowNumbered("RCB", "KKR", day8.plus(1, ChronoUnit.DAYS), 5),
                row("Chennai Super Kings", "Rajasthan Royals", day8.plus(2, ChronoUnit.DAYS)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.created").value(3))
                .andExpect(jsonPath("$.rows[0].contestNumber").value(1))
                .andExpect(jsonPath("$.rows[1].contestNumber").value(5))
                .andExpect(jsonPath("$.rows[2].contestNumber").value(2))
                .andExpect(jsonPath("$.rows[2].teamB.shortCode").value("RR")));

        assertThat(contestCount(league)).isEqualTo(3);
    }

    @Test
    void oneBadRowMeansNothingIsCreated() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);

        importRows(league.organizer(), league, false, row("MI", "CSK", day8), row("MI", "Nobody", day8))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.created").value(0));

        assertThat(contestCount(league)).isZero();
    }

    @Test
    void theListCannotGoPastTheLeaguesPlannedMatches() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 2);

        JsonNode report = read(importRows(league.organizer(), league, true,
                row("MI", "CSK", day8), row("RCB", "KKR", day8), row("GT", "LSG", day8)));

        assertThat(report.get("rows").get(2).get("error").asText()).contains("Only 2 more contests fit");
    }

    @Test
    void onlyTheOrganizerCanImport() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        Registered stranger = TestAuth.register(mvc, mapper);

        importRows(stranger, league, true, row("MI", "CSK", day8)).andExpect(status().isForbidden());
    }

    @Test
    void anEmptyListIsRefused() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        mvc.perform(post("/api/leagues/" + league.id() + "/contests/import")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rows\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private Map<String, Object> row(String a, String b, Instant start) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamA", a);
        row.put("teamB", b);
        row.put("startTime", start.toString());
        return row;
    }

    private Map<String, Object> rowNumbered(String a, String b, Instant start, int number) {
        Map<String, Object> row = row(a, b, start);
        row.put("contestNumber", number);
        return row;
    }

    @SafeVarargs
    private ResultActions importRows(Registered who, TestLeague league, boolean dryRun, Map<String, Object>... rows)
            throws Exception {
        return mvc.perform(post("/api/leagues/" + league.id() + "/contests/import")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("rows", List.of(rows), "dryRun", dryRun))));
    }

    private int contestCount(TestLeague league) {
        Integer n = jdbc.queryForObject("select count(*) from contests where league_id = ?", Integer.class, league.id());
        return n == null ? 0 : n;
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
