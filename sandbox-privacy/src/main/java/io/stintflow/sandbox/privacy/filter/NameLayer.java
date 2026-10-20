package io.stintflow.sandbox.privacy.filter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.stintflow.worker.redaction.RedactionLayer;

/**
 * SDD S.6, 8a (layer 3): names from a synthetic list, found whatever the speech-to-text did to them. A listed name is
 * compared with the text <em>normalized</em> — lowercase letters and digits only, digit words as digits, spaces,
 * dashes and punctuation gone — over a window of consecutive words, and a window one edit away from the name counts:
 * Whisper writes {@code SYNTH-PII-2011 Rivera} as {@code synth PI 2011 Rivera}, a letter short. The longest window that
 * matches wins, so a surname that is part of the listed name goes with it.
 */
public final class NameLayer implements RedactionLayer {

    public static final String PERSON = "person";
    private static final int MAX_WORDS = 12;
    private static final int MIN_LENGTH = 8;   // a shorter name would match by chance with one edit of slack

    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Map<String, String> DIGIT_WORDS = Map.ofEntries(Map.entry("zero", "0"), Map.entry("oh", "0"),
            Map.entry("one", "1"), Map.entry("two", "2"), Map.entry("three", "3"), Map.entry("four", "4"),
            Map.entry("five", "5"), Map.entry("six", "6"), Map.entry("seven", "7"), Map.entry("eight", "8"),
            Map.entry("nine", "9"));

    private final List<String> names;

    /** @param names the listed names, as written ({@code SYNTH-PII-2011 Rivera}); shorter than 8 characters are ignored */
    public NameLayer(List<String> names) {
        this.names = names.stream().map(NameLayer::normalize).filter(n -> n.length() >= MIN_LENGTH).distinct()
                .sorted(Comparator.comparingInt(String::length).reversed()).toList();
    }

    @Override
    public String name() {
        return "names";
    }

    @Override
    public Result apply(String text, Scope scope) {
        if (!scope.wants(PERSON) || names.isEmpty()) {
            return new Result(text, Map.of(), false);
        }
        List<int[]> words = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            words.add(new int[] {m.start(), m.end()});
        }
        StringBuilder out = new StringBuilder();
        Map<String, Integer> counts = new LinkedHashMap<>();
        int copied = 0;
        int i = 0;
        while (i < words.size()) {
            int[] hit = longestMatch(text, words, i);
            if (hit == null) {
                i++;
                continue;
            }
            int from = words.get(i)[0];
            int to = words.get(hit[0])[1];
            out.append(text, copied, from).append(scope.pseudonyms().label(scope.instanceId(), PERSON, text.substring(from, to)));
            counts.merge(PERSON, 1, Integer::sum);
            copied = to;
            i = hit[0] + 1;
        }
        out.append(text, copied, text.length());
        return new Result(out.toString(), counts, false);
    }

    /** The last word index of the longest window starting at {@code start} that is (nearly) a listed name, or null. */
    private int[] longestMatch(String text, List<int[]> words, int start) {
        StringBuilder window = new StringBuilder();
        int best = -1;
        for (int j = start; j < Math.min(words.size(), start + MAX_WORDS); j++) {
            window.append(normalize(text.substring(words.get(j)[0], words.get(j)[1])));
            if (window.length() > names.get(0).length() + 1) {
                break;
            }
            for (String name : names) {
                if (Math.abs(name.length() - window.length()) <= 1 && withinOneEdit(window.toString(), name)) {
                    best = j;
                    break;
                }
            }
        }
        return best < 0 ? null : new int[] {best};
    }

    /** Lowercase letters and digits only; digit words become digits. */
    static String normalize(String text) {
        StringBuilder out = new StringBuilder();
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String word = m.group();
            out.append(DIGIT_WORDS.getOrDefault(word, word));
        }
        return out.toString();
    }

    /** Whether {@code a} and {@code b} differ by at most one insertion, deletion or substitution. */
    static boolean withinOneEdit(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        if (a.length() == b.length()) {
            return a.substring(i + 1).equals(b.substring(i + 1));
        }
        String longer = a.length() > b.length() ? a : b;
        String shorter = a.length() > b.length() ? b : a;
        return longer.substring(i + 1).equals(shorter.substring(i));
    }
}
