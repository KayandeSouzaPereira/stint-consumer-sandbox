package io.stintflow.sandbox.contracts;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * SDD S.5, 8b: checks a pipeline event — a CloudEvent in structured JSON — against contracts/v1: the type is known,
 * the source is its publisher's, {@code subject} is an id, every correlation extension is there and well formed, and
 * {@code data} validates against the type's JSON Schema (no extra field, so no free text). Review events
 * ({@code io.stintflow.review.*}) follow Stint's own contract and are not checked here.
 */
public final class PipelineContracts {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "https://sandbox.stintflow.io/contracts/v1/";
    private static final Pattern SUBJECT = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    private final JsonNode envelopes;
    private final JsonSchemaFactory factory;
    private final SchemaValidatorsConfig config;
    private final Map<String, JsonSchema> schemas = new ConcurrentHashMap<>();

    public PipelineContracts() {
        this.envelopes = read("envelopes.json");
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012,
                builder -> builder.schemaMappers(m -> m.mapPrefix(BASE, "classpath:contracts/v1/")));
        this.config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    }

    /** Whether {@code type} is one of the pipeline's own types (contracts/v1). */
    public boolean isPipelineType(String type) {
        return envelopes.path("types").has(type);
    }

    /** The violations of {@code event} (structured CloudEvent JSON); empty when it keeps the contract. */
    public List<String> violations(JsonNode event) {
        List<String> out = new ArrayList<>();
        String type = event.path("type").asText();
        JsonNode rule = envelopes.path("types").get(type);
        if (rule == null) {
            out.add("unknown type " + type);
            return out;
        }
        JsonNode source = rule.get("source");
        if (source != null && !source.isNull() && !source.asText().equals(event.path("source").asText())) {
            out.add(type + ": source " + event.path("source").asText() + " is not " + source.asText());
        }
        if (!SUBJECT.matcher(event.path("subject").asText()).matches()) {
            out.add(type + ": subject missing or not an id");
        }
        for (JsonNode extension : rule.path("extensions")) {
            String name = extension.asText();
            Pattern pattern = Pattern.compile(envelopes.path("extensions").path(name).asText());
            JsonNode value = event.get(name);
            if (value == null || !value.isTextual() || !pattern.matcher(value.asText()).matches()) {
                out.add(type + ": extension " + name + " missing or malformed");
            }
        }
        JsonNode data = event.get("data");
        if (data == null || !data.isObject()) {
            out.add(type + ": data missing (or not inline JSON)");
            return out;
        }
        for (ValidationMessage message : schema(rule.path("schema").asText()).validate(data)) {
            out.add(type + ": " + message.getMessage());
        }
        return out;
    }

    /** The violations of {@code data} against one schema file of contracts/v1. */
    public List<String> dataViolations(String schemaFile, JsonNode data) {
        return schema(schemaFile).validate(data).stream().map(ValidationMessage::getMessage).toList();
    }

    private JsonSchema schema(String file) {
        return schemas.computeIfAbsent(file,
                f -> factory.getSchema(SchemaLocation.of("classpath:contracts/v1/" + f), config));
    }

    private static JsonNode read(String file) {
        try (InputStream in = PipelineContracts.class.getClassLoader().getResourceAsStream("contracts/v1/" + file)) {
            if (in == null) {
                throw new IllegalStateException("contracts/v1/" + file + " is not on the classpath");
            }
            return JSON.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
