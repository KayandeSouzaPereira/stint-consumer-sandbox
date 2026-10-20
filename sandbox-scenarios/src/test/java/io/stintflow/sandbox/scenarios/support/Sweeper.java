package io.stintflow.sandbox.scenarios.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import io.stintflow.sandbox.contracts.Corpus;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;

/**
 * SDD S.6, RF5 (8d): the PII sweeper. It looks for the planted data — in every form of {@code corpus/pii-forms.json}
 * and in the pieces speech-to-text leaves of a marker ("synth…", "sinthe…", the synthetic surname) — in what the pipeline
 * writes outside the restricted blobs: process logs, spans, bus events (given as texts), and, by itself, every e-mail,
 * the pages of the fake Confluence, every object of every bucket and every item of every table of floci.
 * <p>
 * Left out, on purpose and declared: the bucket {@code sandbox-meetings-raw} (the raw audio and transcript, classes
 * {@code raw-audio} and {@code raw-transcript}, 7 days), where restricted content belongs. A zero here proves nothing
 * unless the sweeper is shown to see a leak: {@code PiiSweepIT} plants one in each kind of store.
 */
public final class Sweeper {

    /** Buckets where restricted content is allowed to rest. */
    public static final Set<String> RESTRICTED_BUCKETS = Set.of("sandbox-meetings-raw");
    private static final Pattern TOKENS = Pattern.compile("(?i)\\b(?:synth|sinth)\\w*|\\brivera\\b");
    private static final int MAX_OBJECT_BYTES = 4 * 1024 * 1024;

    /** One thing found: the kind of store, where in it, and what matched. */
    public record Finding(String source, String where, String what) {
        @Override
        public String toString() {
            return source + " " + where + ": " + what;
        }
    }

    private final Corpus corpus;
    private final Floci floci;
    private final MailHog mail;
    private final Integer channelsPort;
    private final HttpClient http = HttpClient.newHttpClient();

    /** @param channelsPort the running sandbox-channels' port (for the Confluence pages), or null */
    public Sweeper(Corpus corpus, Floci floci, MailHog mail, Integer channelsPort) {
        this.corpus = corpus;
        this.floci = floci;
        this.mail = mail;
        this.channelsPort = channelsPort;
    }

    /** What is wrong with one text: the scanner's forms, and the pieces of a marker. Empty = clean. */
    public List<String> leaksIn(String text) {
        List<String> found = new ArrayList<>(corpus.leaksIn(text));
        var m = TOKENS.matcher(text);
        while (m.find()) {
            found.add("fragment '" + m.group() + "'");
        }
        return found;
    }

    /** Texts handed in by the caller: log lines, spans, bus events. */
    public List<Finding> texts(String source, Iterable<String> texts) {
        List<Finding> out = new ArrayList<>();
        int i = 0;
        for (String text : texts) {
            check(out, source, "#" + i++, text);
        }
        return out;
    }

    /** Every e-mail, the Confluence pages, every bucket but the restricted one, every table. */
    public List<Finding> stores() {
        List<Finding> out = new ArrayList<>();
        mails(out);
        confluence(out);
        buckets(out);
        tables(out);
        return out;
    }

    private void mails(List<Finding> out) {
        for (MailHog.Mail m : mail.all()) {
            String where = m.to() + " | " + m.subject();
            check(out, "mailhog", where, m.subject() + "\n" + m.body());
            m.attachments().forEach(a -> check(out, "mailhog", where + " | " + a.name(),
                    new String(a.content(), StandardCharsets.UTF_8)));
        }
    }

    private void confluence(List<Finding> out) {
        if (channelsPort == null) {
            return;
        }
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + channelsPort
                    + "/dev/confluence/pages?bodies=true")).GET().build(), HttpResponse.BodyHandlers.ofString());
            Spy.JSON.readTree(response.body()).forEach(page -> check(out, "confluence", page.path("id").asText()
                    + " " + page.path("title").asText(), page.toString()));
        } catch (Exception e) {
            throw new IllegalStateException("the Confluence pages are not readable from the channels", e);
        }
    }

    private void buckets(List<Finding> out) {
        for (var bucket : floci.s3.listBuckets().buckets()) {
            if (RESTRICTED_BUCKETS.contains(bucket.name())) {
                continue;
            }
            floci.s3.listObjectsV2Paginator(r -> r.bucket(bucket.name())).contents().forEach(object -> {
                if (object.size() > MAX_OBJECT_BYTES) {
                    return;
                }
                byte[] bytes = floci.s3.getObjectAsBytes(r -> r.bucket(bucket.name()).key(object.key())).asByteArray();
                check(out, "s3", "s3://" + bucket.name() + "/" + object.key(), new String(bytes, StandardCharsets.UTF_8));
            });
        }
    }

    private void tables(List<Finding> out) {
        for (String table : floci.ddb.listTablesPaginator().tableNames()) {
            floci.ddb.scanPaginator(ScanRequest.builder().tableName(table).consistentRead(true).build()).items()
                    .forEach(item -> {
                        StringBuilder text = new StringBuilder();
                        item.forEach((k, v) -> text.append(k).append('=').append(show(v)).append('\n'));
                        String key = item.values().stream().findFirst().map(Sweeper::show).orElse("?");
                        check(out, "dynamodb", table + " " + key, text.toString());
                    });
        }
    }

    private static String show(AttributeValue v) {
        return v.toString();
    }

    private void check(List<Finding> out, String source, String where, String text) {
        List<String> leaks = leaksIn(text);
        if (!leaks.isEmpty()) {
            out.add(new Finding(source, where, String.join("; ", leaks)));
        }
    }
}
