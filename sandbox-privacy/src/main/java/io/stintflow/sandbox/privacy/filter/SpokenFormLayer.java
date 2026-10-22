package io.stintflow.sandbox.privacy.filter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.stintflow.worker.redaction.RedactionLayer;

/**
 * SDD S.6, 8a (layer 2): personal data as Whisper writes it when it is dictated — the forms the reference
 * {@code PatternLayer} cannot see (Stint 3a.6 finding, measured in S.5 CA7):
 * <ul>
 *   <li>an e-mail address: {@code synth p i i zero two zero one at example dot com}, or with the punctuation and the
 *       mishearings Whisper adds ({@code Sinthe-P-I-I-2-0-2-1 at Example. Calm.});</li>
 *   <li>a number of seven or more digits, as digits, as digit words or in groups ({@code plus five five one one nine
 *       eight ...}, {@code plus 5511 98022 4022});</li>
 *   <li>letters spelled out ({@code P I I}, {@code p-i-i}), three or more.</li>
 * </ul>
 * Each is replaced by the pseudonym of its type ({@code spoken-email}, {@code spoken-number}, {@code spelled}).
 * Leaning on the side of redacting more (a word before a dictated address may go too): the filter fails closed.
 */
public final class SpokenFormLayer implements RedactionLayer {

    public static final String EMAIL = "spoken-email";
    public static final String NUMBER = "spoken-number";
    public static final String SPELLED = "spelled";

    private static final String DIGIT_WORD = "zero|oh|one|two|three|four|five|six|seven|eight|nine";
    /** One token of a dictation: a letter, a digit or a digit word. */
    private static final String ATOM = "(?:[a-z0-9]|\\d{1,6}|" + DIGIT_WORD + ")";
    /** The top-level domain as it is dictated or misheard ({@code com} written "calm", "come", "comm"). */
    private static final String TLD = "(?:com|calm|come|comm|org|net|io|edu|gov|co|br)";

    /** Words that come before a dictated address and are not part of it ("write to j o h n at ..."). */
    private static final String FUNCTION_WORDS = "(?:to|at|is|me|my|on|in|of|the|an|and|or|for|by|us|it|so|if|as|be|we|you|"
            + "he|she|mail|email|e-mail|write|send|reach|call|contact|said|says|from|with)\\b";

    /** {@code [name] <atoms...> at <domain> (dot|.) <tld>}: a dictated e-mail address. */
    private static final Pattern SPOKEN_EMAIL = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:(?!" + FUNCTION_WORDS + ")[a-z]{2,12}[-\\s]+)?(?:" + ATOM + "[-\\s]+){1,16}at\\s+[a-z0-9-]+(?:\\s*\\.\\s*|\\s+dot\\s+)"
                    + TLD + "\\b\\.?");

    /**
     * A dictated address whose local part holds a digit group — "synth PII 2122 at Example. Calm.", "Sinthe-I-2032 at
     * example." (cut short, no top-level domain) — with up to two short fragments before the group, which is where
     * speech-to-text puts the pieces of a name it garbled. The top-level domain is optional.
     */
    private static final Pattern EMAIL_WITH_DIGITS = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:(?!" + FUNCTION_WORDS + ")[a-z]{1,6}[,\\s-]+){0,2}[a-z]*\\d[a-z0-9]*[,\\s-]+at\\s+[a-z0-9-]+"
                    + "(?:[\\s.,]+" + TLD + "\\b\\.?|\\s*\\.)?");

    /** Digit words and digit groups, separated by spaces, dots, commas or dashes: a dictated number. */
    private static final Pattern NUMBER_RUN = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])(?:plus\\s+)?(?:(?:" + DIGIT_WORD + "|o?\\d+)[\\s,.-]+){1,}(?:" + DIGIT_WORD + "|o?\\d+)(?![\\p{L}\\p{N}])");
    private static final int MIN_DIGITS = 7;

    private static final Pattern SPELLING = Pattern.compile("(?<![\\p{L}\\p{N}])(?:\\p{L}[-\\s]+){2,}\\p{L}(?![\\p{L}\\p{N}])");

    @Override
    public String name() {
        return "spoken";
    }

    @Override
    public Result apply(String text, Scope scope) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        String current = text;
        if (scope.wants(EMAIL)) {
            current = replace(EMAIL_WITH_DIGITS, current, EMAIL, scope, counts, null);
            current = replace(SPOKEN_EMAIL, current, EMAIL, scope, counts, null);
        }
        if (scope.wants(NUMBER)) {
            current = replace(NUMBER_RUN, current, NUMBER, scope, counts, SpokenFormLayer::enoughDigits);
        }
        if (scope.wants(SPELLED)) {
            current = replace(SPELLING, current, SPELLED, scope, counts, null);
        }
        return new Result(current, counts, false);
    }

    private static String replace(Pattern pattern, String text, String type, Scope scope, Map<String, Integer> counts,
            java.util.function.Predicate<String> accept) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String found = m.group();
            if (accept != null && !accept.test(found)) {
                m.appendReplacement(out, Matcher.quoteReplacement(found));
                continue;
            }
            counts.merge(type, 1, Integer::sum);
            m.appendReplacement(out, Matcher.quoteReplacement(scope.pseudonyms().label(scope.instanceId(), type, found.strip())));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** At least {@value #MIN_DIGITS} digits, counting a digit word as one and a number by its length. */
    static boolean enoughDigits(String run) {
        int digits = 0;
        Matcher m = Pattern.compile("(?i)" + DIGIT_WORD + "|o?\\d+").matcher(run);
        while (m.find()) {
            String token = m.group().toLowerCase(java.util.Locale.ROOT);
            digits += Character.isDigit(token.charAt(0)) ? token.length() : token.startsWith("o") && token.length() > 1 && Character.isDigit(token.charAt(1)) ? token.length() : 1;
        }
        return digits >= MIN_DIGITS;
    }
}
