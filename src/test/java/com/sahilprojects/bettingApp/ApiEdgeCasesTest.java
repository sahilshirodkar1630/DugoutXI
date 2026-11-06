package com.sahilprojects.bettingApp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestLeagues.TestLeague;
import com.sahilprojects.bettingApp.contest.ContestStatus;
import com.sahilprojects.bettingApp.jobs.ContestStartJob;
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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The edges of the API that no single feature test owns: what comes back for malformed requests, the response
 * fields the screens rely on, the admin listings, and the gaps found while reviewing the services.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApiEdgeCasesTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContestStartJob startJob;

    // ---------- malformed requests always come back as a readable problem ----------

    @Test
    void brokenJsonIsA400WithADetail() throws Exception {
        Registered me = TestAuth.register(mvc, mapper);
        mvc.perform(post("/api/leagues").header("Authorization", me.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNREADABLE_BODY"))
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    @Test
    void aDateThatDoesNotParseIsA400() throws Exception {
        Registered me = TestAuth.register(mvc, mapper);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("teamAId", 1);
        body.put("teamBId", 2);
        body.put("startTime", "next tuesday");
        mvc.perform(post("/api/contests").header("Authorization", me.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNREADABLE_BODY"));
    }

    @Test
    void anIdThatIsNotANumberIsA400NotA500() throws Exception {
        Registered me = TestAuth.register(mvc, mapper);
        mvc.perform(get("/api/leagues/abc").header("Authorization", me.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_PARAMETER"));
    }

    @Test
    void anUnknownStatusFilterIsA400() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        mvc.perform(get("/api/leagues/" + league.id() + "/members?status=NOT_A_STATUS")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnknownRouteIsA404Problem() throws Exception {
        Registered me = TestAuth.register(mvc, mapper);
        mvc.perform(get("/api/nothing-here").header("Authorization", me.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void theWrongMethodIsA405() throws Exception {
        Registered me = TestAuth.register(mvc, mapper);
        mvc.perform(delete("/api/me").header("Authorization", me.bearer()))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void noTokenAGarbageTokenAndAMissingBearerPrefixAreAll401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer not.a.jwt")).andExpect(status().isUnauthorized());
        Registered me = TestAuth.register(mvc, mapper);
        mvc.perform(get("/api/me").header("Authorization", me.token())).andExpect(status().isUnauthorized());
    }

    @Test
    void aZeroOrNegativeLimitIsTreatedAsOne() throws Exception {
        Registered me = TestAuth.register(mvc, mapper);
        mvc.perform(get("/api/notifications?limit=0").header("Authorization", me.bearer())).andExpect(status().isOk());
        mvc.perform(get("/api/notifications?limit=-5").header("Authorization", me.bearer())).andExpect(status().isOk());
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        mvc.perform(get("/api/admin/users?limit=0").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    // ---------- the fields the screens rely on ----------

    @Test
    void aLeagueSaysHowManyContestsItHasAndWhetherIHavePaid() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        Registered friend = TestAuth.register(mvc, mapper);
        TestLeagues.request(mvc, mapper, league, friend);
        TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");

        JsonNode mine = read(mvc.perform(get("/api/leagues/" + league.id())
                .header("Authorization", league.organizer().bearer())).andExpect(status().isOk()));
        assertThat(mine.get("contestsCreated").asInt()).isEqualTo(1);
        assertThat(mine.get("myMembershipStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(mine.get("myPaymentStatus").asText()).isEqualTo("PAID");
        assertThat(mine.get("organizerDisplayName").asText()).isNotBlank();

        JsonNode theirs = read(mvc.perform(get("/api/leagues/" + league.id())
                .header("Authorization", friend.bearer())).andExpect(status().isOk()));
        assertThat(theirs.get("myMembershipStatus").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(theirs.get("myPaymentStatus").asText()).isEqualTo("PENDING");
        assertThat(theirs.get("inviteCode").isNull()).isTrue();          // only the organizer gets the code

        JsonNode list = read(mvc.perform(get("/api/leagues")
                .header("Authorization", league.organizer().bearer())).andExpect(status().isOk()));
        assertThat(list.get(0).get("confirmedMembers").asInt()).isEqualTo(1);
    }

    @Test
    void aStrangerCanSeeALeagueByItsInviteCodeButNotByItsId() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        String code = read(mvc.perform(get("/api/leagues/" + league.id())
                .header("Authorization", league.organizer().bearer()))).get("inviteCode").asText();
        Registered stranger = TestAuth.register(mvc, mapper);

        mvc.perform(get("/api/leagues/" + league.id()).header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/leagues/invite/" + code.toLowerCase()).header("Authorization", stranger.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(league.id()))
                .andExpect(jsonPath("$.myMembershipStatus").doesNotExist());
        mvc.perform(get("/api/leagues/invite/NOPE0000").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());

        // Once they have asked, the league page opens for them.
        TestLeagues.request(mvc, mapper, league, stranger);
        mvc.perform(get("/api/leagues/" + league.id()).header("Authorization", stranger.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void aContestCarriesItsWinnerLockTimeAndMyPlace() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered friend = TestAuth.register(mvc, mapper);
        mvc.perform(post("/api/contests/" + contestId + "/join-requests").header("Authorization", friend.bearer()))
                .andExpect(status().isCreated());

        JsonNode seen = read(mvc.perform(get("/api/contests/" + contestId)
                .header("Authorization", friend.bearer())).andExpect(status().isOk()));
        assertThat(seen.get("myParticipantStatus").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(seen.get("standalone").asBoolean()).isTrue();
        assertThat(seen.get("winningTeam").isNull()).isTrue();
        assertThat(seen.get("lockedAt").isNull()).isTrue();

        mvc.perform(post("/api/contests/" + contestId + "/cancel").header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"rain all week\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cancelledReason").value("rain all week"));

        JsonNode mine = read(mvc.perform(get("/api/contests/mine")
                .header("Authorization", organizer.bearer())).andExpect(status().isOk()));
        assertThat(mine.get(0).has("confirmedParticipants")).isTrue();
        assertThat(mine.get(0).has("winningTeam")).isTrue();
    }

    @Test
    void aWithdrawnContestRequestCanBeMadeAgain() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered friend = TestAuth.register(mvc, mapper);
        mvc.perform(post("/api/contests/" + contestId + "/join-requests").header("Authorization", friend.bearer()))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/contests/" + contestId + "/join-requests/me").header("Authorization", friend.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(post("/api/contests/" + contestId + "/join-requests").header("Authorization", friend.bearer()))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        // The organizer cannot withdraw from their own contest.
        mvc.perform(delete("/api/contests/" + contestId + "/join-requests/me")
                        .header("Authorization", organizer.bearer()))
                .andExpect(status().isConflict());
    }

    // ---------- league notifications ----------

    @Test
    void theOrganizerHearsAboutAJoinRequestAndTheMemberHearsTheDecision() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        Registered friend = TestAuth.register(mvc, mapper);
        long memberId = TestLeagues.request(mvc, mapper, league, friend);

        assertThat(read(inbox(league.organizer()))).anySatisfy(n ->
                assertThat(n.get("type").asText()).isEqualTo("JOIN_REQUEST_RECEIVED"));

        mvc.perform(post("/api/leagues/" + league.id() + "/members/" + memberId + "/accept")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":false}"))
                .andExpect(status().isOk());
        assertThat(read(inbox(friend))).anySatisfy(n -> {
            assertThat(n.get("type").asText()).isEqualTo("JOIN_REQUEST_DECISION");
            assertThat(n.get("message").asText()).contains("1000");
        });
    }

    // ---------- league edits keep contests after the payment deadline ----------

    @Test
    void thePaymentDeadlineCannotBeMovedPastTheFirstContest() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");      // starts in eight days
        JsonNode current = read(mvc.perform(get("/api/leagues/" + league.id())
                .header("Authorization", league.organizer().bearer())));

        Map<String, Object> body = editBody(current);
        body.put("paymentDeadline", Instant.now().plus(9, ChronoUnit.DAYS).toString());
        mvc.perform(put("/api/leagues/" + league.id()).header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("first contest")));

        body.put("paymentDeadline", Instant.now().plus(7, ChronoUnit.DAYS).plus(3, ChronoUnit.HOURS).toString());
        mvc.perform(put("/api/leagues/" + league.id()).header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
    }

    @Test
    void aStaleVersionIsRefusedSoATabCannotOverwriteANewerEdit() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        JsonNode current = read(mvc.perform(get("/api/leagues/" + league.id())
                .header("Authorization", league.organizer().bearer())));
        Map<String, Object> body = editBody(current);
        body.put("name", "First edit");
        mvc.perform(put("/api/leagues/" + league.id()).header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isOk());
        body.put("name", "Second tab, old version");
        mvc.perform(put("/api/leagues/" + league.id()).header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));
    }

    // ---------- the start job closes unanswered requests ----------

    @Test
    void aRequestNobodyAnsweredIsClosedWhenTheContestStarts() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered player = TestAuth.register(mvc, mapper);
        Registered waiting = TestAuth.register(mvc, mapper);
        long playerRow = read(mvc.perform(post("/api/contests/" + contestId + "/join-requests")
                .header("Authorization", player.bearer()))).get("participantId").asLong();
        mvc.perform(post("/api/contests/" + contestId + "/participants/" + playerRow + "/accept")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":true}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/contests/" + contestId + "/join-requests").header("Authorization", waiting.bearer()))
                .andExpect(status().isCreated());

        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));
        assertThat(startJob.startOne(contestId)).isEqualTo(ContestStatus.LOCKED);

        assertThat(jdbc.queryForObject("select participant_status from contest_participants "
                + "where contest_id = ? and user_id = ?", String.class, contestId, waiting.id())).isEqualTo("CANCELLED");
        assertThat(read(inbox(waiting))).anySatisfy(n ->
                assertThat(n.get("title").asText()).contains("has started without you"));
    }

    // ---------- admin listings ----------

    @Test
    void adminsSeeRetiredTournamentsAndInactiveTeamsButUsersCannotListThem() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        Registered user = TestAuth.register(mvc, mapper);
        long tournamentId = read(mvc.perform(post("/api/admin/tournaments").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Retired Cup " + System.nanoTime() + "\"}"))
                .andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(put("/api/admin/tournaments/" + tournamentId + "/active").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"reason\":\"over\"}"))
                .andExpect(status().isOk());

        JsonNode all = read(mvc.perform(get("/api/admin/tournaments").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()));
        assertThat(all).anySatisfy(t -> assertThat(t.get("id").asLong()).isEqualTo(tournamentId));
        JsonNode picker = read(mvc.perform(get("/api/tournaments").header("Authorization", user.bearer())));
        assertThat(picker).noneSatisfy(t -> assertThat(t.get("id").asLong()).isEqualTo(tournamentId));

        mvc.perform(get("/api/admin/teams").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(10)));
        long ipl = TestContests.ipl(jdbc);
        mvc.perform(get("/api/admin/tournaments/" + ipl + "/teams").header("Authorization", admin.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(10)));

        mvc.perform(get("/api/admin/tournaments").header("Authorization", user.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/teams").header("Authorization", user.bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void removingATeamFromATournamentTakesTheReasonAsAQueryParameter() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournamentId = read(mvc.perform(post("/api/admin/tournaments").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cup " + System.nanoTime() + "\"}"))).get("id").asLong();
        long mi = TestContests.team(jdbc, "MI");
        mvc.perform(put("/api/admin/tournaments/" + tournamentId + "/teams/" + mi)
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/admin/tournaments/" + tournamentId + "/teams/" + mi)
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isBadRequest());                 // no reason
        mvc.perform(delete("/api/admin/tournaments/" + tournamentId + "/teams/" + mi + "?reason=added%20by%20mistake")
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isNoContent());
    }

    // ---------- helpers ----------

    private Map<String, Object> editBody(JsonNode league) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", league.get("version").asInt());
        body.put("name", league.get("name").asText());
        body.put("amountPerContest", league.get("amountPerContest").decimalValue());
        body.put("totalContests", league.get("totalContests").asInt());
        body.put("registrationStart", league.get("registrationStart").asText());
        body.put("registrationDeadline", league.get("registrationDeadline").asText());
        body.put("paymentDeadline", league.get("paymentDeadline").asText());
        return body;
    }

    private ResultActions inbox(Registered who) throws Exception {
        return mvc.perform(get("/api/notifications").header("Authorization", who.bearer())).andExpect(status().isOk());
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
