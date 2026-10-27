package io.plainround.seatholds.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.GeneralCodingRules;

/**
 * The dependency rule, enforced: domain depends on nothing; interfaces are depended on by no one.
 */
@AnalyzeClasses(
    packages = "io.plainround.seatholds",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  private static final String DOMAIN = "io.plainround.seatholds.domain..";
  private static final String APPLICATION = "io.plainround.seatholds.application..";
  private static final String INFRASTRUCTURE = "io.plainround.seatholds.infrastructure..";
  private static final String INTERFACES = "io.plainround.seatholds.interfaces..";

  @ArchTest
  static final ArchRule layersPointInwardOnly =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Domain")
          .definedBy(DOMAIN)
          .layer("Application")
          .definedBy(APPLICATION)
          .layer("Infrastructure")
          .definedBy(INFRASTRUCTURE)
          .layer("Interfaces")
          .definedBy(INTERFACES)
          .whereLayer("Interfaces")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Infrastructure")
          .mayOnlyBeAccessedByLayers("Interfaces")
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Infrastructure", "Interfaces")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Interfaces");

  @ArchTest
  static final ArchRule domainUsesOnlyTheJdk =
      classes()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .onlyDependOnClassesThat(resideInAnyPackage(DOMAIN, "java.."));

  @ArchTest
  static final ArchRule applicationDependsOnlyOnTheDomainAndTheJdk =
      classes()
          .that()
          .resideInAPackage(APPLICATION)
          .should()
          .onlyDependOnClassesThat(resideInAnyPackage(DOMAIN, APPLICATION, "java.."));

  @ArchTest
  static final ArchRule noPrintingOutsideTheInterfaces =
      noClasses()
          .that()
          .resideOutsideOfPackage(INTERFACES)
          .should(GeneralCodingRules.ACCESS_STANDARD_STREAMS);

  @ArchTest
  static final ArchRule noJavaUtilLoggingOutsideTheInterfaces =
      noClasses()
          .that()
          .resideOutsideOfPackage(INTERFACES)
          .should(GeneralCodingRules.USE_JAVA_UTIL_LOGGING);

  @ArchTest
  static final ArchRule theDomainHasNoLocksOrThreadsBecauseTheGuaranteeLivesInTheStore =
      noClasses()
          .that()
          .resideInAPackage(DOMAIN)
          .should()
          .dependOnClassesThat(resideInAnyPackage("java.util.concurrent.."));

  @ArchTest
  static final ArchRule databaseCodeStaysInTheJdbcAdapter =
      noClasses()
          .that()
          .resideOutsideOfPackage("io.plainround.seatholds.infrastructure.jdbc..")
          .should()
          .dependOnClassesThat(
              resideInAnyPackage("java.sql..", "javax.sql..", "com.zaxxer..", "org.postgresql.."));
}
