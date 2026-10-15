package io.stintflow.sandbox.contracts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

/**
 * SDD S.5, 8d: the pipeline corpus, format v2 — Stint 3a.6's format v1 plus optional fields: the project, cycle and
 * stage of a meeting, the answer key of every LitJev decision, and each planted marker with its written and spoken
 * forms. Synthetic only. A v1 reader ignores what it does not know; this reader reads both.
 */
public final class Corpus {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String[] DIGITS = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight",
        "nine"};
    /** Forms shorter than this are left out of the scanner's list: they would match ids and hashes by chance. */
    public static final int MIN_FORM_LENGTH = 10;

    public record Speaker(String id, String voice) {
    }

    public record Turn(String speaker, String text) {
    }

    /**
     * One SDD the technical meeting should produce.
     *
     * @param covers       what the SDD must cover (coverage, not equality: Stint 3a.6 finding)
     * @param failAttempts for the fakes only: how many implementation attempts fail before one passes
     */
    public record Sdd(String id, String title, List<String> covers, Integer failAttempts) {
        public int failures() {
            return failAttempts == null ? 0 : failAttempts;
        }
    }

    /** The answer key of a meeting: one entry per LitJev decision (8d). */
    public record Expected(String meetingType, String channel, String outcome, Boolean blockingQuestion,
            Double priority, Integer sddCount, List<Sdd> sdds, List<String> questions, Boolean review) {
    }

    /** A planted marker and the forms it takes: written (what the redactor knows) and spoken (what Whisper writes). */
    public record Pii(String marker, String kind, String written, List<String> spoken) {
    }

    @JsonIgnoreProperties({"notes"})
    public record Meeting(String id, String project, Integer cycle, String stage, String language, String label,
            List<Speaker> speakers, List<Turn> script, Expected expected, List<Pii> pii) {

        /** The script as one text, a turn per line: what the fake transcriber returns. */
        public String text() {
            StringBuilder out = new StringBuilder();
            script.forEach(t -> out.append(t.text()).append('\n'));
            return out.toString();
        }
    }

    private final Path dir;
    private final List<Meeting> meetings;
    private final JsonNode manifest;

    private Corpus(Path dir, List<Meeting> meetings, JsonNode manifest) {
        this.dir = dir;
        this.meetings = meetings;
        this.manifest = manifest;
    }

    /** Reads {@code dir/meetings/*.yaml} (sorted by file name) and {@code dir/manifest.json}. */
    public static Corpus load(Path dir) {
        try (Stream<Path> files = Files.list(dir.resolve("meetings"))) {
            List<Meeting> meetings = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                meetings.add(YAML.readValue(file.toFile(), Meeting.class));
            }
            return new Corpus(dir, List.copyOf(meetings), JSON.readTree(dir.resolve("manifest.json").toFile()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path dir() {
        return dir;
    }

    public List<Meeting> meetings() {
        return meetings;
    }

    public JsonNode manifest() {
        return manifest;
    }

    public Meeting meeting(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("no meeting " + id + " in the corpus"));
    }

    public Optional<Meeting> find(String id) {
        return meetings.stream().filter(m -> m.id().equals(id)).findFirst();
    }

    /** The meeting of a project's cycle and stage, if the corpus has one. */
    public Optional<Meeting> of(String project, int cycle, String stage) {
        return meetings.stream().filter(m -> project.equals(m.project()) && m.cycle() != null && m.cycle() == cycle
                && stage.equals(m.stage())).findFirst();
    }

    /** An SDD of the corpus by id (SDD ids are unique across the corpus). */
    public Optional<Sdd> sdd(String sddId) {
        return meetings.stream().filter(m -> m.expected() != null && m.expected().sdds() != null)
                .flatMap(m -> m.expected().sdds().stream()).filter(s -> s.id().equals(sddId)).findFirst();
    }

    /** Lowercase letters and digits only: how text is compared with the scanner's forms. */
    public static String normalize(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** {@code 0201} → {@code zerotwozeroone}. */
    public static String digitsAsWords(String digits) {
        StringBuilder out = new StringBuilder();
        for (char c : digits.toCharArray()) {
            if (c >= '0' && c <= '9') {
                out.append(DIGITS[c - '0']);
            }
        }
        return out.toString();
    }

    /**
     * The scanner's list (8d): for each marker, every normalized form at least {@link #MIN_FORM_LENGTH} long — the
     * marker, its written form, its spoken forms and, for a phone, its digits. Sorted, by marker.
     */
    public Map<String, List<String>> piiForms() {
        Map<String, List<String>> out = new TreeMap<>();
        for (Meeting meeting : meetings) {
            for (Pii pii : meeting.pii() == null ? List.<Pii>of() : meeting.pii()) {
                Set<String> forms = new LinkedHashSet<>();
                forms.add(normalize(pii.marker()));
                forms.add(normalize(pii.written()));
                if ("phone".equals(pii.kind())) {
                    forms.add(pii.written().replaceAll("[^0-9]", ""));
                }
                pii.spoken().forEach(s -> forms.add(normalize(s)));
                out.put(pii.marker(), forms.stream().filter(f -> f.length() >= MIN_FORM_LENGTH).sorted().toList());
            }
        }
        return out;
    }

    /** The markers (and their forms) found in {@code text}, normalized: what a scanner reports. */
    public List<String> leaksIn(String text) {
        String haystack = normalize(text);
        List<String> found = new ArrayList<>();
        piiForms().forEach((marker, forms) -> forms.stream().filter(haystack::contains).findFirst()
                .ifPresent(form -> found.add(marker + " as " + form)));
        return found;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Stand-in audio for the build: a few bytes per meeting, never a recording (as Stint's 3a.6 example). */
    public static byte[] syntheticAudio(String meetingId) {
        return ("RIFF-synthetic-meeting-" + meetingId).getBytes(StandardCharsets.UTF_8);
    }
}
