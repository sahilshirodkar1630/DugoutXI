package com.sahilprojects.bettingApp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import com.sahilprojects.bettingApp.TestLeagues.TestLeague;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Builds contests through the real API, and nudges times in the database when a test needs a deadline to have passed. */
public final class TestContests {

    private TestContests() {
    }

    public static long team(JdbcTemplate jdbc, String shortCode) {
        return jdbc.queryForObject("select id from teams where short_code = ?", Long.class, shortCode);
    }

    public static long ipl(JdbcTemplate jdbc) {
        return jdbc.queryForObject("select id from tournaments where name = 'IPL 2026'", Long.class);
    }

    /**
     * A league contest starting in eight days, which is after the payment deadline TestLeagues gives a league
     * (seven days): the API refuses a league contest that starts before members have had to pay.
     * Every CONFIRMED member is enrolled by the service.
     */
    public static long leagueContest(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc,
                                     TestLeague league, String teamA, String teamB) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("leagueId", league.id());
        body.put("teamAId", team(jdbc, teamA));
        body.put("teamBId", team(jdbc, teamB));
        body.put("startTime", Instant.now().plus(8, ChronoUnit.DAYS).toString());
        return create(mvc, mapper, league.organizer(), body);
    }

    /** A standalone contest starting in two days, with a registration deadline in one day. */
    public static long standalone(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc,
                                  Registered organizer, int amount, String teamA, String teamB) throws Exception {
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tournamentId", ipl(jdbc));
        body.put("teamAId", team(jdbc, teamA));
        body.put("teamBId", team(jdbc, teamB));
        body.put("startTime", now.plus(2, ChronoUnit.DAYS).toString());
        body.put("amount", amount);
        body.put("registrationDeadline", now.plus(1, ChronoUnit.DAYS).toString());
        body.put("paymentDeadline", now.plus(36, ChronoUnit.HOURS).toString());
        body.put("paymentInstructions", "Pay to org@okbank");
        return create(mvc, mapper, organizer, body);
    }

    public static long create(MockMvc mvc, ObjectMapper mapper, Registered who, Map<String, Object> body)
            throws Exception {
        JsonNode created = mapper.readTree(mvc.perform(post("/api/contests")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return created.get("id").asLong();
    }

    /**
     * Moves a contest's start time, which the API refuses to do into the past.
     * Tests use it to make a contest due to start, or already started.
     */
    public static void setStartTime(JdbcTemplate jdbc, long contestId, Instant startTime) {
        // A standalone contest's deadlines may not be after its start, so they are pulled back with it.
        java.sql.Timestamp start = java.sql.Timestamp.from(startTime);
        jdbc.update("update contests set start_time = ?, "
                        + "registration_deadline = case when league_id is null then least(registration_deadline, ?) end, "
                        + "payment_deadline = case when league_id is null then least(payment_deadline, ?) end "
                        + "where id = ?",
                start, start, start, contestId);
    }

    /**
     * Puts a contest in the state it reaches when its start time arrives: started and locked.
     * The start job does this for real; a test that only cares about the result does not need to wait for it.
     */
    public static void lock(JdbcTemplate jdbc, long contestId) {
        setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));
        jdbc.update("update contests set contest_status = 'LOCKED', locked_at = now() where id = ?", contestId);
    }

    /** Moves a standalone contest's payment deadline, so the cutoff job has something to do. */
    public static void setPaymentDeadline(JdbcTemplate jdbc, long contestId, Instant deadline) {
        // The registration deadline may not be after the payment deadline, so it is pulled back with it.
        java.sql.Timestamp at = java.sql.Timestamp.from(deadline);
        jdbc.update("update contests set payment_deadline = ?, registration_deadline = least(registration_deadline, ?) "
                + "where id = ?", at, at, contestId);
    }

    /**
     * A one-off contest played to its result: the organizer and the player both take part, each picks a team, and
     * the given team wins. Returns the contest id.
     */
    public static long playStandalone(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc, Registered organizer,
                                      Registered player, int amount, String organizerPick, String playerPick,
                                      String winner) throws Exception {
        long contestId = standalone(mvc, mapper, jdbc, organizer, amount, "MI", "CSK");
        long row = mapper.readTree(mvc.perform(post("/api/contests/" + contestId + "/join-requests")
                        .header("Authorization", player.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("participantId").asLong();
        mvc.perform(post("/api/contests/" + contestId + "/participants/" + row + "/accept")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":true}"))
                .andExpect(status().isOk());
        pick(mvc, organizer, contestId, team(jdbc, organizerPick));
        pick(mvc, player, contestId, team(jdbc, playerPick));
        lock(jdbc, contestId);
        Map<String, Object> result = Map.of("winningTeamId", team(jdbc, winner));
        String token = mapper.readTree(mvc.perform(post("/api/contests/" + contestId + "/result/preview")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(result)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("previewToken").asText();
        mvc.perform(post("/api/contests/" + contestId + "/result/confirm")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("winningTeamId", team(jdbc, winner), "previewToken", token))))
                .andExpect(status().isOk());
        return contestId;
    }

    public static void pick(MockMvc mvc, Registered who, long contestId, long teamId) throws Exception {
        mvc.perform(put("/api/contests/" + contestId + "/predictions/me")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":" + teamId + "}"))
                .andExpect(status().isOk());
    }
}
