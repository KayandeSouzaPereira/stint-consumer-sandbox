package io.stintflow.sandbox.privacy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.stintflow.aws.S3BlobStore;
import io.stintflow.aws.worker.SqsWorkerBinding;
import io.stintflow.otel.OpenTelemetryExecutionTracer;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.spi.BlobStore;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.PatternLayer;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionHandler;
import io.stintflow.worker.redaction.TermListLayer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.5, 8c: the privacy service. It serves the {@code redactor} task to every domain through one queue per caller
 * — {@code privacy-tasks-<domain>} — and answers on that domain's {@code <domain>-results}, because a task invocation
 * carries no reply address (finding 8l). It reads the raw transcript by pointer (any bucket) and returns the redacted
 * text with its provenance; nothing of the text is logged.
 * <p>
 * S.5 runs Stint's reference redactor: the patterns, plus every planted marker of the corpus as a term (a marker is
 * the stand-in of a name). It does not catch spoken PII (Stint 3a.6 finding): the layered filter is S.6.
 */
@ApplicationScoped
public class PrivacyWorker {

    private static final Logger LOG = Logger.getLogger(PrivacyWorker.class);
    public static final String ROUTE = "redactor";
    /** A test-only pseudonymization secret: synthetic data only, never a real secret. */
    static final byte[] TEST_SECRET = "sandbox-privacy-test-secret-not-for-real-data".getBytes(StandardCharsets.UTF_8);

    @Inject
    SqsClient sqs;
    @Inject
    S3Client s3;
    @Inject
    OpenTelemetry openTelemetry;

    @ConfigProperty(name = "sandbox.sqs.base-url")
    String queueBase;
    @ConfigProperty(name = "sandbox.corpus-dir")
    String corpusDir;
    @ConfigProperty(name = "privacy.callers", defaultValue = "business,engineering,delivery")
    List<String> callers;
    @ConfigProperty(name = "privacy.blobs-bucket", defaultValue = "sandbox-meetings-raw")
    String blobsBucket;
    @ConfigProperty(name = "privacy.worker.concurrency", defaultValue = "4")
    int concurrency;

    private final List<SqsWorkerBinding> bindings = new ArrayList<>();

    void onStart(@Observes StartupEvent event) {
        S3BlobStore blobs = new S3BlobStore(s3, blobsBucket);
        WorkerRuntime runtime = new WorkerRuntime(handlers(Corpus.load(Path.of(corpusDir)), blobs), blobs,
                new OpenTelemetryExecutionTracer(openTelemetry));
        for (String caller : callers) {
            bindings.add(SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(queueBase + "privacy-tasks-" + caller)
                    .resultQueueUrl(queueBase + caller + "-results").pool(new WorkerPool(runtime, concurrency))
                    .waitTime(Duration.ofSeconds(5)).build().start());
        }
        LOG.infof("Privacy service started: route %s for callers %s", ROUTE, callers);
    }

    /** The reference redactor (8c): patterns, and the corpus's markers as terms. */
    public static TaskHandlerRegistry handlers(Corpus corpus, BlobStore blobs) {
        List<String> markers = corpus.meetings().stream().flatMap(m -> m.pii().stream()).map(Corpus.Pii::marker)
                .toList();
        LayeredRedactor redactor = new LayeredRedactor("sandbox-reference", "s5",
                List.of(new PatternLayer(), new TermListLayer("person", markers)));
        return new TaskHandlerRegistry().register(ROUTE, new RedactionHandler(redactor, new Pseudonyms(TEST_SECRET),
                blobs));
    }

    void onStop(@Observes ShutdownEvent event) {
        bindings.forEach(SqsWorkerBinding::close);
    }

    /** For the health check: whether every caller's binding started. */
    public boolean started() {
        return !callers.isEmpty() && bindings.size() == callers.size();
    }
}
