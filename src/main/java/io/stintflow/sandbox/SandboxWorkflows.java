package io.stintflow.sandbox;

import io.stintflow.spi.WorkflowRef;

/** Constants shared between the YAML contracts and the handlers registered for their routing keys. */
public final class SandboxWorkflows {

    public static final WorkflowRef PRIMARY = new WorkflowRef("sandbox", "query-report-email", "1.0.0");
    public static final WorkflowRef SECONDARY = new WorkflowRef("sandbox", "query-report-email-secondary", "1.0.0");

    public static final String ROUTE_QUERY = "query-and-stage";
    public static final String ROUTE_FORMAT = "format-file";
    public static final String ROUTE_SEND_EMAIL = "send-email";

    private SandboxWorkflows() {
    }
}
