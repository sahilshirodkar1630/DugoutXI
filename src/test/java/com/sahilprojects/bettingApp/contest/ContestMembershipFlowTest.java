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

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Joining somebody else's standalone contest, and the organizer's side of it. */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContestMembershipFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void aRequestIsAcceptedAsUnpaidAndThenConfirmedWhenTheMoneyArrives() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);

        long participantId = requestToJoin(joiner, contestId);
        assertThat(participantStatus(contestId, joiner.id())).isEqualTo("PENDING_APPROVAL");

        accept(organizer, contestId, participantId, false).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED_UNPAID"))
                .andExpect(jsonPath("$.paymentStatus").value("PENDING"));

        mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/confirm-payment")
                        .header("Authorization", organizer.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
    }

    @Test
    void acceptingSomeoneWhoHasAlreadyPaidConfirmsThemInOneStep() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        long participantId = requestToJoin(joiner, contestId);

        accept(organizer, contestId, participantId, true).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.paymentStatus").value("PAID"));
    }

    @Test
    void onlyTheOrganizerCanAcceptARequest() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        Registered bystander = TestAuth.register(mvc, mapper);
        long participantId = requestToJoin(joiner, contestId);

        accept(bystander, contestId, participantId, true).andExpect(status().isForbidden());
    }

    @Test
    void askingTwiceIsRefusedButSomeoneRejectedMayAskAgain() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        long participantId = requestToJoin(joiner, contestId);

        join(joiner, contestId).andExpect(status().isConflict());

        mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/reject")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"contest is full\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        join(joiner, contestId).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"));
    }

    @Test
    void aPendingRequestCanBeWithdrawnButAConfirmedPlaceCannot() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        long participantId = requestToJoin(joiner, contestId);

        mvc.perform(delete("/api/contests/" + contestId + "/join-requests/me")
                        .header("Authorization", joiner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        join(joiner, contestId).andExpect(status().isCreated());
        accept(organizer, contestId, participantId, true).andExpect(status().isOk());

        mvc.perform(delete("/api/contests/" + contestId + "/join-requests/me")
                        .header("Authorization", joiner.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void joiningIsRefusedOnceTheRegistrationDeadlineHasPassed() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        jdbc.update("update contests set registration_deadline = now() - interval '1 minute' where id = ?", contestId);
        Registered joiner = TestAuth.register(mvc, mapper);

        join(joiner, contestId).andExpect(status().isConflict());
    }

    @Test
    void joiningIsRefusedOnceTheContestHasStarted() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));
        Registered joiner = TestAuth.register(mvc, mapper);

        join(joiner, contestId).andExpect(status().isConflict());
    }

    @Test
    void aLeagueContestCannotBeJoinedDirectly() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        Registered outsider = TestAuth.register(mvc, mapper);

        join(outsider, contestId).andExpect(status().isConflict());
    }

    @Test
    void theOrganizerSeesPaymentDetailsAndOtherPlayersDoNot() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        jdbc.update("update users set upi_id = 'joiner@okbank', phone_number = '9999999999' where id = ?",
                joiner.id());
        long joinerRow = requestToJoin(joiner, contestId);
        accept(organizer, contestId, joinerRow, true).andExpect(status().isOk());
        long otherRow = requestToJoin(other, contestId);
        accept(organizer, contestId, otherRow, true).andExpect(status().isOk());

        JsonNode asOrganizer = read(list(organizer, contestId));
        assertThat(asOrganizer).anySatisfy(row -> {
            if (row.get("userId").asLong() == joiner.id()) {
                assertThat(row.get("upiId").asText()).isEqualTo("joiner@okbank");
            }
        });

        JsonNode asOther = read(list(other, contestId));
        assertThat(asOther).allSatisfy(row -> {
            if (row.get("userId").asLong() != other.id()) {
                assertThat(row.get("upiId").isNull()).isTrue();
                assertThat(row.get("phoneNumber").isNull()).isTrue();
            }
        });
    }

    @Test
    void aConfirmedPlaceCanBeReversedBeforeTheContestStarts() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        long participantId = requestToJoin(joiner, contestId);
        accept(organizer, contestId, participantId, true).andExpect(status().isOk());

        mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/reverse")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"entry returned in cash\",\"target\":\"REMOVED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REMOVED"));
    }

    @Test
    void aPlaceCannotBeReversedOnceTheContestHasStarted() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        long participantId = requestToJoin(joiner, contestId);
        accept(organizer, contestId, participantId, true).andExpect(status().isOk());
        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));

        mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/reverse")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"entry returned in cash\",\"target\":\"REMOVED\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theOrganizersOwnPlaceCannotBeTouched() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Long ownRow = jdbc.queryForObject(
                "select id from contest_participants where contest_id = ? and user_id = ?",
                Long.class, contestId, organizer.id());

        mvc.perform(post("/api/contests/" + contestId + "/participants/" + ownRow + "/remove")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed my mind\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(delete("/api/contests/" + contestId + "/join-requests/me")
                        .header("Authorization", organizer.bearer()))
                .andExpect(status().isConflict());
    }

    @Test
    void theOrganizerIsToldWhenSomeoneAsksToJoin() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = standalone(organizer);
        Registered joiner = TestAuth.register(mvc, mapper);
        requestToJoin(joiner, contestId);

        JsonNode inbox = read(mvc.perform(get("/api/notifications")
                .header("Authorization", organizer.bearer())).andExpect(status().isOk()));
        assertThat(inbox).anySatisfy(n ->
                assertThat(n.get("type").asText()).isEqualTo("JOIN_REQUEST_RECEIVED"));
    }

    // ---------- helpers ----------

    private long standalone(Registered organizer) throws Exception {
        return TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
    }

    private ResultActions join(Registered who, long contestId) throws Exception {
        return mvc.perform(post("/api/contests/" + contestId + "/join-requests")
                .header("Authorization", who.bearer()));
    }

    private long requestToJoin(Registered who, long contestId) throws Exception {
        return read(join(who, contestId).andExpect(status().isCreated())).get("participantId").asLong();
    }

    private ResultActions accept(Registered who, long contestId, long participantId, boolean paid) throws Exception {
        return mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/accept")
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"paid\":" + paid + "}"));
    }

    private ResultActions list(Registered who, long contestId) throws Exception {
        return mvc.perform(get("/api/contests/" + contestId + "/participants")
                .header("Authorization", who.bearer())).andExpect(status().isOk());
    }

    private String participantStatus(long contestId, long userId) {
        return jdbc.queryForObject("select participant_status from contest_participants "
                + "where contest_id = ? and user_id = ?", String.class, contestId, userId);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
