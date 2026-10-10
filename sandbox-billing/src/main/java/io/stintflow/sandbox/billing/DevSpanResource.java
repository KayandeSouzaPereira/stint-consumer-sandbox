package io.stintflow.sandbox.billing;

import java.util.List;
import java.util.Map;

import io.stintflow.spi.ExecutionSpan;
import io.stintflow.spi.TraceAttributes;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * SDD S.1, CA6: records one test span through the same {@code ExecutionTracer} the engine uses (stint-otel), so it
 * shows up in Jaeger under this service. Identifiers only, as Stint's tracer requires (SDD 2.5).
 */
@Path("/dev/span")
public class DevSpanResource {

    @Inject
    BillingEngine billing;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> span() {
        ExecutionSpan span = billing.tracer().start("sandbox.dev-span",
                Map.of(TraceAttributes.INSTANCE_ID, "dev-span", TraceAttributes.WORKFLOW, "sandbox:billing:dev"),
                null, List.of());
        String traceparent = span.context().traceparent();
        span.close();
        return Map.of("traceparent", traceparent == null ? "" : traceparent);
    }
}
