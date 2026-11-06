package com.sahilprojects.bettingApp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Registers users through the real API so integration tests can act as a normal user or an admin. */
public final class TestAuth {

    public record Registered(long id, String token, String email) {
        public String bearer() {
            return "Bearer " + token;
        }
    }

    private TestAuth() {
    }

    public static Registered register(MockMvc mvc, ObjectMapper mapper) throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        MvcResult result = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("name", "Test User", "email", email, "password", "password123"))))
                .andExpect(status().isCreated()).andReturn();
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        return new Registered(body.get("user").get("id").asLong(), body.get("token").asText(), email);
    }

    /** The role is read from the database on every request, so the token from registration works as an admin token. */
    public static Registered registerAdmin(MockMvc mvc, ObjectMapper mapper, JdbcTemplate jdbc) throws Exception {
        Registered admin = register(mvc, mapper);
        jdbc.update("update users set role = 'ADMIN' where id = ?", admin.id());
        return admin;
    }
}
