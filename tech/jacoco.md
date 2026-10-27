# JaCoCo 0.8.16 (code coverage)

## What it is

**Code coverage** answers: "while the tests ran, which lines of my code actually executed?" JaCoCo (Java Code Coverage) is a tool that measures it. It watches the program while the tests run and records which instructions, lines and branches (the two sides of an `if`) were used. Then it can print a report and **fail the build** if the numbers are too low. Coverage cannot prove tests are good, but low coverage proves some code is untested.

## Why this repository uses it

The template says "coverage reported, with the domain at or near full coverage". The domain (the business rules) is where bugs cost the most, so the build fails if the domain or application packages fall under 95 percent of lines or 90 percent of branches. That keeps "tests read as the lesson's requirements" from quietly rotting.

## Where it appears

| Path | What it does with JaCoCo |
| --- | --- |
| `pom.xml` | Declares the plugin, three executions and the coverage rule. |
| `target/jacoco.exec` | The raw recording the tests produced (binary, not committed). |
| `target/site/jacoco/` | The HTML and CSV reports (not committed). |
| `Makefile` (`test`) | `./mvnw -B verify` runs the tests and then the check. |
| `.github/workflows/ci.yml` | Runs the same through `make check`. |

## How it works underneath

JaCoCo works with a **Java agent**: a small piece of code the JVM loads before your program that can change classes as they load. The agent adds counters to the bytecode (`javac.md`) of your classes. The JVM that runs the tests uses it because Maven passes a flag `-javaagent:.../jacocoagent.jar=...` to the test process. While tests run, each counter is bumped when its piece of code runs. When the test JVM ends, the agent writes all counters to `target/jacoco.exec`.

Later, in the `verify` phase, the `report` goal reads that file together with the compiled classes and writes reports, and the `check` goal compares the numbers with the rules and fails if any is below its minimum.

Terms: a **counter** type such as `LINE` or `BRANCH`; a **covered ratio** is covered divided by total. A **branch** is one outcome of a decision: an `if` has two. JaCoCo ignores some generated code in records (the automatic `equals`, `hashCode`, `toString`, accessors) so they do not count against you.

## Walkthrough of this repository's use

```xml
<plugin>
  <groupId>org.jacoco</groupId>
  <artifactId>jacoco-maven-plugin</artifactId>
  <version>${jacoco.version}</version>
  <executions>
    <execution>
      <id>prepare-agent</id>
      <goals>
        <goal>prepare-agent</goal>
      </goals>
    </execution>
    <execution>
      <id>report</id>
      <phase>verify</phase>
      <goals>
        <goal>report</goal>
      </goals>
    </execution>
    <execution>
      <id>check-domain-and-application</id>
      <phase>verify</phase>
      <goals>
        <goal>check</goal>
      </goals>
      <configuration>
        <rules>
          <rule>
            <element>PACKAGE</element>
            <includes>
              <include>io.plainround.seatholds.domain</include>
              <include>io.plainround.seatholds.application</include>
              <include>io.plainround.seatholds.application.simulation</include>
            </includes>
            <limits>
              <limit>
                <counter>LINE</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.95</minimum>
              </limit>
              <limit>
                <counter>BRANCH</counter>
                <value>COVEREDRATIO</value>
                <minimum>0.90</minimum>
              </limit>
            </limits>
          </rule>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- `<version>${jacoco.version}</version>` is 0.8.16, from the properties.
- **Execution `prepare-agent`** (goal `prepare-agent`, default phase `initialize`) runs before the tests. It sets a Maven property called `argLine` to the `-javaagent:...` flag. The test plugin (`maven-surefire.md`) puts `argLine` on the command line of the JVM it starts, which is how the agent gets attached.
- **Execution `report`** (goal `report`, phase `verify`) turns `target/jacoco.exec` into the HTML and CSV reports in `target/site/jacoco/`.
- **Execution `check-domain-and-application`** (goal `check`, phase `verify`) enforces the rule:
  - `<element>PACKAGE</element>`: apply the limits to each **package** separately.
  - The `<includes>` list names the packages that must meet them: `io.plainround.seatholds.domain` and `io.plainround.seatholds.application`, and `io.plainround.seatholds.application.simulation` (the race drivers, which are tested like the rest). Other packages (infrastructure, interfaces) are measured but not gated.
  - Limit 1: counter `LINE`, value `COVEREDRATIO`, `minimum` `0.95`: at least 95 percent of lines run.
  - Limit 2: counter `BRANCH`, `COVEREDRATIO`, `minimum` `0.90`: at least 90 percent of branches run.
  - If a package falls short, Maven stops with "Coverage checks have not been met".

A real case from this repository: the first full run failed with `Rule violated for package ...application.simulation: lines covered ratio is 0.82, but expected minimum is 0.95`. The race drivers' error paths (an interrupted thread, a failing store) had no tests. The fix was a test class that makes those paths happen (`SimulationEdgeCasesTest`), which brought the number up and also found that one branch could not be tested without a short timeout parameter, so the code gained one.

## Try it yourself

1. `./mvnw -B verify` runs everything; the last lines say `All coverage checks have been met.`
2. Open `target/site/jacoco/index.html` in a browser: a table of packages with bars; click a package, a class, then a method to see lines in green (run) and red (never run).
3. `head -3 target/site/jacoco/jacoco.csv` shows the same numbers as a table: `LINE_MISSED`, `LINE_COVERED`, `BRANCH_MISSED`, `BRANCH_COVERED`.
4. In a scratch copy, delete a test class and run `./mvnw verify`: the check may fail with the ratio it computed.
5. `ls -l target/jacoco.exec` shows the binary recording; `./mvnw clean` removes it.

## Mistakes people make

- **Treating 100 percent as proof of quality.** Executing a line is not the same as checking its result. The tests here assert on results.
- **Gating total coverage** so a well-tested domain hides an untested adapter. Here the gate names the packages that matter.
- **Lowering the number to get green.** The rule is a promise; fix the tests instead.
- **Forgetting that the agent is attached by `argLine`.** Setting your own `argLine` without including the JaCoCo one disables coverage silently.
- **Reading line coverage without branch coverage.** A line with `if (a || b)` can be run without trying both outcomes.

## Check yourself

1. *How does JaCoCo see which code ran?* A Java agent adds counters to classes as they load; the counters are saved to `jacoco.exec` when the test JVM ends.
2. *Which phase fails the build on low coverage?* `verify`, in the `check` goal.
3. *What is a branch?* One outcome of a decision, such as the true or false side of an `if`.
4. *Why is the gate limited to the domain and application packages?* They hold the business rules; the gate applies where bugs cost the most.
5. *What does `argLine` do?* It carries the `-javaagent` flag from JaCoCo to the test JVM.

## Further reading

- JaCoCo 0.8.16 documentation: <https://www.jacoco.org/jacoco/trunk/doc/> (Maven plugin: <https://www.jacoco.org/jacoco/trunk/doc/maven.html>)
- Coverage counters explained: <https://www.jacoco.org/jacoco/trunk/doc/counters.html>
