package com.sahilprojects.bettingApp.notification;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The in-app inbox: what lands in it, when a queued reminder is delivered, and reading it. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NotificationFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired NotificationService notificationService;

    @Test
    void aNewContestQueuesRemindersForEveryMember() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        // One an hour before and one a quarter of an hour before, for each of the two members.
        assertThat(queued(contestId, "CONTEST_REMINDER")).isEqualTo(2);
        assertThat(queued(contestId, "PREDICTION_REMINDER")).isEqualTo(2);
    }

    @Test
    void aQueuedReminderStaysOutOfTheInboxUntilItIsDue() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        JsonNode inbox = read(inbox(league.organizer()));
        assertThat(inbox).noneSatisfy(n ->
                assertThat(n.get("type").asText()).isEqualTo("CONTEST_REMINDER"));

        // Bring one reminder's moment forward and let the sender run.
        jdbc.update("update notifications set scheduled_for = now() - interval '1 minute' "
                + "where reference_id = ? and notification_type = 'CONTEST_REMINDER'", contestId);
        assertThat(notificationService.sendDue(50)).isEqualTo(2);

        assertThat(read(inbox(league.organizer()))).anySatisfy(n ->
                assertThat(n.get("type").asText()).isEqualTo("CONTEST_REMINDER"));
    }

    @Test
    void movingAContestThrowsAwayTheOldRemindersAndQueuesNewOnes() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        String originalDue = jdbc.queryForObject("select min(scheduled_for)::text from notifications "
                + "where reference_id = ? and sent_at is null", String.class, contestId);

        mvc.perform(patch("/api/contests/" + contestId + "/start-time")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startTime\":\"" + Instant.now().plus(9, ChronoUnit.DAYS) + "\"}"))
                .andExpect(status().isOk());

        assertThat(queued(contestId, "CONTEST_REMINDER")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select min(scheduled_for)::text from notifications "
                + "where reference_id = ? and sent_at is null", String.class, contestId))
                .isNotEqualTo(originalDue);

        // And everybody is told about the change straight away.
        assertThat(read(inbox(league.organizer()))).anySatisfy(n ->
                assertThat(n.get("type").asText()).isEqualTo("CONTEST_START_TIME_CHANGED"));
    }

    @Test
    void cancellingAContestDropsItsRemindersAndSaysSo() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        mvc.perform(post("/api/contests/" + contestId + "/cancel")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"rained off\"}"))
                .andExpect(status().isOk());

        assertThat(queued(contestId, "CONTEST_REMINDER")).isZero();
        assertThat(read(inbox(league.organizer()))).anySatisfy(n -> {
            assertThat(n.get("type").asText()).isEqualTo("CONTEST_CANCELLED");
            assertThat(n.get("message").asText()).contains("rained off");
        });
    }

    @Test
    void theUnreadCountDropsAsThingsAreRead() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        Registered organizer = league.organizer();

        long before = unreadCount(organizer);
        assertThat(before).isPositive();

        long first = read(inbox(organizer)).get(0).get("id").asLong();
        mvc.perform(post("/api/notifications/" + first + "/read")
                        .header("Authorization", organizer.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readAt").exists());
        assertThat(unreadCount(organizer)).isEqualTo(before - 1);

        mvc.perform(post("/api/notifications/read-all").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk());
        assertThat(unreadCount(organizer)).isZero();
    }

    @Test
    void anInboxIsPrivateToItsOwner() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        Registered stranger = TestAuth.register(mvc, mapper);
        long someoneElses = read(inbox(league.organizer())).get(0).get("id").asLong();

        mvc.perform(post("/api/notifications/" + someoneElses + "/read")
                        .header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
        assertThat(read(inbox(stranger))).isEmpty();
    }

    @Test
    void theSameNotificationIsNotDeliveredTwice() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        // Cancelling is tagged, so a repeat of the same event does not pile up in the inbox.
        mvc.perform(post("/api/contests/" + contestId + "/cancel")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from notifications where reference_id = ? "
                        + "and notification_type = 'CONTEST_CANCELLED' and user_id = ?",
                Integer.class, contestId, league.organizer().id())).isEqualTo(1);
    }

    @Test
    void theSenderSkipsNothingAndReportsWhatItSent() throws Exception {
        TestLeague league = openLeagueWithOneFriend();
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        jdbc.update("update notifications set scheduled_for = now() - interval '1 minute' "
                + "where reference_id = ? and sent_at is null", contestId);

        int sent = notificationService.sendDue(100);
        assertThat(sent).isEqualTo(4);                 // two reminders each for two members
        assertThat(notificationService.sendDue(100)).isZero();   // nothing left to send
    }

    // ---------- helpers ----------

    private TestLeague openLeagueWithOneFriend() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        TestLeagues.joinConfirmed(mvc, mapper, league, TestAuth.register(mvc, mapper));
        return league;
    }

    private ResultActions inbox(Registered who) throws Exception {
        return mvc.perform(get("/api/notifications").header("Authorization", who.bearer()))
                .andExpect(status().isOk());
    }

    private long unreadCount(Registered who) throws Exception {
        return read(mvc.perform(get("/api/notifications/unread-count").header("Authorization", who.bearer()))
                .andExpect(status().isOk())).get("unread").asLong();
    }

    private int queued(long contestId, String type) {
        return jdbc.queryForObject("select count(*) from notifications where reference_id = ? "
                + "and notification_type = ? and sent_at is null", Integer.class, contestId, type);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
