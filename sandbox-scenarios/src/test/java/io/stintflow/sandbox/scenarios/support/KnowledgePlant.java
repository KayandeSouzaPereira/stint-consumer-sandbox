package io.stintflow.sandbox.scenarios.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * SDD S.13: a stand-in for the register of documents (Records). It keeps a synthetic text in the register's bucket and its
 * catalog row exactly as the register does, and announces it with the fact {@code document-registered}, so that a base can be
 * fed texts the pipeline's own writers do not produce (variations of a term, say). Synthetic texts only.
 */
public final class KnowledgePlant {

    public static final String BUCKET = "sandbox-records-docs";

    private final Floci floci;

    public KnowledgePlant(Floci floci) {
        this.floci = floci;
    }

    /** Plants {@code text} as version {@code version} of {@code recordId} (kind design, read by Engineering) and announces it. */
    public void register(String recordId, int version, String text, String project) throws Exception {
        String key = "plant/" + recordId + "/v" + version;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        floci.s3.putObject(r -> r.bucket(BUCKET).key(key), RequestBody.fromBytes(bytes));
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        floci.ddb.putItem(r -> r.tableName("records-catalog").item(Map.ofEntries(
                Map.entry("recordId", AttributeValue.fromS(recordId)),
                Map.entry("version", AttributeValue.fromN(String.valueOf(version))),
                Map.entry("sha256", AttributeValue.fromS(sha)),
                Map.entry("kind", AttributeValue.fromS("design")),
                Map.entry("ownerRole", AttributeValue.fromS("business-rep")),
                Map.entry("sensitivity", AttributeValue.fromS("confidential")),
                Map.entry("storedRef", AttributeValue.fromS("s3://" + BUCKET + "/" + key)),
                Map.entry("projectId", AttributeValue.fromS(project)),
                Map.entry("cycle", AttributeValue.fromS("1")),
                Map.entry("readers", AttributeValue.fromSs(java.util.List.of("engineering", "delivery"))))));
        announce(recordId, version, sha, project);
    }

    /** The fact again, as the bus may deliver it twice (the same id). */
    public void announce(String recordId, int version, String sha, String project) throws Exception {
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0")
                .put("id", project + ":plant:" + recordId + ":v" + version)
                .put("type", "io.sandbox.records.document-registered.v1")
                .put("source", "https://sandbox.stintflow.io/records").put("subject", recordId)
                .put("datacontenttype", "application/json").put("sandboxproject", project).put("sandboxcycle", "1");
        event.putObject("data").put("recordId", recordId).put("version", version).put("sha256", sha).put("kind", "design")
                .put("sensitivity", "confidential").put("ownerRole", "business-rep");
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/records").detailType("io.sandbox.records.document-registered.v1")
                .detail(detail).build()));
    }

    /** A question for a domain's base, as a fact. */
    public void ask(String domain, String questionId, String question) throws Exception {
        ObjectNode event = Spy.JSON.createObjectNode().put("specversion", "1.0").put("id", questionId + ":asked")
                .put("type", "io.sandbox." + domain + ".question-asked.v1")
                .put("source", "https://sandbox.stintflow.io/" + domain).put("subject", questionId)
                .put("datacontenttype", "application/json").put("sandboxproject", "plant").put("sandboxcycle", "1");
        event.putObject("data").put("questionId", questionId).put("question", question).put("label", "confidential");
        String detail = Spy.JSON.writeValueAsString(event);
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/" + domain).detailType("io.sandbox." + domain + ".question-asked.v1")
                .detail(detail).build()));
    }
}
