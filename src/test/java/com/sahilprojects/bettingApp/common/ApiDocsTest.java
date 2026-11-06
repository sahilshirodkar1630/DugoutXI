package com.sahilprojects.bettingApp.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sahilprojects.bettingApp.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API reference: the OpenAPI description springdoc generates from the controllers, and the Scalar page that
 * shows it. Guards that every endpoint is described and grouped, so a new one cannot slip in undocumented.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class ApiDocsTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void theDescriptionIsServedWithoutSigningIn() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.get("openapi").asText()).startsWith("3.");
        assertThat(spec.at("/info/title").asText()).isEqualTo("Dugout XI API");
        assertThat(spec.at("/components/securitySchemes/bearerAuth/scheme").asText()).isEqualTo("bearer");
    }

    @Test
    void everyEndpointHasASummaryAndAKnownGroup() throws Exception {
        JsonNode spec = spec();
        Set<String> groups = new HashSet<>();
        ApiDocsConfig.TAGS.forEach(tag -> groups.add(tag.getName()));
        List<String> problems = new ArrayList<>();
        int operations = 0;

        for (var path = spec.get("paths").fields(); path.hasNext(); ) {
            var entry = path.next();
            for (var op = entry.getValue().fields(); op.hasNext(); ) {
                var operation = op.next();
                String name = operation.getKey().toUpperCase() + " " + entry.getKey();
                JsonNode body = operation.getValue();
                operations++;
                if (body.path("summary").asText("").isBlank()) problems.add(name + " has no summary");
                if (!body.has("tags") || body.get("tags").isEmpty()) problems.add(name + " has no group");
                body.path("tags").forEach(tag -> {
                    if (!groups.contains(tag.asText())) problems.add(name + " is in unknown group " + tag.asText());
                });
            }
        }
        assertThat(problems).isEmpty();
        assertThat(operations).isGreaterThan(70);
    }

    @Test
    void signingInNeedsNoTokenButEverythingElseDoes() throws Exception {
        JsonNode spec = spec();
        assertThat(spec.at("/paths/~1api~1auth~1login/post/security")).hasSize(0);
        assertThat(spec.at("/paths/~1api~1leagues/post/security").isMissingNode()).isTrue();   // the global default
        assertThat(spec.at("/security/0/bearerAuth").isArray()).isTrue();
    }

    @Test
    void onlyTheApiIsDescribedNotTheDocsPageItself() throws Exception {
        JsonNode paths = spec().get("paths");
        paths.fieldNames().forEachRemaining(path -> assertThat(path).startsWith("/api/"));
    }

    @Test
    void theReferencePageLoadsScalarPointedAtTheDescription() throws Exception {
        String page = mvc.perform(get("/docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn().getResponse().getContentAsString();
        assertThat(page).contains("data-url=\"/v3/api-docs\"").contains(ApiDocsController.SCALAR);
        assertThat(ApiDocsController.SCALAR).matches(".*@\\d+\\.\\d+\\.\\d+$");      // pinned, never "latest"
    }

    private JsonNode spec() throws Exception {
        return mapper.readTree(mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }
}
