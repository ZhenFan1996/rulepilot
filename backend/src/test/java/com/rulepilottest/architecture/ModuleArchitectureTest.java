package com.rulepilottest.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.rulepilot.RulePilotApplication;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {

    private final JavaClasses productionClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.rulepilot");

    @Test
    void businessModulesHaveNoCyclesOrInternalModuleAccess() {
        ApplicationModules.of(RulePilotApplication.class).verify();
    }

    @Test
    void domainCodeRemainsFrameworkFree() {
        noClasses()
                .that()
                .resideInAPackage("..domain..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "..adapter..")
                .check(productionClasses);
    }

    @Test
    void webAdaptersDoNotDependOnPersistenceAdapters() {
        noClasses()
                .that()
                .resideInAPackage("..adapter.in.web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("..adapter.out.persistence..")
                .check(productionClasses);
    }
}
