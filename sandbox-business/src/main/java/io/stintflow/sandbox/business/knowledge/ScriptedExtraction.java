package io.stintflow.sandbox.business.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.stintflow.knowledge.extraction.Glossary;
import io.stintflow.sandbox.contracts.KnowledgeCorpus;
import io.stintflow.wire.Json;

/**
 * SDD S.12, 8a: the extraction model of the build (no test calls a real provider). It answers, for a window of text, the
 * glossary terms the window mentions and the people it names (a pseudonym, never a name), as the knowledge schema
 * {@code meetings@1} asks. The same text always gives the same answer.
 */
public final class ScriptedExtraction implements ChatModel {

    private static final Pattern PSEUDONYM = Pattern.compile("PERSON_[0-9a-f]{6,32}");

    private final Glossary glossary;
    private final List<KnowledgeCorpus.Decision> decisions;
    private final List<KnowledgeCorpus.Requirement> requirements;

    public ScriptedExtraction(Glossary glossary) {
        this(glossary, List.of(), List.of());
    }

    /** SDD S.14, RF2: also cites the decisions and requirements the answer key says the text holds (by their words). */
    public ScriptedExtraction(Glossary glossary, List<KnowledgeCorpus.Decision> decisions,
            List<KnowledgeCorpus.Requirement> requirements) {
        this.glossary = glossary;
        this.decisions = List.copyOf(decisions);
        this.requirements = List.copyOf(requirements);
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        String text = request.messages().stream().filter(m -> m instanceof UserMessage)
                .map(m -> ((UserMessage) m).singleText()).reduce("", (a, b) -> a + b);
        String lower = text.toLowerCase();
        ArrayNode entities = Json.MAPPER.createArrayNode();
        ArrayNode relations = Json.MAPPER.createArrayNode();
        List<String> seen = new ArrayList<>();
        int n = 0;
        for (Glossary.Term term : glossary.terms()) {
            List<String> names = new ArrayList<>(term.aliases());
            names.add(term.canonicalName());
            for (String name : names) {
                int at = lower.indexOf(name.toLowerCase());
                if (at >= 0 && !seen.contains(term.id())) {
                    seen.add(term.id());
                    // the mention as the text wrote it (case and all): the base resolves it to the glossary term (S.13, RF2)
                    ObjectNode e = entities.addObject().put("ref", "t" + (++n)).put("type", term.type())
                            .put("mention", text.substring(at, at + name.length()));
                    e.set("passages", Json.MAPPER.createArrayNode().add(0));
                }
            }
        }
        Matcher person = PSEUDONYM.matcher(text);
        int p = 0;
        List<String> people = new ArrayList<>();
        while (person.find()) {
            if (!people.contains(person.group())) {
                people.add(person.group());
                String ref = "p" + (++p);
                ObjectNode e = entities.addObject().put("ref", ref).put("type", "Person").put("mention", person.group());
                e.set("passages", Json.MAPPER.createArrayNode().add(0));
                relations.addObject().put("type", "participated").put("from", ref).put("to", "meeting");
            }
        }
        for (KnowledgeCorpus.Decision decision : decisions) {
            if (lower.contains(decision.surface().toLowerCase())) {
                String ref = "d" + (++n);
                ObjectNode e = entities.addObject().put("ref", ref).put("type", "Decision").put("mention", decision.label());
                e.putObject("properties").put("summary", decision.summary()).put("status", decision.status());
                e.set("passages", Json.MAPPER.createArrayNode().add(0));
                relations.addObject().put("type", "decided").put("from", "meeting").put("to", ref);
            }
        }
        for (KnowledgeCorpus.Requirement requirement : requirements) {
            if (lower.contains(requirement.surface().toLowerCase())) {
                ObjectNode e = entities.addObject().put("ref", "q" + (++n)).put("type", "Requirement")
                        .put("mention", requirement.label());
                e.putObject("properties").put("summary", requirement.summary());
                e.set("passages", Json.MAPPER.createArrayNode().add(0));
            }
        }
        ObjectNode answer = Json.obj();
        answer.set("entities", entities);
        answer.set("relations", relations);
        return ChatResponse.builder().aiMessage(AiMessage.from(answer.toString())).modelName("sandbox-scripted").build();
    }
}
