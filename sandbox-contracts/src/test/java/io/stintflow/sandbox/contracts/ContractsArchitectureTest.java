package io.stintflow.sandbox.contracts;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * SDD S.5, 8c: sandbox-contracts is a library of contracts, not a domain — no Stint engine or worker, no cloud SDK, no
 * Quarkus, no other sandbox package. The rule analyses more than zero classes.
 */
class ContractsArchitectureTest {

    @Test
    void the_contracts_hold_no_domain_logic() {
        JavaClasses classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.stintflow.sandbox.contracts");
        assertThat(classes.size()).isGreaterThan(0);
        noClasses().should().dependOnClassesThat().resideInAnyPackage("io.stintflow.core..", "io.stintflow.worker..",
                "io.stintflow.aws..", "io.stintflow.dsl..", "io.quarkus..", "software.amazon..", "jakarta..")
                .check(classes);
        noClasses().should().dependOnClassesThat(JavaClass.Predicates.resideInAPackage("io.stintflow.sandbox..")
                .and(JavaClass.Predicates.resideOutsideOfPackage("io.stintflow.sandbox.contracts..")))
                .check(classes);
    }
}
