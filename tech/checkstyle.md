# Checkstyle 13.0.0 (`maven-checkstyle-plugin` 3.6.0, `config/checkstyle.xml`)

## What it is

Checkstyle is a **linter** for Java: a tool that reads source code (without running it) and reports places that break a list of style and quality rules. A rule can say "no unused imports" or "every public method has documentation". Checkstyle runs inside Maven through a plugin, and the rules live in an XML file (`xml.md`).

## Why this repository uses it

The template asks for "docstrings or Javadoc on the public surface, no dead code, no TODOs left in" and a linter configured in the repository and run in CI. The compiler cannot check Javadoc or leftover `TODO` comments; checkstyle can. The formatter (`spotless-google-java-format.md`) fixes layout; checkstyle checks content rules a formatter cannot change.

## Where it appears

| Path | What it does with checkstyle |
| --- | --- |
| `config/checkstyle.xml` | The rule list. |
| `pom.xml` | Declares the plugin and the checkstyle engine version (13.0.0). |
| `Makefile` (`lint`) | Runs `./mvnw -B -q -DskipTests compile checkstyle:check`. |
| `.github/workflows/ci.yml` | Runs `make check`, which includes `lint`. |

## How it works underneath

`checkstyle:check` is a Maven goal. It loads the XML rule file, reads each `.java` file under `src/main/java` (not the tests: the plugin is told `includeTestSourceDirectory` false), builds a **syntax tree** of each file (a structured view of its classes, methods, statements) and **visits** the tree. Each rule is a small checker that reports a **violation** when it sees something it forbids. The plugin counts violations at or above a **severity** (here `warning`). If there is any, the goal fails and Maven stops with a message listing the file, line and rule. `failOnViolation` true and `violationSeverity` `warning` make every violation a failure.

Rules come in two groups. **File-level** checks run on each file as text (trailing spaces, a missing final newline, a banned word). **Tree-walker** checks run on the syntax tree, so they know what a class, a method or an import is.

## Walkthrough of this repository's use

### The plugin settings in `pom.xml`

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-checkstyle-plugin</artifactId>
  <version>3.6.0</version>
  <dependencies>
    <dependency>
      <groupId>com.puppycrawl.tools</groupId>
      <artifactId>checkstyle</artifactId>
      <version>${checkstyle.version}</version>
    </dependency>
  </dependencies>
  <configuration>
    <configLocation>config/checkstyle.xml</configLocation>
    <consoleOutput>true</consoleOutput>
    <failOnViolation>true</failOnViolation>
    <violationSeverity>warning</violationSeverity>
    <includeTestSourceDirectory>false</includeTestSourceDirectory>
  </configuration>
</plugin>
```

- The nested `<dependency>` pins the **checkstyle engine** (13.0.0), separate from the plugin (3.6.0) that runs it.
- `configLocation` is the rule file.
- `consoleOutput` prints violations to the terminal. `failOnViolation` stops the build on any. `violationSeverity` `warning` means warnings count (the file below marks every rule as `warning`).
- `includeTestSourceDirectory` `false`: the rules apply to production code only. Tests are checked by the compiler and the formatter.

### The rule file, top to bottom

```xml
<?xml version="1.0"?>
<!DOCTYPE module PUBLIC
    "-//Checkstyle//DTD Checkstyle Configuration 1.3//EN"
    "https://checkstyle.org/dtds/configuration_1_3.dtd">
<module name="Checker">
  <property name="severity" value="warning"/>
  <property name="charset" value="UTF-8"/>

  <module name="NewlineAtEndOfFile"/>
  <module name="RegexpSingleline">
    <property name="format" value="\s+$"/>
    <property name="message" value="Trailing whitespace"/>
  </module>
  <module name="RegexpSingleline">
    <property name="format" value="\b(TODO|FIXME|XXX)\b"/>
    <property name="message" value="No TODO markers: finish it or delete it"/>
  </module>

  <module name="TreeWalker">
    <module name="AvoidStarImport"/>
    <module name="UnusedImports"/>
    <module name="RedundantImport"/>
    <module name="ModifierOrder"/>
    <module name="RedundantModifier"/>
    <module name="NeedBraces"/>
    <module name="OneStatementPerLine"/>
    <module name="EmptyCatchBlock"/>
    <module name="EqualsHashCode"/>
    <module name="FinalClass"/>
    <module name="HideUtilityClassConstructor"/>
    <module name="InterfaceIsType"/>
    <module name="MissingOverride"/>
    <module name="UpperEll"/>
    <module name="ArrayTypeStyle"/>
    <module name="SimplifyBooleanExpression"/>
    <module name="SimplifyBooleanReturn"/>
    <module name="StringLiteralEquality"/>
    <module name="MissingJavadocType">
      <property name="scope" value="public"/>
    </module>
    <module name="MissingJavadocMethod">
      <property name="scope" value="public"/>
      <property name="tokens" value="METHOD_DEF, CTOR_DEF"/>
      <property name="allowedAnnotations" value="Override"/>
    </module>
  </module>
