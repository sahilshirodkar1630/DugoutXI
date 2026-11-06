package com.sahilprojects.bettingApp.jobs;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin-only shortcuts for trying the app out. They were once broken by self-invocation: startNow called
 * fastForward on itself, skipping the transaction proxy, so the lock button failed every time.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@TestPropertySource(properties = "app.dev-tools.enabled=true")
class DevToolsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void startNowLocksALeagueContestAndStartsItsLeague() throws Exception {
        TestLeague league = TestLeagues.create(mvc, mapper, jdbc, 100, 10);
        TestLeagues.open(mvc, league);
        TestLeagues.joinConfirmed(mvc, mapper, league, TestAuth.register(mvc, mapper));
        long contestId = TestContests.leagueContest(mvc, mapper, jdbc, league, "MI", "CSK");
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        mvc.perform(post("/api/dev/contests/" + contestId + "/start-now").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contestStatus").value("LOCKED"));
    }

    @Test
    void startNowLocksAStandaloneContestWhoseDeadlinesWereStillAhead() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");
        Registered player = TestAuth.register(mvc, mapper);
        long row = mapper.readTree(mvc.perform(post("/api/contests/" + contestId + "/join-requests")
                        .header("Authorization", player.bearer()))
                .andReturn().getResponse().getContentAsString()).get("participantId").asLong();
        mvc.perform(post("/api/contests/" + contestId + "/participants/" + row + "/accept")
                        .header("Authorization", organizer.bearer())
                        .contentType("application/json").content("{\"paid\":true}"))
                .andExpect(status().isOk());
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        mvc.perform(post("/api/dev/contests/" + contestId + "/start-now").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contestStatus").value("LOCKED"));
    }

    @Test
    void theShortcutsAreAdminOnly() throws Exception {
        Registered organizer = TestAuth.register(mvc, mapper);
        long contestId = TestContests.standalone(mvc, mapper, jdbc, organizer, 100, "MI", "CSK");

        mvc.perform(post("/api/dev/contests/" + contestId + "/start-now").header("Authorization", organizer.bearer()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/dev/jobs/run").header("Authorization", organizer.bearer()))
                .andExpect(status().isForbidden());
    }
}
