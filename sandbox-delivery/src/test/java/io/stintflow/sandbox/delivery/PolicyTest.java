package io.stintflow.sandbox.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.stintflow.core.policy.Policy;
import io.stintflow.dsl.DslLoader;
import io.stintflow.dsl.LoadOptions;
import io.stintflow.spi.Sensitivity;

/**
 * SDD S.6, RF3 and CA4: the Delivery's workflows never touch the raw recording or its transcript. Their data is
 * confidential (documents already redacted and registered): no route of theirs is allowed at restricted, and neither
 * {@code transcribe} nor {@code redactor} is allowed at all.
 */
class PolicyTest {

    static final Map<String, List<String>> ROUTES = Map.of("delivery-cycle.yaml", List.of("load-batch", "check-sdds"),
            "sdd-delivery.yaml", List.of("implement", "run-target-tests", "validate"));

    static Policy policy(String file) throws Exception {
        try (InputStream in = PolicyTest.class.getClassLoader().getResourceAsStream("stint/workflows/" + file)) {
            var def = new DslLoader().load(in, file, LoadOptions.STRICT);
            assertThat(def.policy()).as(file + " declares its policy").isNotNull();
            return def.policy();
        }
    }

    @Test
    void no_route_reads_restricted_data_and_the_raw_routes_are_not_allowed() throws Exception {
        for (var entry : ROUTES.entrySet()) {
            Policy p = policy(entry.getKey());
            for (String route : entry.getValue()) {
                assertThat(p.refusal("/do/1/x", route, Sensitivity.CONFIDENTIAL)).as(entry.getKey() + " " + route).isEmpty();
                assertThat(p.refusal("/do/1/x", route, Sensitivity.RESTRICTED)).as(entry.getKey() + " " + route
                        + " at restricted").isPresent();
            }
            for (String raw : List.of("transcribe", "redactor")) {
                assertThat(p.refusal("/do/1/x", raw, Sensitivity.CONFIDENTIAL)).as(entry.getKey() + " " + raw).isPresent();
                assertThat(p.refusal("/do/1/x", raw, Sensitivity.RESTRICTED)).as(entry.getKey() + " " + raw).isPresent();
            }
        }
    }
}
