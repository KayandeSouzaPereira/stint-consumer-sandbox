package io.stintflow.sandbox.contracts;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * SDD S.14, RF1: the answer key of the knowledge of the pipeline, in {@code corpus/kb.json}, beside the meetings and apart
 * from them (their manifest and hashes are not touched). What the business meetings decide and require, and what the
 * architecture side drafts to answer them, with the verdict each draft should get. Synthetic only.
 */
public record KnowledgeCorpus(int version, List<Decision> decisions, List<Requirement> requirements,
        List<Architecture> architecture) {

    /** A decision a business meeting takes; {@code surface} is the words of the meeting that hold it. */
    public record Decision(String meetingId, String surface, String key, String label, String summary, String status) {
    }

    public record Requirement(String meetingId, String surface, String key, String label, String summary) {
    }

    /** An architecture session (text only) and the decisions it drafts. */
    public record Architecture(String id, List<String> script, List<Draft> drafts) {
    }

    /** One drafted decision: the product decisions and requirements it answers (by key) and its verdict. */
    public record Draft(String id, String title, String summary, List<String> motivatedBy, String verdict) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static KnowledgeCorpus load(Path corpusDir) {
        try {
            return MAPPER.readValue(corpusDir.resolve("kb.json").toFile(), KnowledgeCorpus.class);
        } catch (IOException e) {
            throw new IllegalStateException("corpus/kb.json is not readable", e);
        }
    }
}
