package com.sahilprojects.bettingApp.league;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.participation.ParticipationConfirmationChanged;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The join flow over real HTTP: request, withdraw, accept, confirm payment, reject, remove, reverse. */
@RecordApplicationEvents
class LeagueMembershipFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationEvents events;

    // ---------- helpers ----------

    private record TestLeague(long id, Registered organizer) {}

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    /** Creates a league as a fresh organizer. Not opened yet. */
    private TestLeague newLeague() throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Join Flow League");
        body.put("tournamentId", jdbc.queryForObject("select id from tournaments where name = 'IPL 2026'", Long.class));
        body.put("amountPerContest", 200);
        body.put("totalContests", 74);
        body.put("registrationStart", now.plus(1, ChronoUnit.DAYS).toString());
        body.put("registrationDeadline", now.plus(5, ChronoUnit.DAYS).toString());
        body.put("paymentDeadline", now.plus(7, ChronoUnit.DAYS).toString());
        body.put("paymentInstructions", "Pay to org@okbank");
        JsonNode created = read(mvc.perform(post("/api/leagues").header("Authorization", org.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isCreated()));
        return new TestLeague(created.get("id").asLong(), org);
    }

    private TestLeague openLeague() throws Exception {
        TestLeague league = newLeague();
        mvc.perform(post("/api/leagues/" + league.id() + "/open").header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk());
        return league;
    }

    private long requestToJoin(TestLeague league, Registered user) throws Exception {
        JsonNode member = read(mvc.perform(post("/api/leagues/" + league.id() + "/join-requests")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isCreated()));
        return member.get("memberId").asLong();
    }

    private ResultActions act(Registered who, TestLeague league, long memberId, String action, Object body) throws Exception {
        var request = post("/api/leagues/" + league.id() + "/members/" + memberId + "/" + action)
                .header("Authorization", who.bearer());
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json(body));
        }
        return mvc.perform(request);
    }

    private ResultActions acceptPaid(TestLeague league, long memberId) throws Exception {
        return act(league.organizer(), league, memberId, "accept", Map.of("paid", true));
    }

    private ResultActions acceptUnpaid(TestLeague league, long memberId) throws Exception {
        return act(league.organizer(), league, memberId, "accept", Map.of("paid", false));
    }

    /** A request that is accepted and paid, so the member is CONFIRMED. */
    private long confirmedMember(TestLeague league) throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);
        acceptPaid(league, memberId).andExpect(status().isOk());
        return memberId;
    }

    private Map<String, Object> row(long memberId) {
        return jdbc.queryForMap(
                "select league_member_status, payment_status, paid_at, confirmed_at, removed_at, status_reason from league_members where id = ?",
                memberId);
    }

    private Map<String, Object> lastAudit(long memberId, String action) {
        return jdbc.queryForMap("""
                select actor_type, actor_id, reason, visible_to_members from audit_log
                 where entity_type = 'LEAGUE_MEMBER' and entity_id = ? and action = ? order by id desc limit 1
                """, memberId, action);
    }

    private void setLeagueTimes(long leagueId, String start, String registrationDeadline, String paymentDeadline) {
        jdbc.update("update leagues set registration_start = now() + ?::interval, registration_deadline = now() + ?::interval, "
                + "payment_deadline = now() + ?::interval where id = ?", start, registrationDeadline, paymentDeadline, leagueId);
    }


    private static JsonNode byUser(JsonNode list, long userId) {
        for (JsonNode row : list) {
            if (row.get("userId").asLong() == userId) return row;
        }
        throw new AssertionError("No member row for user " + userId);
    }

    // ---------- requesting to join ----------

    @Test
    void cannotRequestBeforeJoiningOpens() throws Exception {
        TestLeague league = newLeague();                       // UPCOMING
        Registered user = TestAuth.register(mvc, mapper);

        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void userRequestsToJoinAnOpenLeague() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);

        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.organizer").value(false));
        assertThat(jdbc.queryForObject("select count(*) from league_members where league_id = ? and user_id = ?",
                Integer.class, league.id(), user.id())).isEqualTo(1);
    }

    @Test
    void duplicateRequestAndOrganizerRequestAreRefused() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        requestToJoin(league, user);

        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", league.organizer().bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotRequestAfterTheRegistrationDeadline() throws Exception {
        TestLeague league = openLeague();
        setLeagueTimes(league.id(), "-3 days", "-1 day", "1 day");
        Registered user = TestAuth.register(mvc, mapper);

        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void cannotRequestOnceTheLeagueHasStartedOrBeenCancelled() throws Exception {
        TestLeague started = openLeague();
        jdbc.update("update leagues set status = 'ACTIVE' where id = ?", started.id());
        TestLeague cancelled = openLeague();
        mvc.perform(post("/api/leagues/" + cancelled.id() + "/cancel").header("Authorization", cancelled.organizer().bearer()))
                .andExpect(status().isOk());
        Registered user = TestAuth.register(mvc, mapper);

        mvc.perform(post("/api/leagues/" + started.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/leagues/" + cancelled.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void requestingNeedsALogin() throws Exception {
        TestLeague league = openLeague();
        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests")).andExpect(status().isUnauthorized());
    }

    // ---------- accepting ----------

    @Test
    void acceptAsPaidConfirmsTheMemberAndPublishesAnEvent() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);

        acceptPaid(league, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"))
                .andExpect(jsonPath("$.paidAt").isNotEmpty())
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        Map<String, Object> audit = lastAudit(memberId, "MEMBER_ACCEPTED");
        assertThat(audit.get("actor_type")).isEqualTo("USER");
        assertThat(audit.get("visible_to_members")).isEqualTo(false);
        assertThat(events.stream(ParticipationConfirmationChanged.class)
                .filter(e -> e.participationId() == memberId && e.confirmed()).count()).isEqualTo(1);
        mvc.perform(get("/api/leagues/" + league.id()).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.myMembershipStatus").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedMembers").value(2));
    }

    @Test
    void acceptUnpaidThenConfirmPayment() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);

        acceptUnpaid(league, memberId).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_UNPAID"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));
        mvc.perform(get("/api/leagues/" + league.id()).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.paymentInstructions").value("Pay to org@okbank"));   // now they know how to pay

        act(league.organizer(), league, memberId, "confirm-payment", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
        assertThat(lastAudit(memberId, "PAYMENT_STATUS_CHANGED").get("actor_type")).isEqualTo("USER");
    }

    @Test
    void confirmPaymentBeforeAcceptingIsRefused() throws Exception {
        TestLeague league = openLeague();
        long memberId = requestToJoin(league, TestAuth.register(mvc, mapper));

        act(league.organizer(), league, memberId, "confirm-payment", null).andExpect(status().isConflict());
        assertThat(row(memberId).get("league_member_status")).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void onlyTheOrganizerCanActOnMembers() throws Exception {
        TestLeague league = openLeague();
        Registered applicant = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, applicant);
        Registered stranger = TestAuth.register(mvc, mapper);
        long confirmedId = confirmedMember(league);
        Registered confirmedUser = TestAuth.register(mvc, mapper);

        act(stranger, league, memberId, "accept", Map.of("paid", true)).andExpect(status().isForbidden());
        act(applicant, league, memberId, "accept", Map.of("paid", true)).andExpect(status().isForbidden());   // not even themselves
        act(stranger, league, memberId, "reject", null).andExpect(status().isForbidden());
        act(stranger, league, confirmedId, "reverse", Map.of("reason", "x", "target", "REMOVED")).andExpect(status().isForbidden());
        assertThat(row(memberId).get("league_member_status")).isEqualTo("PENDING_APPROVAL");
    }

    @Test
    void adminActingForTheOrganizerNeedsAReasonAndIsAuditedAsAdmin() throws Exception {
        TestLeague league = openLeague();
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long memberId = requestToJoin(league, TestAuth.register(mvc, mapper));

        act(admin, league, memberId, "accept", Map.of("paid", true)).andExpect(status().isBadRequest());
        act(admin, league, memberId, "accept", Map.of("paid", true, "reason", "Organizer is unreachable"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));

        Map<String, Object> audit = lastAudit(memberId, "MEMBER_ACCEPTED");
        assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
        assertThat(audit.get("reason")).isEqualTo("Organizer is unreachable");
    }

    @Test
    void anActionOnAMemberOfAnotherLeagueIsNotFound() throws Exception {
        TestLeague league = openLeague();
        TestLeague other = openLeague();
        long memberId = requestToJoin(other, TestAuth.register(mvc, mapper));

        act(league.organizer(), league, memberId, "accept", Map.of("paid", true)).andExpect(status().isNotFound());
    }

    @Test
    void theOrganizersOwnMembershipCannotBeChanged() throws Exception {
        TestLeague league = openLeague();
        long organizerRow = jdbc.queryForObject("select id from league_members where league_id = ? and user_id = ?",
                Long.class, league.id(), league.organizer().id());

        act(league.organizer(), league, organizerRow, "reject", null).andExpect(status().isConflict());
        act(league.organizer(), league, organizerRow, "remove", Map.of("reason", "x")).andExpect(status().isConflict());
        act(league.organizer(), league, organizerRow, "reverse", Map.of("reason", "x", "target", "REMOVED")).andExpect(status().isConflict());
        assertThat(row(organizerRow).get("league_member_status")).isEqualTo("CONFIRMED");
    }

    @Test
    void acceptingIsRefusedOnceTheLeagueHasStarted() throws Exception {
        TestLeague league = openLeague();
        long memberId = requestToJoin(league, TestAuth.register(mvc, mapper));
        jdbc.update("update leagues set status = 'ACTIVE' where id = ?", league.id());

        acceptPaid(league, memberId).andExpect(status().isConflict());
    }

    @Test
    void paymentDeadlineRulesForAcceptingAndConfirming() throws Exception {
        TestLeague league = openLeague();
        long toAcceptPaid = requestToJoin(league, TestAuth.register(mvc, mapper));
        long toAcceptUnpaid = requestToJoin(league, TestAuth.register(mvc, mapper));
        long toConfirm = requestToJoin(league, TestAuth.register(mvc, mapper));
        acceptUnpaid(league, toConfirm).andExpect(status().isOk());

        setLeagueTimes(league.id(), "-3 days", "-2 days", "-1 day");     // the payment deadline has now passed

        acceptUnpaid(league, toAcceptUnpaid).andExpect(status().isBadRequest());      // would be removed straight away
        act(league.organizer(), league, toConfirm, "confirm-payment", null).andExpect(status().isBadRequest());
        acceptPaid(league, toAcceptPaid).andExpect(status().isOk());                  // already paid, so still fine
    }

    // ---------- rejecting ----------

    @Test
    void rejectKeepsTheReasonAndTheUserCanAskAgain() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);

        act(league.organizer(), league, memberId, "reject", Map.of("reason", "Group is full")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.statusReason").value("Group is full"));
        assertThat(lastAudit(memberId, "MEMBER_REJECTED").get("actor_type")).isEqualTo("USER");

        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.memberId").value(memberId))                      // the same row is reused
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
        assertThat(row(memberId).get("status_reason")).isNull();
    }

    @Test
    void aConfirmedMemberCannotBeRejected() throws Exception {
        TestLeague league = openLeague();
        long memberId = confirmedMember(league);

        act(league.organizer(), league, memberId, "reject", null).andExpect(status().isConflict());
    }

    // ---------- removing ----------

    @Test
    void removeAnUnpaidMemberNeedsAReasonAndIsFinal() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);
        acceptUnpaid(league, memberId).andExpect(status().isOk());

        act(league.organizer(), league, memberId, "remove", Map.of("reason", " ")).andExpect(status().isBadRequest());
        act(league.organizer(), league, memberId, "remove", Map.of("reason", "Never paid")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REMOVED"));
        assertThat(row(memberId).get("removed_at")).isNotNull();
        assertThat(lastAudit(memberId, "MEMBER_REMOVED").get("reason")).isEqualTo("Never paid");

        mvc.perform(post("/api/leagues/" + league.id() + "/join-requests").header("Authorization", user.bearer()))
                .andExpect(status().isConflict());                                       // removed is final in V1
    }

    @Test
    void aConfirmedMemberMustBeReversedNotRemoved() throws Exception {
        TestLeague league = openLeague();
        long memberId = confirmedMember(league);

        act(league.organizer(), league, memberId, "remove", Map.of("reason", "Left")).andExpect(status().isBadRequest());
        assertThat(row(memberId).get("league_member_status")).isEqualTo("CONFIRMED");
    }

    // ---------- withdrawing ----------

    @Test
    void aPendingOrAcceptedUserCanWithdrawAndAskAgain() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);

        mvc.perform(delete("/api/leagues/" + league.id() + "/join-requests/me").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        requestToJoin(league, user);                                                      // asks again
        acceptUnpaid(league, memberId).andExpect(status().isOk());
        mvc.perform(delete("/api/leagues/" + league.id() + "/join-requests/me").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void aConfirmedMemberCannotWithdraw() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);
        long memberId = requestToJoin(league, user);
        acceptPaid(league, memberId).andExpect(status().isOk());

        mvc.perform(delete("/api/leagues/" + league.id() + "/join-requests/me").header("Authorization", user.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("cannot withdraw")));
        assertThat(row(memberId).get("league_member_status")).isEqualTo("CONFIRMED");
    }

    @Test
    void withdrawingWithoutARequestIsNotFound() throws Exception {
        TestLeague league = openLeague();
        Registered user = TestAuth.register(mvc, mapper);

        mvc.perform(delete("/api/leagues/" + league.id() + "/join-requests/me").header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());
    }

    // ---------- reversing ----------

    @Test
    void reverseBeforeStartToUnpaidReturnsTheMoneyAndPublishesALossEvent() throws Exception {
        TestLeague league = openLeague();
        long memberId = confirmedMember(league);

        act(league.organizer(), league, memberId, "reverse", Map.of("reason", " ", "target", "ACCEPTED_UNPAID"))
                .andExpect(status().isBadRequest());
        act(league.organizer(), league, memberId, "reverse",
                Map.of("reason", "Paid by mistake, money returned", "target", "ACCEPTED_UNPAID"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_UNPAID"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"))
                .andExpect(jsonPath("$.paidAt").isEmpty())
                .andExpect(jsonPath("$.confirmedAt").isEmpty());

        assertThat(lastAudit(memberId, "MEMBER_REVERSED").get("reason")).isEqualTo("Paid by mistake, money returned");
        assertThat(events.stream(ParticipationConfirmationChanged.class)
                .filter(e -> e.participationId() == memberId && !e.confirmed()).count()).isEqualTo(1);
    }

    @Test
    void reverseBeforeStartToRemovedResetsPaymentSoNoSettlementRowWillExist() throws Exception {
        TestLeague league = openLeague();
        long memberId = confirmedMember(league);

        act(league.organizer(), league, memberId, "reverse",
                Map.of("reason", "Left the group, money returned", "target", "REMOVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVED"));

        Map<String, Object> row = row(memberId);
        assertThat(row.get("payment_status")).isEqualTo("PENDING");
        assertThat(row.get("paid_at")).isNull();
        assertThat(row.get("removed_at")).isNotNull();
    }

    @Test
    void reverseAfterStartIsAdminOnlyAndKeepsThePayment() throws Exception {
        TestLeague league = openLeague();
        long memberId = confirmedMember(league);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        jdbc.update("update leagues set status = 'ACTIVE' where id = ?", league.id());

        act(league.organizer(), league, memberId, "reverse", Map.of("reason", "Left", "target", "REMOVED"))
                .andExpect(status().isForbidden());                                       // the organizer cannot after the start
        act(admin, league, memberId, "reverse", Map.of("reason", "Left mid-league", "target", "ACCEPTED_UNPAID"))
                .andExpect(status().isBadRequest());                                      // cannot go back to unpaid after the start
        act(admin, league, memberId, "reverse", Map.of("reason", "Left mid-league", "target", "REMOVED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));                    // the money comes back through settlement

        Map<String, Object> audit = lastAudit(memberId, "MEMBER_REVERSED");
        assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
        assertThat(row(memberId).get("paid_at")).isNotNull();
    }

    @Test
    void reverseIsRefusedWhenTheLeagueIsNotOpenOrActive() throws Exception {
        TestLeague league = openLeague();
        long memberId = confirmedMember(league);
        jdbc.update("update leagues set status = 'PENDING_SETTLEMENT' where id = ?", league.id());
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        act(admin, league, memberId, "reverse", Map.of("reason", "x", "target", "REMOVED")).andExpect(status().isConflict());
    }

    // ---------- the member list ----------

    @Test
    void memberListVisibilityAndPrivacy() throws Exception {
        TestLeague league = openLeague();
        Registered confirmedUser = TestAuth.register(mvc, mapper);
        long confirmedId = requestToJoin(league, confirmedUser);
        acceptPaid(league, confirmedId).andExpect(status().isOk());
        Registered pendingUser = TestAuth.register(mvc, mapper);
        requestToJoin(league, pendingUser);
        Registered stranger = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        jdbc.update("update users set phone_number = '9999999999', upi_id = 'member@okbank' where id = ?", confirmedUser.id());

        // the organizer sees everyone, including the pending request and contact details
        JsonNode asOrganizer = read(mvc.perform(get("/api/leagues/" + league.id() + "/members")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk()));
        assertThat(asOrganizer).hasSize(3);
        JsonNode theirRow = byUser(asOrganizer, confirmedUser.id());
        assertThat(theirRow.get("phoneNumber").asText()).isEqualTo("9999999999");
        assertThat(theirRow.get("upiId").asText()).isEqualTo("member@okbank");

        // a confirmed member sees only confirmed members, without contact details
        JsonNode asMember = read(mvc.perform(get("/api/leagues/" + league.id() + "/members")
                        .header("Authorization", confirmedUser.bearer()))
                .andExpect(status().isOk()));
        assertThat(asMember).hasSize(2);
        for (JsonNode row : asMember) {
            assertThat(row.get("status").asText()).isEqualTo("CONFIRMED");
            assertThat(row.get("phoneNumber").isNull()).isTrue();
            assertThat(row.get("upiId").isNull()).isTrue();
            assertThat(row.get("statusReason").isNull()).isTrue();
        }
        // a pending applicant and a stranger see nothing
        mvc.perform(get("/api/leagues/" + league.id() + "/members").header("Authorization", pendingUser.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/leagues/" + league.id() + "/members").header("Authorization", stranger.bearer()))
                .andExpect(status().isForbidden());
        // an admin sees everyone
        mvc.perform(get("/api/leagues/" + league.id() + "/members").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.length()").value(3));
        // the organizer can filter by status
        mvc.perform(get("/api/leagues/" + league.id() + "/members").param("status", "PENDING_APPROVAL")
                        .header("Authorization", league.organizer().bearer()))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
