package io.stintflow.sandbox.engineering;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * SDD S.9, RF2 and 8b: the shape of an SDD the pipeline writes — the process's own: a title with the id, then
 * "1. Análise", "3. Requisitos" (items {@code RF<n>}) and "5. Critérios de aceite" (items {@code CA<n>}), in that
 * order. Other sections may follow (a revision, for one). It checks the shape, not whether the SDD is right.
 */
public final class SddFormat {

    public static final String ANALYSIS = "## 1. Análise";
    public static final String REQUIREMENTS = "## 3. Requisitos";
    public static final String CRITERIA = "## 5. Critérios de aceite";

    private static final Pattern ITEM_RF = Pattern.compile("(?m)^- RF\\d+\\b");
    private static final Pattern ITEM_CA = Pattern.compile("(?m)^- CA\\d+\\b");

    private SddFormat() {
    }

    /** The SDD written for {@code sddId}, with its title, analysis, requirements and criteria. */
    public static String write(String sddId, String title, String analysis, List<String> requirements,
            List<String> criteria) {
        StringBuilder md = new StringBuilder("# SDD ").append(sddId).append(" — ").append(title).append("\n\n")
                .append(ANALYSIS).append('\n').append(analysis).append("\n\n").append(REQUIREMENTS).append('\n');
        for (int i = 0; i < requirements.size(); i++) {
            md.append("- RF").append(i + 1).append(" — ").append(requirements.get(i)).append('\n');
        }
        md.append('\n').append(CRITERIA).append('\n');
        for (int i = 0; i < criteria.size(); i++) {
            md.append("- CA").append(i + 1).append(" — ").append(criteria.get(i)).append('\n');
        }
        return md.toString();
    }

    /** What is wrong with {@code markdown} as the SDD {@code sddId}; empty if its shape is right. */
    public static List<String> violations(String markdown, String sddId) {
        List<String> problems = new ArrayList<>();
        String text = markdown == null ? "" : markdown;
        if (!text.startsWith("# SDD " + sddId + " — ") || text.lines().findFirst().orElse("").length()
                <= ("# SDD " + sddId + " — ").length()) {
            problems.add("the title must be '# SDD " + sddId + " — <title>'");
        }
        int analysis = text.indexOf(ANALYSIS);
        int requirements = text.indexOf(REQUIREMENTS);
        int criteria = text.indexOf(CRITERIA);
        if (analysis < 0) {
            problems.add("missing section '" + ANALYSIS + "'");
        }
        if (requirements < 0) {
            problems.add("missing section '" + REQUIREMENTS + "'");
        }
        if (criteria < 0) {
            problems.add("missing section '" + CRITERIA + "'");
        }
        if (analysis >= 0 && requirements >= 0 && criteria >= 0
                && !(analysis < requirements && requirements < criteria)) {
            problems.add("the sections must come in order: análise, requisitos, critérios de aceite");
        }
        if (requirements >= 0 && !ITEM_RF.matcher(section(text, requirements, REQUIREMENTS)).find()) {
            problems.add("the requirements need at least one 'RF<n>' item");
        }
        if (criteria >= 0 && !ITEM_CA.matcher(section(text, criteria, CRITERIA)).find()) {
            problems.add("the criteria need at least one 'CA<n>' item");
        }
        return problems;
    }

    /** The text of the section that starts at {@code from} up to the next level-2 heading. */
    private static String section(String text, int from, String heading) {
        int start = from + heading.length();
        int next = text.indexOf("\n## ", start);
        return next < 0 ? text.substring(start) : text.substring(start, next);
    }

    /** The number of {@code CA<n>} criteria the SDD lists. */
    public static int criteriaCount(String markdown) {
        int at = markdown.indexOf(CRITERIA);
        if (at < 0) {
            return 0;
        }
        var matcher = ITEM_CA.matcher(section(markdown, at, CRITERIA));
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
