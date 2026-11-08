package com.anbar.wms.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the package rules from docs/ARCHITECTURE.md (section D). Rules for modules that don't exist yet
 * pass vacuously (see archunit.properties) and start biting as soon as the module is added.
 */
@AnalyzeClasses(packages = ArchitectureTest.ROOT, importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final String ROOT = "com.anbar.wms";

    static final List<String> FEATURE_MODULES = List.of(
            "identity", "catalog", "warehouse", "supplier", "purchasing", "audit",
            "inventory", "importing", "reporting", "alerting");

    // ------------------------------------------------------------------ module boundaries

    @ArchTest
    static final ArchRule commonDependsOnNoOtherModule = noClasses()
            .that().resideInAPackage(ROOT + ".common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    concat(List.of(ROOT + ".security.."), FEATURE_MODULES.stream().map(m -> ROOT + "." + m + "..").toList()))
            .because("common is the shared kernel; everything may depend on it, it depends on nothing");

    @ArchTest
    static final ArchRule modulesUseEachOtherOnlyThroughServices = CompositeArchRule.of(
            FEATURE_MODULES.stream().map(ArchitectureTest::noForeignRepositoryAccess).toList());

    @ArchTest
    static final ArchRule onlyThePostingServiceWritesBalances = noClasses()
            .that().doNotHaveFullyQualifiedName(ROOT + ".inventory.service.InventoryPostingService")
            .and().doNotHaveFullyQualifiedName(ROOT + ".inventory.repository.BalanceWriter")
            .should().dependOnClassesThat().haveFullyQualifiedName(ROOT + ".inventory.repository.BalanceWriter")
            .because("stock balances change only through the posting service, together with a ledger record");

    // ------------------------------------------------------------------ layering inside a module

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .because("controllers call services, which own transactions and business rules");

    @ArchTest
    static final ArchRule controllersDoNotUseEntities = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..entity..")
            .because("the API exchanges DTOs only, so entities never leak into JSON");

    @ArchTest
    static final ArchRule dtosDoNotReferenceEntities = noClasses()
            .that().resideInAPackage("..dto..")
            .should().dependOnClassesThat().resideInAPackage("..entity..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule repositoriesDependOnNothingAbove = noClasses()
            .that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..dto..");

    // ------------------------------------------------------------------ transactions

    @ArchTest
    static final ArchRule transactionsStartInServicesOnlyOnClasses = noClasses()
            .that().resideOutsideOfPackage("..service..")
            .should().beAnnotatedWith(Transactional.class)
            .because("one business command = one transaction, opened by its application service");

    @ArchTest
    static final ArchRule transactionsStartInServicesOnlyOnMethods = noMethods()
            .that().areDeclaredInClassesThat().resideOutsideOfPackage("..service..")
            .should().beAnnotatedWith(Transactional.class);

    @ArchTest
    static final ArchRule noJakartaTransactional = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.transaction.Transactional")
            .because("use Spring's @Transactional, which supports readOnly, propagation and isolation");

    // ------------------------------------------------------------------ general coding rules

    @ArchTest
    static final ArchRule noFieldInjection = GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noJavaUtilLogging = GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noStandardStreams = GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule timeComesFromInjectedClock = noClasses()
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDate.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(OffsetDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .because("inject java.time.Clock so timestamps and expiry rules are testable");

    // ------------------------------------------------------------------ helpers

    private static ArchRule noForeignRepositoryAccess(String module) {
        String[] foreignRepositories = FEATURE_MODULES.stream()
                .filter(other -> !other.equals(module))
                .map(other -> ROOT + "." + other + ".repository..")
                .toArray(String[]::new);
        return noClasses()
                .that().resideInAPackage(ROOT + "." + module + "..")
                .should().dependOnClassesThat().resideInAnyPackage(foreignRepositories)
                .because("modules call each other's services, never each other's repositories");
    }

    private static String[] concat(List<String> first, List<String> second) {
        return java.util.stream.Stream.concat(first.stream(), second.stream()).toArray(String[]::new);
    }
}
