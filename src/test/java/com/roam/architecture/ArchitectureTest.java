package com.roam.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable package-boundary rules for the api/agent/service/domain/infrastructure/shared layout.
 *
 * <p>The detailed rationale and migration history live in
 * {@code docs/research/package-layering.md} and {@code docs/adr/0002-package-layering.md}.
 */
@AnalyzeClasses(packages = "com.roam", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule layered_architecture = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Api").definedBy("com.roam.api..")
            .layer("Agent").definedBy("com.roam.agent..")
            .layer("Service").definedBy("com.roam.service..")
            .layer("Domain").definedBy("com.roam.domain..")
            .layer("Infrastructure").definedBy("com.roam.infrastructure..")
            .layer("Shared").definedBy("com.roam.shared..")
            .whereLayer("Api").mayNotBeAccessedByAnyLayer()
            .whereLayer("Agent").mayOnlyBeAccessedByLayers("Api")
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Api", "Agent")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers(
                    "Api", "Agent", "Service", "Infrastructure")
            .whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Agent", "Service")
            .whereLayer("Shared").mayOnlyBeAccessedByLayers(
                    "Api", "Agent", "Service", "Domain", "Infrastructure");

    @ArchTest
    static final ArchRule domain_is_independent_of_frameworks_and_outer_layers = noClasses()
            .that().resideInAPackage("com.roam.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "com.roam.api..",
                    "com.roam.agent..",
                    "com.roam.service..",
                    "com.roam.infrastructure..")
            .because("the domain model must stay pure so it can be tested without Spring or IO");

    @ArchTest
    static final ArchRule domain_subpackages_are_free_of_cycles = slices()
            .matching("com.roam.domain.(*)..")
            .should().beFreeOfCycles()
            .because("trip, plan, booking, review, workflow, audit, shared, and port must not form a cycle");

    @ArchTest
    static final ArchRule service_does_not_depend_on_inbound_or_agent_layers = noClasses()
            .that().resideInAPackage("com.roam.service..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.roam.api..", "com.roam.agent..")
            .because("agents and HTTP adapters call services, not the other way round");

    @ArchTest
    static final ArchRule outbound_adapters_do_not_depend_on_application_or_inbound_layers =
            noClasses()
                    .that().resideInAPackage("com.roam.infrastructure..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.roam.api..", "com.roam.agent..", "com.roam.service..")
                    .because(
                            "outbound adapters implement ports; they must not reach back into the application layer");

    @ArchTest
    static final ArchRule api_does_not_depend_on_outbound_adapters = noClasses()
            .that().resideInAPackage("com.roam.api..")
            .should().dependOnClassesThat().resideInAPackage("com.roam.infrastructure..")
            .because(
                    "HTTP adapters should call application services, not persistence or supplier adapters");

    @ArchTest
    static final ArchRule api_dto_does_not_depend_on_application_or_infrastructure = noClasses()
            .that().resideInAPackage("com.roam.api.dto..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.roam.agent..", "com.roam.service..", "com.roam.infrastructure..")
            .because("HTTP DTOs may only mirror domain shapes, not application or adapter details");

    @ArchTest
    static final ArchRule service_dto_does_not_depend_on_agent_or_inbound = noClasses()
            .that().resideInAPackage("com.roam.service.dto..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.roam.api..", "com.roam.agent..")
            .because("application results must not depend on orchestration or HTTP types");

    @ArchTest
    static final ArchRule agent_does_not_depend_on_the_inbound_layer = noClasses()
            .that().resideInAPackage("com.roam.agent..")
            .should().dependOnClassesThat().resideInAPackage("com.roam.api..")
            .because("the orchestration layer must not depend on an inbound adapter");

    @ArchTest
    static final ArchRule shared_does_not_depend_on_business_packages = noClasses()
            .that().resideInAPackage("com.roam.shared..")
            .should().dependOnClassesThat()
            .resideInAnyPackage(
                    "com.roam.api..",
                    "com.roam.agent..",
                    "com.roam.service..",
                    "com.roam.domain..",
                    "com.roam.infrastructure..")
            .because("shared types must stay usable by every layer without pulling in business code");

    @ArchTest
    static final ArchRule top_level_packages_are_free_of_cycles = slices()
            .matching("com.roam.(*)..")
            .should().beFreeOfCycles()
            .because("a cycle between layers or features makes the dependency direction meaningless");
}
