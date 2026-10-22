package io.stintflow.sandbox.business;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.sandbox.contracts.PipelineContracts;
import io.stintflow.wire.Json;

/** SDD S.8, CA6: {@code design-published} takes the priority as an optional, additive field and still takes the old shape. */
class DesignPublishedContractTest {

    static final String SCHEMA = "io.sandbox.business.design-published.v1.schema.json";
    final PipelineContracts contracts = new PipelineContracts();

    ObjectNode published() {
        return Json.obj().put("documentId", "p1-c1-design").put("recordId", "rec-p1-c1-design").put("version", 2)
                .put("designRef", "s3://sandbox-documents/design/p1-c1-design.md").put("channel", "confluence");
    }

    @Test
    void the_priority_is_optional_and_a_score_between_zero_and_one() {
        assertThat(contracts.dataViolations(SCHEMA, published())).as("the shape before S.8").isEmpty();
        assertThat(contracts.dataViolations(SCHEMA, published().put("priority", 0.8))).isEmpty();
        assertThat(contracts.dataViolations(SCHEMA, published().put("priority", 0))).isEmpty();
        assertThat(contracts.dataViolations(SCHEMA, published().put("priority", 1.4))).isNotEmpty();
        assertThat(contracts.dataViolations(SCHEMA, published().put("priority", "high"))).isNotEmpty();
        assertThat(contracts.dataViolations(SCHEMA, published().put("text", "a design"))).as("still no free text")
                .isNotEmpty();
    }
}
