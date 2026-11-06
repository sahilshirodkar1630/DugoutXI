package com.sahilprojects.bettingApp.payout;

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

/** The money side of a standalone contest: who is owed what, and ticking it off once it has been sent. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PayoutFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aWinningPayoutIsCreatedForTheWinnerAndPaidStraightAwayForTheOrganizer() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());

        JsonNode payouts = read(mvc.perform(get("/api/contests/" + f.contestId() + "/payouts")
                .header("Authorization", f.organizer().bearer())).andExpect(status().isOk()));

        // Two winners split a 300 pool, so 150 each. The loser is the organizer, who gets nothing.
        assertThat(payouts).hasSize(2);
        assertThat(payouts).allSatisfy(p -> {
            assertThat(p.get("type").asText()).isEqualTo("WINNING");
            assertThat(p.get("amount").asDouble()).isEqualTo(150.0);
            assertThat(p.get("status").asText()).isEqualTo("PENDING");
        });
    }

    @Test
    void theOrganizersOwnWinningsNeedNoTransfer() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        predict(f.b(), f.contestId(), f.csk());
        declare(f, f.mi());

        assertThat(jdbc.queryForObject("select payout_status from payouts where contest_id = ? and user_id = ?",
                String.class, f.contestId(), f.organizer().id())).isEqualTo("PAID_OUT");
        // One winner takes the whole pool.
        assertThat(jdbc.queryForObject("select amount from payouts where contest_id = ? and user_id = ?",
                java.math.BigDecimal.class, f.contestId(), f.organizer().id()))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void theContestBecomesSettledOnceEveryPayoutIsMarkedPaid() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());
        assertThat(contestStatus(f.contestId())).isEqualTo("RESULT_DECLARED");

        for (JsonNode payout : read(payouts(f.organizer(), f.contestId()))) {
            if (!"PAID_OUT".equals(payout.get("status").asText())) {
                markPaid(f.organizer(), f.contestId(), payout.get("id").asLong()).andExpect(status().isOk());
            }
        }
        assertThat(contestStatus(f.contestId())).isEqualTo("SETTLED");
    }

    @Test
    void takingAPayoutBackNeedsAReasonAndReopensTheContest() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());

        long payoutId = firstPendingPayout(f);
        markPaid(f.organizer(), f.contestId(), payoutId).andExpect(status().isOk());

        updateStatus(f.organizer(), f.contestId(), payoutId, Map.of("status", "PENDING"))
                .andExpect(status().isBadRequest());

        updateStatus(f.organizer(), f.contestId(), payoutId,
                Map.of("status", "PENDING", "reason", "the transfer bounced"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(contestStatus(f.contestId())).isEqualTo("RESULT_DECLARED");
    }

    @Test
    void aParticipantSeesOnlyTheirOwnPayout() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());

        JsonNode mine = read(payouts(f.a(), f.contestId()));
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("userId").asLong()).isEqualTo(f.a().id());
        assertThat(mine.get(0).get("me").asBoolean()).isTrue();
    }

    @Test
    void onlyTheOrganizerCanMarkAPayoutAsPaid() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());

        markPaid(f.a(), f.contestId(), firstPendingPayout(f)).andExpect(status().isForbidden());
    }

    @Test
    void aPayoutCannotBeVoidedThroughTheApi() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());

        updateStatus(f.organizer(), f.contestId(), firstPendingPayout(f),
                Map.of("status", "VOID", "reason", "trying it on"))
                .andExpect(status().isForbidden());
    }

    @Test
    void aNoResultContestRefundsEveryEntry() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.csk());
        predict(f.b(), f.contestId(), f.mi());
        TestContests.lock(jdbc, f.contestId());
        String token = previewToken(f.organizer(), f.contestId(), Map.of("noResult", true));
        result(f.organizer(), f.contestId(), "confirm", Map.of("noResult", true, "previewToken", token))
                .andExpect(status().isOk());

        JsonNode payouts = read(payouts(f.organizer(), f.contestId()));
        assertThat(payouts).hasSize(3);
        assertThat(payouts).allSatisfy(p -> {
            assertThat(p.get("type").asText()).isEqualTo("REFUND");
            assertThat(p.get("amount").asDouble()).isEqualTo(100.0);
        });
    }

    @Test
    void correctingAResultIsRefusedOnceSomebodyElseHasBeenPaid() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());
        markPaid(f.organizer(), f.contestId(), firstPendingPayout(f)).andExpect(status().isOk());

        result(f.organizer(), f.contestId(), "correct",
                Map.of("winningTeamId", f.csk(), "reason", "I misread the scorecard"))
                .andExpect(status().isConflict());
    }

    @Test
    void correctingAResultReplacesThePayoutsThatHaveNotBeenPaid() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.csk());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.mi());
        declare(f, f.mi());

        result(f.organizer(), f.contestId(), "correct",
                Map.of("winningTeamId", f.csk(), "reason", "I misread the scorecard"))
                .andExpect(status().isOk());

        // The two old winning payouts are void, and the organizer's new one is already settled with themselves.
        assertThat(jdbc.queryForObject(
                "select count(*) from payouts where contest_id = ? and payout_status = 'VOID'",
                Integer.class, f.contestId())).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "select payout_status from payouts where contest_id = ? and user_id = ? and payout_status <> 'VOID'",
                String.class, f.contestId(), f.organizer().id())).isEqualTo("PAID_OUT");
    }

    @Test
    void correctingAResultVoidsTheOrganizersOwnAutomaticPayoutWhenTheyNoLongerWin() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        declare(f, f.mi());

        result(f.organizer(), f.contestId(), "correct",
                Map.of("winningTeamId", f.csk(), "reason", "I misread the scorecard"))
                .andExpect(status().isOk());

        // The organizer's 150 is gone; the only live payout is b's 300.
        assertThat(jdbc.queryForObject("select count(*) from payouts where contest_id = ? and user_id = ? "
                + "and payout_status <> 'VOID'", Integer.class, f.contestId(), f.organizer().id())).isZero();
        assertThat(jdbc.queryForObject("select coalesce(sum(amount), 0) from payouts where contest_id = ? "
                + "and payout_status <> 'VOID'", java.math.BigDecimal.class, f.contestId()))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void correctingAResultToNoResultRefundsTheOrganizerToo() throws Exception {
        Fixture f = standaloneWithTwoJoiners();
        predict(f.organizer(), f.contestId(), f.mi());
        predict(f.a(), f.contestId(), f.mi());
        predict(f.b(), f.contestId(), f.csk());
        declare(f, f.mi());

        // The organizer already holds a PAID_OUT winning row. The refund must replace it, not collide with it.
        result(f.organizer(), f.contestId(), "correct",
                Map.of("noResult", true, "reason", "the match was abandoned"))
                .andExpect(status().isOk());

        JsonNode live = read(payouts(f.organizer(), f.contestId()));
        long refunds = 0;
        for (JsonNode p : live) {
            if (!"VOID".equals(p.get("status").asText())) {
                assertThat(p.get("type").asText()).isEqualTo("REFUND");
                refunds++;
            }
        }
        assertThat(refunds).isEqualTo(3);
        assertThat(jdbc.queryForObject("select payout_status from payouts where contest_id = ? and user_id = ? "
                + "and payout_status <> 'VOID'", String.class, f.contestId(), f.organizer().id()))
                .isEqualTo("PAID_OUT");
    }

    @Test
    void aLeagueContestHasNoPayoutsAtAll() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        Registered friend = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, friend);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        mvc.perform(get("/api/contests/" + contestId + "/payouts")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isConflict());
    }

    // ---------- helpers ----------

    private record Fixture(Registered organizer, long contestId, Registered a, Registered b, long mi, long csk) {
    }

    /** A 100-rupee standalone contest with the organizer and two confirmed joiners. */
    private Fixture standaloneWithTwoJoiners() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered a = TestAuth.register(mvc, mapper);
        Registered b = TestAuth.register(mvc, mapper);
        confirmJoin(organizer, contestId, a);
        confirmJoin(organizer, contestId, b);
        return new Fixture(organizer, contestId, a, b,
                TestContests.team(jdbc, "MI"), TestContests.team(jdbc, "CSK"));
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

    /** Locks the contest and confirms the given winner, which is what creates the payouts. */
    private void declare(Fixture f, long winningTeamId) throws Exception {
        TestContests.lock(jdbc, f.contestId());
        String token = previewToken(f.organizer(), f.contestId(), Map.of("winningTeamId", winningTeamId));
        result(f.organizer(), f.contestId(), "confirm",
                Map.of("winningTeamId", winningTeamId, "previewToken", token))
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

    private ResultActions payouts(Registered who, long contestId) throws Exception {
        return mvc.perform(get("/api/contests/" + contestId + "/payouts")
                .header("Authorization", who.bearer())).andExpect(status().isOk());
    }

    private ResultActions updateStatus(Registered who, long contestId, long payoutId, Map<String, Object> body)
            throws Exception {
        return mvc.perform(put("/api/contests/" + contestId + "/payouts/" + payoutId + "/status")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private ResultActions markPaid(Registered who, long contestId, long payoutId) throws Exception {
        return updateStatus(who, contestId, payoutId, Map.of("status", "PAID_OUT"));
    }

    private long firstPendingPayout(Fixture f) {
        return jdbc.queryForObject("select id from payouts where contest_id = ? and payout_status = 'PENDING' "
                + "order by id limit 1", Long.class, f.contestId());
    }

    private String contestStatus(long contestId) {
        return jdbc.queryForObject("select contest_status from contests where id = ?", String.class, contestId);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
