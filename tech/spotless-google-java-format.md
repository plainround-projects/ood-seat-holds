# Spotless 3.10.4 and google-java-format 1.35.0

## What it is

A **formatter** rewrites source code into one agreed layout: indentation, line breaks, spaces around operators, the order of imports. **google-java-format** is a formatter for Java that follows Google's Java style: two-space indentation, a line limit of 100 characters, one blank line between members. **Spotless** is a Maven plugin that runs formatters on your files and can either **check** them (fail if anything would change) or **apply** them (rewrite the files).

## Why this repository uses it

The template requires "a formatter and a linter configured in the repository and run in CI". A formatter ends arguments about layout and keeps every diff about meaning, not whitespace. Because the formatter's output is the only accepted layout, all the Java in the three repositories looks the same.

## Where it appears

| Path | What it does with the formatter |
| --- | --- |
| `pom.xml` | Declares Spotless and the google-java-format version. |
| `Makefile` | `make lint` runs `spotless:check`; `make format` runs `spotless:apply`. |
| every `*.java` file | Is formatted by it (main and test). |
| `.github/workflows/ci.yml` | Runs `make check`, which includes the format check. |

## How it works underneath

Spotless finds all `.java` files, hands each one to the google-java-format library, which parses it into a tree and **prints** it again in the standard layout, ignoring how it was written before. In `check` mode Spotless compares the printed text to the file on disk; any difference fails the build and prints the first differing lines. In `apply` mode it writes the printed text over the file. The formatter changes only layout, never meaning. `removeUnusedImports` additionally deletes imports nothing uses.

## Walkthrough of this repository's use

```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>3.10.4</version>
  <configuration>
    <java>
      <googleJavaFormat>
        <version>${google-java-format.version}</version>
        <style>GOOGLE</style>
      </googleJavaFormat>
      <removeUnusedImports/>
    </java>
  </configuration>
</plugin>
```

- `<version>3.10.4</version>` is the Spotless Maven plugin.
- `<java>` is the section for Java files. Spotless can also format other kinds (this repository only configures Java).
- `<googleJavaFormat>` chooses the formatter. `<version>${google-java-format.version}</version>` is `1.35.0` (from the POM properties). `<style>GOOGLE</style>` selects Google's 2-space style, as opposed to the 4-space `AOSP` style.
- `<removeUnusedImports/>` is a second step: delete unused imports. (Checkstyle's `UnusedImports` rule then has nothing to report.)

In the `Makefile`:

```make
lint: ## format check, compiler lint (-Xlint:all -Werror), checkstyle
	$(MVN) spotless:check
```
```make
format: ## apply google-java-format
	$(MVN) spotless:apply
```

`spotless:check` is the gate (run by `make lint`, `make check` and CI). `spotless:apply` is the fixer you run when the gate fails.

What the formatter does to code you write, for example:

```java
// you write
record Money(BigDecimal amount) { Money { amount = amount.setScale(2); } }

// the formatter prints
record Money(BigDecimal amount) {
  Money {
    amount = amount.setScale(2);
  }
}
```

It also wraps lines at 100 characters, aligns continuation lines by four spaces, puts the `/** ... */` Javadoc text in the standard shape and sorts imports (static imports first, then others, in ASCII order).

## Try it yourself

1. `make lint` runs the check. No output means every file is formatted.
2. In a scratch copy, put two statements on one line in any `.java` file and run `./mvnw -q spotless:check`: it fails and shows the differences.
3. Run `make format` on the scratch copy: the file is rewritten and `git diff` shows only layout.
4. `./mvnw -q spotless:check` again: now it passes.
5. `grep -rn $'\t' src/main/java | head -2` prints nothing: no tabs in the Java sources.

## Mistakes people make

- **Formatting by hand** and fighting the tool. Run `make format`; do not try to win.
- **Mixing two formatters** (the editor's and Spotless), so each rewrites the other. Use only Spotless, or configure the editor to use google-java-format.
- **Running only `apply` locally** and never `check`. CI runs `check`, so an unformatted commit fails there.
- **Expecting it to catch style problems.** A formatter changes layout; missing Javadoc is checkstyle's job (`checkstyle.md`).
- **Upgrading google-java-format** without a re-format commit. A new version can print slightly different layout; pin it (it is `1.35.0` here) and upgrade deliberately.

## Check yourself

1. *What is the difference between `spotless:check` and `spotless:apply`?* `check` fails if files would change; `apply` rewrites the files.
2. *What does the `GOOGLE` style mean?* Two-space indentation and Google's layout rules (line limit 100).
3. *Does a formatter change what code does?* No, only how it looks.
4. *Why does CI run `check` and not `apply`?* CI must detect a problem, not hide it by rewriting files that no one will commit.
5. *Which tool checks for missing Javadoc?* Checkstyle, not the formatter.

## Further reading

- Spotless Maven plugin: <https://github.com/diffplug/spotless/tree/main/plugin-maven> (3.10.4)
- google-java-format: <https://github.com/google/google-java-format> (1.35.0)
- Google Java Style Guide: <https://google.github.io/styleguide/javaguide.html>
