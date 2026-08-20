package com.rulepilottest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(
        classes = RulePilotApplication.class,
        properties = {"management.health.redis.enabled=false", "management.health.rabbit.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ActuatorHealthIntegrationTest {

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
    void exposesHealthyActuatorEndpointWithoutInternalDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
