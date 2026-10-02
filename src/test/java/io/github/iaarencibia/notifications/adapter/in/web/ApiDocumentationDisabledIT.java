package io.github.iaarencibia.notifications.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.iaarencibia.notifications.IntegrationTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = IntegrationTestSupport.API_KEY_PROPERTY)
@AutoConfigureMockMvc
class ApiDocumentationDisabledIT extends IntegrationTestSupport {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("refuses an anonymous caller on the documentation paths, as on any other path")
    void anonymousCallersAreRefused() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("serves no documentation at all, even to a caller with the key")
    void theDocumentationIsOff() throws Exception {
        mockMvc.perform(get("/v3/api-docs").header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY))
                .andExpect(status().isNotFound());
    }
}
