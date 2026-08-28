package com.rulepilot.shared.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PublicReleaseIdentityControllerTest {

    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String RELEASE_ID = COMMIT_SHA + "-12345-2";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PublicReleaseIdentityController(new ReleaseIdentityProperties(RELEASE_ID)))
                .build();
    }

    @Test
    void exposesTheExactReleaseAndDisablesBrowserAndIntermediaryCaching() throws Exception {
        mockMvc.perform(get("/api/public/release"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.releaseId").value(RELEASE_ID))
                .andExpect(jsonPath("$.commitSha").value(COMMIT_SHA));
    }
}
