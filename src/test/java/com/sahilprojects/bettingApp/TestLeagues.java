package com.sahilprojects.bettingApp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Builds leagues and members through the real API for integration tests. */
public final class TestLeagues {

    public record TestLeague(long id, Registered organizer) {}

    private TestLeagues() {
    }

    /** A new league on IPL 2026, UPCOMING, registration deadline in 5 days and payment deadline in 7 days. */
    public static TestLeague create(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc, int amount, int total) throws Exception {
        Registered org = TestAuth.register(mvc, mapper);
        Instant now = Instant.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Test League");
        body.put("tournamentId", jdbc.queryForObject("select id from tournaments where name = 'IPL 2026'", Long.class));
        body.put("amountPerContest", amount);
        body.put("totalContests", total);
        body.put("registrationStart", now.plus(1, ChronoUnit.DAYS).toString());
        body.put("registrationDeadline", now.plus(5, ChronoUnit.DAYS).toString());
        body.put("paymentDeadline", now.plus(7, ChronoUnit.DAYS).toString());
        body.put("paymentInstructions", "Pay to org@okbank");
        JsonNode created = mapper.readTree(mvc.perform(post("/api/leagues").header("Authorization", org.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return new TestLeague(created.get("id").asLong(), org);
    }

    public static void open(MockMvc mvc, TestLeague league) throws Exception {
        mvc.perform(post("/api/leagues/" + league.id() + "/open").header("Authorization", league.organizer().bearer()))
                .andExpect(status().isOk());
    }

    /** The user asks to join. Returns the member id. */
    public static long request(MockMvc mvc, ObjectMapper mapper, TestLeague league, Registered user) throws Exception {
        JsonNode member = mapper.readTree(mvc.perform(post("/api/leagues/" + league.id() + "/join-requests")
                        .header("Authorization", user.bearer()))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        return member.get("memberId").asLong();
    }

    /** The user asks to join and the organizer accepts them as paid. Returns the member id. */
    public static long joinConfirmed(MockMvc mvc, ObjectMapper mapper, TestLeague league, Registered user) throws Exception {
        long memberId = request(mvc, mapper, league, user);
        mvc.perform(post("/api/leagues/" + league.id() + "/members/" + memberId + "/accept")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":true}"))
                .andExpect(status().isOk());
        return memberId;
    }

    public static void reverse(MockMvc mvc, TestLeague league, long memberId, String target) throws Exception {
        mvc.perform(post("/api/leagues/" + league.id() + "/members/" + memberId + "/reverse")
                        .header("Authorization", league.organizer().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"test reversal, money returned\",\"target\":\"" + target + "\"}"))
                .andExpect(status().isOk());
    }
}
