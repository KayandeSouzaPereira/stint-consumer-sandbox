package io.stintflow.sandbox.scenarios;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.stintflow.sandbox.contracts.PipelineEvents;
import io.stintflow.sandbox.scenarios.support.App;
import io.stintflow.sandbox.scenarios.support.App.Module;
import io.stintflow.sandbox.scenarios.support.PipelineScenario;
import io.stintflow.sandbox.scenarios.support.Scenario;
import io.stintflow.sandbox.scenarios.support.Spy;

/**
 * SDD S.11, RF2 (CA2): the pipeline's processes killed at the worst moments, and two engines on the same tables and
 * queues. Project p1 goes through its cycle (a business meeting, two SDDs, one failing its first attempt); the roles are
 * split with the {@code <domain>.engine.enabled} / {@code .worker.enabled} flags, and the kill points are the
 * {@code <domain>.chaos.*} properties of S.11 8b (a process that halts itself, {@code Runtime.halt(137)}). Every
 * scenario ends with the closing check of {@link PipelineScenario#verify}: the bus keeps its contract, the sweeper finds
 * nothing planted, and (where the cycle closes) nothing is left over.
 */
class PipelineChaosIT extends PipelineScenario {

    private static final String P1 = "p1";
    private static final Duration EXIT = Duration.ofSeconds(30);

    private static Map<Module, Map<String, String>> only(Module module, Map<String, String> props) {
        return Map.of(module, props);
    }

    private void recordAndWait(String what, App halting) {
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        note("recorded p1-c1-business");
        awaitAnswering(what, () -> !halting.alive(), CYCLE);
    }

    private void finish(String evidence, String title, String result) throws Exception {
        awaitClosed(P1, "1");
        spy.settle(Duration.ofSeconds(3));
        List<Spy.Event> ofP1 = eventsOf(P1);
        assertTheCountsOfACompleteCycle(ofP1, P1);
        String closing = verify(List.of(PipelineEvents.chainIdOf(P1)), true);
        Scenario.evidence(evidence, title + "\n" + timeline() + "  reviews answered through the portal: " + answered.size()
                + "\n  result: " + result + "\n" + busOf(P1, ofP1) + closing);
    }

