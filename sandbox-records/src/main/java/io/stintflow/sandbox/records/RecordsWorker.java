package io.stintflow.sandbox.records;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.stintflow.aws.S3BlobStore;
import io.stintflow.aws.worker.SqsWorkerBinding;
import io.stintflow.otel.OpenTelemetryExecutionTracer;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SDD S.5, 8c: the register service. {@code register-document} for every domain, through one queue per caller
 * ({@code records-tasks-<domain>} -> {@code <domain>-results}, finding 8l).
 */
@ApplicationScoped
@Readiness
public class RecordsWorker implements HealthCheck {

    private static final Logger LOG = Logger.getLogger(RecordsWorker.class);

    @Inject
    SqsClient sqs;
    @Inject
    S3Client s3;
    @Inject
    DynamoDbClient ddb;
    @Inject
    OpenTelemetry openTelemetry;

    @ConfigProperty(name = "sandbox.sqs.base-url")
    String queueBase;
    @ConfigProperty(name = "records.callers")
    List<String> callers;
    @ConfigProperty(name = "records.catalog-table")
    String catalogTable;
    @ConfigProperty(name = "records.approvals-table")
    String approvalsTable;
    @ConfigProperty(name = "records.docs-bucket")
    String docsBucket;
    @ConfigProperty(name = "records.worker.concurrency", defaultValue = "4")
    int concurrency;

    private final List<SqsWorkerBinding> bindings = new ArrayList<>();

    void onStart(@Observes StartupEvent event) {
        S3BlobStore copies = new S3BlobStore(s3, docsBucket);
        DynamoDbCatalog catalog = new DynamoDbCatalog(ddb, catalogTable);
        TaskHandlerRegistry handlers = new TaskHandlerRegistry()
                .register(RegisterDocumentHandler.ROUTE, new RegisterDocumentHandler(catalog, copies, copies))
                .register(ApproveRecordHandler.ROUTE,
                        new ApproveRecordHandler(catalog, new DynamoDbApprovals(ddb, approvalsTable)))
                .register(ReadRecordHandler.ROUTE, new ReadRecordHandler(catalog));
        WorkerRuntime runtime = new WorkerRuntime(handlers, copies, new OpenTelemetryExecutionTracer(openTelemetry));
        for (String caller : callers) {
            bindings.add(SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(queueBase + "records-tasks-" + caller)
                    .resultQueueUrl(queueBase + caller + "-results").pool(new WorkerPool(runtime, concurrency))
                    .waitTime(Duration.ofSeconds(5)).build().start());
        }
        LOG.infof("Records service started: routes %s, %s, %s for callers %s", RegisterDocumentHandler.ROUTE,
                ApproveRecordHandler.ROUTE, ReadRecordHandler.ROUTE, callers);
    }

    void onStop(@Observes ShutdownEvent event) {
        bindings.forEach(SqsWorkerBinding::close);
    }

    @Override
    public HealthCheckResponse call() {
        return HealthCheckResponse.named("records-worker").status(!callers.isEmpty()
                && bindings.size() == callers.size()).build();
    }
}
