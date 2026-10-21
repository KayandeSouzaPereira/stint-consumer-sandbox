package io.stintflow.sandbox.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** SDD S.5, 8b: the pipeline's v1 contract — schemas, envelopes, and the copy of Stint's meeting-recorded schema. */
class ContractsTest {

    /** SHA-256 of docs/contracts/meeting-recorded.v1.schema.json at Stint 500dfd1 (git show 500dfd1:...). */
    static final String STINT_MEETING_RECORDED_SHA256 =
            "70b413beadf45cd0c0c6d2e283992ad92a548926b85516bcf6b20c4ce1d3d753";

    static final ObjectMapper JSON = new ObjectMapper();
    final PipelineContracts contracts = new PipelineContracts();

    static ObjectNode event(String type, String source, String subject) {
        ObjectNode e = JSON.createObjectNode().put("specversion", "1.0").put("id", "e-1").put("type", type)
                .put("source", source).put("subject", subject).put("datacontenttype", "application/json");
        e.put(PipelineEvents.EXT_PROJECT, "p1").put(PipelineEvents.EXT_CYCLE, "1");
        return e;
    }

    @Test
    void the_meeting_recorded_schema_is_stints_unchanged() throws Exception {
        try (InputStream in = getClass().getClassLoader()
                .getResourceAsStream("contracts/v1/stint-meeting-recorded.v1.schema.json")) {
            assertThat(Corpus.sha256(in.readAllBytes())).isEqualTo(STINT_MEETING_RECORDED_SHA256);
        }
    }

    @Test
    void every_type_has_a_schema_that_loads() {
        assertThat(PipelineEvents.TYPES).hasSize(12).allMatch(contracts::isPipelineType);
    }

    @Test
    void a_meeting_recorded_with_its_extensions_keeps_the_contract() {
        ObjectNode e = event(PipelineEvents.MEETING_RECORDED, PipelineEvents.SOURCE_CHANNELS, "p1-c1-business");
        e.put(PipelineEvents.EXT_STAGE, "business");
        e.putObject("data").put("meetingId", "p1-c1-business").put("audioRef", "s3://sandbox-meetings-raw/a.wav")
                .put("language", "en").put("label", "restricted");
        assertThat(contracts.violations(e)).isEmpty();
    }

    @Test
    void a_project_in_data_instead_of_an_extension_breaks_the_contract() {
        ObjectNode e = event(PipelineEvents.MEETING_RECORDED, PipelineEvents.SOURCE_CHANNELS, "m-1");
        e.remove(PipelineEvents.EXT_PROJECT);
        e.putObject("data").put("meetingId", "m-1").put("audioRef", "s3://b/a.wav").put("language", "en")
                .put("label", "restricted").put("projectId", "p1");
        List<String> violations = contracts.violations(e);
        assertThat(violations).anyMatch(v -> v.contains("extension sandboxproject"))
                .anyMatch(v -> v.contains("projectId"));
    }

    @Test
    void free_text_in_data_breaks_the_contract() {
        ObjectNode e = event(PipelineEvents.DESIGN_PUBLISHED, PipelineEvents.SOURCE_BUSINESS, "doc-1");
        e.putObject("data").put("documentId", "doc-1").put("recordId", "rec-1").put("version", 1)
                .put("designRef", "s3://sandbox-documents/d.md").put("channel", "email")
                .put("summary", "Readers want to find books by author");
        assertThat(contracts.violations(e)).singleElement().asString().contains("summary");
    }

    @Test
    void a_pointer_must_be_a_pointer_and_an_enum_an_enum() {
        ObjectNode e = event(PipelineEvents.DESIGN_PUBLISHED, PipelineEvents.SOURCE_BUSINESS, "doc-1");
        e.putObject("data").put("documentId", "doc-1").put("recordId", "rec-1").put("version", 1)
                .put("designRef", "the design text").put("channel", "slack");
        assertThat(contracts.violations(e)).hasSize(2);
    }

    @Test
    void a_delivery_event_needs_the_sdd_extension_and_its_publishers_source() {
        ObjectNode e = event(PipelineEvents.SDD_VALIDATED, PipelineEvents.SOURCE_BUSINESS, "p1-sdd-1");
        e.putObject("data").put("sddId", "p1-sdd-1").put("attempts", 2).put("outcome", "validated")
                .put("reportRef", "s3://sandbox-target-runs/p1-sdd-1/2.txt");
        assertThat(contracts.violations(e)).hasSize(2).anyMatch(v -> v.contains("sandboxsdd"))
                .anyMatch(v -> v.contains("source"));
    }

    @Test
    void a_batch_holds_at_most_twenty_sdds() {
        ObjectNode e = event(PipelineEvents.SDD_BATCH_APPROVED, PipelineEvents.SOURCE_ENGINEERING, "b-1");
        ObjectNode data = e.putObject("data").put("batchId", "b-1").put("batchRef", "s3://sandbox-documents/b.json");
        var ids = data.putArray("sddIds");
        for (int i = 0; i < 21; i++) {
            ids.add("s-" + i);
        }
        data.putArray("recordIds");
        assertThat(contracts.violations(e)).singleElement().asString().contains("sddIds");
    }
}
