package io.github.iaarencibia.notifications;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the dependency rule each layer's package-info states. */
@AnalyzeClasses(
        packages = "io.github.iaarencibia.notifications",
        importOptions = ImportOption.DoNotIncludeTests.class)
class LayerDependenciesTest {

    @ArchTest
    static final ArchRule domainDependsOnlyOnJavaAndDomain =
            classes()
                    .that()
                    .resideInAPackage("..notifications.domain..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage("java..", "..notifications.domain..");

    @ArchTest
    static final ArchRule applicationDependsOnlyOnJavaDomainAndApplication =
            classes()
                    .that()
                    .resideInAPackage("..notifications.application..")
                    .should()
                    .onlyDependOnClassesThat()
                    .resideInAnyPackage(
                            "java..", "..notifications.domain..", "..notifications.application..");
}
