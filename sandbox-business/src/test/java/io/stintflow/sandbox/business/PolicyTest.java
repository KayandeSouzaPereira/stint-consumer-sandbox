package io.stintflow.sandbox.business;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.policy.Policy;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.spi.Sensitivity;
import io.stintflow.worker.TaskContext;
import io.stintflow.spi.Lineage;
import io.stintflow.spi.WorkflowRef;
import io.stintflow.wire.Json;
import io.stintflow.worker.decision.AdapterDescriptor;
import io.stintflow.worker.decision.SensitivityPolicy;

/**
 * SDD S.6, RF3/RF4 and CA4: the sensitivity policy of {@code business-design} — what each level may dispatch — and the
 * worker-side rule for model providers. Restricted data (the raw audio and transcript) reaches only {@code transcribe}
 * and {@code redactor}; confidential data reaches no route that can read the raw transcript; the raw classes are kept
 * seven days; a remote provider never sees confidential data by default.
 */
class PolicyTest {

    static final String WORKFLOW = "business-design.yaml";
    /** The only routes that may see restricted data: the raw recording and its transcript. */
    static final List<String> RESTRICTED = List.of("transcribe", "redactor");
    /** The routes that work on redacted (confidential) data. */
    static final List<String> CONFIDENTIAL = List.of("decide-meeting-type", "decide-priority", "write-design",
            "register-document", "decide-channel", "publish-page", "send-email");

    static Policy policy() throws Exception {
        try (InputStream in = PolicyTest.class.getClassLoader().getResourceAsStream("stint/workflows/" + WORKFLOW)) {
            WorkflowDefinition def = new DslLoader().load(in, WORKFLOW, LoadOptions.STRICT);
            assertThat(def.policy()).as("the workflow declares its policy").isNotNull();
            return def.policy();
        }
    }

    @Test
    void restricted_data_reaches_only_the_transcription_and_the_redactor() throws Exception {
        Policy p = policy();
        for (String route : RESTRICTED) {
            assertThat(p.refusal("/do/1/x", route, Sensitivity.RESTRICTED)).as(route).isEmpty();
        }
        for (String route : CONFIDENTIAL) {
            assertThat(p.refusal("/do/1/x", route, Sensitivity.RESTRICTED)).as(route + " at restricted").isPresent();
        }
        assertThat(p.refusal("/do/1/x", "anything", null)).as("no level is restricted").isPresent();
    }

    @Test
    void redacted_data_reaches_no_route_that_reads_the_raw_transcript() throws Exception {
        Policy p = policy();
        assertThat(p.refusal("/do/1/x", "transcribe", Sensitivity.CONFIDENTIAL)).isPresent();
        for (String route : CONFIDENTIAL) {
            assertThat(p.refusal("/do/1/x", route, Sensitivity.CONFIDENTIAL)).as(route).isEmpty();
        }
        assertThat(p.refusal("/do/1/x", "redactor", Sensitivity.CONFIDENTIAL)).isEmpty();   // may be asked twice
    }

    @Test
    void the_raw_audio_and_transcript_are_kept_seven_days() throws Exception {
        Policy p = policy();
        for (String blobClass : List.of("restricted", "raw-audio", "raw-transcript")) {
            assertThat(p.retentionOf(blobClass)).as(blobClass).contains(Duration.ofDays(7));
        }
    }

    @Test
    void a_remote_provider_is_refused_for_confidential_and_restricted_data() {
        SensitivityPolicy policy = SensitivityPolicy.fromProperties(Map.of());
        AdapterDescriptor remote = new AdapterDescriptor("remote-model", "some-cloud", "m", "1", true);
        AdapterDescriptor local = new AdapterDescriptor("litjev", "litjev", "Qwen/Qwen3.5-2B", "1", false);
        for (Sensitivity level : List.of(Sensitivity.CONFIDENTIAL, Sensitivity.RESTRICTED)) {
            assertThat(policy.refusal(task(level), remote)).as("remote at " + level).isPresent();
            assertThat(policy.refusal(task(level), local)).as("local at " + level).isEmpty();
        }
        assertThat(policy.refusal(task(null), remote)).as("no level is restricted").isPresent();
        assertThat(policy.refusal(task(Sensitivity.INTERNAL), remote)).isEmpty();
    }

    static TaskContext task(Sensitivity level) {
        return new TaskContext("/do/1/x", "i-1", "c-1", 1, Json.obj(), new WorkflowRef("business", "business-design",
                "1.0.0"), "chn-1", Lineage.NONE, null, level, null);
    }

    /**
     * The raw classes the workers use are the ones the policy declares, and a raw blob is gone after its seven days (with
     * a controllable clock; the S3 lifecycle that does the same in AWS cannot be shown on floci, which accepts the rule
     * and does not apply it: the gate of 3a.5, "execução manual na AWS").
     */
    @Test
    void a_raw_blob_expires_after_its_retention() throws Exception {
        Policy p = policy();
        assertThat(io.stintflow.worker.transcription.TranscriptionHandler.RAW_TRANSCRIPT.ttl())
                .isEqualTo(p.retentionOf("raw-transcript").orElseThrow());
        io.stintflow.spi.RetentionClass rawAudio = new io.stintflow.spi.RetentionClass("raw-audio",
                p.retentionOf("raw-audio").orElseThrow());
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> now = new java.util.concurrent.atomic.AtomicReference<>(
                java.time.Instant.parse("2026-10-20T12:00:00Z"));
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("s6-retention");
        var store = new io.stintflow.inmemory.FilesystemBlobStore(dir, () -> now.get());
        java.net.URI audio = store.put(new byte[] {1, 2, 3}, "audio/a.wav", rawAudio).toCompletableFuture().join();
        java.net.URI transcript = store.put(new byte[] {4, 5, 6}, "transcripts/t.json",
                io.stintflow.worker.transcription.TranscriptionHandler.RAW_TRANSCRIPT).toCompletableFuture().join();

        now.updateAndGet(t -> t.plus(Duration.ofDays(6)));
        assertThat(store.get(audio).toCompletableFuture().join()).containsExactly(1, 2, 3);
        now.updateAndGet(t -> t.plus(Duration.ofDays(2)));   // eight days in
        for (java.net.URI raw : List.of(audio, transcript)) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.get(raw).toCompletableFuture().join())
                    .hasRootCauseInstanceOf(io.stintflow.spi.BlobNotFoundException.class);
        }
    }
}