</module>
```

**Header.** The first lines are the XML prolog and a `DOCTYPE` that names the checkstyle configuration format (`xml.md`).

**`Checker`** is the outermost module. `severity` `warning` applies to every rule. `charset` `UTF-8` is how files are read.

**File-level rules:**

- `NewlineAtEndOfFile`: every file ends with a line break (Unix tools expect it; `.editorconfig` says the same).
- First `RegexpSingleline` with `format` `\s+$`: fail if any line ends with whitespace. `message` is the text shown.
- Second `RegexpSingleline` with `format` `\b(TODO|FIXME|XXX)\b`: fail on those marker words. `\b` is a word boundary, so `TODOS` would not match. This enforces "no TODOs left in" from the template: finish the work or delete the note.

**`TreeWalker`** holds the rules that understand Java:

- `AvoidStarImport`: no `import java.util.*;`. Name each class, so a reader sees where it comes from.
- `UnusedImports`, `RedundantImport`: no import that nothing uses, and no import of the same class twice or of a class in the same package.
- `ModifierOrder`: modifiers appear in the standard order (`public static final`, not `final static public`).
- `RedundantModifier`: no modifier that is already implied (for example `public` on an interface method).
- `NeedBraces`: an `if` or loop body always has `{ }`.
- `OneStatementPerLine`: no two statements on one line.
- `EmptyCatchBlock`: a `catch` must do something. Silently swallowing an exception hides problems.
- `EqualsHashCode`: a class that defines `equals` must define `hashCode` too (collections break otherwise).
- `FinalClass`: a class whose constructors are all private should be declared `final`.
- `HideUtilityClassConstructor`: a class with only static methods must not have a public constructor (nobody should create one).
- `InterfaceIsType`: an interface should define a type, not only constants.
- `MissingOverride`: a method that overrides another and has the `{@inheritDoc}` Javadoc tag must also carry `@Override`.
- `UpperEll`: write long numbers as `1L`, not `1l` (a lowercase L looks like a one).
- `ArrayTypeStyle`: write `String[] a`, not `String a[]`.
- `SimplifyBooleanExpression` and `SimplifyBooleanReturn`: no `x == true`, and no `if (c) return true; else return false;`.
- `StringLiteralEquality`: never compare strings with `==`; use `.equals`.
- `MissingJavadocType` with `scope` `public`: every public class, record or interface has a Javadoc comment (`/** ... */`).
- `MissingJavadocMethod` with `scope` `public`, `tokens` `METHOD_DEF, CTOR_DEF` and `allowedAnnotations` `Override`: every public method and constructor has Javadoc, except methods marked `@Override` (their documentation comes from the parent). That is the template's "Javadoc on the public surface".

A real effect in this repository: every public class such as `SeatHoldService` and every public method such as `SeatStore.update(...)` has a Javadoc comment saying what it does, takes and returns; `make lint` fails the moment one is missing.

## Try it yourself

1. `make lint` runs the format check and checkstyle. Silence means pass.
2. In a scratch copy, add `// TODO later` to a main source file and run `./mvnw -q checkstyle:check`: it fails and names the rule `RegexpSingleline`.
3. In a scratch copy, delete a `/** ... */` comment from a public method and run the same command: it fails on `MissingJavadocMethod`.
4. `./mvnw -q checkstyle:checkstyle` writes a report to `target/checkstyle-result.xml` even when nothing fails.
5. `grep -c '<module' config/checkstyle.xml` counts the rules.

## Mistakes people make

- **A rule file that is never run in CI.** Here `make check` includes it, and CI runs `make check`.
- **Too many rules at once on an old codebase.** This repository started with a small, agreed set and no violations.
- **Suppressing a violation** with a comment instead of fixing it. None is suppressed here.
- **Confusing the plugin version with the engine version.** They are separate lines in the POM (3.6.0 and 13.0.0).
- **Expecting checkstyle to format code.** It only reports. Spotless fixes layout.

## Check yourself

1. *Does checkstyle run the program?* No, it reads source code and reports rule violations.
2. *Which rule enforces "no TODOs left in"?* The `RegexpSingleline` rule with `\b(TODO|FIXME|XXX)\b`.
3. *What does `MissingJavadocMethod` with `scope` `public` check?* That every public method and constructor has a Javadoc comment (except `@Override` methods).
4. *Why are tests excluded?* `includeTestSourceDirectory` is `false`; the rules target production code.
5. *What happens when a rule is violated?* `failOnViolation` makes `checkstyle:check` fail and the build stop.

## Further reading

- Checkstyle 13.0.0 documentation: <https://checkstyle.sourceforge.io/> (check list: <https://checkstyle.sourceforge.io/checks.html>)
- Maven Checkstyle Plugin 3.6.0: <https://maven.apache.org/plugins/maven-checkstyle-plugin/>
