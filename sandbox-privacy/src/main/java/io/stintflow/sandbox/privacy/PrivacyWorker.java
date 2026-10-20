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
import io.stintflow.sandbox.privacy.filter.CorpusKeyCheck;
import io.stintflow.sandbox.privacy.filter.LayeredFilter;
import io.stintflow.sandbox.privacy.filter.LitJevPersonalDataCheck;
import io.stintflow.sandbox.privacy.filter.LlmCheckLayer;
import io.stintflow.sandbox.privacy.filter.PersonalDataCheck;
import io.stintflow.spi.BlobStore;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;
import io.stintflow.worker.redaction.LayeredRedactor;
import io.stintflow.worker.redaction.Pseudonyms;
import io.stintflow.worker.redaction.RedactionHandler;
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
 * S.6 (8a): the filter is layered — patterns, spoken forms, listed and introduced names, and a local model's check —
 * and fails closed: doubt of any layer is the redaction's doubt. {@code privacy.check} picks the model check:
 * {@code litjev} (the local LitJev, loopback only), {@code fake} (the CI: it knows the corpus's markers and says so when
 * one is left) or {@code off}.
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
    @ConfigProperty(name = "privacy.check", defaultValue = "fake")
    String checkKind;
    @ConfigProperty(name = "privacy.check.threshold", defaultValue = "0.5")
    double threshold;
    /** The threshold of the LitJev check. 1.01 = observation only (never doubts but on a failure): see the properties. */
    @ConfigProperty(name = "privacy.check.litjev.threshold", defaultValue = "1.01")
    double litjevThreshold;
    @ConfigProperty(name = "privacy.check.litjev.base-uri", defaultValue = "http://127.0.0.1:8000")
    String litjevBase;
    @ConfigProperty(name = "privacy.check.litjev.model", defaultValue = "Qwen/Qwen3.5-2B")
    String litjevModel;
    @ConfigProperty(name = "privacy.check.timeout", defaultValue = "PT30S")
    Duration checkTimeout;

    private final List<SqsWorkerBinding> bindings = new ArrayList<>();

    void onStart(@Observes StartupEvent event) throws Exception {
        S3BlobStore blobs = new S3BlobStore(s3, blobsBucket);
        Corpus corpus = Corpus.load(Path.of(corpusDir));
        PersonalDataCheck check = switch (checkKind) {
            case "litjev" -> new LitJevPersonalDataCheck(java.net.URI.create(litjevBase), litjevModel, checkTimeout);
            case "off" -> null;
            default -> new CorpusKeyCheck(corpus);
        };
        WorkerRuntime runtime = new WorkerRuntime(handlers(corpus, blobs, check,
                "litjev".equals(checkKind) ? litjevThreshold : threshold), blobs,
                new OpenTelemetryExecutionTracer(openTelemetry));
        for (String caller : callers) {
            bindings.add(SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(queueBase + "privacy-tasks-" + caller)
                    .resultQueueUrl(queueBase + caller + "-results").pool(new WorkerPool(runtime, concurrency))
                    .waitTime(Duration.ofSeconds(5)).build().start());
        }
        LOG.infof("Privacy service started: route %s for callers %s, model check %s", ROUTE, callers, checkKind);
    }

    /** The layered filter (S.6) with the CI's model check: the corpus-aware fake. */
    public static TaskHandlerRegistry handlers(Corpus corpus, BlobStore blobs) {
        return handlers(corpus, blobs, new CorpusKeyCheck(corpus), LlmCheckLayer.DEFAULT_THRESHOLD);
    }

    /** The layered filter (S.6, 8a) behind the {@code redactor} route; {@code check} null = no model layer. */
    public static TaskHandlerRegistry handlers(Corpus corpus, BlobStore blobs, PersonalDataCheck check,
            double threshold) {
        LayeredRedactor redactor = LayeredFilter.build(LayeredFilter.namesOf(corpus), check, threshold);
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
