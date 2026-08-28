package com.rulepilot.shared.adapter.in.web;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicReleaseIdentityController {

    private final ReleaseIdentityProperties releaseIdentity;

    public PublicReleaseIdentityController(ReleaseIdentityProperties releaseIdentity) {
        this.releaseIdentity = releaseIdentity;
    }

    @GetMapping("/api/public/release")
    ResponseEntity<PublicReleaseIdentity> releaseIdentity() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PublicReleaseIdentity(releaseIdentity.id(), releaseIdentity.commitSha()));
    }

    public record PublicReleaseIdentity(String releaseId, String commitSha) {}
}
