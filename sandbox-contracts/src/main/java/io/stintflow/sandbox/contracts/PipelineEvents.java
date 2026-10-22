package io.stintflow.sandbox.contracts;

import java.util.List;

/**
 * SDD S.5, 8b: the meeting pipeline's event types, sources and correlation extensions (contracts/pipeline-events.md).
 * Correlation is by these extensions and {@code subject}, never by {@code data} (Stint 3a.5 finding). Every
 * {@code data} holds ids, pointers, enums and counts only.
 */
public final class PipelineEvents {

    public static final String MEETING_RECORDED = "io.sandbox.channels.meeting-recorded.v1";
    public static final String DESIGN_PUBLISHED = "io.sandbox.business.design-published.v1";
    public static final String REFINEMENT_REQUESTED = "io.sandbox.engineering.refinement-requested.v1";
    public static final String QUESTIONS_SENT = "io.sandbox.engineering.questions-sent.v1";
    public static final String SDD_BATCH_APPROVED = "io.sandbox.engineering.sdd-batch-approved.v1";
    public static final String SDD_READY = "io.sandbox.delivery.sdd-ready.v1";
    public static final String SDD_IMPLEMENTED = "io.sandbox.delivery.sdd-implemented.v1";
    public static final String SDD_VALIDATED = "io.sandbox.delivery.sdd-validated.v1";
    public static final String SDD_CANCELLED = "io.sandbox.delivery.sdd-cancelled.v1";
    public static final String SDD_DEAD_LETTERED = "io.sandbox.delivery.sdd-dead-lettered.v1";
    public static final String CYCLE_CLOSED = "io.sandbox.delivery.cycle-closed.v1";
    public static final String DOCUMENT_REGISTERED = "io.sandbox.records.document-registered.v1";

    /** Every pipeline type of contracts/v1, in pipeline order. */
    public static final List<String> TYPES = List.of(MEETING_RECORDED, DESIGN_PUBLISHED, REFINEMENT_REQUESTED,
            QUESTIONS_SENT, SDD_BATCH_APPROVED, SDD_READY, SDD_IMPLEMENTED, SDD_VALIDATED, SDD_CANCELLED,
            SDD_DEAD_LETTERED, CYCLE_CLOSED, DOCUMENT_REGISTERED);

    /** Stint's review events (SDD 3a.4): requested and reminder from the engine, completed from the portal. */
    public static final String REVIEW_PREFIX = "io.stintflow.review.";
    public static final String REVIEW_REQUESTED = "io.stintflow.review.requested.v1";
    public static final String REVIEW_REMINDER = "io.stintflow.review.reminder.v1";
    public static final String REVIEW_COMPLETED = "io.stintflow.review.completed.v1";

    public static final String EXT_PROJECT = "sandboxproject";
    public static final String EXT_CYCLE = "sandboxcycle";
    public static final String EXT_SDD = "sandboxsdd";
    public static final String EXT_STAGE = "sandboxstage";
    /** The domain a review belongs to: set on review requests by the domain's publisher, echoed by the portal. */
    public static final String EXT_DOMAIN = "sandboxdomain";

    public static final String STAGE_BUSINESS = "business";
    public static final String STAGE_TECHNICAL = "technical";

    public static final String SOURCE_CHANNELS = "https://sandbox.stintflow.io/channels";
    public static final String SOURCE_BUSINESS = "https://sandbox.stintflow.io/business";
    public static final String SOURCE_ENGINEERING = "https://sandbox.stintflow.io/engineering";
    public static final String SOURCE_DELIVERY = "https://sandbox.stintflow.io/delivery";
    /** The only source trusted for review answers: x-stint-policy.review.sources of every pipeline workflow. */
    public static final String SOURCE_PORTAL = "https://portal.sandbox.example";

    /** The approval roles (8g). */
    public static final String ROLE_BUSINESS = "business-rep";
    public static final String ROLE_TECH = "tech-rep";
    public static final String ROLE_TECHNICIAN = "technician";

    /** The domains whose engines run workflows. */
    public static final List<String> ENGINE_DOMAINS = List.of("business", "engineering", "delivery");

    private PipelineEvents() {
    }

    /** The project's chain: one for every cycle of a project, from its first meeting on (8b). */
    public static String chainIdOf(String projectId) {
        return "chn-" + java.util.UUID.nameUUIDFromBytes(("project|" + projectId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
