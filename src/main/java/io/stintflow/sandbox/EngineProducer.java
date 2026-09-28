package io.stintflow.sandbox;

import java.util.List;

import io.stintflow.aws.S3BlobStore;
import io.stintflow.core.WorkflowDefinition;
import io.stintflow.core.WorkflowEngine;
import io.stintflow.core.WorkflowRegistry;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.inmemory.InMemoryStateStore;
import io.stintflow.inmemory.InMemoryTaskTransport;
import io.stintflow.inmemory.InMemoryTimerService;
import io.stintflow.worker.TaskHandlerRegistry;
import io.stintflow.worker.WorkerRuntime;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;

/**
 * Wires a real (non-reactor) consumption of Stint: in-memory engine mechanics (state/transport/
 * timer, from stint-inmemory) with a real S3 blob store (floci) and real SMTP delivery (MailHog).
 * The workflow itself is loaded from a YAML contract via stint-dsl, not hand-built in Java.
 */
@ApplicationScoped
public class EngineProducer {

    @Inject
    S3BlobStore blob;
    @Inject
    QueryAndStageHandler queryAndStageHandler;
    @Inject
    FormatFileHandler formatFileHandler;
    @Inject
    SendEmailHandler sendEmailHandler;

    @Produces
    @ApplicationScoped
    public WorkflowEngine engine() {
        TaskHandlerRegistry handlers = new TaskHandlerRegistry()
                .register(SandboxWorkflows.ROUTE_QUERY, queryAndStageHandler)
                .register(SandboxWorkflows.ROUTE_FORMAT, formatFileHandler)
                .register(SandboxWorkflows.ROUTE_SEND_EMAIL, sendEmailHandler);

        InMemoryTaskTransport transport = new InMemoryTaskTransport();
        transport.connectWorker(new WorkerRuntime(handlers, blob)::handle);

        WorkflowRegistry registry = new WorkflowRegistry();
        List<WorkflowDefinition> defs = new DslLoader().loadClasspath("stint/workflows/*.yaml", LoadOptions.STRICT);
        defs.forEach(registry::register);

        return new WorkflowEngine(registry, transport, new InMemoryStateStore(),
                new InMemoryTimerService(), blob);
    }
}
