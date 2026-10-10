package io.stintflow.sandbox.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.stintflow.sandbox.billing.support.BillingFixture;
import io.stintflow.sandbox.billing.worker.BillingHandlers;
import io.stintflow.sandbox.billing.worker.BillingPorts.Invoice;
import io.stintflow.spi.ScheduleSpec;

/**
 * SDD S.2, RF3/RF5 on the in-memory engine: {@code billing:billing-summary:1.0.0} counts the invoices of
 * {@code [T - 1 min, T)} for its occurrence T (sec. 8d) and mails finance once (sec. 8e), even when the occurrence
 * is delivered twice at the same moment.
 */
class BillingSummaryWorkflowTest {

    private static final Instant T = Instant.parse("2026-10-10T12:01:00Z");

    @Test
    void the_summary_runs_every_minute_and_counts_the_minute_before_its_occurrence() throws Exception {
        BillingFixture f = new BillingFixture();
        ScheduleSpec spec = f.summarySpec();
        assertThat(spec.cron().nextAfter(T, spec.zone())).isEqualTo(T.plus(Duration.ofMinutes(1)));

        issued(f, "inv-before", T.minusSeconds(61));         // previous window
        issued(f, "inv-first", T.minusSeconds(60));          // window start: included
        issued(f, "inv-last", T.minusMillis(1));             // just before T: included
        issued(f, "inv-at", T);                              // T itself: next window

        f.clock.set(T);
        f.schedules.deliver(spec.name(), T).toCompletableFuture().get(10, TimeUnit.SECONDS);
        JsonNode context = f.awaitCompleted(f.summaryInstance(T));

        assertThat(context.get("invoiceCount").asLong()).isEqualTo(2);
        assertThat(context.get("mailed").asBoolean()).isTrue();
        assertThat(f.mail.sent).singleElement().satisfies(mail -> {
            assertThat(mail.from()).isEqualTo("billing@sandbox.local");
            assertThat(mail.to()).isEqualTo("finance@sandbox.local");
            assertThat(mail.subject())
                    .isEqualTo("Billing summary 2026-10-10T12:00:00Z – 2026-10-10T12:01:00Z UTC: 2 invoice(s)");
            assertThat(mail.body()).contains("[2026-10-10T12:00:00Z, 2026-10-10T12:01:00Z) UTC: 2");
        });
    }

    @Test
    void a_duplicated_occurrence_sends_one_mail() throws Exception {
        BillingFixture f = new BillingFixture();
        ScheduleSpec spec = f.summarySpec();
        f.clock.set(T);
        CyclicBarrier together = new CyclicBarrier(2);

        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> deliverTogether(f, spec, together));
        CompletableFuture<Void> second = CompletableFuture.runAsync(() -> deliverTogether(f, spec, together));
        CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
        f.awaitCompleted(f.summaryInstance(T));

        assertThat(f.state.creates()).isEqualTo(1);
        assertThat(f.mail.sent).hasSize(1);
    }

    @Test
    void a_redelivered_send_summary_task_does_not_mail_again() throws Exception {
        BillingFixture f = new BillingFixture();
        f.clock.set(T);
        f.schedules.deliver(f.summarySpec().name(), T).toCompletableFuture().get(10, TimeUnit.SECONDS);
        String instanceId = f.summaryInstance(T);
        f.awaitCompleted(instanceId);

        // RF5: the same invocation arriving again (SQS redelivery) finds the marker of instance + task.
        var invoke = f.invokesOf(Billing.ROUTE_SEND_SUMMARY).getFirst();
        String taskId = invoke.getExtension("taskid").toString();
        assertThat(f.markers.keys).containsExactly(BillingHandlers.mailEffectKey(instanceId, taskId));
        JsonNode replayed = f.replayToWorker(invoke);

        assertThat(replayed.at("/output/mailed").asBoolean()).isFalse();
        assertThat(f.mail.sent).hasSize(1);
    }

    private static void issued(BillingFixture f, String invoiceId, Instant at) {
        f.records.recordIfAbsent(new Invoice(invoiceId, "o-" + invoiceId, BigDecimal.TEN, "BRL", at));
    }

    private static void deliverTogether(BillingFixture f, ScheduleSpec spec, CyclicBarrier together) {
        try {
            together.await(10, TimeUnit.SECONDS);
            f.schedules.deliver(spec.name(), T).toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
