package com.rulepilot.modelconfig.adapter.in.web;

import com.rulepilot.identity.AccountDirectory;
import com.rulepilot.modelconfig.ModelAccountQuota;
import com.rulepilot.modelconfig.RuntimeModelConfiguration;
import com.rulepilot.modelconfig.RuntimeModelConfiguration.Snapshot;
import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/model-configuration")
public class ModelAdministrationController {

    private final RuntimeModelConfiguration configuration;
    private final ModelAccountQuota quota;
    private final AccountDirectory accounts;

    public ModelAdministrationController(
            RuntimeModelConfiguration configuration,
            ModelAccountQuota quota,
            AccountDirectory accounts) {
        this.configuration = configuration;
        this.quota = quota;
        this.accounts = accounts;
    }

    @GetMapping
    Snapshot read() {
        return configuration.platformSnapshot();
    }

    @GetMapping("/accounts")
    List<AccountUsageView> accounts() {
        LocalDate periodStart = LocalDate.now(Clock.systemUTC()).withDayOfMonth(1);
        return accounts.accounts().stream()
                .map(account -> new AccountUsageView(
                        account.username(),
                        account.email(),
                        account.enabled(),
                        account.authorities(),
                        quota.usage(account.username(), periodStart)))
                .toList();
    }

    @PutMapping("/providers/{provider}")
    Snapshot configureProvider(
            @PathVariable String provider,
            @RequestBody ConfigureProviderRequest request,
            Principal principal) {
        return configuration.configurePlatform(
                principal.getName(),
                provider,
                request.apiKey(),
                request.baseUrl(),
                request.model(),
                request.visionCapable());
    }

    @DeleteMapping("/providers/{provider}")
    Snapshot disableProvider(@PathVariable String provider, Principal principal) {
        return configuration.disablePlatform(principal.getName(), provider);
    }

    @PutMapping("/assignments")
    Snapshot assign(@RequestBody AssignmentsRequest request, Principal principal) {
        return configuration.assignPlatform(
                principal.getName(),
                request.teaching(),
                request.visual(),
                request.answer(),
                request.critic(),
                request.recommendation());
    }

    @GetMapping("/accounts/{username}/quota")
    ModelAccountQuota.AccountUsage quota(@PathVariable String username) {
        LocalDate today = LocalDate.now(Clock.systemUTC());
        return quota.usage(username, today.withDayOfMonth(1));
    }

    @PutMapping("/accounts/{username}/quota")
    ModelAccountQuota.AccountUsage updateQuota(
            @PathVariable String username,
            @RequestBody UpdateQuotaRequest request,
            Principal principal) {
        return quota.updateLimit(
                username,
                request.platformAccessEnabled(),
                request.monthlyTokenLimit(),
                principal.getName(),
                Instant.now());
    }

    record ConfigureProviderRequest(String apiKey, String baseUrl, String model, boolean visionCapable) {}

    record AssignmentsRequest(String teaching, String visual, String answer, String critic, String recommendation) {}

    record UpdateQuotaRequest(boolean platformAccessEnabled, long monthlyTokenLimit) {}

    record AccountUsageView(
            String username,
            String email,
            boolean enabled,
            Set<String> authorities,
            ModelAccountQuota.AccountUsage usage) {}
}
