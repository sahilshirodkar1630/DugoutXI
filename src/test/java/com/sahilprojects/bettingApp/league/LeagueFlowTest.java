package com.sahilprojects.bettingApp.league;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Leagues over real HTTP against a real Postgres. Uses the seeded IPL 2026 tournament (read only). */
class LeagueFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LeagueService leagueService;

    // ---------- helpers ----------

    private long iplId() {
        return jdbc.queryForObject("select id from tournaments where name = 'IPL 2026'", Long.class);
    }

    private long team(String shortCode) {
        return jdbc.queryForObject("select id from teams where short_code = ?", Long.class, shortCode);
    }

    private Map<String, Object> createBody() {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Office League");
        body.put("tournamentId", iplId());
        body.put("amountPerContest", 200);
        body.put("totalContests", 74);
        body.put("registrationStart", now.plus(1, ChronoUnit.DAYS).toString());
        body.put("registrationDeadline", now.plus(5, ChronoUnit.DAYS).toString());
        body.put("paymentDeadline", now.plus(7, ChronoUnit.DAYS).toString());
        body.put("paymentInstructions", "Pay to org@okbank");
        return body;
    }

    private Map<String, Object> updateBody(JsonNode league) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", league.get("version").asInt());
        body.put("name", league.get("name").asText());
        body.put("amountPerContest", league.get("amountPerContest").decimalValue());
        body.put("totalContests", league.get("totalContests").asInt());
        body.put("registrationStart", league.get("registrationStart").asText());
        body.put("registrationDeadline", league.get("registrationDeadline").asText());
        body.put("paymentDeadline", league.get("paymentDeadline").asText());
        body.put("paymentInstructions", league.get("paymentInstructions").asText());
        return body;
    }

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private JsonNode createLeague(Registered organizer, Map<String, Object> body) throws Exception {
        return read(mvc.perform(post("/api/leagues").header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()));
    }

    private JsonNode createLeague(Registered organizer) throws Exception {
        return createLeague(organizer, createBody());
    }

    private void addMember(long leagueId, long userId, String status, String payment) {
        jdbc.update("""
                insert into league_members (league_id, user_id, league_member_status, payment_status, paid_at)
                values (?, ?, ?, ?, case when ? = 'PAID' then now() end)
                """, leagueId, userId, status, payment, payment);
    }

    private void insertLeagueContest(long leagueId, long organizerId) {
        jdbc.update("""
                insert into contests (league_id, organizer_id, tournament_id, team_a_id, team_b_id, start_time, contest_amount)
                values (?, ?, ?, ?, ?, now() + interval '3 days', 200)
                """, leagueId, organizerId, iplId(), team("MI"), team("CSK"));
    }

    private ResultActions updateLeague(Registered who, long id, Map<String, Object> body) throws Exception {
        return mvc.perform(put("/api/leagues/" + id).header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private String leagueStatus(long id) {
        return jdbc.queryForObject("select status from leagues where id = ?", String.class, id);
    }

    // ---------- create ----------

    @Test
    void creatorBecomesOrganizerAndAConfirmedPaidMember() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);

        JsonNode league = createLeague(org);
        long id = league.get("id").asLong();

        assertThat(league.get("status").asText()).isEqualTo("UPCOMING");
        assertThat(league.get("joiningAmount").decimalValue()).isEqualByComparingTo("14800");
        assertThat(league.get("organizer").asBoolean()).isTrue();
        assertThat(league.get("confirmedMembers").asLong()).isEqualTo(1);
        assertThat(league.get("myMembershipStatus").asText()).isEqualTo("CONFIRMED");
        assertThat(league.get("inviteCode").asText()).hasSize(10);

        Map<String, Object> member = jdbc.queryForMap(
                "select league_member_status, payment_status, paid_at, confirmed_at from league_members where league_id = ? and user_id = ?",
                id, org.id());
        assertThat(member.get("league_member_status")).isEqualTo("CONFIRMED");
        assertThat(member.get("payment_status")).isEqualTo("PAID");
        assertThat(member.get("paid_at")).isNotNull();
        assertThat(member.get("confirmed_at")).isNotNull();
        assertThat(jdbc.queryForObject("select joining_amount from leagues where id = ?", java.math.BigDecimal.class, id))
                .isEqualByComparingTo("14800");

        Map<String, Object> audit = jdbc.queryForMap(
                "select action, actor_type, visible_to_members from audit_log where entity_type = 'LEAGUE' and entity_id = ?", id);
        assertThat(audit.get("action")).isEqualTo("LEAGUE_CREATED");
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat(audit.get("visible_to_members")).isEqualTo(false);
    }

    @Test
    void leagueWhoseRegistrationStartHasPassedOpensImmediately() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Map<String, Object> body = createBody();
        body.put("registrationStart", Instant.now().minus(1, ChronoUnit.HOURS).toString());

        assertThat(createLeague(org, body).get("status").asText()).isEqualTo("OPEN");
    }

    @Test
    void invalidScheduleIsRefused() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Instant now = Instant.now();

        Map<String, Object> deadlineBeforeStart = createBody();
        deadlineBeforeStart.put("registrationDeadline", now.plus(2, ChronoUnit.HOURS).toString());   // start is in 1 day
        postCreate(org, deadlineBeforeStart, 400);

        Map<String, Object> paymentBeforeRegistrationDeadline = createBody();
        paymentBeforeRegistrationDeadline.put("paymentDeadline", now.plus(2, ChronoUnit.DAYS).toString());   // deadline is in 5 days
        postCreate(org, paymentBeforeRegistrationDeadline, 400);

        Map<String, Object> deadlineInThePast = createBody();
        deadlineInThePast.put("registrationStart", now.minus(3, ChronoUnit.DAYS).toString());
        deadlineInThePast.put("registrationDeadline", now.minus(1, ChronoUnit.DAYS).toString());
        deadlineInThePast.put("paymentDeadline", now.plus(1, ChronoUnit.DAYS).toString());
        postCreate(org, deadlineInThePast, 400);
    }

    private void postCreate(Registered who, Map<String, Object> body, int expectedStatus) throws Exception {
        mvc.perform(post("/api/leagues").header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void invalidAmountsAndCountsFailValidation() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);

        Map<String, Object> zeroAmount = createBody();
        zeroAmount.put("amountPerContest", 0);
        mvc.perform(post("/api/leagues").header("Authorization", org.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(json(zeroAmount)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.amountPerContest").exists());

        Map<String, Object> threeDecimals = createBody();
        threeDecimals.put("amountPerContest", 100.123);
        postCreate(org, threeDecimals, 400);

        Map<String, Object> zeroContests = createBody();
        zeroContests.put("totalContests", 0);
        postCreate(org, zeroContests, 400);

        Map<String, Object> tooManyContests = createBody();
        tooManyContests.put("totalContests", 1001);
        postCreate(org, tooManyContests, 400);

        Map<String, Object> noName = createBody();
        noName.remove("name");
        postCreate(org, noName, 400);
    }

    @Test
    void unknownOrInactiveTournamentIsNotFound() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);

        Map<String, Object> unknown = createBody();
        unknown.put("tournamentId", 999999);
        postCreate(org, unknown, 404);

        jdbc.update("insert into tournaments (name, is_active) values (?, false)", "Closed Cup " + UUID.randomUUID());
        long inactive = jdbc.queryForObject("select max(id) from tournaments", Long.class);
        Map<String, Object> inactiveBody = createBody();
        inactiveBody.put("tournamentId", inactive);
        postCreate(org, inactiveBody, 404);
    }

    @Test
    void creatingNeedsALogin() throws Exception {
        mvc.perform(post("/api/leagues").contentType(MediaType.APPLICATION_JSON).content(json(createBody())))
                .andExpect(status().isUnauthorized());
    }

    // ---------- read ----------

    @Test
    void organizerSeesInviteCodeAndPaymentInstructionsButAStrangerCannotOpenTheLeague() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered stranger = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();

        mvc.perform(get("/api/leagues/" + id).header("Authorization", org.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").isNotEmpty())
                .andExpect(jsonPath("$.paymentInstructions").value("Pay to org@okbank"));
        mvc.perform(get("/api/leagues/" + id).header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/leagues/999999").header("Authorization", org.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void inviteLinkShowsTheJoinScreenWithoutSecrets() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered stranger = TestAuth.register(mvc, mapper);
        String code = createLeague(org).get("inviteCode").asText();

        mvc.perform(get("/api/leagues/invite/" + code.toLowerCase()).header("Authorization", stranger.bearer()))   // case does not matter
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Office League"))
                .andExpect(jsonPath("$.tournamentName").value("IPL 2026"))
                .andExpect(jsonPath("$.joiningAmount").value(14800.0))     // what they would pay, and the most they could lose
                .andExpect(jsonPath("$.inviteCode").isEmpty())
                .andExpect(jsonPath("$.paymentInstructions").isEmpty())
                .andExpect(jsonPath("$.myMembershipStatus").isEmpty())
                .andExpect(jsonPath("$.organizer").value(false));
        mvc.perform(get("/api/leagues/invite/NOSUCHCODE").header("Authorization", stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    void anAcceptedMemberSeesPaymentInstructionsButNotTheInviteCode() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered member = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();

        addMember(id, member.id(), "PENDING_APPROVAL", "PENDING");
        mvc.perform(get("/api/leagues/" + id).header("Authorization", member.bearer()))
                .andExpect(status().isOk())                                // a pending request can see the league
                .andExpect(jsonPath("$.paymentInstructions").isEmpty())    // but not how to pay yet
                .andExpect(jsonPath("$.myMembershipStatus").value("PENDING_APPROVAL"));

        jdbc.update("update league_members set league_member_status = 'ACCEPTED_UNPAID' where league_id = ? and user_id = ?",
                id, member.id());
        mvc.perform(get("/api/leagues/" + id).header("Authorization", member.bearer()))
                .andExpect(jsonPath("$.paymentInstructions").value("Pay to org@okbank"))
                .andExpect(jsonPath("$.inviteCode").isEmpty());
    }

    @Test
    void adminCanSeeAnyLeagueWithItsInviteCode() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long id = createLeague(org).get("id").asLong();

        mvc.perform(get("/api/leagues/" + id).header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inviteCode").isNotEmpty());
    }

    @Test
    void myLeaguesListsOrganizedAndPendingButNotRejected() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        long rejectedLeague = createLeague(org).get("id").asLong();

        mvc.perform(get("/api/leagues").header("Authorization", org.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].organizer").value(true))
                .andExpect(jsonPath("$[0].myMembershipStatus").value("CONFIRMED"));
        mvc.perform(get("/api/leagues").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.length()").value(0));

        addMember(id, other.id(), "PENDING_APPROVAL", "PENDING");
        addMember(rejectedLeague, other.id(), "REJECTED", "PENDING");
        mvc.perform(get("/api/leagues").header("Authorization", other.bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id))
                .andExpect(jsonPath("$[0].organizer").value(false));
    }

    // ---------- edit ----------

    @Test
    void organizerEditsTheLeagueAndTheChangeIsAudited() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);
        long id = league.get("id").asLong();
        Map<String, Object> body = updateBody(league);
        body.put("name", "Renamed League");
        body.put("paymentInstructions", "New UPI id");

        JsonNode updated = read(updateLeague(org, id, body).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed League")));

        assertThat(updated.get("version").asInt()).isGreaterThan(league.get("version").asInt());
        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_type, before_data::text as b, after_data::text as a from audit_log where entity_type = 'LEAGUE' and entity_id = ? and action = 'LEAGUE_UPDATED'", id);
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat((String) audit.get("b")).contains("Office League");
        assertThat((String) audit.get("a")).contains("Renamed League");
    }

    @Test
    void editWithAnOldVersionIsRefusedAsStale() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);
        long id = league.get("id").asLong();
        Map<String, Object> first = updateBody(league);
        first.put("name", "First change");
        updateLeague(org, id, first).andExpect(status().isOk());

        Map<String, Object> second = updateBody(league);          // still carries the version from before the first change
        second.put("name", "Second tab's change");
        updateLeague(org, id, second).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_DATA"));
    }

    @Test
    void onlyTheOrganizerCanEdit() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);

        updateLeague(other, league.get("id").asLong(), updateBody(league)).andExpect(status().isForbidden());
    }

    @Test
    void amountAndContestCountAreLockedOnceAMemberIsAcceptedButOtherFieldsStayEditable() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered member = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);
        long id = league.get("id").asLong();

        Map<String, Object> changeAmount = updateBody(league);
        changeAmount.put("amountPerContest", 250);
        JsonNode afterFirstEdit = read(updateLeague(org, id, changeAmount).andExpect(status().isOk())
                .andExpect(jsonPath("$.joiningAmount").value(18500.0)));     // allowed while only the organizer is in

        addMember(id, member.id(), "ACCEPTED_UNPAID", "PENDING");

        Map<String, Object> changeAgain = updateBody(afterFirstEdit);
        changeAgain.put("totalContests", 60);
        updateLeague(org, id, changeAgain).andExpect(status().isConflict());

        Map<String, Object> renameOnly = updateBody(afterFirstEdit);
        renameOnly.put("name", "Still editable");
        updateLeague(org, id, renameOnly).andExpect(status().isOk());
    }

    @Test
    void organizerRowDoesNotLockTheAmount() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);          // the organizer is already CONFIRMED
        Map<String, Object> body = updateBody(league);
        body.put("totalContests", 70);

        updateLeague(org, league.get("id").asLong(), body).andExpect(status().isOk());
    }

    @Test
    void amountAndContestCountAreLockedOnceAContestExists() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);
        long id = league.get("id").asLong();
        insertLeagueContest(id, org.id());

        Map<String, Object> body = updateBody(league);
        body.put("totalContests", 70);
        updateLeague(org, id, body).andExpect(status().isConflict());
    }

    @Test
    void aLeagueThatHasStartedCannotBeEdited() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);
        long id = league.get("id").asLong();
        jdbc.update("update leagues set status = 'ACTIVE' where id = ?", id);

        updateLeague(org, id, updateBody(league)).andExpect(status().isConflict());
    }

    @Test
    void registrationStartCannotChangeOnceJoiningIsOpen() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        JsonNode opened = read(mvc.perform(post("/api/leagues/" + id + "/open").header("Authorization", org.bearer()))
                .andExpect(status().isOk()));

        Map<String, Object> body = updateBody(opened);
        body.put("registrationStart", Instant.now().plus(2, ChronoUnit.HOURS).toString());
        updateLeague(org, id, body).andExpect(status().isConflict());
    }

    @Test
    void editWithABadScheduleIsRefused() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        JsonNode league = createLeague(org);
        Map<String, Object> body = updateBody(league);
        body.put("registrationDeadline", Instant.now().minus(1, ChronoUnit.DAYS).toString());

        updateLeague(org, league.get("id").asLong(), body).andExpect(status().isBadRequest());
    }

    @Test
    void paymentDeadlineCanBeExtendedAfterTheRegistrationDeadlineHasPassed() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        jdbc.update("""
                update leagues set status = 'OPEN',
                                   registration_start = now() - interval '3 days',
                                   registration_deadline = now() - interval '1 day',
                                   payment_deadline = now() + interval '1 day' where id = ?
                """, id);
        JsonNode current = read(mvc.perform(get("/api/leagues/" + id).header("Authorization", org.bearer()))
                .andExpect(status().isOk()));

        Map<String, Object> body = updateBody(current);                      // registration deadline left as it is (in the past)
        body.put("paymentDeadline", Instant.now().plus(3, ChronoUnit.DAYS).toString());
        updateLeague(org, id, body).andExpect(status().isOk());

        Map<String, Object> pastPayment = updateBody(read(mvc.perform(get("/api/leagues/" + id)
                .header("Authorization", org.bearer())).andExpect(status().isOk())));
        pastPayment.put("paymentDeadline", Instant.now().minus(1, ChronoUnit.HOURS).toString());
        updateLeague(org, id, pastPayment).andExpect(status().isBadRequest());   // a changed payment deadline must be in the future
    }

    // ---------- open joining ----------

    @Test
    void organizerOpensJoiningEarly() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();

        mvc.perform(post("/api/leagues/" + id + "/open").header("Authorization", other.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/leagues/" + id + "/open").header("Authorization", org.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/api/leagues/" + id + "/open").header("Authorization", org.bearer()))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "select registration_start <= now() from leagues where id = ?", Boolean.class, id)).isTrue();
    }

    @Test
    void joiningCannotBeOpenedAfterTheRegistrationDeadline() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        jdbc.update("""
                update leagues set registration_start = now() - interval '3 days',
                                   registration_deadline = now() - interval '1 day',
                                   payment_deadline = now() + interval '1 day' where id = ?
                """, id);

        mvc.perform(post("/api/leagues/" + id + "/open").header("Authorization", org.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theOpenJobOpensDueLeaguesOnce() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        jdbc.update("update leagues set registration_start = now() - interval '1 minute' where id = ?", id);

        int first = leagueService.openDueLeagues();

        assertThat(first).isGreaterThanOrEqualTo(1);
        assertThat(leagueStatus(id)).isEqualTo("OPEN");
        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_type, actor_id, reason from audit_log where entity_type = 'LEAGUE' and entity_id = ? and action = 'LEAGUE_OPENED'", id);
        assertThat(audit.get("actor_type")).isEqualTo("SYSTEM");
        assertThat(audit.get("actor_id")).isNull();
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action = 'LEAGUE_OPENED'",
                Integer.class, id)).isEqualTo(1);

        leagueService.openDueLeagues();                              // a second run changes nothing for this league
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_id = ? and action = 'LEAGUE_OPENED'",
                Integer.class, id)).isEqualTo(1);
    }

    // ---------- cancel ----------

    @Test
    void organizerCancelsAnUnstartedLeague() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", other.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", org.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Not enough interest"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_type, reason, visible_to_members from audit_log where entity_type = 'LEAGUE' and entity_id = ? and action = 'LEAGUE_CANCELLED'", id);
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat(audit.get("reason")).isEqualTo("Not enough interest");
        assertThat(audit.get("visible_to_members")).isEqualTo(true);

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", org.bearer()))     // no body at all is fine
                .andExpect(status().isConflict());
        mvc.perform(post("/api/leagues/" + id + "/open").header("Authorization", org.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void organizerCanCancelWithoutAReason() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", org.bearer()))
                .andExpect(status().isOk());
        assertThat(leagueStatus(id)).isEqualTo("CANCELLED");
    }

    @Test
    void cancellingALeagueMembersHavePaidForLeavesMoneyToReturn() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered payer = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        addMember(id, payer.id(), "CONFIRMED", "PAID");

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", org.bearer()))
                .andExpect(status().isOk());
        assertThat(leagueStatus(id)).isEqualTo("CANCELLED");

        // Nobody played a contest, so each member's balance is exactly what they paid in.
        assertThat(jdbc.queryForMap("select net_pl, final_balance, joining_amount_paid, settlement_status "
                + "from settlement_rows where league_id = ? and user_id = ?", id, payer.id()))
                .containsEntry("settlement_status", "PENDING")
                .satisfies(row -> assertThat(row.get("final_balance")).isEqualTo(row.get("joining_amount_paid")));
        // The organizer holds the money, so their own row needs no transfer.
        assertThat(jdbc.queryForObject("select settlement_status from settlement_rows "
                + "where league_id = ? and user_id = ?", String.class, id, org.id())).isEqualTo("SETTLED");
    }

    @Test
    void cancelWithOnlyUnpaidMembersIsAllowed() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered unpaid = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        addMember(id, unpaid.id(), "ACCEPTED_UNPAID", "PENDING");

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", org.bearer()))
                .andExpect(status().isOk());
    }

    @Test
    void adminCancelNeedsAReasonAndIsAuditedAsAdmin() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long id = createLeague(org).get("id").asLong();

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Organizer is unreachable"))))
                .andExpect(status().isOk());

        Map<String, Object> audit = jdbc.queryForMap(
                "select actor_type, reason from audit_log where entity_type = 'LEAGUE' and entity_id = ? and action = 'LEAGUE_CANCELLED'", id);
        assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
        assertThat(audit.get("reason")).isEqualTo("Organizer is unreachable");
    }

    @Test
    void cancellingALeagueCancelsItsScheduledContests() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        long id = createLeague(org).get("id").asLong();
        insertLeagueContest(id, org.id());

        mvc.perform(post("/api/leagues/" + id + "/cancel").header("Authorization", org.bearer()))
                .andExpect(status().isOk());

        Map<String, Object> contest = jdbc.queryForMap(
                "select contest_status, cancelled_reason from contests where league_id = ?", id);
        assertThat(contest.get("contest_status")).isEqualTo("CANCELLED");
        assertThat(contest.get("cancelled_reason")).isEqualTo("League cancelled");
    }
}
