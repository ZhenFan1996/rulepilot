package com.rulepilot.shared.adapter.in.web;

import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rulepilot.release")
public record ReleaseIdentityProperties(String id) {

    private static final Pattern DEPLOYED_RELEASE_ID =
            Pattern.compile("[0-9a-f]{40}-[0-9]+(?:-[0-9]+)?");

    public ReleaseIdentityProperties {
        if (!"local".equals(id) && (id == null || !DEPLOYED_RELEASE_ID.matcher(id).matches())) {
            throw new IllegalArgumentException("RulePilot release id is invalid");
        }
    }

    public String commitSha() {
        return "local".equals(id) ? "local" : id.substring(0, 40);
    }
}
