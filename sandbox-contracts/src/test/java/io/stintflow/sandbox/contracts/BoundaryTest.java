package io.stintflow.sandbox.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * SDD S.5, CA1 (8c): no sandbox module depends on another, except on {@code sandbox-contracts}. Reads every module's
 * pom from the root reactor and fails naming each forbidden edge; the negative case plants a domain depending on
 * another and checks the rule sees it. (Each domain's enforcer bans the same edges at build time.) It lives here, not
 * in sandbox-scenarios, because the scenarios' tests only run with -Pscenarios.
 */
class BoundaryTest {

    static final Path ROOT = Path.of(System.getProperty("sandbox.root", ".."));
    static final Pattern MODULE = Pattern.compile("<module>([^<]+)</module>");
    static final Pattern DEPENDENCY = Pattern.compile(
            "<dependency>\\s*<groupId>io\\.stintflow\\.sandbox</groupId>\\s*<artifactId>([^<]+)</artifactId>");
    static final String CONTRACTS = "sandbox-contracts";

    /** The forbidden edges of one pom: {@code module -> other sandbox module}. */
    static List<String> forbiddenEdges(String module, String pom) {
        List<String> edges = new ArrayList<>();
        Matcher m = DEPENDENCY.matcher(pom);
        while (m.find()) {
            String target = m.group(1);
            if (!target.equals(CONTRACTS) && !target.equals(module)) {
                edges.add(module + " -> " + target);
            }
        }
        return edges;
    }

    static List<String> modules() throws Exception {
        List<String> modules = new ArrayList<>();
        Matcher m = MODULE.matcher(Files.readString(ROOT.resolve("pom.xml")));
        while (m.find()) {
            modules.add(m.group(1));
        }
        return modules;
    }

    @Test
    void no_module_depends_on_another_but_the_contracts() throws Exception {
        List<String> modules = modules();
        assertThat(modules).contains("sandbox-business", "sandbox-engineering", "sandbox-delivery",
                "sandbox-privacy", "sandbox-records", "sandbox-channels", "sandbox-target", "sandbox-orders",
                "sandbox-billing", CONTRACTS);
        List<String> edges = new ArrayList<>();
        for (String module : modules) {
            edges.addAll(forbiddenEdges(module, Files.readString(ROOT.resolve(module).resolve("pom.xml"))));
        }
        assertThat(edges).as("forbidden dependencies between sandbox modules").isEmpty();
    }

    @Test
    void every_domain_pom_bans_the_other_modules_at_build_time() throws Exception {
        for (String module : modules()) {
            if (module.equals(CONTRACTS) || module.equals("sandbox-reports")) {
                continue;
            }
            String pom = Files.readString(ROOT.resolve(module).resolve("pom.xml"));
            assertThat(pom).as(module).contains("<bannedDependencies>").contains("io.stintflow.sandbox");
        }
    }

    @Test
    void a_planted_dependency_between_domains_is_seen() throws Exception {
        String planted = new String(getClass().getResourceAsStream("/boundary/planted-pom.xml").readAllBytes());
        assertThat(forbiddenEdges("sandbox-engineering", planted))
                .containsExactly("sandbox-engineering -> sandbox-business");
    }
}
