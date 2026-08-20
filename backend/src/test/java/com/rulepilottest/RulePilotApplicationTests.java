package com.rulepilottest;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = RulePilotApplication.class)
@ActiveProfiles("test")
class RulePilotApplicationTests {

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
    private ApplicationContext applicationContext;

    @Test
    void applicationContextLoads() {
        assertThat(applicationContext.getBean(RulePilotApplication.class)).isNotNull();
    }
}
