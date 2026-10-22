package io.stintflow.sandbox.business;

import java.util.List;

import dev.langchain4j.service.UserMessage;
import io.quarkus.runtime.annotations.RegisterForProxy;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * SDD S.5, 8e: the AI Service that turns a redacted business meeting into a design document (manual run only; the CI's
 * writer is {@link DesignHandler#fake}). Synthetic data; the pseudonyms of the redaction are kept as they are.
 */
@RegisterForProxy
@RegisterForReflection(methods = true)
public interface DesignWriter {

    String PROMPT = "Write the design document of the business meeting below. Answer with JSON: summary, goals, "
            + "requirements (each a short sentence), openQuestions. Keep the pseudonyms as they are.\nTranscript:\n%s";

    Design write(@UserMessage String prompt);

    @RegisterForReflection
    record Design(String summary, List<String> goals, List<String> requirements, List<String> openQuestions) {

        public String markdown(String title) {
            StringBuilder md = new StringBuilder("# ").append(title).append("\n## Summary\n")
                    .append(summary == null ? "" : summary).append('\n');
            section(md, "Goals", goals);
            section(md, "Requirements", requirements);
            section(md, "Open questions", openQuestions);
            return md.toString();
        }

        private static void section(StringBuilder md, String name, List<String> lines) {
            if (lines == null || lines.isEmpty()) {
                return;
            }
            md.append("## ").append(name).append('\n');
            lines.forEach(line -> md.append("- ").append(line).append('\n'));
        }
    }
}