    /** a. The privacy worker dies after redacting a transcript, before its result: the task comes back. */
    @Test
    void a_privacy_worker_killed_inside_the_redactor() throws Exception {
        Map<Module, App> running = startPipeline(only(Module.PRIVACY, Map.of("privacy.chaos.halt-in-task", "redactor")));
        App privacy1 = running.get(Module.PRIVACY);
        recordAndWait("the privacy worker halted", privacy1);
        String halt = privacy1.awaitLine(l -> l.contains(HALT), Duration.ofSeconds(5));
        int exit = privacy1.awaitExit(EXIT);
        note("privacy worker 1 halted, exit %d: %s", exit, message(halt));
        assertThat(exit).isEqualTo(137);
        assertThat(spy.ofType(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();

        start("privacy-2", Module.PRIVACY, Map.of());
        finish("S.11-CA2-a.txt", "S.11 RF2a: the privacy worker killed inside the redactor (after the redaction, before"
                + " its result)", "the redaction task came back and ran again; the cycle closed with the counts of an"
                + " undisturbed one");
    }

    /** b. The business engine dies between the checkpoint and the publication of design-published: the outbox delivers. */
    @Test
    void b_business_engine_killed_between_checkpoint_and_publish() throws Exception {
        Map<String, String> engineOnly = Map.of("business.worker.enabled", "false");
        Map<Module, App> running = startPipeline(only(Module.BUSINESS,
                with(engineOnly, "business.chaos.halt-before-publish", PipelineEvents.DESIGN_PUBLISHED)));
        start("business-worker", Module.BUSINESS, Map.of("business.engine.enabled", "false", "quarkus.http.port", "8097"));
        App engine1 = running.get(Module.BUSINESS);
        recordAndWait("the business engine halted", engine1);
        String halt = engine1.awaitLine(l -> l.contains(HALT), Duration.ofSeconds(5));
        int exit = engine1.awaitExit(EXIT);
        int pending = leftovers.of("business").outbox();
        note("business engine 1 halted, exit %d: %s", exit, message(halt));
        note("business-outbox right after the halt: %d pending fact(s)", pending);
        spy.settle(Duration.ofSeconds(3));
        assertThat(exit).isEqualTo(137);
        assertThat(pending).isEqualTo(1);
        assertThat(spy.ofType(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();

        App engine2 = start("business-engine-2", Module.BUSINESS, engineOnly);
        finish("S.11-CA2-b.txt", "S.11 RF2b: the business engine killed between the checkpoint and the publication of"
                + " design-published", "the fact left in business-outbox was published once by the restarted engine");
        assertThat(engine2.lines()).noneMatch(l -> l.contains(HALT));
    }

    /** c. The business process dies while the design waits for the representative; restarted, it resumes on the answer. */
    @Test
    void c_business_killed_while_the_design_waits_for_its_review() throws Exception {
        Map<Module, App> running = startPipeline(Map.of());
        App business1 = running.get(Module.BUSINESS);
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        note("recorded p1-c1-business");
        spy.until("the design waits for the business representative", () -> !portal.open(PipelineEvents.ROLE_BUSINESS)
                .isEmpty(), CYCLE);
        int waits = leftovers.of("business").waits();
        note("a review is open for %s; business-waits holds %d wait(s); killing the business process",
                PipelineEvents.ROLE_BUSINESS, waits);
        assertThat(waits).isGreaterThanOrEqualTo(1);
        assertThat(spy.ofType(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();
        business1.kill();
        assertThat(business1.alive()).isFalse();

        start("business-2", Module.BUSINESS, Map.of());
        finish("S.11-CA2-c.txt", "S.11 RF2c: the business process killed while the design waits for its review",
                "restarted, it kept the wait and resumed when the representative answered through the portal");
    }

    /** d. The delivery worker dies after an implementation, before its result: the task comes back, nothing doubles. */
    @Test
    void d_delivery_worker_killed_inside_implement() throws Exception {
        Map<Module, App> running = startPipeline(only(Module.DELIVERY, Map.of("delivery.worker.enabled", "false")));
        App worker1 = start("delivery-worker-1", Module.DELIVERY, Map.of("delivery.engine.enabled", "false",
                "quarkus.http.port", "8098", "delivery.chaos.halt-in-task", "implement"));
        assertThat(running.get(Module.DELIVERY).alive()).isTrue();
        recordAndWait("the delivery worker halted", worker1);
        String halt = worker1.awaitLine(l -> l.contains(HALT), Duration.ofSeconds(5));
        int exit = worker1.awaitExit(EXIT);
        note("delivery worker 1 halted, exit %d: %s", exit, message(halt));
        assertThat(exit).isEqualTo(137);
        assertThat(spy.ofType(PipelineEvents.CYCLE_CLOSED)).isEmpty();

        start("delivery-worker-2", Module.DELIVERY, Map.of("delivery.engine.enabled", "false", "quarkus.http.port", "8098"));
        finish("S.11-CA2-d.txt", "S.11 RF2d: the delivery worker killed inside implement (after the implementation,"
                + " before its result)", "the task came back to another worker; one patch and one report per attempt,"
                + " the counts of an undisturbed cycle");
    }

    /** e. Two delivery engines on the same tables and queues: each SDD advances once. */
    @Test
    void e_two_delivery_replicas_on_the_same_tables_and_queues() throws Exception {
        startPipeline(Map.of());
        start("delivery-2", Module.DELIVERY, Map.of("quarkus.http.port", "8098"));
        assertThat(portal.record("p1-c1-business")).isEqualTo(202);
        note("recorded p1-c1-business");
        finish("S.11-CA2-e.txt", "S.11 RF2e: two delivery engines (and workers) on the same tables and queues",
                "each SDD was implemented and validated once per attempt and the cycle closed once");
    }

    /**
     * g. The business worker dies inside a decision (after the model answered, before the answer was sent): the task comes
     * back, the decision is made once, and the design carries that decision (SDD S.11, RF3).
     */
    @Test
    void g_business_worker_killed_inside_a_decision_leaves_one_decision_and_one_design() throws Exception {
        Map<String, String> engineOnly = Map.of("business.worker.enabled", "false");
        startPipeline(only(Module.BUSINESS, engineOnly));
        Map<String, String> workerOnly = Map.of("business.engine.enabled", "false", "quarkus.http.port", "8097");
        App worker1 = start("business-worker-1", Module.BUSINESS, with(workerOnly,
                "business.chaos.halt-in-task", "decide-priority"));
        recordAndWait("the business worker halted", worker1);
        String halt = worker1.awaitLine(l -> l.contains(HALT), Duration.ofSeconds(5));
        int exit = worker1.awaitExit(EXIT);
        note("business worker 1 halted, exit %d: %s", exit, message(halt));
        assertThat(exit).isEqualTo(137);
        assertThat(spy.ofType(PipelineEvents.DESIGN_PUBLISHED)).isEmpty();

        start("business-worker-2", Module.BUSINESS, workerOnly);
        finish("S.11-CA3.txt", "S.11 RF3: the business worker killed inside the priority decision (after the answer, before"
                + " its delivery)", "the decision was asked again and made once; one design-published carries it; one"
                + " design record");
        List<Spy.Event> published = spy.ofType(PipelineEvents.DESIGN_PUBLISHED);
        assertThat(published).hasSize(1);
        assertThat(published.getFirst().data().path("priority").asDouble())
                .as("the priority of the corpus's key for p1-c1-business")
                .isEqualTo(CORPUS.meeting("p1-c1-business").expected().priority(),
                        org.assertj.core.data.Offset.offset(0.0001));
    }

    /**
     * f. The model of the personal-data check hangs: the filter fails closed (S.6 8b). The branch ends in
     * {@code redaction-doubt} (S.6: the human review of a doubt belongs to S.8/S.9 and was not built, finding S11-1): the
     * text goes no further, nothing is published, the privacy process survives, and nothing is left running.
     */
    @Test
    void f_the_model_check_hangs_and_the_meeting_stops_at_the_redaction_doubt() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket hold = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (!hold.isClosed()) {
                        hold.accept();      // accepted and never answered or closed
                        connections.incrementAndGet();
                    }
                } catch (Exception closed) {
                    // the scenario is over
                }
            });
            Map<String, String> privacy = Map.of("privacy.check", "litjev", "privacy.check.litjev.base-uri",
                    "http://127.0.0.1:" + hold.getLocalPort(), "privacy.check.timeout", "PT3S");
            Map<Module, App> running = startPipeline(only(Module.PRIVACY, privacy));
            assertThat(portal.record("p1-c1-business")).isEqualTo(202);
            note("recorded p1-c1-business; the personal-data check hangs on 127.0.0.1:%d", hold.getLocalPort());

            App privacyApp = running.get(Module.PRIVACY);
            spy.until("the filter answered in doubt and the business instance ended", () -> connections.get() >= 1
                    && privacyApp.lines().stream().anyMatch(l -> l.contains("doubt true"))
                    && floci.ddb.scan(r -> r.tableName("business-instances").consistentRead(true)).count() >= 1
                    && leftovers.of("business").nonTerminal().isEmpty(), CYCLE);
            note("the check was asked %d time(s); the filter answered in doubt; the business instance ended",
                    connections.get());
            spy.settle(Duration.ofSeconds(5));

            assertThat(running.get(Module.PRIVACY).alive()).as("the privacy process survived the hang").isTrue();
            assertThat(spy.ofType(PipelineEvents.DESIGN_PUBLISHED)).as("nothing went on as if the text were clean")
                    .isEmpty();
            assertThat(openReviews()).as("no review opens for the doubt (finding S11-1)").isEmpty();
            String closing = verify(List.of(PipelineEvents.chainIdOf(P1)), true);
            Scenario.evidence("S.11-CA2-f.txt", "S.11 RF2f: the model of the personal-data check hangs (3 s timeout)\n"
                    + timeline() + "  result: the filter failed closed; the business instance ended (outcome"
                    + " redaction-doubt); the check was tried " + connections.get() + " time(s); nothing was published;"
                    + " no review was opened for a person (finding S11-1)\n" + busOf(P1, eventsOf(P1)) + closing);
            hold.close();
            acceptor.join(2000);
        }
    }

    private List<String> openReviews() {
        List<String> open = new java.util.ArrayList<>();
        for (String role : ROLES) {
            portal.open(role).forEach(r -> open.add(role + " " + r.path("id").asText()));
        }
        return open;
    }
}
