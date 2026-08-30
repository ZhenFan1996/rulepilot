package com.rulepilot.teaching.adapter.out.vision;

import static org.assertj.core.api.Assertions.assertThat;

import com.rulepilot.ingestion.layout.RulebookUnderstanding.Rectangle;
import com.rulepilot.teaching.VisualRegionProposer.Diagnostic;
import com.rulepilot.document.DocumentPageImages;
import io.micrometer.observation.ObservationRegistry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class OpenCvVisualRegionProposerTest {

    @Test
    void runsTheBoundedExternalToolAndAcceptsOnlyItsTypedPixelGeometry(@TempDir Path temporaryDirectory)
            throws Exception {
        Path executable = temporaryDirectory.resolve("typed-region-tool");
        Files.writeString(
                executable,
                "#!/bin/sh\nprintf 'memory=%s\\ncpu=%s\\nargs=%s\\n' "
                        + "\"$RULEPILOT_OPENCV_WORKING_ADDRESS_SPACE_BYTES\" "
                        + "\"$RULEPILOT_OPENCV_CPU_LIMIT_SECONDS\" \"$*\" "
                        + "> \"$0.invocation\"\ncat >/dev/null\nprintf '%s' "
                        + "'{\"schemaVersion\":1,\"width\":2000,\"height\":1000,"
                        + "\"regions\":[{\"x\":200,\"y\":100,\"width\":400,\"height\":300}]}'\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var proposer = new OpenCvVisualRegionProposer(
                ObservationRegistry.create(), true, executable.toString(), Duration.ofSeconds(5), 32);

        var result = proposer.propose(
                new DocumentPageImages.PageImage(4, "image/jpeg", new byte[] {1, 2, 3}, 2000, 1000),
                Duration.ofSeconds(5));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(result.proposals()).singleElement().satisfies(proposal ->
                assertThat(proposal.rectangle()).isEqualTo(new Rectangle(100, 100, 200, 300)));
        assertThat(Files.readString(executable.resolveSibling("typed-region-tool.invocation")))
                .contains("memory=402653184", "cpu=6", "--page-width 2000", "--page-height 1000");
    }

    @Test
    void keepsCommandLaunchFailureLocalSoALaterWorkflowCanRecover(@TempDir Path temporaryDirectory) {
        var proposer = new OpenCvVisualRegionProposer(
                ObservationRegistry.create(),
                true,
                temporaryDirectory.resolve("missing-python").toString(),
                Duration.ofSeconds(1),
                32);

        var result = proposer.propose(
                new DocumentPageImages.PageImage(1, "image/jpeg", new byte[] {1}, 1000, 1000),
                Duration.ofSeconds(1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNAVAILABLE);
        assertThat(proposer.configured()).isTrue();
    }

    @Test
    void classifiesAStartedToolWithABrokenOutputContractAsRuntimeUnavailable(@TempDir Path temporaryDirectory)
            throws Exception {
        Path executable = temporaryDirectory.resolve("broken-region-tool");
        Files.writeString(
                executable,
                "#!/bin/sh\nprintf '%s' '{\"schemaVersion\":1,\"width\":1000,\"height\":1000,"
                        + "\"regions\":[],\"unexpected\":true}'\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var proposer = new OpenCvVisualRegionProposer(
                ObservationRegistry.create(), true, executable.toString(), Duration.ofSeconds(5), 32);

        var result = proposer.propose(
                new DocumentPageImages.PageImage(1, "image/jpeg", new byte[] {1}, 1000, 1000),
                Duration.ofSeconds(5));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.UNAVAILABLE);
    }

    @Test
    @Timeout(2)
    void stopsOnePageLocallyWhenTheExternalToolExceedsItsDeadline(@TempDir Path temporaryDirectory)
            throws Exception {
        Path executable = temporaryDirectory.resolve("slow-region-tool");
        Files.writeString(executable, "#!/bin/sh\nwhile :; do :; done\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var proposer = new OpenCvVisualRegionProposer(
                ObservationRegistry.create(), true, executable.toString(), Duration.ofMillis(20), 32);

        var result = proposer.propose(
                new DocumentPageImages.PageImage(1, "image/jpeg", new byte[5 * 1024 * 1024], 1000, 1000),
                Duration.ofSeconds(1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.TIMEOUT);
        assertThat(proposer.configured()).isTrue();
    }

    @Test
    @Timeout(8)
    void timeoutTerminatesForkedDescendantsBeforeReturning(@TempDir Path temporaryDirectory) throws Exception {
        Path executable = temporaryDirectory.resolve("forking-region-tool");
        Path childPid = temporaryDirectory.resolve("forking-region-tool.child.pid");
        Files.writeString(
                executable,
                "#!/bin/sh\nsleep 30 &\nchild=$!\nprintf '%s' \"$child\" > \"$0.child.pid\"\n"
                        + "while :; do :; done\n");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        var proposer = new OpenCvVisualRegionProposer(
                ObservationRegistry.create(), true, executable.toString(), Duration.ofSeconds(3), 32);
        AtomicReference<com.rulepilot.teaching.VisualRegionProposer.ProposalResult> outcome =
                new AtomicReference<>();

        Thread proposalCall = Thread.ofVirtual().start(() -> outcome.set(proposer.propose(
                new DocumentPageImages.PageImage(1, "image/jpeg", new byte[] {1}, 1000, 1000),
                Duration.ofSeconds(4))));

        long childStartedDeadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!Files.exists(childPid) && proposalCall.isAlive() && System.nanoTime() < childStartedDeadline) {
            Thread.sleep(10);
        }
        assertThat(childPid).exists();
        long childProcessId = Long.parseLong(Files.readString(childPid).strip());
        assertThat(isAlive(childProcessId)).isTrue();

        proposalCall.join(Duration.ofSeconds(5).toMillis());
        assertThat(proposalCall.isAlive()).isFalse();
        assertThat(outcome.get()).isNotNull().satisfies(result ->
                assertThat(result.diagnostic()).isEqualTo(Diagnostic.TIMEOUT));
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (isAlive(childProcessId) && System.nanoTime() < deadlineNanos) {
            Thread.sleep(10);
        }
        assertThat(isAlive(childProcessId)).isFalse();
    }

    @Test
    void rejectsOversizedDecodedGeometryBeforeStartingTheExternalProcess(@TempDir Path temporaryDirectory) {
        var proposer = new OpenCvVisualRegionProposer(
                ObservationRegistry.create(),
                true,
                temporaryDirectory.resolve("must-not-start").toString(),
                Duration.ofSeconds(1),
                32);

        var result = proposer.propose(
                new DocumentPageImages.PageImage(1, "image/jpeg", new byte[] {1}, 4_000, 4_000),
                Duration.ofSeconds(1));

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.FAILED);
        assertThat(proposer.configured()).isTrue();
    }

    @Test
    void admitsOnlyExactToolGeometryAndNormalizesItIntoApplicationCoordinates() throws Exception {
        byte[] response = """
                {"schemaVersion":1,"width":2000,"height":1000,"regions":[
                  {"x":200,"y":100,"width":400,"height":300},
                  {"x":1200,"y":250,"width":600,"height":500}
                ]}
                """.getBytes(StandardCharsets.UTF_8);

        var result = OpenCvVisualRegionProposer.parse(response, 2000, 1000, 32);

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.FOUND);
        assertThat(result.proposals())
                .extracting(candidate -> candidate.rectangle())
                .containsExactly(
                        new Rectangle(100, 100, 200, 300),
                        new Rectangle(600, 250, 300, 500));
    }

    @Test
    void rejectsUnexpectedFieldsAndPageDimensionSubstitution() throws Exception {
        byte[] unexpected = """
                {"schemaVersion":1,"width":2000,"height":1000,"regions":[],"text":"private OCR"}
                """.getBytes(StandardCharsets.UTF_8);
        byte[] wrongPage = """
                {"schemaVersion":1,"width":1999,"height":1000,"regions":[]}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(OpenCvVisualRegionProposer.parse(unexpected, 2000, 1000, 32).diagnostic())
                .isEqualTo(Diagnostic.FAILED);
        assertThat(OpenCvVisualRegionProposer.parse(wrongPage, 2000, 1000, 32).diagnostic())
                .isEqualTo(Diagnostic.FAILED);
    }

    @Test
    void rejectsIntegralGeometryThatCannotBeRepresentedWithoutTruncation() throws Exception {
        byte[] oversizedCoordinate = """
                {"schemaVersion":1,"width":2000,"height":1000,"regions":[
                  {"x":4294967296,"y":100,"width":400,"height":300}
                ]}
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(OpenCvVisualRegionProposer.parse(oversizedCoordinate, 2000, 1000, 32).diagnostic())
                .isEqualTo(Diagnostic.FAILED);
    }

    @Test
    void keepsAnEmptyDetectorResultLocalInsteadOfInventingACrop() throws Exception {
        byte[] response = """
                {"schemaVersion":1,"width":2000,"height":1000,"regions":[]}
                """.getBytes(StandardCharsets.UTF_8);

        var result = OpenCvVisualRegionProposer.parse(response, 2000, 1000, 32);

        assertThat(result.diagnostic()).isEqualTo(Diagnostic.NONE);
        assertThat(result.proposals()).isEmpty();
    }

    private static boolean isAlive(long processId) {
        return ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false);
    }
}
