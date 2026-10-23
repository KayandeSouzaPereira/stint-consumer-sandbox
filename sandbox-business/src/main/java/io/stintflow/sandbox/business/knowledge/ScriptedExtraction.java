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
import io.stintflow.wire.Json;

/**
 * SDD S.12, 8a: the extraction model of the build (no test calls a real provider). It answers, for a window of text, the
 * glossary terms the window mentions and the people it names (a pseudonym, never a name), as the knowledge schema
 * {@code meetings@1} asks. The same text always gives the same answer.
 */
public final class ScriptedExtraction implements ChatModel {

    private static final Pattern PSEUDONYM = Pattern.compile("PERSON_[0-9a-f]{6,32}");

    private final Glossary glossary;

    public ScriptedExtraction(Glossary glossary) {
        this.glossary = glossary;
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
                if (lower.contains(name.toLowerCase()) && !seen.contains(term.id())) {
                    seen.add(term.id());
                    ObjectNode e = entities.addObject().put("ref", "t" + (++n)).put("type", term.type())
                            .put("mention", name);
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
        ObjectNode answer = Json.obj();
        answer.set("entities", entities);
        answer.set("relations", relations);
        return ChatResponse.builder().aiMessage(AiMessage.from(answer.toString())).modelName("sandbox-scripted").build();
    }
}
