package io.stintflow.sandbox.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.wire.Json;

/**
 * SDD S.10, CA2: the three validators. A changeset that passes the tests but leaves a criterion of the SDD uncited is
 * refused by the coverage check, and the report says which; one that does not cite a requirement is refused by the
 * conformance check; a complete one passes. The report holds ids and verdicts, never the text of a document.
 */
class ValidatorsTest {

    static final String SDD = "# SDD s-1 — Search by author\n\n## 1. Análise\nSecret-free text.\n\n## 3. Requisitos\n"
            + "- RF1 — search is case-insensitive\n- RF2 — an unknown author gives an empty list\n\n"
            + "## 5. Critérios de aceite\n- CA1 — proves RF1\n- CA2 — proves RF2\n";
    static final String TECH = "{\"sdds\":[{\"sddId\":\"s-1\"}]}";
    static final String DESIGN = "A business design.";

    static JsonNode changeset(String testText, String moduleText) {
        ObjectNode changeset = Json.obj();
        var files = changeset.putArray("files");
        files.addObject().put("path", "catalog/sdd_s_1.py").put("content", moduleText);
        files.addObject().put("path", "tests/test_sdd_s_1.py").put("content", testText);
        return changeset;
    }

    @Test
    void a_complete_changeset_passes_the_three_checks() {
        var verdict = Validators.run("s-1", SDD, TECH, DESIGN,
                changeset("# CA1\n# CA2\nimport unittest\n", "\"\"\"s-1: RF1 RF2.\"\"\"\n"), true);

        assertThat(verdict.validated()).isTrue();
        assertThat(verdict.failedCriteria()).isEmpty();
    }

    @Test
    void passing_tests_that_leave_a_criterion_uncited_fail_the_coverage_and_the_report_says_which() {
        var verdict = Validators.run("s-1", SDD, TECH, DESIGN,
                changeset("# CA1\nimport unittest\n", "\"\"\"s-1: RF1 RF2.\"\"\"\n"), true);

        assertThat(verdict.tests().passed()).isTrue();
        assertThat(verdict.coverage().passed()).isFalse();
        assertThat(verdict.coverage().failed()).containsExactly("CA2");
        assertThat(verdict.conformance().passed()).isTrue();
        assertThat(verdict.validated()).isFalse();
        JsonNode report = verdict.toJson("s-1", 2, "s3://sandbox-test/runs/s-1/2.txt");
        assertThat(report.path("coverage").path("failed")).extracting(JsonNode::asText).containsExactly("CA2");
        assertThat(report.path("failedCriteria")).extracting(JsonNode::asText).containsExactly("CA2");
        assertThat(report.toString()).doesNotContain("Secret-free").doesNotContain("search is case");
    }

    @Test
    void a_criterion_is_cited_by_a_comment_or_a_test_named_for_it_and_ca1_is_not_ca10() {
        String sdd = SDD + "- CA10 — a tenth\n";
        var named = Validators.run("s-1", sdd, TECH, DESIGN, changeset("def test_ca1_x(): pass\n"
                + "def test_CA2(): pass\n# CA10\n", "\"\"\"s-1: RF1 RF2.\"\"\"\n"), true);
        assertThat(named.coverage().passed()).isTrue();

        var tenthOnly = Validators.run("s-1", sdd, TECH, DESIGN, changeset("# CA10\n# CA2\n",
                "\"\"\"s-1: RF1 RF2.\"\"\"\n"), true);
        assertThat(tenthOnly.coverage().failed()).containsExactly("CA1");   // CA10 does not stand for CA1
    }

    @Test
    void a_requirement_the_changeset_never_cites_fails_the_conformance() {
        var verdict = Validators.run("s-1", SDD, TECH, DESIGN,
                changeset("# CA1\n# CA2\n", "\"\"\"s-1: RF1.\"\"\"\n"), true);

        assertThat(verdict.conformance().passed()).isFalse();
        assertThat(verdict.conformance().failed()).containsExactly("RF2");
        assertThat(verdict.failedCriteria()).containsExactly("RF2");
    }

    @Test
    void a_batch_that_does_not_list_the_sdd_or_a_missing_design_fails_the_conformance() {
        JsonNode good = changeset("# CA1\n# CA2\n", "\"\"\"s-1: RF1 RF2.\"\"\"\n");

        assertThat(Validators.run("s-1", SDD, "{\"sdds\":[{\"sddId\":\"other\"}]}", DESIGN, good, true)
                .conformance().failed()).containsExactly("batch");
        assertThat(Validators.run("s-1", SDD, TECH, " ", good, true).conformance().failed())
                .containsExactly("design");
        assertThat(Validators.run("s-1", SDD, null, null, good, true).conformance().failed())
                .containsExactly("batch", "design");
    }

    @Test
    void a_failing_test_run_fails_the_tests_check_whatever_the_rest_says() {
        var verdict = Validators.run("s-1", SDD, TECH, DESIGN,
                changeset("# CA1\n# CA2\n", "\"\"\"s-1: RF1 RF2.\"\"\"\n"), false);

        assertThat(verdict.validated()).isFalse();
        assertThat(verdict.failedCriteria()).isEqualTo(List.of("tests"));
    }

    @Test
    void an_sdd_with_no_criteria_has_nothing_to_cover() {
        var verdict = Validators.run("s-1", "# SDD s-1\n", TECH, DESIGN, changeset("import unittest\n", "x = 1\n"),
                true);

        assertThat(verdict.validated()).isTrue();
    }
}
