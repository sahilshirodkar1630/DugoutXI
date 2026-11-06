package com.sahilprojects.bettingApp.dashboard;

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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The read-only screens: the league table, a player's record, and the home screen. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class DashboardFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void theLeagueBoardRanksMembersByProfitAndLoss() throws Exception {
        Fixture f = leagueWithOneContestPlayed();

        JsonNode board = read(mvc.perform(get("/api/leagues/" + f.league().id() + "/board")
                .header("Authorization", f.b().bearer())).andExpect(status().isOk()));

        assertThat(board.get("contestsPlayed").asInt()).isEqualTo(1);
        assertThat(board.get("contestsPlanned").asInt()).isEqualTo(10);
        assertThat(board.get("rows")).hasSize(3);

        // The two winners are 50 up and share first place; the loser is 100 down and last.
        assertThat(board.get("rows").get(0).get("netPl").asDouble()).isEqualTo(50.0);
        assertThat(board.get("rows").get(0).get("rank").asInt()).isEqualTo(1);
        assertThat(board.get("rows").get(1).get("rank").asInt()).isEqualTo(1);
        JsonNode last = board.get("rows").get(2);
        assertThat(last.get("netPl").asDouble()).isEqualTo(-100.0);
        assertThat(last.get("rank").asInt()).isEqualTo(3);
        assertThat(last.get("runningBalance").asDouble()).isEqualTo(900.0);
    }

    @Test
    void theBoardMarksTheCallersOwnLine() throws Exception {
        Fixture f = leagueWithOneContestPlayed();

        JsonNode board = read(mvc.perform(get("/api/leagues/" + f.league().id() + "/board")
                .header("Authorization", f.b().bearer())).andExpect(status().isOk()));

        assertThat(board.get("me").get("userId").asLong()).isEqualTo(f.b().id());
        assertThat(board.get("me").get("me").asBoolean()).isTrue();
    }

    @Test
    void onlyMembersCanSeeALeaguesBoard() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        Registered outsider = TestAuth.register(mvc, mapper);

        mvc.perform(get("/api/leagues/" + f.league().id() + "/board")
                        .header("Authorization", outsider.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theBoardShowsSettlementStatusOnceTheLeagueHasEnded() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        jdbc.update("update leagues set status = 'ACTIVE', started_at = now() where id = ?", f.league().id());
        mvc.perform(post("/api/leagues/" + f.league().id() + "/end")
                        .header("Authorization", f.organizer().bearer()))
                .andExpect(status().isOk());

        JsonNode board = read(mvc.perform(get("/api/leagues/" + f.league().id() + "/board")
                .header("Authorization", f.organizer().bearer())).andExpect(status().isOk()));

        assertThat(board.get("rows")).allSatisfy(row ->
                assertThat(row.get("settlementStatus").asText()).isIn("PENDING", "SETTLED"));
    }

    @Test
    void myStatsCountWinsLossesAndTheWinPercentage() throws Exception {
        Fixture f = leagueWithOneContestPlayed();

        JsonNode stats = read(mvc.perform(get("/api/me/stats")
                .header("Authorization", f.a().bearer())).andExpect(status().isOk()));

        assertThat(stats.get("leaguesJoined").asInt()).isEqualTo(1);
        assertThat(stats.get("contestsPlayed").asInt()).isEqualTo(1);
        assertThat(stats.get("contestsWon").asInt()).isEqualTo(1);
        assertThat(stats.get("netPl").asDouble()).isEqualTo(50.0);
        assertThat(stats.get("winPercentage").asDouble()).isEqualTo(100.0);
    }

    @Test
    void aBrandNewPlayerHasAnEmptyRecordRatherThanAnError() throws Exception {
        Registered fresh = TestAuth.register(mvc, mapper);

        mvc.perform(get("/api/me/stats").header("Authorization", fresh.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contestsPlayed").value(0))
                .andExpect(jsonPath("$.netPl").value(0))
                .andExpect(jsonPath("$.winPercentage").value(0));
    }

    @Test
    void aRefundedContestIsNotCountedAsPlayed() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        // A second contest where everybody picks the same team, so everyone is refunded.
        long second = TestContests.leagueContest(mvc, mapper, jdbc, f.league(), "RCB", "KKR");
        long rcb = TestContests.team(jdbc, "RCB");
        predict(f.organizer(), second, rcb);
        predict(f.a(), second, rcb);
        predict(f.b(), second, rcb);
        TestContests.lock(jdbc, second);
        confirmResult(f.organizer(), second, rcb);

        JsonNode stats = read(mvc.perform(get("/api/me/stats")
                .header("Authorization", f.a().bearer())).andExpect(status().isOk()));

        assertThat(stats.get("contestsPlayed").asInt()).isEqualTo(1);     // still just the first
        assertThat(stats.get("contestsRefunded").asInt()).isEqualTo(1);
        assertThat(stats.get("netPl").asDouble()).isEqualTo(50.0);
    }

    @Test
    void theHomeScreenShowsMoneyStillOwedToTheCaller() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered winner = TestAuth.register(mvc, mapper);
        confirmJoin(organizer, contestId, winner);
        long mi = TestContests.team(jdbc, "MI");
        long csk = TestContests.team(jdbc, "CSK");
        predict(organizer, contestId, csk);
        predict(winner, contestId, mi);
        TestContests.lock(jdbc, contestId);
        confirmResult(organizer, contestId, mi);

        JsonNode summary = read(mvc.perform(get("/api/me/summary")
                .header("Authorization", winner.bearer())).andExpect(status().isOk()));

        assertThat(summary.get("pendingPayoutTotal").asDouble()).isEqualTo(200.0);
        assertThat(summary.get("pendingPayouts")).hasSize(1);
        assertThat(summary.get("pendingPayouts").get(0).get("owedToMe").asBoolean()).isTrue();
        assertThat(summary.get("pendingPayouts").get(0).get("kind").asText()).isEqualTo("PAYOUT");
    }

    @Test
    void theHomeScreenTellsAnOrganizerWhichResultsAreWaitingOnThem() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        confirmJoin(organizer, contestId, TestAuth.register(mvc, mapper));
        TestContests.lock(jdbc, contestId);

        JsonNode summary = read(mvc.perform(get("/api/me/summary")
                .header("Authorization", organizer.bearer())).andExpect(status().isOk()));

        assertThat(summary.get("awaitingMyAction")).anySatisfy(item -> {
            assertThat(item.get("kind").asText()).isEqualTo("RESULT_DUE");
            assertThat(item.get("referenceId").asLong()).isEqualTo(contestId);
        });
    }

    @Test
    void theHomeScreenShowsWhatComesBackToTheCallerAfterALeagueEnds() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        jdbc.update("update leagues set status = 'ACTIVE', started_at = now() where id = ?", f.league().id());
        mvc.perform(post("/api/leagues/" + f.league().id() + "/end")
                        .header("Authorization", f.organizer().bearer()))
                .andExpect(status().isOk());

        JsonNode summary = read(mvc.perform(get("/api/me/summary")
                .header("Authorization", f.b().bearer())).andExpect(status().isOk()));

        assertThat(summary.get("pendingSettlements")).hasSize(1);
        // b paid the whole buy-in up front and lost one contest, so the organizer hands back what is left.
        long expected = jdbc.queryForObject("select final_balance from settlement_rows where league_id = ? "
                + "and user_id = ?", java.math.BigDecimal.class, f.league().id(), f.b().id()).longValue();
        assertThat(summary.get("pendingSettlements").get(0).get("owedToMe").asBoolean()).isTrue();
        assertThat(summary.get("pendingSettlements").get(0).get("amount").asDouble()).isEqualTo((double) expected);
        assertThat(summary.get("pendingSettlementTotal").asDouble()).isEqualTo((double) expected);
    }

    // ---------- helpers ----------

    private record Fixture(TestLeague league, long contestId, Registered a, Registered b) {
        Registered organizer() {
            return league.organizer();
        }
    }

    /** 10 contests at 100 each, three paid members, one contest played: the organizer and 'a' pick the winner. */
    private Fixture leagueWithOneContestPlayed() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        Registered a = TestAuth.register(mvc, mapper);
        Registered b = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, a);
        TestLeagues.joinConfirmed(mvc, mapper, league, b);

        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        long mi = TestContests.team(jdbc, "MI");
        long csk = TestContests.team(jdbc, "CSK");
        predict(league.organizer(), contestId, mi);
        predict(a, contestId, mi);
        predict(b, contestId, csk);
        TestContests.lock(jdbc, contestId);
        confirmResult(league.organizer(), contestId, mi);
        return new Fixture(league, contestId, a, b);
    }

    private void confirmJoin(Registered organizer, long contestId, Registered joiner) throws Exception {
        long participantId = read(mvc.perform(post("/api/contests/" + contestId + "/join-requests")
                .header("Authorization", joiner.bearer())).andExpect(status().isCreated()))
                .get("participantId").asLong();
        mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/accept")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":true}"))
                .andExpect(status().isOk());
    }

    private void predict(Registered who, long contestId, long teamId) throws Exception {
        mvc.perform(put("/api/contests/" + contestId + "/predictions/me")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + teamId + "}"))
                .andExpect(status().isOk());
    }

    private void confirmResult(Registered organizer, long contestId, long winningTeamId) throws Exception {
        String token = read(result(organizer, contestId, "preview", Map.of("winningTeamId", winningTeamId))
                .andExpect(status().isOk())).get("previewToken").asText();
        result(organizer, contestId, "confirm", Map.of("winningTeamId", winningTeamId, "previewToken", token))
                .andExpect(status().isOk());
    }

    private ResultActions result(Registered who, long contestId, String step, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/contests/" + contestId + "/result/" + step)
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
