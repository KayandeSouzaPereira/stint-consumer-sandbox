package io.stintflow.sandbox.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.stintflow.sandbox.contracts.Corpus.Meeting;
import io.stintflow.sandbox.contracts.Corpus.Pii;

/**
 * SDD S.5, CA4: the pipeline corpus (format v2) is complete — hashes against the manifest, an answer key for every
 * decision of its stage, every marker planted and listed, every spoken form tied to its marker, the scanner's list in
 * step with the corpus — and covers every meeting type, channel and outcome.
 */
class PipelineCorpusTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Corpus CORPUS = Corpus.load(ROOT.resolve("corpus"));
    static final Pattern MARKER = Pattern.compile("SYNTH-PII-\\d{4}");

    @Test
    void fourteen_meetings_cover_every_type_channel_and_outcome() {
        List<Meeting> meetings = CORPUS.meetings();
        assertThat(meetings).hasSize(14);
        assertThat(meetings).extracting(m -> m.expected().meetingType()).containsOnly("business-design",
                "technical-refinement", "out-of-scope").contains("business-design", "technical-refinement",
                        "out-of-scope");
        assertThat(meetings).extracting(m -> m.expected().channel()).contains("email", "confluence", "meeting");
        assertThat(meetings).extracting(m -> m.expected().outcome()).contains("sdds", "questions");
        assertThat(meetings).filteredOn(m -> Boolean.TRUE.equals(m.expected().review())).hasSize(2);
        assertThat(meetings).filteredOn(m -> "technical-refinement".equals(m.expected().meetingType())
                && "sdds".equals(m.expected().outcome())).extracting(m -> m.expected().sddCount())
                .contains(1, 2, 3);
    }

    @Test
    void every_script_and_audio_matches_the_manifest() throws Exception {
        JsonNode entries = CORPUS.manifest().path("meetings");
        assertThat(entries).hasSize(CORPUS.meetings().size());
        for (JsonNode entry : entries) {
            Path script = CORPUS.dir().resolve(entry.path("script").asText());
            assertThat(Corpus.sha256(Files.readAllBytes(script))).as(script.toString())
                    .isEqualTo(entry.path("scriptSha256").asText());
            CORPUS.meeting(entry.path("id").asText());
            JsonNode audio = entry.path("audio");
            if (!audio.isMissingNode() && Files.exists(CORPUS.dir().resolve(audio.path("file").asText()))) {
                assertThat(Corpus.sha256(Files.readAllBytes(CORPUS.dir().resolve(audio.path("file").asText()))))
                        .as("audio of " + entry.path("id").asText()).isEqualTo(audio.path("sha256").asText());
            }
        }
    }

    @Test
    void every_meeting_has_the_answer_key_of_its_stage() {
        for (Meeting m : CORPUS.meetings()) {
            Corpus.Expected e = m.expected();
            assertThat(m.project()).as(m.id()).isNotBlank();
            assertThat(m.cycle()).as(m.id()).isPositive();
            assertThat(m.stage()).as(m.id()).isIn("business", "technical");
            assertThat(m.id()).isEqualTo(m.project() + "-c" + m.cycle() + "-" + m.stage());
            assertThat(e.meetingType()).as(m.id()).isNotNull();
            assertThat(e.priority()).as(m.id()).isBetween(0.0, 1.0);
            assertThat(e.review()).as(m.id()).isNotNull();
            boolean inScope = !"out-of-scope".equals(e.meetingType());
            if ("business".equals(m.stage()) && inScope) {
                assertThat(e.channel()).as(m.id()).isIn("email", "confluence", "meeting");
            }
            if ("technical".equals(m.stage()) && inScope) {
                assertThat(e.outcome()).as(m.id()).isIn("sdds", "questions");
                assertThat(e.blockingQuestion()).as(m.id()).isEqualTo("questions".equals(e.outcome()));
                if ("sdds".equals(e.outcome())) {
                    assertThat(e.sddCount()).as(m.id()).isEqualTo(e.sdds().size()).isBetween(1, 20);
                    e.sdds().forEach(s -> assertThat(s.covers()).as(s.id()).isNotEmpty());
                } else {
                    assertThat(e.questions()).as(m.id()).isNotEmpty();
                }
            }
        }
    }

    @Test
    void sdd_ids_are_unique_across_the_corpus() {
        List<String> ids = CORPUS.meetings().stream().filter(m -> m.expected().sdds() != null)
                .flatMap(m -> m.expected().sdds().stream()).map(Corpus.Sdd::id).toList();
        assertThat(ids).doesNotHaveDuplicates().hasSizeGreaterThanOrEqualTo(8);
    }

    @Test
    void the_cycle_with_questions_restarts_with_the_next_cycle() {
        assertThat(CORPUS.of("p2", 1, "technical")).get().extracting(m -> m.expected().outcome()).isEqualTo("questions");
        assertThat(CORPUS.of("p2", 2, "business")).isPresent();
        assertThat(CORPUS.of("p2", 2, "technical")).get().extracting(m -> m.expected().sddCount()).isEqualTo(2);
        assertThat(CORPUS.of("p1", 1, "technical")).get().extracting(m -> m.expected().sddCount()).isEqualTo(2);
    }

    @Test
    void every_marker_is_planted_listed_and_has_its_spoken_forms() {
        for (Meeting m : CORPUS.meetings()) {
            Set<String> planted = MARKER.matcher(m.text()).results().map(r -> r.group()).collect(Collectors.toSet());
            Set<String> listed = m.pii().stream().map(Pii::marker).collect(Collectors.toSet());
            assertThat(planted).as(m.id()).isEqualTo(listed).isNotEmpty();
            for (Pii pii : m.pii()) {
                assertThat(m.text()).as(pii.marker()).contains(pii.written());
                assertThat(pii.kind()).isIn("email", "phone", "name", "id-number");
                assertThat(pii.spoken()).as(pii.marker()).hasSizeGreaterThanOrEqualTo(2);
                String digits = pii.marker().substring(pii.marker().length() - 4);
                String phoneDigits = pii.written().replaceAll("[^0-9]", "");
                for (String spoken : pii.spoken()) {
                    String n = Corpus.normalize(spoken);
                    assertThat(n.contains(digits) || n.contains(Corpus.digitsAsWords(digits))
                            || n.contains(phoneDigits) || n.contains(Corpus.digitsAsWords(phoneDigits)))
                            .as("spoken form '%s' of %s", spoken, pii.marker()).isTrue();
                }
            }
        }
    }

    @Test
    void markers_are_unique_and_the_scanner_finds_each_in_every_form() {
        List<String> markers = CORPUS.meetings().stream().flatMap(m -> m.pii().stream()).map(Pii::marker).toList();
        assertThat(markers).doesNotHaveDuplicates();
        for (Meeting m : CORPUS.meetings()) {
            for (Pii pii : m.pii()) {
                assertThat(CORPUS.leaksIn(pii.written())).as(pii.written()).anyMatch(l -> l.startsWith(pii.marker()));
                pii.spoken().forEach(s -> assertThat(CORPUS.leaksIn("he said " + s + " yesterday")).as(s)
                        .anyMatch(l -> l.startsWith(pii.marker())));
            }
        }
        assertThat(CORPUS.leaksIn("sha256 70b413beadf45cd0c0c6d2e283992ad92a548926 evt-2021 rec-p1:1")).isEmpty();
    }

    /** corpus/pii-forms.json is what S.6's scanner reads: it must be the list the corpus gives, in step with it. */
    @Test
    void the_scanner_list_is_in_step_with_the_corpus() throws Exception {
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        Map<String, List<String>> forms = CORPUS.piiForms();
        Path file = CORPUS.dir().resolve("pii-forms.json");
        Map<String, Object> content = new java.util.LinkedHashMap<>();
        content.put("description", "SDD S.5, 8d: normalized forms (lowercase letters and digits, at least 10 long) of "
                + "every planted marker, written and spoken. Generated from the corpus by PipelineCorpusTest "
                + "(-Dcorpus.write=true); do not edit.");
        content.put("forms", forms);
        String expected = json.writeValueAsString(content).replace("\r\n", "\n") + "\n";
        if (Boolean.getBoolean("corpus.write")) {
            Files.writeString(file, expected);
        }
        assertThat(Files.readString(file).replace("\r\n", "\n")).isEqualTo(expected);
        Matcher anyMarker = MARKER.matcher(Files.readString(file));
        assertThat(anyMarker.results().count()).isEqualTo(forms.size());
    }
}
