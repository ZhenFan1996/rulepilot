package com.rulepilot.document.application;

import com.rulepilot.document.RulebookTeachingHandoffs;
import com.rulepilot.document.domain.DocumentSourceType;
import com.rulepilot.document.domain.OfficialRulebookImportJob;
import com.rulepilot.document.domain.OfficialRulebookImportJob.TeachingHandoffState;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class OfficialRulebookImportJobService implements RulebookTeachingHandoffs {

    private final OfficialRulebookImportJobRepository jobs;
    private final OfficialRulebookImportService imports;
    private final TaskExecutor executor;
    private final Clock clock;

    @Autowired
    public OfficialRulebookImportJobService(
            OfficialRulebookImportJobRepository jobs,
            OfficialRulebookImportService imports,
            @Qualifier("officialRulebookImportExecutor") TaskExecutor executor) {
        this(jobs, imports, executor, Clock.systemUTC());
    }

    OfficialRulebookImportJobService(
            OfficialRulebookImportJobRepository jobs,
            OfficialRulebookImportService imports,
            TaskExecutor executor,
            Clock clock) {
        this.jobs = jobs;
        this.imports = imports;
        this.executor = executor;
        this.clock = clock;
    }

    public Launch enqueue(Command command, String ownerUsername) {
        Command checked = command.checked();
        String owner = checkedOwner(ownerUsername);
        var active = jobs.findActiveOwnedBySource(owner, checked.officialSourceUrl());
        if (active.isPresent()) {
            return new Launch(ensureTeachingRequested(active.orElseThrow(), checked), true);
        }
        if (checked.startTeaching()) {
            var completed = jobs.findCompletedOwnedBySourceAndEdition(
                    owner, checked.officialSourceUrl(), checked.editionId());
            if (completed.isPresent()) {
                return new Launch(ensureTeachingRequested(completed.orElseThrow(), checked), true);
            }
        }
        Instant now = Instant.now(clock);
        var job = OfficialRulebookImportJob.queued(
                UUID.randomUUID(), owner, checked.editionId(), checked.title(), checked.sourceType(),
                checked.officialSourceUrl(), checked.startTeaching(), checked.learningGoal(), now);
        jobs.insert(job);
        try {
            executor.execute(() -> execute(job));
        } catch (TaskRejectedException exception) {
            jobs.fail(job.id(), "IMPORT_QUEUE_FULL", Instant.now(clock));
            return new Launch(requireOwned(job.id(), owner), false);
        }
        return new Launch(job, false);
    }

    public OfficialRulebookImportJob requireOwned(UUID jobId, String ownerUsername) {
        return jobs.findOwned(jobId, checkedOwner(ownerUsername))
                .orElseThrow(() -> new IllegalArgumentException("official rulebook import job does not exist"));
    }

    public List<OfficialRulebookImportJob> recentOwned(String ownerUsername) {
        return jobs.findRecentOwned(checkedOwner(ownerUsername), 12);
    }

    public int failInterrupted() {
        return jobs.failInterrupted(Instant.now(clock));
    }

    @Override
    public List<ReadyHandoff> claimReady(int limit) {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("teaching handoff claim limit is invalid");
        return jobs.claimReadyTeaching(limit, Instant.now(clock)).stream()
                .map(job -> new ReadyHandoff(
                        job.id(),
                        job.documentVersionId(),
                        job.ownerUsername(),
                        job.teachingHandoff().learningGoal()))
                .toList();
    }

    @Override
    public void markLaunched(UUID importJobId, UUID preparationRunId) {
        if (importJobId == null || preparationRunId == null) {
            throw new IllegalArgumentException("teaching handoff launch identity is required");
        }
        jobs.completeTeachingLaunch(importJobId, preparationRunId, Instant.now(clock));
    }

    @Override
    public void markFailed(UUID importJobId, String errorCode) {
        if (importJobId == null || errorCode == null || errorCode.isBlank() || errorCode.length() > 64) {
            throw new IllegalArgumentException("teaching handoff failure is invalid");
        }
        jobs.failTeachingLaunch(importJobId, errorCode, Instant.now(clock));
    }

    @Override
    public int failInterruptedLaunches() {
        return jobs.failInterruptedTeachingLaunches(Instant.now(clock));
    }

    private OfficialRulebookImportJob ensureTeachingRequested(
            OfficialRulebookImportJob job, Command command) {
        if (!command.startTeaching()
                || job.teachingHandoff().state() != TeachingHandoffState.NOT_REQUESTED
                        && job.teachingHandoff().state() != TeachingHandoffState.FAILED) {
            return job;
        }
        jobs.requestTeaching(job.id(), command.learningGoal(), Instant.now(clock));
        return requireOwned(job.id(), job.ownerUsername());
    }

    private void execute(OfficialRulebookImportJob job) {
        try {
            jobs.updateProgress(job.id(), OfficialRulebookImportJob.Stage.CONNECTING, 0, null, Instant.now(clock));
            var result = imports.importRulebook(
                    job.editionId(),
                    job.title(),
                    job.sourceType(),
                    job.sourceUrl(),
                    true,
                    job.ownerUsername(),
                    new OfficialRulebookSourceFetcher.ProgressListener() {
                        @Override
                        public void downloadStarted(Long totalBytes) {
                            jobs.updateProgress(
                                    job.id(), OfficialRulebookImportJob.Stage.DOWNLOADING, 0, totalBytes, Instant.now(clock));
                        }

                        @Override
                        public void downloaded(long downloadedBytes, Long totalBytes) {
                            jobs.updateProgress(
                                    job.id(), OfficialRulebookImportJob.Stage.DOWNLOADING,
                                    downloadedBytes, totalBytes, Instant.now(clock));
                        }

                        @Override
                        public void compressing() {
                            var current = requireOwned(job.id(), job.ownerUsername());
                            jobs.updateProgress(
                                    job.id(), OfficialRulebookImportJob.Stage.COMPRESSING,
                                    current.downloadedBytes(), current.totalBytes(), Instant.now(clock));
                        }

                        @Override
                        public void verifying() {
                            var current = requireOwned(job.id(), job.ownerUsername());
                            jobs.updateProgress(
                                    job.id(), OfficialRulebookImportJob.Stage.VERIFYING_FILE,
                                    current.downloadedBytes(), current.totalBytes(), Instant.now(clock));
                        }

                        @Override
                        public void saving() {
                            var current = requireOwned(job.id(), job.ownerUsername());
                            jobs.updateProgress(
                                    job.id(), OfficialRulebookImportJob.Stage.SAVING,
                                    current.downloadedBytes(), current.totalBytes(), Instant.now(clock));
                        }
                    });
            jobs.complete(job.id(), result.version().id(), result.duplicate(), Instant.now(clock));
        } catch (RuntimeException exception) {
            jobs.fail(job.id(), failureCode(exception), Instant.now(clock));
        }
    }

    private String failureCode(RuntimeException exception) {
        if (exception instanceof OfficialRulebookSourceAccessException access
                && access.reason() == OfficialRulebookSourceAccessException.Reason.INTERACTIVE_BROWSER_REQUIRED) {
            return "SOURCE_BROWSER_REQUIRED";
        }
        if (exception instanceof IllegalArgumentException) return "INVALID_PDF_SOURCE";
        return "SOURCE_UNAVAILABLE";
    }

    private String checkedOwner(String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) {
            throw new IllegalArgumentException("official rulebook import owner is required");
        }
        return ownerUsername.strip();
    }

    public record Command(
            UUID editionId,
            String title,
            DocumentSourceType sourceType,
            String officialSourceUrl,
            boolean rightsConfirmed,
            boolean startTeaching,
            String learningGoal) {

        public Command(
                UUID editionId,
                String title,
                DocumentSourceType sourceType,
                String officialSourceUrl,
                boolean rightsConfirmed) {
            this(editionId, title, sourceType, officialSourceUrl, rightsConfirmed, false, null);
        }

        Command checked() {
            if (!rightsConfirmed) throw new IllegalArgumentException("official source rights confirmation is required");
            if (title == null || title.isBlank() || title.strip().length() > 160 || sourceType == null) {
                throw new IllegalArgumentException("official rulebook import metadata is invalid");
            }
            URI source = URI.create(officialSourceUrl == null ? "" : officialSourceUrl.strip());
            if (!"https".equalsIgnoreCase(source.getScheme()) || source.getHost() == null
                    || source.getUserInfo() != null || source.getFragment() != null
                    || source.getPort() != -1 && source.getPort() != 443
                    || source.toASCIIString().length() > 2000) {
                throw new IllegalArgumentException("official rulebook source must use standard public HTTPS");
            }
            String normalizedGoal = learningGoal == null || learningGoal.isBlank() ? null : learningGoal.strip();
            if (normalizedGoal != null && normalizedGoal.length() > 500) {
                throw new IllegalArgumentException("teaching learning goal is too long");
            }
            if (!startTeaching && normalizedGoal != null) {
                throw new IllegalArgumentException("teaching goal requires an automatic teaching handoff");
            }
            return new Command(
                    editionId, title.strip(), sourceType, source.toASCIIString(), true, startTeaching, normalizedGoal);
        }
    }

    public record Launch(OfficialRulebookImportJob job, boolean reused) {}
}
