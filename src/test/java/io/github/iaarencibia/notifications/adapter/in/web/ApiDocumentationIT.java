package io.github.iaarencibia.notifications.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.iaarencibia.notifications.IntegrationTestSupport;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        properties = {
            IntegrationTestSupport.API_KEY_PROPERTY,
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true"
        })
@AutoConfigureMockMvc
class ApiDocumentationIT extends IntegrationTestSupport {

    private static final String SPEC_PATH = "/v3/api-docs";
    private static final String COLLECTION_PATH = "/api/v1/notifications";
    private static final String ITEM_PATH = "/api/v1/notifications/{id}";

    @Autowired private MockMvc mockMvc;

    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("serves the OpenAPI document without a credential")
    void servesTheSpecWithoutACredential() throws Exception {
        mockMvc.perform(get(SPEC_PATH))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.info.title").value("Notification Service API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    @Test
    @DisplayName("serves Swagger UI without a credential")
    void servesSwaggerUiWithoutACredential() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/swagger-ui/index.html"));
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("keeps the business endpoints behind the credential")
    void keepsBusinessEndpointsProtected() throws Exception {
        mockMvc.perform(post(COLLECTION_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(COLLECTION_PATH + "/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("declares the shared key as an Authorization header scheme, applied to everything")
    void declaresTheApiKeyScheme() throws Exception {
        JsonNode spec = spec();

        JsonNode scheme = spec.at("/components/securitySchemes").elements().next();
        assertThat(scheme.path("type").asText()).isEqualTo("apiKey");
        assertThat(scheme.path("in").asText()).isEqualTo("header");
        assertThat(scheme.path("name").asText()).isEqualTo("Authorization");
        assertThat(scheme.path("description").asText()).contains("ApiKey <key>");

        String schemeName = spec.at("/components/securitySchemes").fieldNames().next();
        assertThat(spec.at("/security")).hasSize(1);
        assertThat(spec.at("/security/0").has(schemeName)).isTrue();
    }

    @Test
    @DisplayName("lists exactly the responses the submit operation can give")
    void listsTheSubmitResponses() throws Exception {
        JsonNode responses = spec().at("/paths").path(COLLECTION_PATH).at("/post/responses");

        assertThat(fieldNamesOf(responses)).isEqualTo(Set.of("202", "400", "401", "422"));
        assertThat(responses.at("/202/content/application~1json/schema/$ref").asText())
                .endsWith("/SubmitNotificationResponse");
        assertThat(responses.at("/400/content").has("application/problem+json")).isTrue();
        assertThat(responses.at("/401/content").has("application/problem+json")).isTrue();
        assertThat(responses.at("/422/content").has("application/problem+json")).isTrue();
    }

    @Test
    @DisplayName("lists exactly the responses the query operation can give")
    void listsTheQueryResponses() throws Exception {
        JsonNode responses = spec().at("/paths").path(ITEM_PATH).at("/get/responses");

        assertThat(fieldNamesOf(responses)).isEqualTo(Set.of("200", "400", "401", "404"));
        assertThat(responses.at("/200/content/application~1json/schema/$ref").asText())
                .endsWith("/NotificationDetailsResponse");
        assertThat(responses.at("/404/content").has("application/problem+json")).isTrue();
    }

    @Test
    @DisplayName("documents the two optional headers of the submit operation")
    void documentsTheSubmitHeaders() throws Exception {
        JsonNode parameters = spec().at("/paths").path(COLLECTION_PATH).at("/post/parameters");

        Set<String> headers = new HashSet<>();
        parameters.forEach(
                parameter -> {
                    assertThat(parameter.path("in").asText()).isEqualTo("header");
                    assertThat(parameter.path("required").asBoolean()).isFalse();
                    headers.add(parameter.path("name").asText());
                });
        assertThat(headers).isEqualTo(Set.of("Idempotency-Key", "X-Correlation-Id"));
    }

    @Test
    @DisplayName("carries the character limits of the request into the schema")
    void carriesTheCharacterLimits() throws Exception {
        JsonNode properties = spec().at("/components/schemas/SubmitNotificationRequest/properties");

        assertThat(properties.at("/recipient/maxLength").asInt()).isEqualTo(2048);
        assertThat(properties.at("/subject/maxLength").asInt()).isEqualTo(512);
        assertThat(properties.at("/body/maxLength").asInt()).isEqualTo(16384);
    }

    @Test
    @DisplayName("carries the metadata limits of the request into the schema")
    void carriesTheMetadataLimits() throws Exception {
        JsonNode metadata =
                spec().at("/components/schemas/SubmitNotificationRequest/properties/metadata");

        assertThat(metadata.path("maxProperties").asInt()).isEqualTo(32);
        assertThat(metadata.path("description").asText())
                .contains("Keys at most 128 characters")
                .contains("values at most 2048 characters");
    }

    @Test
    @DisplayName("describes every error once, as a problem document")
    void describesErrorsOnceAsAProblemDocument() throws Exception {
        JsonNode spec = spec();

        assertThat(spec.at("/components/schemas").has("ProblemDetail")).isTrue();
        assertThat(unauthorizedOf(spec, COLLECTION_PATH, "post")).endsWith("/ProblemDetail");
        assertThat(unauthorizedOf(spec, ITEM_PATH, "get")).endsWith("/ProblemDetail");
    }

    private static String unauthorizedOf(JsonNode spec, String path, String method) {
        return spec.at("/paths")
                .path(path)
                .at("/" + method + "/responses/401/content/application~1problem+json/schema/$ref")
                .asText();
    }

    private JsonNode spec() throws Exception {
        String json =
                mockMvc.perform(get(SPEC_PATH))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return objectMapper.readTree(json);
    }

    private static Set<String> fieldNamesOf(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
