package com.rulepilottest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import com.rulepilot.RulePilotApplication;
import com.rulepilot.catalog.CatalogGameSelectionLookup;
import com.rulepilot.identity.AccountDirectory;
import com.rulepilot.identity.AccountEmailRegistry;
import com.rulepilot.identity.BoardGameIdentityGrid;
import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.ModelConfigurationStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = RulePilotApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityHeadersIntegrationTest {

    @MockitoBean
    private ModelConfigurationStore modelConfigurationStore;

    @MockitoBean
    private ModelAccountQuota modelAccountQuota;

    @MockitoBean
    private AccountDirectory accountDirectory;

    @MockitoBean
    private BoardGameIdentityGrid boardGameIdentityGrid;

    @MockitoBean
    private AccountEmailRegistry accountEmailRegistry;

    @MockitoBean
    private CatalogGameSelectionLookup catalogGameSelectionLookup;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void appliesBrowserSecurityHeadersToPublicResponses() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Content-Security-Policy",
                        "default-src 'self'; base-uri 'none'; object-src 'none'; frame-ancestors 'none'; "
                                + "form-action 'self'; script-src 'self' 'wasm-unsafe-eval'; "
                                + "worker-src 'self' blob:; style-src 'self'; "
                                + "img-src 'self' data: https:; "
                                + "font-src 'self'; media-src 'self' blob:; "
                                + "connect-src 'self'"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().string(
                        "Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"));
    }

    @Test
    void protectsMcpEndpoint() throws Exception {
        mockMvc.perform(post("/mcp")
                        .contentType("application/json")
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permitsAnonymousBggSelectionReadsButProtectsAgentsAndImports() throws Exception {
        mockMvc.perform(get("/api/v1/bgg/games/42"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bgg/discovery"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bgg/search").param("q", "Wingspan"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bgg/catalog"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/bgg/catalog/covers/42/thumbnail"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/bgg/recommendation-agent"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/bgg/recommendation-agent").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bgg/recommendation-agent").with(user("player")).with(csrf()))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/v1/bgg/recommendation-agent/stream").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/bgg/games/42/import"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/documents"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/documents").with(csrf()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/bgg/ranked-catalog"))
                .andExpect(status().isForbidden());
    }
}
