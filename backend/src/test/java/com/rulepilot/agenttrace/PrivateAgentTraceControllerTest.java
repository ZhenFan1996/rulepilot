package com.rulepilot.agenttrace;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PrivateAgentTraceControllerTest {

    private static final Principal ALICE = () -> "alice";

    private MockMvc mvc;
    private PrivateAgentTraceService service;

    @BeforeEach
    void setUp() {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        PrivateAgentTraceProperties properties = new PrivateAgentTraceProperties();
        properties.setAllowedUsers(java.util.List.of("alice"));
        service = new PrivateAgentTraceService(
                new InMemoryPrivateAgentTraceStore(),
                properties,
                Clock.fixed(Instant.parse("2026-08-24T10:00:00Z"), ZoneOffset.UTC));
        var controller = new PrivateAgentTraceController(service, new AgentTraceExporter(json));
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void exposesOnlyNoStoreMetadataAndKeepsTheTraceIdServerSide() throws Exception {
        MockHttpSession session = new MockHttpSession();

        mvc.perform(post("/api/v1/private-agent-trace/start").session(session).principal(ALICE))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(jsonPath("$.state").value("ACTIVE"))
                .andExpect(jsonPath("$.traceId").doesNotExist());

        mvc.perform(get("/api/v1/private-agent-trace").session(session).principal(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventCount").value(0));

        mvc.perform(post("/api/v1/private-agent-trace/start").session(session).principal(ALICE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACTIVE_TRACE_EXISTS"));
    }

    @Test
    void sealsAndDownloadsAZipAttachmentWithoutPreviewingEvents() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/private-agent-trace/start").session(session).principal(ALICE))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/private-agent-trace/seal").session(session).principal(ALICE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("SEALED"));

        MvcResult started = mvc.perform(get("/api/v1/private-agent-trace/export").session(session).principal(ALICE))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", "application/zip"))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        MvcResult completed = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(completed.getResponse().getContentAsByteArray()).isNotEmpty();

        mvc.perform(delete("/api/v1/private-agent-trace").session(session).principal(ALICE))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/private-agent-trace").session(session).principal(ALICE))
                .andExpect(status().isNoContent());
    }

    @Test
    void hidesAllowlistDenialsBehindTheOwnershipNotFoundBoundary() throws Exception {
        Principal unlisted = () -> "unlisted-user";

        mvc.perform(post("/api/v1/private-agent-trace/start")
                        .session(new MockHttpSession())
                        .principal(unlisted))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.code").value("TRACE_NOT_FOUND"));
    }

    @Test
    void rejectsAConcurrentOwnerExportImmediately() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/v1/private-agent-trace/start").session(session).principal(ALICE))
                .andExpect(status().isCreated());

        try (PrivateAgentTraceService.ExportLease ignored = service.beginExport(ALICE, session)) {
            mvc.perform(get("/api/v1/private-agent-trace/export").session(session).principal(ALICE))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(header().string("Cache-Control", "private, no-store"))
                    .andExpect(jsonPath("$.code").value("TRACE_EXPORT_BUSY"));
        }
    }
}
