package io.stintflow.sandbox.channels;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.cloudevents.CloudEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.quarkus.mailer.Mailer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.stintflow.aws.S3BlobStore;
import io.stintflow.aws.worker.SqsWorkerBinding;
import io.stintflow.otel.OpenTelemetryExecutionTracer;
import io.stintflow.sandbox.contracts.Corpus;
import io.stintflow.wire.CeWire;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerPool;
import io.stintflow.worker.WorkerRuntime;
import io.stintflow.worker.publishing.ConfluencePagePublisher;
import io.stintflow.worker.publishing.FakeConfluenceServer;
import io.stintflow.worker.publishing.PublishHandler;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

/**
 * SDD S.5, 8c: the channels service — the fake Confluence (embedded, fake credentials, one space), the workers of
 * {@code send-email} and {@code publish-page} for every caller, the consumer of {@code channels-domain-events}, the
 * recorder and the portal (resources in {@link ChannelsResource}).
 */
@ApplicationScoped
public class ChannelsService {

    private static final Logger LOG = Logger.getLogger(ChannelsService.class);

    @Inject
    SqsClient sqs;
    @Inject
    S3Client s3;
    @Inject
    EventBridgeClient eventBridge;
    @Inject
    Mailer mailer;
    @Inject
    OpenTelemetry openTelemetry;

    @ConfigProperty(name = "sandbox.sqs.base-url")
    String queueBase;
    @ConfigProperty(name = "sandbox.corpus-dir")
    String corpusDir;
    @ConfigProperty(name = "channels.callers")
    List<String> callers;
    @ConfigProperty(name = "channels.worker.concurrency", defaultValue = "4")
    int concurrency;
    @ConfigProperty(name = "channels.domain-events-queue")
    String eventsQueue;
    @ConfigProperty(name = "channels.domain-bus")
    String busName;
    @ConfigProperty(name = "channels.raw-bucket")
    String rawBucket;
    @ConfigProperty(name = "channels.documents-bucket")
    String documentsBucket;
    @ConfigProperty(name = "channels.recorder.audio")
    String audio;
    @ConfigProperty(name = "channels.recorder.auto")
    boolean autoRecord;
    @ConfigProperty(name = "channels.confluence.space")
    String space;
    @ConfigProperty(name = "channels.confluence.email")
    String confluenceEmail;
    @ConfigProperty(name = "channels.confluence.token")
    String confluenceToken;
    @ConfigProperty(name = "channels.portal.source")
    String portalSource;
    @ConfigProperty(name = "channels.mail.from")
    String mailFrom;

    private final List<SqsWorkerBinding> bindings = new ArrayList<>();
    private final ExecutorService consumer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "channels-events");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile FakeConfluenceServer confluence;
    private volatile Recorder recorder;
    private volatile Portal portal;

    void onStart(@Observes StartupEvent event) throws Exception {
        Corpus corpus = Corpus.load(Path.of(corpusDir));
        S3BlobStore documents = new S3BlobStore(s3, documentsBucket);
        Bus bus = new Bus(eventBridge, busName);
        confluence = new FakeConfluenceServer(confluenceEmail, confluenceToken, space);
        recorder = new Recorder(corpus, new S3BlobStore(s3, rawBucket), bus, "corpus".equals(audio));
        portal = new Portal(bus::publish, URI.create(portalSource));
        Mail mail = new Mail(mailer, documents, mailFrom);
        Notices notices = new Notices(portal, mail, recorder, autoRecord);

        TaskHandlerRegistry handlers = new TaskHandlerRegistry().register(Mail.ROUTE, mail)
                .register("publish-page", new PublishHandler(ConfluencePagePublisher.builder()
                        .baseUri(confluence.baseUri()).space(space).email(confluenceEmail).apiToken(confluenceToken)
                        .build(), documents, space));
        WorkerRuntime runtime = new WorkerRuntime(handlers, documents, new OpenTelemetryExecutionTracer(openTelemetry));
        for (String caller : callers) {
            bindings.add(SqsWorkerBinding.builder().sqs(sqs).invokeQueueUrl(queueBase + "channels-tasks-" + caller)
                    .resultQueueUrl(queueBase + caller + "-results").pool(new WorkerPool(runtime, concurrency))
                    .waitTime(Duration.ofSeconds(5)).build().start());
        }
        running = true;
        consumer.submit(() -> consume(notices));
        LOG.infof("Channels started: callers %s, Confluence space %s, recorder audio %s (auto %s)", callers, space,
                audio, autoRecord);
    }

    /** channels-domain-events: one message at a time, deleted once handled (at least once, deduplicated). */
    private void consume(Notices notices) {
        String url = queueBase + eventsQueue;
        while (running) {
            try {
                for (Message m : sqs.receiveMessage(r -> r.queueUrl(url).waitTimeSeconds(5).maxNumberOfMessages(10))
                        .messages()) {
                    CloudEvent ce = CeWire.fromJson(m.body().getBytes(StandardCharsets.UTF_8));
                    try {
                        notices.on(ce);
                        sqs.deleteMessage(r -> r.queueUrl(url).receiptHandle(m.receiptHandle()));
                    } catch (Exception e) {
                        LOG.warnf("Event %s of type %s not handled (%s); it comes back", ce.getId(), ce.getType(),
                                e.getClass().getSimpleName());
                    }
                }
            } catch (Exception e) {
                if (running) {
                    LOG.warn("Channels poll failed", e);
                }
            }
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        running = false;
        consumer.shutdownNow();
        bindings.forEach(SqsWorkerBinding::close);
        if (confluence != null) {
            confluence.close();
        }
    }

    public Recorder recorder() {
        return recorder;
    }

    public Portal portal() {
        return portal;
    }

    public FakeConfluenceServer confluence() {
        return confluence;
    }

    /** Ready once the event consumer and every caller's worker binding run. */
    public boolean ready() {
        return running && bindings.size() == callers.size();
    }
}
