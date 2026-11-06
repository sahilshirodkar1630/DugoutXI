package com.sahilprojects.bettingApp.tournament;

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

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uses its own tournaments and teams, so it never changes the seeded IPL 2026 data other tests rely on. */
class TournamentFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private long idOf(ResultActions result) throws Exception {
        JsonNode body = mapper.readTree(result.andReturn().getResponse().getContentAsString());
        return body.get("id").asLong();
    }

    private long createTournament(Registered admin) throws Exception {
        return idOf(mvc.perform(post("/api/admin/tournaments").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Cup " + UUID.randomUUID()))))
                .andExpect(status().isCreated()));
    }

    private String code() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private long createTeam(Registered admin, String shortCode) throws Exception {
        return idOf(mvc.perform(post("/api/admin/teams").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Team " + UUID.randomUUID(), "shortCode", shortCode))))
                .andExpect(status().isCreated()));
    }

    private void addTeam(Registered admin, long tournamentId, long teamId) throws Exception {
        mvc.perform(put("/api/admin/tournaments/" + tournamentId + "/teams/" + teamId)
                .header("Authorization", admin.bearer())).andExpect(status().isOk());
    }

    @Test
    void seededIplTournamentListsItsTenTeams() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);

        String list = mvc.perform(get("/api/tournaments").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long iplId = 0;
        for (JsonNode t : mapper.readTree(list)) {
            if (t.get("name").asText().equals("IPL 2026")) iplId = t.get("id").asLong();
        }
        assertThat(iplId).isPositive();

        mvc.perform(get("/api/tournaments/" + iplId + "/teams").header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(10));
    }

    @Test
    void pickersNeedALogin() throws Exception {
        mvc.perform(get("/api/tournaments")).andExpect(status().isUnauthorized());
    }

    @Test
    void normalUserCannotCreateTeamsOrTournaments() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);

        mvc.perform(post("/api/admin/teams").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sneaky", "shortCode", "SNK"))))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/tournaments").header("Authorization", user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Sneaky Cup"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminBuildsATournamentWithTeams() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournament = createTournament(admin);
        long a = createTeam(admin, code());
        long b = createTeam(admin, code());

        addTeam(admin, tournament, a);
        addTeam(admin, tournament, b);
        addTeam(admin, tournament, b);                       // adding twice changes nothing

        mvc.perform(get("/api/tournaments/" + tournament + "/teams").header("Authorization", admin.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void shortCodeIsStoredUpperCaseAndMustBeUnique() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        String lower = code().toLowerCase();
        createTeam(admin, lower);

        mvc.perform(post("/api/admin/teams").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Another " + UUID.randomUUID(), "shortCode", lower.toUpperCase()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(jdbc.queryForObject("select count(*) from teams where short_code = ?", Integer.class,
                lower.toUpperCase())).isEqualTo(1);
    }

    @Test
    void invalidShortCodeFailsValidation() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        mvc.perform(post("/api/admin/teams").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Bad Code Team", "shortCode", "TOO-LONG-CODE"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.shortCode").exists());
    }

    @Test
    void endDateBeforeStartDateIsRefused() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        mvc.perform(post("/api/admin/tournaments").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Backwards Cup " + UUID.randomUUID(),
                                "startDate", "2026-05-10", "endDate", "2026-05-01"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deactivatedTeamLeavesThePickerAndTheChangeIsAudited() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournament = createTournament(admin);
        long a = createTeam(admin, code());
        long b = createTeam(admin, code());
        addTeam(admin, tournament, a);
        addTeam(admin, tournament, b);

        mvc.perform(put("/api/admin/teams/" + a + "/active").header("Authorization", admin.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false, "reason", "Team withdrew"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tournaments/" + tournament + "/teams").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.length()").value(1));
        Map<String, Object> audit = jdbc.queryForMap(
                "select action, reason from audit_log where entity_type = 'TEAM' and entity_id = ?", a);
        assertThat(audit.get("action")).isEqualTo("TEAM_ACTIVE_CHANGED");
        assertThat(audit.get("reason")).isEqualTo("Team withdrew");
    }

    @Test
    void inactiveTeamCannotBeAddedToATournament() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournament = createTournament(admin);
        long team = createTeam(admin, code());
        mvc.perform(put("/api/admin/teams/" + team + "/active").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(Map.of("active", false, "reason", "Not playing")))).andExpect(status().isOk());

        mvc.perform(put("/api/admin/tournaments/" + tournament + "/teams/" + team)
                        .header("Authorization", admin.bearer()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void teamUsedByAContestCannotBeRemovedFromTheTournament() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournament = createTournament(admin);
        long a = createTeam(admin, code());
        long b = createTeam(admin, code());
        addTeam(admin, tournament, a);
        addTeam(admin, tournament, b);
        jdbc.update("""
                insert into contests (organizer_id, tournament_id, team_a_id, team_b_id, start_time,
                                      contest_amount, registration_deadline, payment_deadline)
                values (?, ?, ?, ?, now() + interval '2 days', 100, now(), now() + interval '1 day')
                """, admin.id(), tournament, a, b);

        mvc.perform(delete("/api/admin/tournaments/" + tournament + "/teams/" + a)
                        .header("Authorization", admin.bearer()).param("reason", "Cleanup"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("used by contests")));

        mvc.perform(get("/api/tournaments/" + tournament + "/teams").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.length()").value(2));
        assertThat(jdbc.queryForObject("select count(*) from audit_log where entity_type = 'TOURNAMENT' and entity_id = ?",
                Integer.class, tournament)).isZero();
    }

    @Test
    void unusedTeamCanBeRemovedAndTheRemovalIsAudited() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournament = createTournament(admin);
        long team = createTeam(admin, code());
        addTeam(admin, tournament, team);

        mvc.perform(delete("/api/admin/tournaments/" + tournament + "/teams/" + team)
                        .header("Authorization", admin.bearer()).param("reason", "Added by mistake"))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/tournaments/" + tournament + "/teams").header("Authorization", admin.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
        Map<String, Object> audit = jdbc.queryForMap(
                "select action, actor_type, reason from audit_log where entity_type = 'TOURNAMENT' and entity_id = ?", tournament);
        assertThat(audit.get("action")).isEqualTo("TOURNAMENT_TEAM_REMOVED");
        assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
        assertThat(audit.get("reason")).isEqualTo("Added by mistake");
    }

    @Test
    void removingATeamThatIsNotInTheTournamentIsNotFound() throws Exception {
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        long tournament = createTournament(admin);
        long team = createTeam(admin, code());

        mvc.perform(delete("/api/admin/tournaments/" + tournament + "/teams/" + team)
                        .header("Authorization", admin.bearer()).param("reason", "Cleanup"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unknownTournamentIsNotFound() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        mvc.perform(get("/api/tournaments/999999/teams").header("Authorization", user.bearer()))
                .andExpect(status().isNotFound());
    }
}
