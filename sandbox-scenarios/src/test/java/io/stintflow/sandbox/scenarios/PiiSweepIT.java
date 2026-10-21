package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.ScenarioBase;
import io.stintflow.sandbox.scenarios.support.Spy;
import io.stintflow.sandbox.scenarios.support.Sweeper;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;

/**
 * SDD S.6, CA6: a zero from the sweeper means nothing unless it can see a leak. Here a planted datum is put in each kind
 * of store it reads — a log line, a bus event, an e-mail, an S3 object, a DynamoDB item, a span — in a different form
 * each time (written, spoken, a fragment of a garbled marker), and the sweeper must find every one, and nothing in a
 * clean store. The restricted bucket is the only place a marker may rest: a marker there must NOT be reported.
 */
class PiiSweepIT extends ScenarioBase {

    static final Corpus CORPUS = Corpus.load(Scenario.root().resolve("corpus"));

    @Test
    void the_sweeper_sees_a_planted_leak_in_every_kind_of_store_and_only_there() throws Exception {
        Sweeper sweeper = new Sweeper(CORPUS, floci, mail, null);
        Corpus.Meeting meeting = CORPUS.meeting("p1-c1-business");
        String written = meeting.pii().get(0).written();                    // SYNTH-PII-2011 Rivera
        String email = meeting.pii().get(1).written();                      // SYNTH-PII-2012@example.com
        String spoken = meeting.pii().get(1).spoken().get(1);               // Sinthe-P-I-I-2-0-1-2 at Example. Calm.
        String fragment = "I am Sinthe P.I. 2031 and so on";                // what Whisper leaves of a marker

        assertThat(sweeper.stores()).as("a clean emulator").isEmpty();
        assertThat(sweeper.texts("log", List.of("INFO Recorded the output of /do/2/redact: counts={}"))).isEmpty();

        // a log line and a span (texts the caller hands in)
        assertThat(sweeper.texts("log business", List.of("clean line", "INFO the user said " + written)))
                .singleElement().satisfies(f -> assertThat(f.what()).contains("SYNTH-PII-2011"));
        assertThat(sweeper.texts("span", List.of("{\"operationName\":\"x\",\"tag\":\"" + spoken + "\"}"))).hasSize(1);

        // a bus event, read back through the spy
        floci.eventBridge.putEvents(r -> r.entries(PutEventsRequestEntry.builder().eventBusName("sandbox-domain")
                .source("https://sandbox.stintflow.io/test").detailType("io.sandbox.test.leak.v1")
                .detail("{\"specversion\":\"1.0\",\"id\":\"leak-1\",\"type\":\"io.sandbox.test.leak.v1\","
                        + "\"source\":\"https://sandbox.stintflow.io/test\",\"data\":{\"note\":\"" + email + "\"}}")
                .build()));
        spy.untilEvents("the planted event on the bus", ev -> ev.stream().anyMatch(e -> e.type().contains("leak")),
                Duration.ofSeconds(30));
        assertThat(sweeper.texts("bus", spy.events().stream().map(Spy.Event::json).toList())).hasSize(1);

        // an e-mail
        sendMail("plant@sandbox.local", "Notes", "Reach me at " + fragment + ", thanks.");
        spy.until("the planted e-mail in MailHog", () -> !mail.all().isEmpty(), Duration.ofSeconds(30));
        assertThat(sweeper.stores()).extracting(Sweeper.Finding::source).containsExactly("mailhog");

        // an S3 object in a bucket that is not the restricted one, and the same text in the restricted one (not reported)
        floci.s3.putObject(r -> r.bucket("sandbox-documents").key("plant/design.md"),
                RequestBody.fromString("# Design\nCall " + written + " tomorrow.", StandardCharsets.UTF_8));
        floci.s3.putObject(r -> r.bucket(Sweeper.RESTRICTED_BUCKETS.iterator().next()).key("plant/raw.txt"),
                RequestBody.fromString("raw transcript: " + written, StandardCharsets.UTF_8));
        // a DynamoDB item
        floci.ddb.putItem(r -> r.tableName("records-catalog").item(Map.of("recordId", AttributeValue.fromS("plant"),
                "version", AttributeValue.fromN("1"), "note", AttributeValue.fromS(spoken))));

        List<Sweeper.Finding> found = sweeper.stores();
        assertThat(found).extracting(Sweeper.Finding::source).containsExactlyInAnyOrder("mailhog", "s3", "dynamodb");
        assertThat(found).filteredOn(f -> f.source().equals("s3")).singleElement()
                .satisfies(f -> assertThat(f.where()).isEqualTo("s3://sandbox-documents/plant/design.md"));
        assertThat(found).noneMatch(f -> f.where().contains("sandbox-meetings-raw"));

        Scenario.evidence("S.6-CA6.txt", "S.6 CA6: the sweeper sees a planted leak in every kind of store, and not in the restricted one\n"
                + "  planted: a log line, a span, a bus event, an e-mail, an S3 object, a DynamoDB item; and a marker in the\n"
                + "  restricted bucket sandbox-meetings-raw (must not be reported)\n  found:\n"
                + found.stream().map(f -> "    " + f + "\n").reduce("", String::concat));
    }

    /** A minimal SMTP session with MailHog on :1025 (loopback; no mail library in this module). */
    static void sendMail(String to, String subject, String body) throws Exception {
        try (Socket socket = new Socket("localhost", 1025);
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                PrintWriter out = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {
            in.readLine();
            for (String line : List.of("EHLO sandbox.local", "MAIL FROM:<test@sandbox.local>", "RCPT TO:<" + to + ">", "DATA")) {
                out.print(line + "\r\n");
                out.flush();
                String reply;
                do {
                    reply = in.readLine();
                } while (reply != null && reply.length() > 3 && reply.charAt(3) == '-');
            }
            out.print("From: test@sandbox.local\r\nTo: " + to + "\r\nSubject: " + subject + "\r\n\r\n" + body + "\r\n.\r\n");
            out.flush();
            in.readLine();
            out.print("QUIT\r\n");
            out.flush();
        }
    }

}
