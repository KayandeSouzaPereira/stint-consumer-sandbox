package io.stintflow.sandbox.engineering;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.wire.Json;

/** SDD S.9, CA2 and CA6: the SDD format and its validator, and the batch contract's optional priority. */
class SddFormatTest {

    static final String GOOD = SddFormat.write("p1-sdd-1", "Search books by author", "From the meeting.",
            List.of("It matches the author without regard to case."), List.of("Verified: case insensitive match."));

    @Test
    void a_written_sdd_has_the_title_the_three_sections_and_an_item_in_each() {
        assertThat(SddFormat.violations(GOOD, "p1-sdd-1")).isEmpty();
        assertThat(SddFormat.criteriaCount(GOOD)).isEqualTo(1);
        assertThat(SddFormat.violations(GOOD + "\n## Revisão 1\n- Revised.\n", "p1-sdd-1")).as("a revision may follow")
                .isEmpty();
    }

    @Test
    void the_validator_names_what_is_missing() {
        assertThat(SddFormat.violations(GOOD, "other-id")).anyMatch(v -> v.contains("title"));
        assertThat(SddFormat.violations(GOOD.replace(SddFormat.ANALYSIS, "## Notes"), "p1-sdd-1"))
                .anyMatch(v -> v.contains("Análise"));
        assertThat(SddFormat.violations(GOOD.replace(SddFormat.REQUIREMENTS, "## Stuff"), "p1-sdd-1"))
                .anyMatch(v -> v.contains("Requisitos"));
        assertThat(SddFormat.violations(GOOD.replace(SddFormat.CRITERIA, "## Done when"), "p1-sdd-1"))
                .anyMatch(v -> v.contains("Critérios"));
        assertThat(SddFormat.violations(GOOD.replace("- CA1", "- Check"), "p1-sdd-1"))
                .anyMatch(v -> v.contains("CA<n>"));
        assertThat(SddFormat.violations(GOOD.replace("- RF1", "- Need"), "p1-sdd-1"))
                .anyMatch(v -> v.contains("RF<n>"));
        assertThat(SddFormat.violations("", "p1-sdd-1")).isNotEmpty();
        assertThat(SddFormat.violations(null, "p1-sdd-1")).isNotEmpty();
    }

    @Test
    void the_sections_must_come_in_order() {
        String swapped = "# SDD p1-sdd-1 — T\n\n" + SddFormat.CRITERIA + "\n- CA1 — x\n\n" + SddFormat.REQUIREMENTS
                + "\n- RF1 — y\n\n" + SddFormat.ANALYSIS + "\nz\n";
        assertThat(SddFormat.violations(swapped, "p1-sdd-1")).anyMatch(v -> v.contains("order"));
    }

    @Test
    void the_batch_event_takes_a_priority_between_zero_and_one_and_the_old_shape() {
        PipelineContracts contracts = new PipelineContracts();
        String schema = "io.sandbox.engineering.sdd-batch-approved.v1.schema.json";
        ObjectNode batch = Json.obj().put("batchId", "p1-c1-batch").put("batchRef", "s3://sandbox-documents/sdd/b.json");
        batch.putArray("sddIds").add("p1-sdd-1");
        batch.putArray("recordIds").add("rec-p1-c1-batch");
        assertThat(contracts.dataViolations(schema, batch)).as("the shape before S.9").isEmpty();
        assertThat(contracts.dataViolations(schema, batch.deepCopy().put("priority", 0.8))).isEmpty();
        assertThat(contracts.dataViolations(schema, batch.deepCopy().put("priority", 1.4))).isNotEmpty();
        assertThat(contracts.dataViolations(schema, batch.deepCopy().put("text", "an SDD"))).as("still no free text")
                .isNotEmpty();
    }
}
