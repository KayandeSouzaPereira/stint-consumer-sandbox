package io.stintflow.sandbox.privacy.filter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.stintflow.worker.redaction.RedactionLayer;

/**
 * SDD S.6, 8a (layer 3): a person who introduces themselves — "I am Maria Souza", "my name is …", "call me …" — gives a
 * name no list has. The capitalized words right after such a phrase are replaced. A heuristic with a known cost: it
 * does not know a name from another capitalized word ("this is Friday"), so days and months are excluded and the rest
 * is redacted (fail closed). Real names that are never introduced remain for the {@code noul} check and the human.
 */
public final class PresentationLayer implements RedactionLayer {

    public static final String PERSON = "person";

    private static final Pattern PRESENTATION = Pattern.compile(
            "(?i:\\b(?:i am|i'm|my name is|this is|call me|name's))\\s+"
                    + "((?-i:[A-Z][a-z]{1,20})(?:\\s+(?-i:[A-Z][a-z]{1,20})){0,2})");
    private static final Set<String> NOT_NAMES = Set.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
            "Saturday", "Sunday", "January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December", "The", "This", "That", "Here", "There", "Not", "Very", "Just", "Also",
            "Sorry", "Happy", "Glad", "Calling", "Looking", "Going", "Still", "Now");

    @Override
    public String name() {
        return "presentation";
    }

    @Override
    public Result apply(String text, Scope scope) {
        if (!scope.wants(PERSON)) {
            return new Result(text, Map.of(), false);
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = PRESENTATION.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String candidate = m.group(1);
            if (NOT_NAMES.contains(candidate.split("\\s+")[0])) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            counts.merge(PERSON, 1, Integer::sum);
            String lead = m.group().substring(0, m.start(1) - m.start());
            m.appendReplacement(out, Matcher.quoteReplacement(lead + scope.pseudonyms().label(scope.instanceId(), PERSON, candidate)));
        }
        m.appendTail(out);
        return new Result(out.toString(), counts, false);
    }
}
