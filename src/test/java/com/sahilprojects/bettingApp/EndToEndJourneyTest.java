package com.sahilprojects.bettingApp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.TestAuth.Registered;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole thing, start to finish, through the real API.
 *
 * Every other test checks one rule. This one checks that the rules add up: three friends register, join a league,
 * play two contests, and settle the money. It is here to catch the failures the unit tests cannot see — a
 * calculation that is right in isolation but wrong once a second contest is involved, or money that goes missing
 * between a contest and the settlement.
 *
 * The last assertion is the one that matters most: every rupee that went in comes back out, apart from the paise a
 * pool could not divide evenly.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EndToEndJourneyTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ContestStartJob startJob;

    @Test
    void threeFriendsPlayALeagueFromSignUpToSettledUp() throws Exception {
        // ---------- the people ----------
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered amit = TestAuth.register(mvc, mapper);
        Registered riya = TestAuth.register(mvc, mapper);
        jdbc.update("update users set upi_id = 'amit@okbank' where id = ?", amit.id());
        jdbc.update("update users set upi_id = 'riya@okbank' where id = ?", riya.id());

        // ---------- a league, two contests at 100 each ----------
        long tournamentId = TestContests.ipl(jdbc);
        Map<String, Object> leagueBody = new LinkedHashMap<>();
        Instant now = Instant.now();
        leagueBody.put("name", "Flat 304 IPL");
        leagueBody.put("tournamentId", tournamentId);
        leagueBody.put("amountPerContest", 100);
        leagueBody.put("totalContests", 2);
        leagueBody.put("registrationStart", now.plus(1, ChronoUnit.HOURS).toString());
        leagueBody.put("registrationDeadline", now.plus(2, ChronoUnit.DAYS).toString());
        leagueBody.put("paymentDeadline", now.plus(3, ChronoUnit.DAYS).toString());
        leagueBody.put("paymentInstructions", "UPI to organizer@okbank");

        JsonNode league = read(mvc.perform(post("/api/leagues")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(leagueBody)))
                .andExpect(status().isCreated()));
        long leagueId = league.get("id").asLong();

        // The buy-in is the whole league, taken up front.
        assertThat(league.get("joiningAmount").asDouble()).isEqualTo(200.0);
        // The organizer plays in their own league and is in from the start.
        assertThat(league.get("myMembershipStatus").asText()).isEqualTo("CONFIRMED");

        // ---------- joining ----------
        mvc.perform(post("/api/leagues/" + leagueId + "/open").header("Authorization", organizer.bearer()))
                .andExpect(status().isOk());

        long amitMember = ask(leagueId, amit);
        long riyaMember = ask(leagueId, riya);

        // Amit pays up front, Riya is accepted and pays afterwards.
        accept(organizer, leagueId, amitMember, true);
        accept(organizer, leagueId, riyaMember, false);
        assertThat(memberStatus(leagueId, riya.id())).isEqualTo("ACCEPTED_UNPAID");
        mvc.perform(post("/api/leagues/" + leagueId + "/members/" + riyaMember + "/confirm-payment")
                        .header("Authorization", organizer.bearer()))
                .andExpect(status().isOk());
        assertThat(memberStatus(leagueId, riya.id())).isEqualTo("CONFIRMED");

        // ---------- the first contest ----------
        long mi = TestContests.team(jdbc, "MI");
        long csk = TestContests.team(jdbc, "CSK");
        long first = TestContests.leagueContest(mvc, mapper, jdbc, new TestLeagues.TestLeague(leagueId, organizer),
                "MI", "CSK");

        // Every confirmed member is entered automatically, with nothing more to pay.
        assertThat(jdbc.queryForObject("select count(*) from contest_participants "
                + "where contest_id = ? and participant_status = 'CONFIRMED'", Integer.class, first)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select distinct payment_status from contest_participants "
                + "where contest_id = ?", String.class, first)).isEqualTo("NOT_REQUIRED");

        predict(organizer, first, mi);
        predict(amit, first, mi);
        predict(riya, first, csk);

        // Nobody can see anyone else's pick yet.
        mvc.perform(get("/api/contests/" + first + "/predictions").header("Authorization", riya.bearer()))
                .andExpect(status().isConflict());

        // The clock arrives: the contest locks and the league starts with it.
        TestContests.setStartTime(jdbc, first, Instant.now().minus(1, ChronoUnit.MINUTES));
        startJob.startOne(first);
        assertThat(contestStatus(first)).isEqualTo("LOCKED");
        assertThat(leagueStatus(leagueId)).isEqualTo("ACTIVE");

        // And now the picks are public.
        assertThat(read(mvc.perform(get("/api/contests/" + first + "/predictions")
                .header("Authorization", riya.bearer())).andExpect(status().isOk()))).hasSize(3);

        // MI won: a 300 pool split between the two who picked them.
        declare(organizer, first, mi);
        assertThat(money(first, organizer.id())).containsEntry("result", "WON").containsEntry("winning_amount", bd(150));
        assertThat(money(first, amit.id())).containsEntry("result", "WON").containsEntry("winning_amount", bd(150));
        assertThat(money(first, riya.id())).containsEntry("result", "LOST").containsEntry("entry_spent", bd(100));
        // A league contest never creates payouts: the money is netted at the end.
        assertThat(jdbc.queryForObject("select count(*) from payouts where contest_id = ?", Integer.class, first))
                .isZero();

        // ---------- the second contest, where only one person gets it right ----------
        long second = TestContests.leagueContest(mvc, mapper, jdbc,
                new TestLeagues.TestLeague(leagueId, organizer), "RCB", "KKR");
        long rcb = TestContests.team(jdbc, "RCB");
        long kkr = TestContests.team(jdbc, "KKR");
        predict(organizer, second, rcb);
        predict(amit, second, rcb);
        predict(riya, second, kkr);
        TestContests.setStartTime(jdbc, second, Instant.now().minus(1, ChronoUnit.MINUTES));
        startJob.startOne(second);
        declare(organizer, second, kkr);

        // KKR won, so Riya takes the whole 300 pool.
        assertThat(money(second, riya.id()))
                .containsEntry("result", "WON")
                .containsEntry("winning_amount", bd(300));

        // ---------- the table, halfway through nothing: both contests are played ----------
        JsonNode board = read(mvc.perform(get("/api/leagues/" + leagueId + "/board")
                .header("Authorization", amit.bearer())).andExpect(status().isOk()));
        assertThat(board.get("contestsPlayed").asInt()).isEqualTo(2);
        // Riya: +200 on the second, -100 on the first, so +100. Amit and the organizer: +50 then -100, so -50.
        assertThat(boardRow(board, riya.id()).get("netPl").asDouble()).isEqualTo(100.0);
        assertThat(boardRow(board, amit.id()).get("netPl").asDouble()).isEqualTo(-50.0);
        assertThat(boardRow(board, riya.id()).get("rank").asInt()).isEqualTo(1);

        // ---------- ending the league ----------
        JsonNode settlement = read(mvc.perform(post("/api/leagues/" + leagueId + "/end")
                .header("Authorization", organizer.bearer())).andExpect(status().isOk()));

        assertThat(settlement.get("leagueStatus").asText()).isEqualTo("PENDING_SETTLEMENT");
        assertThat(settlement.get("balanced").asBoolean()).isTrue();
        // Each put in 200. Riya walks away with 300, the other two with 150 each.
        assertThat(settlementRow(settlement, riya.id()).get("finalBalance").asDouble()).isEqualTo(300.0);
        assertThat(settlementRow(settlement, amit.id()).get("finalBalance").asDouble()).isEqualTo(150.0);
        assertThat(settlementRow(settlement, organizer.id()).get("finalBalance").asDouble()).isEqualTo(150.0);

        // The organizer holds the pot, so their own row needs no transfer.
        assertThat(settlementRow(settlement, organizer.id()).get("status").asText()).isEqualTo("SETTLED");
        assertThat(settlementRow(settlement, riya.id()).get("status").asText()).isEqualTo("PENDING");

        // Everyone paid up front, so the organizer hands each member back their final balance: 300 to Riya and
        // 150 to Amit. Nobody owes the organizer anything.
        assertThat(settlement.get("owedMoney")).hasSize(2);
        assertThat(settlement.get("owedMoney")).extracting(n -> n.get("userId").asLong())
                .containsExactlyInAnyOrder(riya.id(), amit.id());
        assertThat(settlement.get("oweMoney")).isEmpty();

        // ---------- settling up ----------
        mvc.perform(post("/api/leagues/" + leagueId + "/complete").header("Authorization", organizer.bearer()))
                .andExpect(status().isConflict());               // not while anyone is outstanding

        for (Long rowId : jdbc.queryForList("select id from settlement_rows where league_id = ? "
                + "and settlement_status = 'PENDING'", Long.class, leagueId)) {
            mvc.perform(put("/api/leagues/" + leagueId + "/settlement/" + rowId + "/status")
                            .header("Authorization", organizer.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SETTLED\"}"))
                    .andExpect(status().isOk());
        }

        JsonNode completed = read(mvc.perform(post("/api/leagues/" + leagueId + "/complete")
                .header("Authorization", organizer.bearer())).andExpect(status().isOk()));
        assertThat(completed.get("leagueStatus").asText()).isEqualTo("COMPLETED");
        assertThat(completed.get("pendingCount").asInt()).isZero();

        // ---------- the money has to balance ----------
        // 600 went in. 600 comes out, because neither pool left an odd paisa behind.
        assertThat(jdbc.queryForObject("select sum(joining_amount_paid) from settlement_rows where league_id = ?",
                java.math.BigDecimal.class, leagueId)).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("select sum(final_balance) from settlement_rows where league_id = ?",
                java.math.BigDecimal.class, leagueId)).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("select coalesce(sum(leftover_amount), 0) from contests where league_id = ?",
                java.math.BigDecimal.class, leagueId)).isEqualByComparingTo("0.00");

        // ---------- and everyone's own record agrees with the league ----------
        JsonNode riyaStats = read(mvc.perform(get("/api/me/stats").header("Authorization", riya.bearer()))
                .andExpect(status().isOk()));
        assertThat(riyaStats.get("contestsPlayed").asInt()).isEqualTo(2);
        assertThat(riyaStats.get("contestsWon").asInt()).isEqualTo(1);
        assertThat(riyaStats.get("netPl").asDouble()).isEqualTo(100.0);
        assertThat(riyaStats.get("winPercentage").asDouble()).isEqualTo(50.0);
    }

    @Test
    void aOneOffContestRunsFromJoinRequestToMoneyPaidOut() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        Registered amit = TestAuth.register(mvc, mapper);
        Registered riya = TestAuth.register(mvc, mapper);

        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 200, "MI", "CSK");
        long mi = TestContests.team(jdbc, "MI");
        long csk = TestContests.team(jdbc, "CSK");

        // Both friends ask for a place and the organizer takes their money.
        for (Registered friend : new Registered[] { amit, riya }) {
            long participantId = read(mvc.perform(post("/api/contests/" + contestId + "/join-requests")
                    .header("Authorization", friend.bearer())).andExpect(status().isCreated()))
                    .get("participantId").asLong();
            mvc.perform(post("/api/contests/" + contestId + "/participants/" + participantId + "/accept")
                            .header("Authorization", organizer.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"paid\":true}"))
                    .andExpect(status().isOk());
        }

        predict(organizer, contestId, csk);
        predict(amit, contestId, mi);
        predict(riya, contestId, mi);

        TestContests.setStartTime(jdbc, contestId, Instant.now().minus(1, ChronoUnit.MINUTES));
        startJob.startOne(contestId);
        declare(organizer, contestId, mi);

        // A 600 pool shared by the two who picked MI.
        JsonNode payouts = read(mvc.perform(get("/api/contests/" + contestId + "/payouts")
                .header("Authorization", organizer.bearer())).andExpect(status().isOk()));
        assertThat(payouts).hasSize(2);
        assertThat(payouts).allSatisfy(payout -> {
            assertThat(payout.get("amount").asDouble()).isEqualTo(300.0);
            assertThat(payout.get("status").asText()).isEqualTo("PENDING");
        });
        assertThat(contestStatus(contestId)).isEqualTo("RESULT_DECLARED");

        // The organizer sends both amounts and ticks them off, which settles the contest.
        for (JsonNode payout : payouts) {
            mvc.perform(put("/api/contests/" + contestId + "/payouts/" + payout.get("id").asLong() + "/status")
                            .header("Authorization", organizer.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PAID_OUT\"}"))
                    .andExpect(status().isOk());
        }
        assertThat(contestStatus(contestId)).isEqualTo("SETTLED");

        // 600 in, 600 out.
        assertThat(jdbc.queryForObject("select sum(amount) from payouts where contest_id = ?",
                java.math.BigDecimal.class, contestId)).isEqualByComparingTo("600.00");
        assertThat(jdbc.queryForObject("select leftover_amount from contests where id = ?",
                java.math.BigDecimal.class, contestId)).isEqualByComparingTo("0.00");
    }

    // ---------- helpers ----------

    private long ask(long leagueId, Registered who) throws Exception {
        return read(mvc.perform(post("/api/leagues/" + leagueId + "/join-requests")
                .header("Authorization", who.bearer())).andExpect(status().isCreated()))
                .get("memberId").asLong();
    }

    private void accept(Registered organizer, long leagueId, long memberId, boolean paid) throws Exception {
        mvc.perform(post("/api/leagues/" + leagueId + "/members/" + memberId + "/accept")
                        .header("Authorization", organizer.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paid\":" + paid + "}"))
                .andExpect(status().isOk());
    }

    private void predict(Registered who, long contestId, long teamId) throws Exception {
        mvc.perform(put("/api/contests/" + contestId + "/predictions/me")
                        .header("Authorization", who.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":" + teamId + "}"))
                .andExpect(status().isOk());
    }

    /** The two-step declare: preview for the token, then confirm with it. */
    private void declare(Registered organizer, long contestId, long winningTeamId) throws Exception {
        String token = read(result(organizer, contestId, "preview", Map.of("winningTeamId", winningTeamId))
                .andExpect(status().isOk())).get("previewToken").asText();
        result(organizer, contestId, "confirm", Map.of("winningTeamId", winningTeamId, "previewToken", token))
                .andExpect(status().isOk());
    }

    private ResultActions result(Registered who, long contestId, String step, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/contests/" + contestId + "/result/" + step)
                .header("Authorization", who.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    private Map<String, Object> money(long contestId, long userId) {
        return jdbc.queryForMap("select result, entry_spent, winning_amount from contest_participants "
                + "where contest_id = ? and user_id = ?", contestId, userId);
    }

    private String contestStatus(long contestId) {
        return jdbc.queryForObject("select contest_status from contests where id = ?", String.class, contestId);
    }

    private String leagueStatus(long leagueId) {
        return jdbc.queryForObject("select status from leagues where id = ?", String.class, leagueId);
    }

    private String memberStatus(long leagueId, long userId) {
        return jdbc.queryForObject("select league_member_status from league_members "
                + "where league_id = ? and user_id = ?", String.class, leagueId, userId);
    }

    private static JsonNode boardRow(JsonNode board, long userId) {
        for (JsonNode row : board.get("rows")) {
            if (row.get("userId").asLong() == userId) {
                return row;
            }
        }
        throw new AssertionError("No board row for user " + userId);
    }

    private static JsonNode settlementRow(JsonNode summary, long userId) {
        for (JsonNode row : summary.get("rows")) {
            if (row.get("userId").asLong() == userId) {
                return row;
            }
        }
        throw new AssertionError("No settlement row for user " + userId);
    }

    private static java.math.BigDecimal bd(int rupees) {
        return new java.math.BigDecimal(rupees).setScale(2);
    }

    private JsonNode read(ResultActions result) throws Exception {
        return mapper.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
