package io.stintflow.sandbox.channels;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;

import io.cloudevents.CloudEvent;
import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.wire.Json;

/**
 * SDD S.5, 8b: what the channels do with the events routed to {@code channels-domain-events}: open reviews in the
 * portal, send the invitations, questions and notices, and — the fake recorder, {@code channels.recorder.auto} — record
 * the corpus meeting that answers an invitation (the technical meeting) or the questions (the next cycle's business
 * meeting). Delivery is at least once: an event already handled is skipped.
 */
public final class Notices {

    private static final Logger LOG = Logger.getLogger(Notices.class);

    private final Portal portal;
    private final Mail mail;
    private final Recorder recorder;
    private final boolean autoRecord;
    private final Set<String> handled = ConcurrentHashMap.newKeySet();

    public Notices(Portal portal, Mail mail, Recorder recorder, boolean autoRecord) {
        this.portal = portal;
        this.mail = mail;
        this.recorder = recorder;
        this.autoRecord = autoRecord;
    }

    public void on(CloudEvent event) throws Exception {
        if (!handled.add(event.getSource() + "|" + event.getId())) {
            return;
        }
        String project = ext(event, PipelineEvents.EXT_PROJECT);
        String cycle = ext(event, PipelineEvents.EXT_CYCLE);
        JsonNode data = event.getData() == null ? Json.obj() : Json.read(event.getData().toBytes());
        switch (event.getType()) {
            case PipelineEvents.REVIEW_REQUESTED -> portal.requested(event);
            case PipelineEvents.REVIEW_REMINDER -> LOG.infof("Review %s: reminder for %s", event.getSubject(),
                    event.getExtension("stintrole"));
            case PipelineEvents.REFINEMENT_REQUESTED -> {
                mail.send("invite", "tech-rep", project, cycle, null, "Document " + data.path("documentId").asText()
                        + " (record " + data.path("recordId").asText() + ") is ready for refinement.\n");
                record(project, Integer.parseInt(cycle), PipelineEvents.STAGE_TECHNICAL);
            }
            case PipelineEvents.QUESTIONS_SENT -> {
                mail.send("questions", "business-rep", project, cycle, data.get("questionsRef"), "");
                record(project, data.path("nextCycle").asInt(), PipelineEvents.STAGE_BUSINESS);
            }
            case PipelineEvents.SDD_DEAD_LETTERED -> mail.send("dead-letter", "technician", project, cycle, null,
                    "SDD " + data.path("sddId").asText() + " used " + data.path("attempts").asInt()
                            + " attempts. Decide in the portal: resend, accept or cancel.\n");
            case PipelineEvents.CYCLE_CLOSED -> {
                String text = "Outcome " + data.path("outcome").asText() + ": " + data.path("validated").asInt()
                        + " validated, " + data.path("accepted").asInt() + " accepted, "
                        + data.path("cancelled").asInt() + " cancelled.\n";
                mail.send("cycle-closed", "business-rep", project, cycle, null, text);
                mail.send("cycle-closed", "tech-rep", project, cycle, null, text);
            }
            default -> LOG.debugf("Event %s of type %s: nothing to do", event.getId(), event.getType());
        }
    }

    private void record(String project, int cycle, String stage) throws Exception {
        if (!autoRecord) {
            return;
        }
        var meeting = recorder.corpus().of(project, cycle, stage);
        if (meeting.isEmpty()) {
            LOG.infof("No corpus meeting for %s cycle %d %s: nothing recorded", project, cycle, stage);
            return;
        }
        recorder.record(meeting.get());
    }

    private static String ext(CloudEvent event, String name) {
        Object value = event.getExtension(name);
        return value == null ? "" : value.toString();
    }
}
