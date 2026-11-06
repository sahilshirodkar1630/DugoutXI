package com.sahilprojects.bettingApp.contest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestLeagues;
import com.sahilprojects.bettingApp.TestLeagues.TestLeague;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contests and enrolment over real HTTP against a real Postgres. */
class ContestFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    // ---------- helpers ----------

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private static String uniqueCode() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private long team(String shortCode) {
        return jdbc.queryForObject("select id from teams where short_code = ?", Long.class, shortCode);
    }

    private long ipl() {
        return jdbc.queryForObject("select id from tournaments where name = 'IPL 2026'", Long.class);
    }

    private TestLeague league(int amount, int total) throws Exception {
        return TestLeagues.create(mvc, mapper, jdbc, amount, total);
    }

    private TestLeague openLeague(int amount, int total) throws Exception {
        TestLeague league = league(amount, total);
        TestLeagues.open(mvc, league);
        return league;
    }

    /** MI v CSK, starting in the given number of days (the league's payment deadline is in 7 days). */
    private Map<String, Object> leagueContest(long leagueId, int startInDays) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leagueId", leagueId);
        body.put("teamAId", team("MI"));
        body.put("teamBId", team("CSK"));
        body.put("startTime", Instant.now().plus(startInDays, ChronoUnit.DAYS).toString());
        return body;
    }

    private Map<String, Object> standaloneContest() {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tournamentId", ipl());
        body.put("teamAId", team("RCB"));
        body.put("teamBId", team("KKR"));
        body.put("startTime", now.plus(3, ChronoUnit.DAYS).toString());
        body.put("amount", 500);
        body.put("registrationDeadline", now.plus(1, ChronoUnit.DAYS).toString());
        body.put("paymentDeadline", now.plus(2, ChronoUnit.DAYS).toString());
        body.put("paymentInstructions", "Pay to solo@okbank");
        return body;
    }

    private ResultActions postContest(Registered who, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/contests").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode createContest(Registered who, Map<String, Object> body) throws Exception {
        return read(postContest(who, body).andExpect(status().isCreated()));
    }

    private ResultActions getContest(Registered who, long id) throws Exception {
        return mvc.perform(get("/api/contests/" + id).header("Authorization", who.bearer()));
    }

    private int participantRows(long contestId) {
        return jdbc.queryForObject("select count(*) from contest_participants where contest_id = ?", Integer.class, contestId);
    }

    private Map<String, Object> participant(long contestId, long userId) {
        return jdbc.queryForMap("""
                select participant_status, payment_status, league_member_id, selected_team_id, removed_at, confirmed_at
                  from contest_participants where contest_id = ? and user_id = ?
                """, contestId, userId);
    }

    private Map<String, Object> lastAudit(long contestId, String action) {
        return jdbc.queryForMap("""
                select actor_type, reason, visible_to_members, before_data::text as b, after_data::text as a
                  from audit_log where entity_type = 'CONTEST' and entity_id = ? and action = ? order by id desc limit 1
                """, contestId, action);
    }

    // ---------- creating a league contest ----------

    @Test
    void organizerCreatesALeagueContestWithTheLeaguesAmountAndTournament() throws Exception {
        TestLeague league = league(200, 74);

        JsonNode contest = createContest(league.organizer(), leagueContest(league.id(), 8));
        long id = contest.get("id").asLong();

        assertThat(contest.get("standalone").asBoolean()).isFalse();
        assertThat(contest.get("leagueId").asLong()).isEqualTo(league.id());
        assertThat(contest.get("contestAmount").decimalValue()).isEqualByComparingTo("200");   // copied from the league, not typed
        assertThat(contest.get("tournamentName").asText()).isEqualTo("IPL 2026");
        assertThat(contest.get("contestNumber").asInt()).isEqualTo(1);
        assertThat(contest.get("status").asText()).isEqualTo("SCHEDULED");
        assertThat(contest.get("teamA").get("shortCode").asText()).isEqualTo("MI");
        assertThat(contest.get("teamB").get("shortCode").asText()).isEqualTo("CSK");
        assertThat(contest.get("organizer").asBoolean()).isTrue();
        assertThat(contest.get("confirmedParticipants").asLong()).isEqualTo(1);        // the organizer
        assertThat(contest.get("myParticipantStatus").asText()).isEqualTo("CONFIRMED");

        Map<String, Object> own = participant(id, league.organizer().id());
        assertThat(own.get("participant_status")).isEqualTo("CONFIRMED");
        assertThat(own.get("payment_status")).isEqualTo("NOT_REQUIRED");
        assertThat(own.get("league_member_id")).isNotNull();
        Map<String, Object> audit = lastAudit(id, "CONTEST_CREATED");
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat(audit.get("visible_to_members")).isEqualTo(false);
    }

    @Test
    void onlyConfirmedMembersAreEnrolledWhenAContestIsCreated() throws Exception {
        TestLeague league = openLeague(200, 74);
        Registered confirmedOne = TestAuth.register(mvc, mapper);
        Registered confirmedTwo = TestAuth.register(mvc, mapper);
        Registered removed = TestAuth.register(mvc, mapper);
        Registered pending = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, confirmedOne);
        TestLeagues.joinConfirmed(mvc, mapper, league, confirmedTwo);
        long removedMember = TestLeagues.joinConfirmed(mvc, mapper, league, removed);
        TestLeagues.reverse(mvc, league, removedMember, "REMOVED");
        TestLeagues.request(mvc, mapper, league, pending);

        JsonNode contest = createContest(league.organizer(), leagueContest(league.id(), 8));
        long id = contest.get("id").asLong();

        assertThat(contest.get("confirmedParticipants").asLong()).isEqualTo(3);          // organizer + two confirmed
        assertThat(participantRows(id)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from contest_participants where contest_id = ? and user_id in (?, ?)",
                Integer.class, id, removed.id(), pending.id())).isZero();
    }

    @Test
    void contestsCanBeCreatedWhileTheLeagueIsUpcomingOpenOrActive() throws Exception {
        TestLeague upcoming = league(100, 10);
        createContest(upcoming.organizer(), leagueContest(upcoming.id(), 8));

        TestLeague open = openLeague(100, 10);
        createContest(open.organizer(), leagueContest(open.id(), 8));

        TestLeague active = openLeague(100, 10);
        jdbc.update("update leagues set status = 'ACTIVE' where id = ?", active.id());
        createContest(active.organizer(), leagueContest(active.id(), 8));
    }

    @Test
    void noNewContestsOnceTheLeagueHasEndedOrBeenCancelled() throws Exception {
        TestLeague ended = openLeague(100, 10);
        jdbc.update("update leagues set status = 'PENDING_SETTLEMENT' where id = ?", ended.id());
        TestLeague cancelled = openLeague(100, 10);
        jdbc.update("update leagues set status = 'CANCELLED' where id = ?", cancelled.id());

        postContest(ended.organizer(), leagueContest(ended.id(), 8)).andExpect(status().isConflict());
        postContest(cancelled.organizer(), leagueContest(cancelled.id(), 8)).andExpect(status().isConflict());
    }

    @Test
    void leagueContestValidation() throws Exception {
        TestLeague league = league(100, 10);
        Registered org = league.organizer();

        // in the past
        Map<String, Object> past = leagueContest(league.id(), 8);
        past.put("startTime", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        postContest(org, past).andExpect(status().isBadRequest());

        // in the future but before the league's payment deadline (7 days)
        postContest(org, leagueContest(league.id(), 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("payment deadline")));

        // the same team twice
        Map<String, Object> sameTeam = leagueContest(league.id(), 8);
        sameTeam.put("teamBId", team("MI"));
        postContest(org, sameTeam).andExpect(status().isBadRequest());

        // a team that does not play in the league's tournament
        jdbc.update("insert into teams (name, short_code) values (?, ?)", "Outsiders " + UUID.randomUUID(), uniqueCode());
        long outsider = jdbc.queryForObject("select max(id) from teams", Long.class);
        Map<String, Object> notInTournament = leagueContest(league.id(), 8);
        notInTournament.put("teamBId", outsider);
        postContest(org, notInTournament).andExpect(status().isBadRequest());

        // an inactive team that is in the tournament
        jdbc.update("insert into teams (name, short_code, is_active) values (?, ?, false)", "Retired " + UUID.randomUUID(), uniqueCode());
        long retired = jdbc.queryForObject("select max(id) from teams", Long.class);
        jdbc.update("insert into tournament_teams (tournament_id, team_id) values (?, ?)", ipl(), retired);
        Map<String, Object> inactive = leagueContest(league.id(), 8);
        inactive.put("teamBId", retired);
        postContest(org, inactive).andExpect(status().isBadRequest());
        jdbc.update("delete from tournament_teams where team_id = ?", retired);          // keep the IPL team count at 10 for other tests

        // unknown team and unknown league
        Map<String, Object> unknownTeam = leagueContest(league.id(), 8);
        unknownTeam.put("teamAId", 999999);
        postContest(org, unknownTeam).andExpect(status().isNotFound());
        postContest(org, leagueContest(999999, 8)).andExpect(status().isNotFound());

        // the amount and deadlines belong to the league
        Map<String, Object> withAmount = leagueContest(league.id(), 8);
        withAmount.put("amount", 500);
        postContest(org, withAmount).andExpect(status().isBadRequest());

        // missing start time
        Map<String, Object> noStart = leagueContest(league.id(), 8);
        noStart.remove("startTime");
        postContest(org, noStart).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.startTime").exists());
    }

    @Test
    void onlyTheLeagueOrganizerCanCreateLeagueContests() throws Exception {
        TestLeague league = openLeague(100, 10);
        Registered member = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, member);
        Registered stranger = TestAuth.register(mvc, mapper);

        postContest(member, leagueContest(league.id(), 8)).andExpect(status().isForbidden());
        postContest(stranger, leagueContest(league.id(), 8)).andExpect(status().isForbidden());
        mvc.perform(post("/api/contests").contentType(MediaType.APPLICATION_JSON).content(json(leagueContest(league.id(), 8))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void contestsStopAtThePlannedTotalAndACancelledOneFreesItsSlotAndNumber() throws Exception {
        TestLeague league = league(100, 2);
        Registered org = league.organizer();
        JsonNode first = createContest(org, leagueContest(league.id(), 8));
        createContest(org, leagueContest(league.id(), 9));

        postContest(org, leagueContest(league.id(), 10)).andExpect(status().isConflict());

        mvc.perform(post("/api/contests/" + first.get("id").asLong() + "/cancel").header("Authorization", org.bearer()))
                .andExpect(status().isOk());
        JsonNode replacement = createContest(org, leagueContest(league.id(), 10));
        assertThat(replacement.get("contestNumber").asInt()).isEqualTo(1);              // the lowest free number is reused
    }

    @Test
    void contestNumbersCanBeChosenButNotDuplicatedOrOutOfRange() throws Exception {
        TestLeague league = league(100, 74);
        Registered org = league.organizer();

        Map<String, Object> five = leagueContest(league.id(), 8);
        five.put("contestNumber", 5);
        assertThat(createContest(org, five).get("contestNumber").asInt()).isEqualTo(5);

        postContest(org, five).andExpect(status().isConflict());                         // 5 is taken
        Map<String, Object> tooHigh = leagueContest(league.id(), 8);
        tooHigh.put("contestNumber", 75);
        postContest(org, tooHigh).andExpect(status().isBadRequest());
        assertThat(createContest(org, leagueContest(league.id(), 8)).get("contestNumber").asInt()).isEqualTo(1);
    }

    // ---------- enrolment of members who join or leave later ----------

    @Test
    void aMemberConfirmedLaterIsEnrolledInUnstartedContestsOnly() throws Exception {
        TestLeague league = openLeague(200, 74);
        long future = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();
        jdbc.update("""
                insert into contests (league_id, organizer_id, tournament_id, team_a_id, team_b_id, start_time, contest_amount)
                values (?, ?, ?, ?, ?, now() - interval '1 hour', 200)
                """, league.id(), league.organizer().id(), ipl(), team("MI"), team("CSK"));
        long started = jdbc.queryForObject("select max(id) from contests where league_id = ?", Long.class, league.id());

        Registered late = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, late);

        Map<String, Object> inFuture = participant(future, late.id());
        assertThat(inFuture.get("participant_status")).isEqualTo("CONFIRMED");
        assertThat(inFuture.get("payment_status")).isEqualTo("NOT_REQUIRED");
        assertThat(inFuture.get("league_member_id")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from contest_participants where contest_id = ? and user_id = ?",
                Integer.class, started, late.id())).isZero();                            // a started contest is never touched
        getContest(league.organizer(), future).andExpect(jsonPath("$.confirmedParticipants").value(2));
    }

    @Test
    void reversingAMemberTakesThemOutOfUnstartedContestsAndConfirmingAgainBringsThemBack() throws Exception {
        TestLeague league = openLeague(200, 74);
        long contest = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = TestLeagues.joinConfirmed(mvc, mapper, league, user);
        Map<String, Object> before = participant(contest, user.id());
        jdbc.update("update contest_participants set selected_team_id = ?, predicted_at = now() where contest_id = ? and user_id = ?",
                team("MI"), contest, user.id());                                         // they had made a prediction

        TestLeagues.reverse(mvc, league, memberId, "ACCEPTED_UNPAID");

        Map<String, Object> removed = participant(contest, user.id());
        assertThat(removed.get("participant_status")).isEqualTo("REMOVED");
        assertThat(removed.get("removed_at")).isNotNull();
        assertThat(removed.get("selected_team_id")).isNull();                            // the old pick does not linger
        getContest(league.organizer(), contest).andExpect(jsonPath("$.confirmedParticipants").value(1));

        mvc.perform(post("/api/leagues/" + league.id() + "/members/" + memberId + "/confirm-payment")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk());

        Map<String, Object> back = participant(contest, user.id());
        assertThat(back.get("participant_status")).isEqualTo("CONFIRMED");
        assertThat(back.get("removed_at")).isNull();
        assertThat(participantRows(contest)).isEqualTo(2);                               // the same row was reused, none added
        assertThat(before.get("league_member_id")).isEqualTo(back.get("league_member_id"));
    }

    // ---------- standalone contests ----------

    @Test
    void anyoneCanCreateAStandaloneContestAndIsEnrolledAsPaid() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);

        JsonNode contest = createContest(user, standaloneContest());
        long id = contest.get("id").asLong();

        assertThat(contest.get("standalone").asBoolean()).isTrue();
        assertThat(contest.get("leagueId").isNull()).isTrue();
        assertThat(contest.get("contestNumber").isNull()).isTrue();
        assertThat(contest.get("contestAmount").decimalValue()).isEqualByComparingTo("500");
        assertThat(contest.get("paymentInstructions").asText()).isEqualTo("Pay to solo@okbank");
        assertThat(contest.get("confirmedParticipants").asLong()).isEqualTo(1);
        Map<String, Object> own = participant(id, user.id());
        assertThat(own.get("participant_status")).isEqualTo("CONFIRMED");
        assertThat(own.get("payment_status")).isEqualTo("PAID");
        assertThat(own.get("league_member_id")).isNull();
    }

    @Test
    void standaloneContestValidation() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        Instant now = Instant.now();

        Map<String, Object> noAmount = standaloneContest();
        noAmount.remove("amount");
        postContest(user, noAmount).andExpect(status().isBadRequest());

        Map<String, Object> noDeadlines = standaloneContest();
        noDeadlines.remove("registrationDeadline");
        postContest(user, noDeadlines).andExpect(status().isBadRequest());

        Map<String, Object> paymentAfterStart = standaloneContest();
        paymentAfterStart.put("paymentDeadline", now.plus(4, ChronoUnit.DAYS).toString());      // start is in 3 days
        postContest(user, paymentAfterStart).andExpect(status().isBadRequest());

        Map<String, Object> paymentBeforeRegistration = standaloneContest();
        paymentBeforeRegistration.put("registrationDeadline", now.plus(2, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS).toString());
        postContest(user, paymentBeforeRegistration).andExpect(status().isBadRequest());

        Map<String, Object> registrationInThePast = standaloneContest();
        registrationInThePast.put("registrationDeadline", now.minus(1, ChronoUnit.HOURS).toString());
        postContest(user, registrationInThePast).andExpect(status().isBadRequest());

        Map<String, Object> withNumber = standaloneContest();
        withNumber.put("contestNumber", 3);
        postContest(user, withNumber).andExpect(status().isBadRequest());

        Map<String, Object> unknownTournament = standaloneContest();
        unknownTournament.put("tournamentId", 999999);
        postContest(user, unknownTournament).andExpect(status().isNotFound());

        Map<String, Object> zeroAmount = standaloneContest();
        zeroAmount.put("amount", 0);
        postContest(user, zeroAmount).andExpect(status().isBadRequest());
    }

    @Test
    void aStandaloneContestIsVisibleToAnyoneButOnlyTheOrganizerSeesHowToPay() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered stranger = TestAuth.register(mvc, mapper);
        long id = createContest(organizer, standaloneContest()).get("id").asLong();

        getContest(organizer, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentInstructions").value("Pay to solo@okbank"));
        getContest(stranger, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentInstructions").isEmpty())
                .andExpect(jsonPath("$.myParticipantStatus").isEmpty())
                .andExpect(jsonPath("$.organizer").value(false));
    }

    @Test
    void myStandaloneContestsListsOnlyMine() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        long id = createContest(organizer, standaloneContest()).get("id").asLong();

        mvc.perform(get("/api/contests/mine").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].myParticipantStatus").value("CONFIRMED"));
        mvc.perform(get("/api/contests/mine").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    // ---------- viewing league contests ----------

    @Test
    void leagueContestsAreVisibleToMembersAndNeverShowPredictions() throws Exception {
        TestLeague league = openLeague(200, 74);
        Registered member = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, member);
        Registered pending = TestAuth.register(mvc, mapper);
        TestLeagues.request(mvc, mapper, league, pending);
        Registered stranger = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();

        JsonNode asMember = read(getContest(member, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.myParticipantStatus").value("CONFIRMED")));
        getContest(league.organizer(), id).andExpect(status().isOk());
        getContest(admin, id).andExpect(status().isOk());
        getContest(pending, id).andExpect(status().isForbidden());
        getContest(stranger, id).andExpect(status().isForbidden());
        getContest(member, 999999).andExpect(status().isNotFound());

        // nobody's pick is ever part of this response
        assertThat(asMember.fieldNames()).toIterable()
                .noneMatch(name -> name.toLowerCase().contains("select") || name.toLowerCase().contains("predict"));
    }

    @Test
    void theLeagueContestListIsInStartOrderAndForMembersOnly() throws Exception {
        TestLeague league = openLeague(200, 74);
        long later = createContest(league.organizer(), leagueContest(league.id(), 10)).get("id").asLong();
        long sooner = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();
        Registered stranger = TestAuth.register(mvc, mapper);
        mvc.perform(post("/api/contests/" + later + "/cancel").header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk());

        JsonNode list = read(mvc.perform(get("/api/leagues/" + league.id() + "/contests")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk()));

        assertThat(list).hasSize(2);                                                     // the cancelled one is listed too
        assertThat(list.get(0).get("id").asLong()).isEqualTo(sooner);
        assertThat(list.get(1).get("id").asLong()).isEqualTo(later);
        assertThat(list.get(1).get("status").asText()).isEqualTo("CANCELLED");
        mvc.perform(get("/api/leagues/" + league.id() + "/contests").header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden());
    }

    // ---------- changing the start time ----------

    private ResultActions changeStart(Registered who, long contestId, Instant newStart, String reason) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startTime", newStart.toString());
        if (reason != null) body.put("reason", reason);
        return mvc.perform(patch("/api/contests/" + contestId + "/start-time").header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    @Test
    void organizerMovesTheStartTimeAndMembersCanSeeTheHistory() throws Exception {
        TestLeague league = league(200, 74);
        JsonNode contest = createContest(league.organizer(), leagueContest(league.id(), 8));
        long id = contest.get("id").asLong();
        Instant newStart = Instant.now().plus(9, ChronoUnit.DAYS);

        changeStart(league.organizer(), id, newStart, null).andExpect(status().isOk());

        Instant stored = jdbc.queryForObject("select start_time from contests where id = ?", java.sql.Timestamp.class, id).toInstant();
        assertThat(stored.truncatedTo(ChronoUnit.SECONDS)).isEqualTo(newStart.truncatedTo(ChronoUnit.SECONDS));
        Map<String, Object> audit = lastAudit(id, "START_TIME_CHANGED");
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat(audit.get("visible_to_members")).isEqualTo(true);                     // members can see start-time changes
        assertThat((String) audit.get("b")).contains(contest.get("startTime").asText().substring(0, 16));
    }

    @Test
    void startTimeChangeRules() throws Exception {
        TestLeague league = league(200, 74);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();

        changeStart(league.organizer(), id, Instant.now().minus(1, ChronoUnit.HOURS), null).andExpect(status().isBadRequest());
        changeStart(league.organizer(), id, Instant.now().plus(2, ChronoUnit.DAYS), null).andExpect(status().isBadRequest());   // before the payment deadline

        int auditRows = jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action = 'START_TIME_CHANGED'",
                Integer.class, id);
        Instant current = jdbc.queryForObject("select start_time from contests where id = ?", java.sql.Timestamp.class, id).toInstant();
        changeStart(league.organizer(), id, current, null).andExpect(status().isOk());              // same time: nothing changes
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action = 'START_TIME_CHANGED'",
                Integer.class, id)).isEqualTo(auditRows);
    }

    @Test
    void theStartTimeCannotChangeOnceTheContestHasStarted() throws Exception {
        TestLeague league = league(200, 74);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();
        jdbc.update("update contests set start_time = now() - interval '1 minute' where id = ?", id);

        changeStart(league.organizer(), id, Instant.now().plus(9, ChronoUnit.DAYS), null).andExpect(status().isConflict());
    }

    @Test
    void onlyTheOrganizerOrAnAdminWithAReasonCanChangeTheStartTime() throws Exception {
        TestLeague league = openLeague(200, 74);
        Registered member = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, member);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();
        Instant newStart = Instant.now().plus(9, ChronoUnit.DAYS);

        changeStart(member, id, newStart, null).andExpect(status().isForbidden());
        changeStart(admin, id, newStart, null).andExpect(status().isBadRequest());
        changeStart(admin, id, newStart, "Organizer is unreachable").andExpect(status().isOk());
        assertThat(lastAudit(id, "START_TIME_CHANGED").get("actor_type")).isEqualTo("ADMIN");
    }

    @Test
    void aStandaloneStartTimeCannotMoveBeforeItsPaymentDeadline() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        long id = createContest(user, standaloneContest()).get("id").asLong();      // payment deadline in 2 days, start in 3

        changeStart(user, id, Instant.now().plus(36, ChronoUnit.HOURS), null).andExpect(status().isBadRequest());
        changeStart(user, id, Instant.now().plus(4, ChronoUnit.DAYS), null).andExpect(status().isOk());
    }

    // ---------- cancelling ----------

    @Test
    void organizerCancelsAContestAndMembersCanSeeIt() throws Exception {
        TestLeague league = league(200, 74);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();

        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Match moved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Map<String, Object> audit = lastAudit(id, "CONTEST_CANCELLED");
        assertThat(audit.get("visible_to_members")).isEqualTo(true);
        assertThat(audit.get("reason")).isEqualTo("Match moved");
        assertThat(jdbc.queryForObject("select cancelled_reason from contests where id = ?", String.class, id)).isEqualTo("Match moved");

        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", league.organizer().bearer()))
                .andExpect(status().isConflict());                                       // already cancelled, and no body is fine
    }

    @Test
    void cancelPermissions() throws Exception {
        TestLeague league = openLeague(200, 74);
        Registered member = TestAuth.register(mvc, mapper);
        TestLeagues.joinConfirmed(mvc, mapper, league, member);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();

        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", member.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Rained off"))))
                .andExpect(status().isOk());
        assertThat(lastAudit(id, "CONTEST_CANCELLED").get("actor_type")).isEqualTo("ADMIN");
    }

    @Test
    void aStartedContestCannotBeCancelled() throws Exception {
        TestLeague league = league(200, 74);
        long id = createContest(league.organizer(), leagueContest(league.id(), 8)).get("id").asLong();
        jdbc.update("update contests set contest_status = 'LOCKED' where id = ?", id);

        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", league.organizer().bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void cancellingAStandaloneContestRefundsWhoeverHadPaid() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered payer = TestAuth.register(mvc, mapper);
        long id = createContest(organizer, standaloneContest()).get("id").asLong();
        jdbc.update("insert into contest_participants (contest_id, user_id, participant_status, payment_status) "
                + "values (?, ?, 'CONFIRMED', 'PAID')", id, payer.id());

        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        // The organizer now has a refund to hand to the participant, and their own entry back with nothing to send.
        assertThat(jdbc.queryForMap("select payout_type, payout_status from payouts "
                + "where contest_id = ? and user_id = ?", id, payer.id()))
                .containsEntry("payout_type", "REFUND")
                .containsEntry("payout_status", "PENDING");
        assertThat(jdbc.queryForObject("select payout_status from payouts where contest_id = ? and user_id = ?",
                String.class, id, organizer.id())).isEqualTo("PAID_OUT");
    }

    @Test
    void aStandaloneContestWithNoPayingParticipantCanBeCancelled() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long id = createContest(organizer, standaloneContest()).get("id").asLong();

        mvc.perform(post("/api/contests/" + id + "/cancel").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(jdbc.queryForObject("select contest_status from contests where id = ?", String.class, id))
                .isEqualTo("CANCELLED");
    }
}
