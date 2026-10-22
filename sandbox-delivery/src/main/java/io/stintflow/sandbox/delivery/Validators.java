package io.stintflow.sandbox.delivery;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.stintflow.wire.Json;

/**
 * SDD S.10, RF2 and 8b: the three checks behind {@code validate}, against what the SDD asks. Pure: texts in, verdict out.
 * <ul>
 *   <li><b>tests</b>: the target's tests passed;</li>
 *   <li><b>coverage</b>: every criterion {@code CA<n>} of the SDD is cited by a file of the changeset's tests (a
 *       {@code # CA<n>} comment or a test named for it);</li>
 *   <li><b>conformance</b>: every requirement {@code RF<n>} of the SDD is cited by the changeset, the technical batch
 *       lists the SDD, and the business design is there to be read (the deterministic form of the CI; the manual run
 *       asks a local model).</li>
 * </ul>
 * The report holds ids and verdicts, never the text of a document.
 */
public final class Validators {

    static final Pattern CRITERION = Pattern.compile("(?m)^\\s*[-*]\\s+(CA\\d+)\\b");
    static final Pattern REQUIREMENT = Pattern.compile("(?m)^\\s*[-*]\\s+(RF\\d+)\\b");

    private Validators() {
    }

    /** One check: its name, whether it passed and the ids that failed it. */
    public record Check(String name, boolean passed, List<String> failed) {
    }

    /** The three checks and the ids that failed, in order. */
    public record Verdict(Check tests, Check coverage, Check conformance) {

        public boolean validated() {
            return tests.passed() && coverage.passed() && conformance.passed();
        }

        public List<String> failedCriteria() {
            List<String> out = new ArrayList<>();
            out.addAll(tests.failed());
            out.addAll(coverage.failed());
            out.addAll(conformance.failed());
            return out;
        }

        /** The structured report: per check the verdict and the failed ids. */
        public ObjectNode toJson(String sddId, int attempt, String testsReportRef) {
            ObjectNode out = Json.obj().put("sddId", sddId).put("attempt", attempt).put("validated", validated());
            if (testsReportRef != null && !testsReportRef.isBlank()) {
                out.put("testsReportRef", testsReportRef);
            }
            for (Check check : List.of(tests, coverage, conformance)) {
                ObjectNode node = out.putObject(check.name()).put("passed", check.passed());
                ArrayNode failed = node.putArray("failed");
                check.failed().forEach(failed::add);
            }
            ArrayNode all = out.putArray("failedCriteria");
            failedCriteria().forEach(all::add);
            return out;
        }
    }

    public static List<String> criteria(String sdd) {
        return ids(CRITERION, sdd);
    }

    public static List<String> requirements(String sdd) {
        return ids(REQUIREMENT, sdd);
    }

    /**
     * @param sdd       the SDD's text
     * @param tech      the technical batch's text, or null when it could not be read
     * @param design    the business design's text, or null when it could not be read
     * @param changeset {@code {files: [{path, content}]}}
     */
    public static Verdict run(String sddId, String sdd, String tech, String design, JsonNode changeset,
            boolean testsPassed) {
        StringBuilder tests = new StringBuilder();
        StringBuilder everything = new StringBuilder();
        for (JsonNode file : changeset.path("files")) {
            String content = file.path("content").asText("");
            everything.append(content).append('\n');
            if (file.path("path").asText("").startsWith("tests/")) {
                tests.append(content).append('\n');
            }
        }
        List<String> uncovered = criteria(sdd).stream().filter(c -> !cites(tests, c)).toList();
        List<String> conformance = new ArrayList<>(requirements(sdd).stream().filter(r -> !cites(everything, r))
                .toList());
        if (tech == null || !tech.contains(sddId)) {
            conformance.add("batch");
        }
        if (design == null || design.isBlank()) {
            conformance.add("design");
        }
        return new Verdict(new Check("tests", testsPassed, testsPassed ? List.of() : List.of("tests")),
                new Check("coverage", uncovered.isEmpty(), uncovered),
                new Check("conformance", conformance.isEmpty(), conformance));
    }

    /** The id as a whole token: {@code CA1} in {@code # CA1} and {@code test_ca1_x}, not in {@code CA10}. */
    static boolean cites(CharSequence text, String id) {
        return Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(id) + "(?![A-Za-z0-9])").matcher(text).find();
    }

    private static List<String> ids(Pattern pattern, String text) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        Matcher m = pattern.matcher(text == null ? "" : text);
        while (m.find()) {
            out.add(m.group(1));
        }
        return List.copyOf(out);
    }
}
