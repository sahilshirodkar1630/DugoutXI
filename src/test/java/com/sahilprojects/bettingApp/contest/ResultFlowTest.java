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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Declaring a result: the preview, the two-step confirm, and corrections. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ResultFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void thePreviewShowsThePoolAndEachWinnersShareWithoutSavingAnything() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());

        JsonNode preview = read(result(f.organizer(), f.contestId(), "preview",
                Map.of("winningTeamId", f.mi())).andExpect(status().isOk()));

        assertThat(preview.get("confirmedCount").asInt()).isEqualTo(3);
        assertThat(preview.get("pool").asDouble()).isEqualTo(300.0);
        assertThat(preview.get("winnerCount").asInt()).isEqualTo(2);
        assertThat(preview.get("perWinner").asDouble()).isEqualTo(150.0);
        assertThat(preview.get("previewToken").asText()).isNotBlank();

        // Nothing was written: the contest is still waiting for a result.
        assertThat(contestStatus(f.contestId())).isEqualTo("LOCKED");
        assertThat(jdbc.queryForObject("select count(*) from contest_participants where contest_id = ? "
                + "and result is not null", Integer.class, f.contestId())).isZero();
    }

    @Test
    void confirmingWritesEveryParticipantsResultAndMoney() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());

        String token = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perWinner").value(150.00));

        // A league contest is settled straight away: its money is netted when the league ends.
        assertThat(contestStatus(f.contestId())).isEqualTo("SETTLED");
        assertThat(participantMoney(f.contestId(), f.a().id()))
                .containsEntry("result", "WON")
                .containsEntry("winning_amount", bd(150))
                .containsEntry("entry_spent", bd(100));
        assertThat(participantMoney(f.contestId(), f.b().id()))
                .containsEntry("result", "LOST")
                .containsEntry("winning_amount", bd(0))
                .containsEntry("entry_spent", bd(100));
        // League contests never create payouts.
        assertThat(jdbc.queryForObject("select count(*) from payouts where contest_id = ?",
                Integer.class, f.contestId())).isZero();
    }

    @Test
    void confirmingWithoutAPreviewTokenIsRefused() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());

        result(f.organizer(), f.contestId(), "confirm", Map.of("winningTeamId", f.mi()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aStaleTokenIsRefusedSoNobodySettlesFiguresTheyNeverSaw() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        predict(f.b(), f.contestId(), f.mi());
        TestContests.lock(jdbc, f.contestId());
        String miToken = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));

        // The figures shown were for MI winning. Confirming CSK with them is refused.
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.csk(), "previewToken", miToken))
                .andExpect(status().isConflict());

        // A token that was tampered with is refused too.
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", "0".repeat(64)))
                .andExpect(status().isConflict());

        // A fresh preview gives a token that works.
        String fresh = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", fresh))
                .andExpect(status().isOk());
    }

    @Test
    void aResultCannotBeEnteredBeforeTheContestLocks() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());

        result(f.organizer(), f.contestId(), "preview", Map.of("winningTeamId", f.mi()))
                .andExpect(status().isConflict());
    }

    @Test
    void onlyTheOrganizerOrAnAdminCanDeclareAResult() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());

        result(f.a(), f.contestId(), "preview", Map.of("winningTeamId", f.mi()))
                .andExpect(status().isForbidden());

        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        result(admin, f.contestId(), "preview",
                Map.of("winningTeamId", f.mi(), "reason", "helping the organizer out"))
                .andExpect(status().isOk());
    }

    @Test
    void aNoResultContestRefundsEveryoneAndSpendsNothing() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        predict(f.b(), f.contestId(), f.mi());
        TestContests.lock(jdbc, f.contestId());

        String token = previewToken(f.organizer(), f.contestId(), Map.of("noResult", true));
        read(result(f.organizer(), f.contestId(), "confirm",
                Map.of("noResult", true, "previewToken", token)).andExpect(status().isOk()));

        assertThat(participantMoney(f.contestId(), f.a().id()))
                .containsEntry("result", "REFUNDED")
                .containsEntry("entry_spent", bd(0))
                .containsEntry("winning_amount", bd(0));
        assertThat(jdbc.queryForObject("select result_type from contests where id = ?",
                String.class, f.contestId())).isEqualTo("NO_RESULT");
    }

    @Test
    void sendingBothAWinnerAndNoResultIsRejected() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());

        result(f.organizer(), f.contestId(), "preview",
                Map.of("winningTeamId", f.mi(), "noResult", true))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theSameResultCannotBeConfirmedTwice() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());

        String token = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", token)).andExpect(status().isOk());

        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", token)).andExpect(status().isConflict());
    }

    @Test
    void aCorrectionNeedsAReasonAndRewritesTheMoney() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());
        String token = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", token)).andExpect(status().isOk());

        result(f.organizer(), f.contestId(), "correct", Map.of("winningTeamId", f.csk()))
                .andExpect(status().isBadRequest());

        result(f.organizer(), f.contestId(), "correct",
                Map.of("winningTeamId", f.csk(), "reason", "I read the scorecard wrong"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.winnerCount").value(1))
                .andExpect(jsonPath("$.perWinner").value(300.00));

        assertThat(participantMoney(f.contestId(), f.b().id())).containsEntry("result", "WON");
        assertThat(participantMoney(f.contestId(), f.a().id())).containsEntry("result", "LOST");
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_log where contest_id = ? and action = 'RESULT_CORRECTED'",
                Integer.class, f.contestId())).isEqualTo(1);
    }

    @Test
    void theOutcomesEndpointShowsWhoWonWhat() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());
        String token = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", token)).andExpect(status().isOk());

        JsonNode rows = read(mvc.perform(get("/api/contests/" + f.contestId() + "/outcomes")
                .header("Authorization", f.b().bearer())).andExpect(status().isOk()));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get("netPl").asDouble()).isEqualTo(50.0);     // best off first
        assertThat(rows.get(2).get("netPl").asDouble()).isEqualTo(-100.0);
    }

    @Test
    void everyoneIsToldOnceTheResultIsIn() throws Exception {
        Fixture f = leagueWithThreeMembers(100);
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        TestContests.lock(jdbc, f.contestId());
        String token = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", f.mi()));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", f.mi(), "previewToken", token)).andExpect(status().isOk());

        JsonNode inbox = read(mvc.perform(get("/api/notifications")
                .header("Authorization", f.b().bearer())).andExpect(status().isOk()));
        assertThat(inbox).anySatisfy(n -> {
            assertThat(n.get("type").asText()).isEqualTo("RESULT_DECLARED");
            assertThat(n.get("message").asText()).contains("lost your entry");
        });
    }

    // ---------- helpers ----------

    private record Fixture(TestLeague league, long contestId, Registered a, Registered b, long mi, long csk) {
        Registered organizer() {
            return league.organizer();
        }
    }

    private Fixture leagueWithThreeMembers(int amount) throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, amount, 10);
        TestLeagues.open(mvc, league);
        Registered a = TestAuth.register(mvc, mapper);
        Registered b = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, a);
        TestLeagues.joinConfirmed(mvc, mapper, league, b);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        return new Fixture(league, contestId, a, b,
                TestContests.team(jdbc, "MI"), TestContests.team(jdbc, "CSK"));
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

    private String contestStatus(long contestId) {
        return jdbc.queryForObject("select contest_status from contests where id = ?", String.class, contestId);
    }

    private Map<String, Object> participantMoney(long contestId, long userId) {
        return jdbc.queryForMap("select result, entry_spent, winning_amount from contest_participants "
                + "where contest_id = ? and user_id = ?", contestId, userId);
    }

    private static java.math.BigDecimal bd(int rupees) {
        return new java.math.BigDecimal(rupees).setScale(2);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
