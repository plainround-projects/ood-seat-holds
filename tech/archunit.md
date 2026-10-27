# ArchUnit 1.5.1 (architecture tests)

## What it is

ArchUnit is a Java library that lets you write **tests about the structure of your code**: which package may use which, which classes may touch the console, which ones may not use a given library. It reads the compiled classes, builds a model of every class and every dependency between classes, and checks rules you write in Java. The rules run like any other test, so a wrong `import` fails `make test`.

## Why this repository uses it

The template says the layers are folders, with the rule "domain depends on nothing; application depends on domain; infrastructure and interfaces depend on both; nothing depends on interfaces", **enforced** where the stack allows it. For Java the tool is ArchUnit. Without a test, such a rule slowly rots: one convenient import and the domain depends on a database class. With the test, the build fails on the day it happens.

## Where it appears

| Path | What it does with ArchUnit |
| --- | --- |
| `pom.xml` | Declares `archunit-junit5` 1.5.1, scope `test`. |
| `src/test/java/io/plainround/seatholds/architecture/ArchitectureTest.java` | The rules. |

## How it works underneath

1. The annotation `@AnalyzeClasses(packages = "...")` on the test class tells ArchUnit to **import** every class under that package. It reads the `.class` files in `target/classes` (compiled code, `javac.md`), not the source.
2. `ImportOption.DoNotIncludeTests` leaves out test classes, so the rules judge only production code.
3. For each class it records **dependencies**: every class it references through fields, method parameters, return types, calls, `new`, annotations and so on.
4. Each field marked `@ArchTest` and typed `ArchRule` is a rule. The JUnit integration (`archunit-junit5`) runs each as a test. A rule is a sentence built with a fluent API: `classes().that().resideInAPackage(X).should().onlyDependOnClassesThat(...)`.
5. If any class breaks a rule, the test fails and lists each violation with the class and line that caused it.

An ArchUnit rule that matches **no classes** fails by default, so a typo in a package name cannot make a rule pass by accident.

## Walkthrough of this repository's use

```java
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
```

### The setup

- `@AnalyzeClasses(packages = "io.plainround.seatholds", importOptions = ImportOption.DoNotIncludeTests.class)`: analyze every class of the project, production code only.
- The four `String` constants (`DOMAIN`, `APPLICATION`, `INFRASTRUCTURE`, `INTERFACES`) are **package patterns**. `..` means "this package and everything below it". They name the four layers.

### Rule 1: `layersPointInwardOnly`

`layeredArchitecture()` is ArchUnit's built-in way to describe layers. `.consideringOnlyDependenciesInLayers()` ignores dependencies on the standard library. Each `.layer("Name").definedBy(pattern)` declares a layer. Then:

- `whereLayer("Interfaces").mayNotBeAccessedByAnyLayer()`: nothing may depend on `interfaces`.
- `whereLayer("Infrastructure").mayOnlyBeAccessedByLayers("Interfaces")`: only `interfaces` may use `infrastructure`.
- `whereLayer("Application").mayOnlyBeAccessedByLayers("Infrastructure", "Interfaces")`: `infrastructure` and `interfaces` may use `application`.
- `whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure", "Interfaces")`: everyone but the domain itself may use the domain, so the domain depends on nothing outward.

Together they are the dependency rule: the arrows only point inward.

### Rule 2: `domainUsesOnlyTheJdk`

`classes().that().resideInAPackage(DOMAIN).should().onlyDependOnClassesThat(resideInAnyPackage(DOMAIN, "java.."))`: a class in the domain may depend only on other domain classes and on the Java standard library (`java..`). So no frameworks, no database, no logging library can leak into the business rules.

### Rule 3: `applicationDependsOnlyOnTheDomainAndTheJdk`

The same idea for the application layer: it may use the domain, itself and `java..`. (Logging goes through `System.Logger`, a `java..` class, `java-logging.md`.)

### Rule 4: `noPrintingOutsideTheInterfaces`

`noClasses().that().resideOutsideOfPackage(INTERFACES).should(GeneralCodingRules.ACCESS_STANDARD_STREAMS)`: no class outside `interfaces` may touch `System.out` or `System.err`. Only the command-line layer prints; everything else returns values or logs.

### Rule 5: `noJavaUtilLoggingOutsideTheInterfaces`

`GeneralCodingRules.USE_JAVA_UTIL_LOGGING`: no direct use of `java.util.logging` outside `interfaces`, where `Logging` configures it. Business code logs through `System.Logger` only.

### Rule 6: `theDomainHasNoLocksOrThreadsBecauseTheGuaranteeLivesInTheStore`

`noClasses()...dependOnClassesThat(resideInAnyPackage("java.util.concurrent.."))` applied to the domain: no locks, atomics or executors in the business rules. This is the lesson's central claim as a test: "a seat is sold at most once" is guaranteed by `SeatStore.update`, **not** by synchronization in domain code. If someone adds `synchronized` machinery to the domain, the build fails.

### Rule 7: `databaseCodeStaysInTheJdbcAdapter`

No class outside `infrastructure.jdbc` may depend on `java.sql..`, `javax.sql..`, `com.zaxxer..` (HikariCP) or `org.postgresql..`. This rule once failed for a good reason: the composition root (`Application`) had used the connection pool type directly. The fix moved pool creation inside `JdbcSeatStore.open(...)`.

## Try it yourself

1. `./mvnw -B test -Dtest=ArchitectureTest` runs only these rules.
2. In a scratch copy, add `import io.plainround.seatholds.interfaces.cli.Main;` and a use of it to a domain class, then run the test: `layersPointInwardOnly` fails and prints which class depends on which.
3. In a scratch copy, add `System.out.println("x");` to a domain class and run the test: `noPrintingOutsideTheInterfaces` fails.
4. `grep -c '@ArchTest' src/test/java/io/plainround/seatholds/architecture/ArchitectureTest.java` counts the rules: 7.
5. `ls target/classes/io/plainround/*/` shows the four layer folders ArchUnit reads (after a build).

## Mistakes people make

- **Rules that can never fail.** A pattern with a typo matches nothing; ArchUnit fails such rules on purpose, and so should you not disable that check.
- **Analyzing tests with the production rules,** which then complain about test helpers. Here tests are left out.
- **Only drawing the layers in a diagram.** A diagram drifts; this test does not.
- **Making rules so strict nobody can work.** Each rule here maps to a sentence in the lesson or the template.
- **Slow rule sets.** ArchUnit imports all classes once per test class, which takes some seconds (25 to 60 seconds on a very loaded machine); keep the rules in one class.

## Check yourself

1. *What does ArchUnit read: source or compiled classes?* Compiled classes.
2. *What does `..` mean in a package pattern?* The package and all packages below it.
3. *Which layer may nothing depend on?* `interfaces`.
4. *What happens if a rule matches no classes?* It fails, so a typo cannot hide a broken rule.
5. *Why check the layers in a test and not just in a diagram?* A test fails the build when someone breaks the rule; a diagram does not.

## Further reading

- ArchUnit User Guide (1.5.x): <https://www.archunit.org/userguide/html/000_Index.html>
- The library: <https://github.com/TNG/ArchUnit>
