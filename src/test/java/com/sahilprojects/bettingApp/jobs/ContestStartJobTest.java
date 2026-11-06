package com.sahilprojects.bettingApp.jobs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestContests;
import com.sahilprojects.bettingApp.TestLeagues;
import com.sahilprojects.bettingApp.TestLeagues.TestLeague;
import com.sahilprojects.bettingApp.contest.ContestStatus;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What the clock does on its own: locking a contest at its start time, starting a league with its first contest,
 * and cancelling what cannot go ahead.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContestStartJobTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContestStartJob startJob;
    @Autowired PaymentCutoffJob cutoffJob;

    @Test
    void aContestWithEnoughPlayersLocksAtItsStartTime() throws Exception {
        TestLeague league = leagueWithMembers(2);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        due(contestId);

        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.LOCKED);
        assertThat(jdbc.queryForObject("select locked_at is not null from contests where id = ?",
                Boolean.class, contestId)).isTrue();
    }

    @Test
    void lockingAContestStartsItsLeague() throws Exception {
        TestLeague league = leagueWithMembers(2);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        assertThat(leagueStatus(league.id())).isEqualTo("OPEN");
        due(contestId);

        startJob.startOne(contestId);

        assertThat(leagueStatus(league.id())).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select started_at is not null from leagues where id = ?",
                Boolean.class, league.id())).isTrue();
    }

    @Test
    void aRequestStillWaitingWhenTheLeagueStartsIsWithdrawn() throws Exception {
        TestLeague league = leagueWithMembers(2);
        Registered latecomer = TestAuth.register(mvc, mapper);
        TestLeagues.request(mvc, mapper, league, latecomer);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        due(contestId);

        startJob.startOne(contestId);

        assertThat(jdbc.queryForObject("select league_member_status from league_members "
                        + "where league_id = ? and user_id = ?", String.class, league.id(), latecomer.id()))
                .isEqualTo("CANCELLED");
    }

    @Test
    void aLeagueWithTooFewPaidMembersIsCancelledInsteadOfStarting() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);     // the organizer alone
        TestLeagues.open(mvc, league);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        due(contestId);

        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.CANCELLED);
        assertThat(leagueStatus(league.id())).isEqualTo("CANCELLED");
        // The organizer paid in, so there is a row telling them to take their own money back out.
        assertThat(jdbc.queryForObject("select count(*) from settlement_rows where league_id = ?",
                Integer.class, league.id())).isEqualTo(1);
    }

    @Test
    void aStandaloneContestWithTooFewPlayersIsCancelledAndEntriesRefunded() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        due(contestId);

        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.CANCELLED);
        // The only entry is the organizer's own, which needs no transfer back to themselves.
        assertThat(jdbc.queryForObject("select payout_status from payouts where contest_id = ?",
                String.class, contestId)).isEqualTo("PAID_OUT");
    }

    @Test
    void anUnpaidPlaceIsCutOffBeforeTheContestCountsItsPlayers() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered neverPaid = TestAuth.register(mvc, mapper);
        long participantId = Long.parseLong(mapper.readTree(mvc.perform(
                                post("/api/contests/" + contestId + "/join-requests")
                                        .header("Authorization", neverPaid.bearer()))
                        .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("participantId").asText());
        mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/accept")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":false}"))
                .andExpect(status().isOk());

        TestContests.setPaymentDeadline(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(cutoffJob.cutoffStandaloneParticipants()).isEqualTo(1);

        assertThat(jdbc.queryForObject("select participant_status from contest_participants where id = ?",
                String.class, participantId)).isEqualTo("REMOVED");
    }

    @Test
    void theJobIgnoresAContestWhoseStartTimeHasNotArrived() throws Exception {
        TestLeague league = leagueWithMembers(2);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.SCHEDULED);
    }

    @Test
    void runningTheJobTwiceChangesNothingTheSecondTime() throws Exception {
        TestLeague league = leagueWithMembers(2);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        due(contestId);

        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.LOCKED);
        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.LOCKED);
        assertThat(jdbc.queryForObject("select count(*) from audit_log "
                        + "where contest_id = ? and action = 'CONTEST_LOCKED'", Integer.class, contestId))
                .isEqualTo(1);
    }

    @Test
    void everybodyIsToldThatPredictionsAreClosed() throws Exception {
        TestLeague league = leagueWithMembers(2);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        long mi = TestContests.team(jdbc, "MI");
        mvc.perform(put("/api/contests/" + contestId + "/predictions/me")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":" + mi + "}"))
                .andExpect(status().isOk());
        due(contestId);

        startJob.startOne(contestId);

        assertThat(jdbc.queryForObject("select count(*) from notifications "
                        + "where reference_id = ? and notification_type = 'PREDICTIONS_LOCKED'",
                Integer.class, contestId)).isEqualTo(3);
        // The two who never picked are told their entry counts but cannot win.
        assertThat(jdbc.queryForObject("select count(*) from notifications "
                        + "where reference_id = ? and notification_type = 'PREDICTIONS_LOCKED' "
                        + "and message like '%did not make a pick%'", Integer.class, contestId))
                .isEqualTo(2);
    }

    @Test
    void lockingAContestDropsItsQueuedReminders() throws Exception {
        TestLeague league = leagueWithMembers(2);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        assertThat(queuedReminders(contestId)).isPositive();
        due(contestId);

        startJob.startOne(contestId);

        assertThat(queuedReminders(contestId)).isZero();
    }

    // ---------- helpers ----------

    /** An open league with the organizer plus the given number of extra confirmed, paid members. */
    private TestLeague leagueWithMembers(int extraMembers) throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        for (int i = 0; i < extraMembers; i++) {
            TestLeagues.joinConfirmed(mvc, mapper, league, TestAuth.register(mvc, mapper));
        }
        return league;
    }

    /** Makes a contest due to start, without touching its status. */
    private void due(long contestId) {
        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(30, ChronoUnit.SECONDS));
    }

    private String leagueStatus(long leagueId) {
        return jdbc.queryForObject("select status from leagues where id = ?", String.class, leagueId);
    }

    private int queuedReminders(long contestId) {
        return jdbc.queryForObject("select count(*) from notifications where reference_id = ? "
                + "and sent_at is null and notification_type in ('CONTEST_REMINDER','PREDICTION_REMINDER')",
                Integer.class, contestId);
    }
}
