package com.sahilprojects.bettingApp.settlement;

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

/** Ending a league, the closing balances, and settling up. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SettlementFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void endingALeagueFreezesEverybodysBalanceAndTheFiguresAddUp() throws Exception {
        Fixture f = leagueWithOneContestPlayed();

        JsonNode summary = read(end(f).andExpect(status().isOk()));

        assertThat(summary.get("leagueStatus").asText()).isEqualTo("PENDING_SETTLEMENT");
        assertThat(summary.get("memberCount").asInt()).isEqualTo(3);
        assertThat(summary.get("balanced").asBoolean()).isTrue();

        // Each member paid 10 contests x 100 = 1000 up front. The winners are 50 up, the loser 100 down.
        assertThat(rowFor(summary, f.a().id()).get("finalBalance").asDouble()).isEqualTo(1050.0);
        assertThat(rowFor(summary, f.b().id()).get("finalBalance").asDouble()).isEqualTo(900.0);
        assertThat(rowFor(summary, f.organizer().id()).get("finalBalance").asDouble()).isEqualTo(1050.0);
    }

    @Test
    void theOrganizersOwnRowIsSettledStraightAwayAndEveryMemberIsWaitingForTheirMoney() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        JsonNode summary = read(end(f).andExpect(status().isOk()));

        assertThat(rowFor(summary, f.organizer().id()).get("status").asText()).isEqualTo("SETTLED");
        assertThat(rowFor(summary, f.a().id()).get("status").asText()).isEqualTo("PENDING");
        assertThat(rowFor(summary, f.b().id()).get("status").asText()).isEqualTo("PENDING");
    }

    @Test
    void aLeagueCannotBeEndedWhileAContestStillOwesAResult() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        long second = TestContests.leagueContest(mvc, mapper, jdbc, f.league(), "RCB", "KKR");
        TestContests.lock(jdbc, second);

        end(f).andExpect(status().isConflict());
    }

    @Test
    void aLeagueCannotBeEndedWhileAContestHasStartedButNotLockedYet() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        long second = TestContests.leagueContest(mvc, mapper, jdbc, f.league(), "RCB", "KKR");
        TestContests.setStartTime(jdbc, second, java.time.Instant.now().minusSeconds(60));

        end(f).andExpect(status().isConflict());
    }

    @Test
    void endingALeagueCancelsContestsThatWereNeverPlayed() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        long unplayed = TestContests.leagueContest(mvc, mapper, jdbc, f.league(), "RCB", "KKR");

        end(f).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select contest_status from contests where id = ?", String.class, unplayed))
                .isEqualTo("CANCELLED");
    }

    @Test
    void onlyTheOrganizerOrAnAdminCanEndALeague() throws Exception {
        Fixture f = leagueWithOneContestPlayed();

        mvc.perform(post("/api/leagues/" + f.league().id() + "/end").header("Authorization", f.a().bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void theSummaryListsEveryMemberTheOrganizerHasToPayBack() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());

        JsonNode summary = read(mvc.perform(get("/api/leagues/" + f.league().id() + "/settlement/summary")
                .header("Authorization", f.organizer().bearer())).andExpect(status().isOk()));

        // Both members get their final balance back, the loser included; the organizer already holds theirs.
        assertThat(summary.get("owedMoney")).extracting(n -> n.get("userId").asLong())
                .containsExactlyInAnyOrder(f.a().id(), f.b().id());
        assertThat(summary.get("oweMoney")).isEmpty();
    }

    @Test
    void aLeagueCannotBeCompletedWhileSomebodyHasNotSettled() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());

        mvc.perform(post("/api/leagues/" + f.league().id() + "/complete")
                        .header("Authorization", f.organizer().bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void aLeagueIsCompletedOnceEverybodyIsTickedOff() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());
        settleEveryone(f);

        mvc.perform(post("/api/leagues/" + f.league().id() + "/complete")
                        .header("Authorization", f.organizer().bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leagueStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.pendingCount").value(0));
    }

    @Test
    void takingASettlementBackNeedsAReason() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());
        long rowId = pendingRow(f.league().id());
        markSettled(f, rowId).andExpect(status().isOk());

        updateStatus(f.organizer(), f.league().id(), rowId, Map.of("status", "PENDING"))
                .andExpect(status().isBadRequest());
        updateStatus(f.organizer(), f.league().id(), rowId,
                Map.of("status", "PENDING", "reason", "the transfer never arrived"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void anAdminCanForceCompleteAStuckLeagueAndWhatIsLeftIsWrittenOff() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        // The organizer cannot do this, however stuck the league is.
        mvc.perform(post("/api/leagues/" + f.league().id() + "/force-complete")
                        .header("Authorization", f.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"let me out\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/leagues/" + f.league().id() + "/force-complete")
                        .header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"a member has left the group and will not pay\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.leagueStatus").value("COMPLETED"));

        assertThat(jdbc.queryForObject("select count(*) from settlement_rows "
                + "where league_id = ? and settlement_status = 'WRITTEN_OFF'", Integer.class, f.league().id()))
                .isEqualTo(2);
    }

    @Test
    void correctingAResultAfterTheLeagueEndedIsAdminOnlyAndRedoesThePendingBalances() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        // The organizer no longer gets to change what members have been told they owe.
        correct(f.organizer(), f.contestId(), f.csk(), "I misread it").andExpect(status().isForbidden());

        correct(admin, f.contestId(), f.csk(), "the match was awarded to CSK on review")
                .andExpect(status().isOk());

        // The loser became the only winner, so their pending balance is now the best in the league.
        assertThat(jdbc.queryForObject("select final_balance from settlement_rows "
                        + "where league_id = ? and user_id = ?",
                java.math.BigDecimal.class, f.league().id(), f.b().id()))
                .isEqualByComparingTo("1200.00");
    }

    @Test
    void aCorrectionIsRefusedOnceSomebodyHasSettled() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());
        markSettled(f, pendingRow(f.league().id())).andExpect(status().isOk());
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        correct(admin, f.contestId(), f.csk(), "too late now").andExpect(status().isConflict());
    }

    @Test
    void aMemberWithNothingLeftDoesNotBlockACorrectionAndIsRedone() throws Exception {
        // One contest at 100, so the buy-in is 100 and a loser has nothing left to be handed back.
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 1);
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
        String token = previewToken(league.organizer(), contestId, Map.of("winningTeamId", mi));
        result(league.organizer(), contestId, "confirm", Map.of("winningTeamId", mi, "previewToken", token))
                .andExpect(status().isOk());
        jdbc.update("update leagues set status = 'ACTIVE', started_at = now() where id = ?", league.id());
        mvc.perform(post("/api/leagues/" + league.id() + "/end")
                .header("Authorization", league.organizer().bearer())).andExpect(status().isOk());

        // b lost their whole buy-in: nothing to hand back, so the row is settled without any money moving.
        assertThat(jdbc.queryForObject("select settlement_status from settlement_rows where league_id = ? "
                + "and user_id = ?", String.class, league.id(), b.id())).isEqualTo("SETTLED");

        // That automatic tick must not block a correction: no money moved on the strength of it.
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        correct(admin, contestId, csk, "the match was awarded to CSK on review").andExpect(status().isOk());

        // Now b is the only winner (pool 300) and a has nothing left.
        assertThat(jdbc.queryForMap("select final_balance, settlement_status from settlement_rows "
                + "where league_id = ? and user_id = ?", league.id(), b.id()))
                .containsEntry("settlement_status", "PENDING")
                .satisfies(row -> assertThat((java.math.BigDecimal) row.get("final_balance"))
                        .isEqualByComparingTo("300.00"));
        assertThat(jdbc.queryForMap("select final_balance, settlement_status from settlement_rows "
                + "where league_id = ? and user_id = ?", league.id(), a.id()))
                .containsEntry("settlement_status", "SETTLED")
                .satisfies(row -> assertThat((java.math.BigDecimal) row.get("final_balance"))
                        .isEqualByComparingTo("0.00"));
        JsonNode summary = read(mvc.perform(get("/api/leagues/" + league.id() + "/settlement/summary")
                .header("Authorization", league.organizer().bearer())).andExpect(status().isOk()));
        assertThat(summary.get("balanced").asBoolean()).isTrue();
    }

    @Test
    void everybodyIsToldWhereTheyStandWhenTheLeagueEnds() throws Exception {
        Fixture f = leagueWithOneContestPlayed();
        end(f).andExpect(status().isOk());

        JsonNode inbox = read(mvc.perform(get("/api/notifications")
                .header("Authorization", f.b().bearer())).andExpect(status().isOk()));
        assertThat(inbox).anySatisfy(n -> {
            assertThat(n.get("type").asText()).isEqualTo("LEAGUE_ENDED");
            assertThat(n.get("message").asText()).contains("The organizer will return 900");
        });
    }

    // ---------- helpers ----------

    private record Fixture(TestLeague league, long contestId, Registered a, Registered b, long mi, long csk) {
        Registered organizer() {
            return league.organizer();
        }
    }

    /**
     * A 10-contest league at 100 a contest, so 1000 each up front, with three paid members and one contest played:
     * the organizer and 'a' pick the winner, 'b' does not.
     */
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

        String token = previewToken(league.organizer(), contestId, Map.of("winningTeamId", mi));
        result(league.organizer(), contestId, "confirm", Map.of("winningTeamId", mi, "previewToken", token))
                .andExpect(status().isOk());

        // The league has to be ACTIVE to be ended, which normally happens when its first contest starts.
        jdbc.update("update leagues set status = 'ACTIVE', started_at = now() where id = ?", league.id());
        return new Fixture(league, contestId, a, b, mi, csk);
    }

    private void predict(Registered who, long contestId, long teamId) throws Exception {
        mvc.perform(put("/api/contests/" + contestId + "/predictions/me")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + teamId + "}"))
                .andExpect(status().isOk());
    }

    private ResultActions result(Registered who, long contestId, String step, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/contests/" + contestId + "/result/" + step)
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private String previewToken(Registered who, long contestId, Map<String, Object> body) throws Exception {
        return read(result(who, contestId, "preview", body).andExpect(status().isOk()))
                .get("previewToken").asText();
    }

    private ResultActions correct(Registered who, long contestId, long winningTeamId, String reason)
            throws Exception {
        return result(who, contestId, "correct", Map.of("winningTeamId", winningTeamId, "reason", reason));
    }

    private ResultActions end(Fixture f) throws Exception {
        return mvc.perform(post("/api/leagues/" + f.league().id() + "/end")
                .header("Authorization", f.organizer().bearer()));
    }

    private ResultActions updateStatus(Registered who, long leagueId, long rowId, Map<String, Object> body)
            throws Exception {
        return mvc.perform(put("/api/leagues/" + leagueId + "/settlement/" + rowId + "/status")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private ResultActions markSettled(Fixture f, long rowId) throws Exception {
        return updateStatus(f.organizer(), f.league().id(), rowId, Map.of("status", "SETTLED"));
    }

    private void settleEveryone(Fixture f) throws Exception {
        for (Long rowId : jdbc.queryForList("select id from settlement_rows where league_id = ? "
                + "and settlement_status = 'PENDING'", Long.class, f.league().id())) {
            markSettled(f, rowId).andExpect(status().isOk());
        }
    }

    private long pendingRow(long leagueId) {
        return jdbc.queryForObject("select id from settlement_rows where league_id = ? "
                + "and settlement_status = 'PENDING' order by id limit 1", Long.class, leagueId);
    }

    private static JsonNode rowFor(JsonNode summary, long userId) {
        for (JsonNode row : summary.get("rows")) {
            if (row.get("userId").asLong() == userId) {
                return row;
            }
        }
        throw new AssertionError("No settlement row for user " + userId);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
