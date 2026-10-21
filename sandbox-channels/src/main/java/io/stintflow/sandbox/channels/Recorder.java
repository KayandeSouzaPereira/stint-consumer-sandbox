package io.stintflow.sandbox.channels;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.cloudevents.CloudEvent;
import io.cloudevents.core.builder.CloudEventBuilder;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.spi.BlobStore;
import io.stintflow.spi.RetentionClass;
import io.stintflow.spi.wire.StintEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.5, 8b/8h: the meeting recorder. It stores a corpus meeting's audio in the restricted raw bucket (class
 * {@code raw-audio}, 7 days) and publishes {@code meeting-recorded.v1}: the data of Stint's
 * {@code io.stintflow.examples.meeting-recorded.v1} (a pointer and a label), project, cycle and stage as extensions,
 * the event id {@code rec-<meetingId>} (a redelivery starts nothing twice) and the project's chain, the same for every
 * cycle. In CI the audio is a few synthetic bytes; in the manual run, the corpus's generated WAV.
 */
public final class Recorder {

    private static final Logger LOG = Logger.getLogger(Recorder.class);
    public static final RetentionClass RAW_AUDIO = new RetentionClass("raw-audio", Duration.ofDays(7));

    private final Corpus corpus;
    private final BlobStore raw;
    private final Bus bus;
    private final boolean realAudio;

    public Recorder(Corpus corpus, BlobStore raw, Bus bus, boolean realAudio) {
        this.corpus = corpus;
        this.raw = raw;
        this.bus = bus;
        this.realAudio = realAudio;
    }

    public Corpus corpus() {
        return corpus;
    }

    /** Records one meeting of the corpus; returns the published event. */
    public CloudEvent record(Corpus.Meeting meeting) throws Exception {
        byte[] audio = realAudio ? readWav(meeting) : Corpus.syntheticAudio(meeting.id());
        URI audioRef = raw.put(audio, "audio/" + meeting.id() + ".wav", RAW_AUDIO).toCompletableFuture()
                .get(30, TimeUnit.SECONDS);
        ObjectNode data = Json.obj().put("meetingId", meeting.id()).put("audioRef", audioRef.toString())
                .put("language", meeting.language()).put("recordedAt", Instant.now().toString())
                .put("label", meeting.label());
        CloudEvent event = CloudEventBuilder.v1().withId("rec-" + meeting.id())
                .withSource(URI.create(PipelineEvents.SOURCE_CHANNELS)).withType(PipelineEvents.MEETING_RECORDED)
                .withSubject(meeting.id()).withDataContentType(StintEvents.CONTENT_TYPE_JSON)
                .withData(Json.bytes(data))
                .withExtension(PipelineEvents.EXT_PROJECT, meeting.project())
                .withExtension(PipelineEvents.EXT_CYCLE, String.valueOf(meeting.cycle()))
                .withExtension(PipelineEvents.EXT_STAGE, meeting.stage())
                .withExtension(StintEvents.EXT_CHAIN_ID, PipelineEvents.chainIdOf(meeting.project())).build();
        bus.publish(event);
        LOG.infof("Recorded meeting %s (project %s, cycle %s, %s): %d bytes of audio", meeting.id(),
                meeting.project(), meeting.cycle(), meeting.stage(), audio.length);
        return event;
    }

    private byte[] readWav(Corpus.Meeting meeting) {
        try {
            return Files.readAllBytes(corpus.dir().resolve("audio").resolve(meeting.id() + ".wav"));
        } catch (IOException e) {
            throw new UncheckedIOException("no generated audio for " + meeting.id()
                    + " (scripts/generate-audio.py corpus)", e);
        }
    }
}
