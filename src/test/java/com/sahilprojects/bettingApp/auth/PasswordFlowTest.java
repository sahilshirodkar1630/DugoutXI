package com.sahilprojects.bettingApp.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import com.sahilprojects.bettingApp.TestAuth;
import com.sahilprojects.bettingApp.TestAuth.Registered;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Password changes and resets. There is no email, so a locked-out player asks the admins ("forgot password"), an
 * admin hands out a temporary password, and the player must replace it. Any password change signs out every older
 * session.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PasswordFlowTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void changingYourPasswordNeedsTheCurrentOne() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        change(user.bearer(), "not-my-password", "brand-new-pass")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Your current password is not right"));
    }

    @Test
    void theNewPasswordMustDifferAndBeLongEnough() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        change(user.bearer(), "password123", "password123").andExpect(status().isBadRequest());
        change(user.bearer(), "password123", "short").andExpect(status().isBadRequest());
    }

    @Test
    void aChangedPasswordWorksAndSignsOutOlderSessions() throws Exception {
        Registered user = TestAuth.register(mvc, mapper);
        Thread.sleep(1100);   // tokens carry whole seconds, so the change must land in a later second to count

        JsonNode changed = read(change(user.bearer(), "password123", "brand-new-pass").andExpect(status().isOk()));
        String fresh = "Bearer " + changed.get("token").asText();

        mvc.perform(get("/api/me").header("Authorization", user.bearer())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header("Authorization", fresh)).andExpect(status().isOk());
        login(user.email(), "password123").andExpect(status().isUnauthorized());
        login(user.email(), "brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void anAdminResetGivesATemporaryPasswordThatMustBeReplaced() throws Exception {
        Registered player = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);
        Thread.sleep(1100);

        JsonNode reset = read(resetBy(admin, player.id(), "Locked out, asked on WhatsApp").andExpect(status().isOk()));
        String temporary = reset.get("temporaryPassword").asText();
        assertThat(temporary).hasSize(10);
        assertThat(reset.get("email").asText()).isEqualTo(player.email());

        mvc.perform(get("/api/me").header("Authorization", player.bearer())).andExpect(status().isUnauthorized());
        login(player.email(), "password123").andExpect(status().isUnauthorized());
        JsonNode signedIn = read(login(player.email(), temporary).andExpect(status().isOk())
                .andExpect(jsonPath("$.user.mustChangePassword").value(true)));

        JsonNode changed = read(change("Bearer " + signedIn.get("token").asText(), temporary, "my-own-password")
                .andExpect(status().isOk()));
        assertThat(changed.get("user").get("mustChangePassword").asBoolean()).isFalse();

        Integer audited = jdbc.queryForObject(
                "select count(*) from audit_log where action = 'USER_PASSWORD_RESET' and entity_id = ? and reason is not null",
                Integer.class, player.id());
        assertThat(audited).isEqualTo(1);
    }

    @Test
    void onlyAnAdminCanResetAPasswordAndOnlyWithAReason() throws Exception {
        Registered player = TestAuth.register(mvc, mapper);
        Registered other = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        resetBy(other, player.id(), "Because").andExpect(status().isForbidden());
        resetBy(admin, player.id(), " ").andExpect(status().isBadRequest());
        resetBy(admin, admin.id(), "Myself").andExpect(status().isBadRequest());
        resetBy(admin, 999_999L, "Nobody").andExpect(status().isNotFound());
    }

    @Test
    void forgotPasswordAlwaysAnswersTheSameAndTellsTheAdminsOnceAnHour() throws Exception {
        Registered player = TestAuth.register(mvc, mapper);
        Registered admin = TestAuth.registerAdmin(mvc, mapper, jdbc);

        forgot("nobody-" + player.email()).andExpect(status().isAccepted());
        forgot(player.email().toUpperCase()).andExpect(status().isAccepted());
        forgot(player.email()).andExpect(status().isAccepted());

        Integer told = jdbc.queryForObject("""
                select count(*) from notifications
                 where user_id = ? and notification_type = 'PASSWORD_RESET_REQUESTED' and message like ?
                """, Integer.class, admin.id(), "%" + player.email() + "%");
        assertThat(told).isEqualTo(1);
    }

    @Test
    void forgotPasswordNeedsNoSignInButAnEmail() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions change(String bearer, String current, String next) throws Exception {
        return mvc.perform(post("/api/me/password").header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("currentPassword", current, "newPassword", next))));
    }

    private ResultActions resetBy(Registered admin, long userId, String reason) throws Exception {
        return mvc.perform(post("/api/admin/users/" + userId + "/password-reset").header("Authorization", admin.bearer())
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("reason", reason))));
    }

    private ResultActions login(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("email", email))));
    }

    private JsonNode read(ResultActions actions) throws Exception {
        return mapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }
}
