package com.rulepilot.document.application;

import com.rulepilot.document.DocumentProcessingJobs;
import com.rulepilot.document.DocumentProcessingStage;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class DocumentProcessingJobService implements DocumentProcessingJobs {

    private final DocumentProcessingJobStore jobs;
    private final Clock clock;

    @Autowired
    public DocumentProcessingJobService(DocumentProcessingJobStore jobs) {
        this(jobs, Clock.systemUTC());
    }

    DocumentProcessingJobService(DocumentProcessingJobStore jobs, Clock clock) {
        this.jobs = jobs;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void stageStarted(UUID jobId, DocumentProcessingStage stage) {
        jobs.update(jobId, stage, "RUNNING", Instant.now(clock));
    }

    @Override
    @Transactional
    public Instant completed(UUID jobId, DocumentProcessingStage stage) {
        Instant completedAt = Instant.now(clock);
        jobs.update(jobId, stage, "COMPLETED", completedAt);
        return completedAt;
    }

    @Override
    @Transactional
    public void failed(UUID jobId, DocumentProcessingStage stage) {
        jobs.update(jobId, stage, "FAILED", Instant.now(clock));
    }
}
