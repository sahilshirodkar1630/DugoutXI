package com.sahilprojects.bettingApp.contest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestContests;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Picking a winner: who may pick, until when, and when other people's picks become visible. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PredictionFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aConfirmedMemberCanPickATeamAndChangeTheirMind() throws Exception {
        TestLeague league = league();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        long mi = TestContests.team(jdbc, "MI");
        long csk = TestContests.team(jdbc, "CSK");

        predict(league.organizer(), contestId, mi)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedTeamId").value(mi))
                .andExpect(jsonPath("$.selectedTeamShortCode").value("MI"))
                .andExpect(jsonPath("$.locked").value(false));

        // Changing the pick before the start simply overwrites it.
        predict(league.organizer(), contestId, csk).andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedTeamId").value(csk));

        JsonNode mine = read(mvc.perform(get("/api/contests/" + contestId + "/predictions/me")
                .header("Authorization", league.organizer().bearer())).andExpect(status().isOk()));
        assertThat(mine.get("selectedTeamId").asLong()).isEqualTo(csk);
        assertThat(mine.get("predictedAt").isNull()).isFalse();
        assertThat(mine.get("teamA").get("shortCode").asText()).isEqualTo("MI");
    }

    @Test
    void aTeamThatIsNotPlayingIsRejected() throws Exception {
        TestLeague league = league();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        predict(league.organizer(), contestId, TestContests.team(jdbc, "RCB"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPickIsRefusedOnceTheStartTimeHasPassed() throws Exception {
        TestLeague league = league();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));

        predict(league.organizer(), contestId, TestContests.team(jdbc, "MI"))
                .andExpect(status().isConflict());

        // My own pick stays readable afterwards, and now reports itself as locked.
        mvc.perform(get("/api/contests/" + contestId + "/predictions/me")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(true))
                .andExpect(jsonPath("$.selectedTeamId").doesNotExist());
    }

    @Test
    void someoneWhoIsNotAParticipantCannotPick() throws Exception {
        TestLeague league = league();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        Registered outsider = TestAuth.register(mvc, mapper);

        predict(outsider, contestId, TestContests.team(jdbc, "MI"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aMemberWhoIsNotConfirmedCannotPick() throws Exception {
        TestLeague league = league();
        TestLeagues.open(mvc, league);
        Registered pending = TestAuth.register(mvc, mapper);
        TestLeagues.request(mvc, mapper, league, pending);         // asked to join, not accepted
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        // No participant row exists for an unconfirmed member, so the contest is simply not theirs.
        predict(pending, contestId, TestContests.team(jdbc, "MI"))
                .andExpect(status().isForbidden());
    }

    @Test
    void otherPeoplesPicksAreHiddenUntilTheContestStarts() throws Exception {
        TestLeague league = league();
        TestLeagues.open(mvc, league);
        Registered friend = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, friend);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        predict(league.organizer(), contestId, TestContests.team(jdbc, "MI")).andExpect(status().isOk());
        predict(friend, contestId, TestContests.team(jdbc, "CSK")).andExpect(status().isOk());

        mvc.perform(get("/api/contests/" + contestId + "/predictions")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void everyPickIsVisibleOnceTheContestStarts() throws Exception {
        TestLeague league = league();
        TestLeagues.open(mvc, league);
        Registered picker = TestAuth.register(mvc, mapper);
        Registered silent = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, picker);
        TestLeagues.joinConfirmed(mvc, mapper, league, silent);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        predict(league.organizer(), contestId, TestContests.team(jdbc, "MI")).andExpect(status().isOk());
        predict(picker, contestId, TestContests.team(jdbc, "CSK")).andExpect(status().isOk());
        // 'silent' never predicts.

        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));

        JsonNode rows = read(mvc.perform(get("/api/contests/" + contestId + "/predictions")
                .header("Authorization", picker.bearer())).andExpect(status().isOk()));

        assertThat(rows).hasSize(3);
        // The two who picked come first, then the one who did not.
        assertThat(rows.get(2).get("selectedTeamId").isNull()).isTrue();
        assertThat(rows.get(0).get("selectedTeamShortCode").asText()).isNotBlank();
        long mine = 0;
        for (JsonNode row : rows) {
            if (row.get("me").asBoolean()) {
                mine++;
                assertThat(row.get("userId").asLong()).isEqualTo(picker.id());
            }
        }
        assertThat(mine).isEqualTo(1);
    }

    @Test
    void theDatabaseTriggerBlocksALatePickEvenIfTheServiceMissesIt() throws Exception {
        TestLeague league = league();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.HOURS));

        // Going behind the service's back: the trigger still refuses.
        Long participantId = jdbc.queryForObject(
                "select id from contest_participants where contest_id = ? and user_id = ?",
                Long.class, contestId, league.organizer().id());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(
                        "update contest_participants set selected_team_id = ?, predicted_at = now() where id = ?",
                        TestContests.team(jdbc, "MI"), participantId))
                .hasMessageContaining("predictions are locked");
    }

    // ---------- helpers ----------

    private TestLeague league() throws Exception {
        return TestLeagues.create(mvc, mapper, jdbc, 100, 10);
    }

    private ResultActions predict(Registered who, long contestId, long teamId) throws Exception {
        return mvc.perform(put("/api/contests/" + contestId + "/predictions/me")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":" + teamId + "}"));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
