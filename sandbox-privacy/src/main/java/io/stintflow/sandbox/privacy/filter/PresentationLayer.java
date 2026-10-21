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

    /** A capitalized word, possibly hyphenated with letters and digits, initials with periods, or a digit group. */
    private static final String TOKEN = "(?:(?:[A-Z]\\.){2,}|[A-Z][A-Za-z]*(?:-[A-Za-z0-9]+)*|\\d{1,6})";

    private static final Pattern PRESENTATION = Pattern.compile(
            "(?i:\\b(?:i am|i'm|my name is|this is|call me|name's))\\s+"
                    + "((?-i:" + TOKEN + ")(?:\\s+(?-i:" + TOKEN + ")){0,4})");
    /**
     * The words between the phrase and a name that an earlier layer already replaced ("I am since P.I. 2041
     * PERSON_ab12"): up to five fragments, initials and digit groups included, and no sentence end before the label.
     */
    private static final Pattern BEFORE_LABEL = Pattern.compile(
            "(?i:\\b(?:i am|i'm|my name is|this is|call me|name's))\\s+((?:(?:(?:[A-Za-z]\\.){2,}|[\\p{L}\\p{N}-]+)\\s+){1,5}?)(PERSON_[0-9a-f_]+)");

    private static final Set<String> NOT_NAMES = Set.of("Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
            "Saturday", "Sunday", "January", "February", "March", "April", "May", "June", "July", "August", "September",
            "October", "November", "December", "The", "This", "That", "Here", "There", "Not", "Very", "Just", "Also",
            "Sorry", "Happy", "Glad", "Calling", "Looking", "Going", "Still", "Now");

    @Override
    public String name() {
        return "presentation";
    }

    /** "I am since P.I. 2041 PERSON_x" becomes "I am PERSON_x": the label takes the fragments that belong to the name. */
    private static String absorbFragments(String text, Map<String, Integer> counts) {
        Matcher m = BEFORE_LABEL.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String lead = m.group().substring(0, m.start(1) - m.start());
            counts.merge(PERSON, 1, Integer::sum);
            m.appendReplacement(out, Matcher.quoteReplacement(lead + m.group(2)));
        }
        m.appendTail(out);
        return out.toString();
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
            if (NOT_NAMES.contains(candidate.split("[\\s-]+")[0]) || Character.isDigit(candidate.charAt(0))) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                continue;
            }
            counts.merge(PERSON, 1, Integer::sum);
            String lead = m.group().substring(0, m.start(1) - m.start());
            m.appendReplacement(out, Matcher.quoteReplacement(lead + scope.pseudonyms().label(scope.instanceId(), PERSON, candidate)));
        }
        m.appendTail(out);
        String absorbed = absorbFragments(out.toString(), counts);
        return new Result(absorbed, counts, false);
    }
}
