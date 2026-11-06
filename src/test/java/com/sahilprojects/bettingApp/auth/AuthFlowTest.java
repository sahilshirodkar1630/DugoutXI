package com.sahilprojects.bettingApp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    private record Registered(long id, String token, String email) {}

    private String json(Object body) throws Exception {
        return mapper.writeValueAsString(body);
    }

    private Registered register() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Test User", "email", email, "password", "password123"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return new Registered(body.get("user").get("id").asLong(), body.get("token").asText(), email);
    }

    private Registered registerAdmin() throws Exception {
        Registered admin = register();
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());   // existing token still works: role is read from the database
        return admin;
    }

    @Test
    void registerThenLoginThenReadProfile() throws Exception {
        Registered user = register();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.email().toUpperCase(), "password", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + user.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.email()))
                .andExpect(jsonPath("$.displayName").value("Test User"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void duplicateEmailIsRefusedIgnoringCase() throws Exception {
        Registered user = register();

        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Other", "email", user.email().toUpperCase(), "password", "password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void shortPasswordFailsValidation() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "X", "email", "x@example.com", "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void wrongPasswordIsUnauthorized() throws Exception {
        Registered user = register();

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", user.email(), "password", "not-the-password"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", "Bearer garbage")).andExpect(status().isUnauthorized());
    }

    @Test
    void profileUpdateCannotChangeTheRole() throws Exception {
        Registered user = register();

        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("displayName", "Rocky", "upiId", "rocky@okbank", "role", "ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Rocky"))
                .andExpect(jsonPath("$.upiId").value("rocky@okbank"))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void invalidUpiIdIsRejected() throws Exception {
        Registered user = register();

        mvc.perform(patch("/api/me").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("upiId", "not a upi id"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void normalUserCannotUseAdminEndpoints() throws Exception {
        Registered user = register();
        Registered other = register();

        mvc.perform(put("/api/admin/users/" + other.id() + "/active").header("Authorization", "Bearer " + user.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false, "reason", "testing"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminDeactivatesAUserWhoseTokenThenStopsWorkingAndTheChangeIsAudited() throws Exception {
        Registered admin = registerAdmin();
        Registered victim = register();

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + victim.token())).andExpect(status().isOk());

        mvc.perform(put("/api/admin/users/" + victim.id() + "/active").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false, "reason", "Left the group"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + victim.token())).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", victim.email(), "password", "password123"))))
                .andExpect(status().isForbidden());

        Map<String, Object> audit = jdbc.queryForMap(
                "select action, actor_type, reason, visible_to_members from audit_log where entity_type = 'USER' and entity_id = ?",
                victim.id());
        assertThat(audit.get("action")).isEqualTo("USER_DEACTIVATED");
        assertThat(audit.get("actor_type")).isEqualTo("ADMIN");
        assertThat(audit.get("reason")).isEqualTo("Left the group");
        assertThat(audit.get("visible_to_members")).isEqualTo(false);
    }

    @Test
    void adminActionWithoutAReasonIsRejected() throws Exception {
        Registered admin = registerAdmin();
        Registered victim = register();

        mvc.perform(put("/api/admin/users/" + victim.id() + "/active").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false, "reason", " "))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminCannotChangeTheirOwnAccess() throws Exception {
        Registered admin = registerAdmin();

        mvc.perform(put("/api/admin/users/" + admin.id() + "/active").header("Authorization", "Bearer " + admin.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("active", false, "reason", "oops"))))
                .andExpect(status().isBadRequest());
    }
}
