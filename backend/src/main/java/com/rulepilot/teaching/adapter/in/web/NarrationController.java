package com.rulepilot.teaching.adapter.in.web;

import com.rulepilot.teaching.application.NarrationService;
import com.rulepilot.teaching.application.NarrationService.NarrationPlayback;
import com.rulepilot.teaching.domain.NarrationScript;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teaching-plans/{planId}/narration")
@Profile("!test")
public class NarrationController {

    private final NarrationService narration;

    public NarrationController(NarrationService narration) {
        this.narration = narration;
    }

    @GetMapping("/script")
    NarrationScript script(@PathVariable UUID planId) {
        return narration.script(planId);
    }

    @GetMapping("/playback")
    NarrationPlayback playback(@PathVariable UUID planId) {
        return narration.playback(planId);
    }

    @GetMapping("/audio")
    ResponseEntity<byte[]> audio(@PathVariable UUID planId) {
        var audio = narration.audio(planId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(audio.contentType()))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .header("X-RulePilot-Speech-Provider", audio.provider())
                .header("X-RulePilot-Audio-Duration-Ms", Long.toString(audio.durationMillis()))
                .body(audio.audio());
    }
}
