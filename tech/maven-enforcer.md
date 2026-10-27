# Maven Enforcer Plugin 3.6.3

## What it is

The Enforcer is a Maven plugin that **checks rules about the build itself** and stops the build when one is broken. It does not look at your code. It looks at your setup: the Maven version, the Java version, whether every plugin has a version, whether two libraries ask for different versions of a third.

## Why this repository uses it

The template says "dependencies pinned" and the build must be repeatable. Maven would otherwise quietly use a default plugin version or pick one of two conflicting library versions. Enforcer turns each quiet choice into a loud failure.

## Where it appears

| Path | What it does with Enforcer |
| --- | --- |
| `pom.xml` | Declares the plugin and its five rules, bound to the build so they run every time. |

## How it works underneath

The plugin's `enforce` goal is attached to a lifecycle phase (`validate` by default), so it runs first on every build, before compiling. It evaluates each **rule** in the `<rules>` list. A rule that fails gives an error message and Maven stops. Rules inspect Maven's model of the project: its version, its dependency tree and the list of plugins that will run (including the ones Maven adds by default).

## Walkthrough of this repository's use

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-enforcer-plugin</artifactId>
  <version>3.6.3</version>
  <executions>
    <execution>
      <id>enforce-build-rules</id>
      <goals>
        <goal>enforce</goal>
      </goals>
      <configuration>
        <rules>
          <requireMavenVersion>
            <version>[3.9.0,)</version>
          </requireMavenVersion>
          <requireJavaVersion>
            <version>[21,)</version>
          </requireJavaVersion>
          <requirePluginVersions/>
          <dependencyConvergence/>
          <banDuplicatePomDependencyVersions/>
        </rules>
      </configuration>
    </execution>
  </executions>
</plugin>
```

- `<execution>` with `<id>enforce-build-rules</id>` and `<goal>enforce</goal>` attaches the goal to its default phase.
- `requireMavenVersion` `[3.9.0,)`: Maven 3.9.0 or newer. In version ranges `[` includes the end and `)` leaves the top open, so `[3.9.0,)` means "3.9.0 and everything above". (The wrapper pins 3.9.16, `maven-wrapper.md`.)
- `requireJavaVersion` `[21,)`: Java 21 or newer.
- `requirePluginVersions`: every plugin the build uses, including the built-in lifecycle plugins, must have an explicit version. This is why the POM lists `maven-clean-plugin`, `maven-site-plugin` and `maven-deploy-plugin` even though nothing in the lesson uses them: Maven would otherwise fill in a default and the rule fails with "Some plugins are missing valid versions" (this happened while building the repository, and the fix was to pin them).
- `dependencyConvergence`: if two different paths in the dependency tree ask for different versions of the same library, the build fails and prints both paths. This rule found a real conflict here: HikariCP asked for `slf4j-api` 2.0.17 while Testcontainers asked for an older line. The fix is the `dependencyManagement` entry in the POM (`maven.md`).
- `banDuplicatePomDependencyVersions`: the same dependency must not be listed twice in the POM with different versions.

## Try it yourself

1. `./mvnw validate` runs the enforcer (first lifecycle phase). No errors means all rules passed.
2. In a scratch copy, delete the `<version>` line of one plugin and run `./mvnw validate`: it fails with "Some plugins are missing valid versions".
3. `./mvnw dependency:tree | head -20` shows the tree `dependencyConvergence` inspects.
4. `./mvnw -v` shows the Maven and Java versions the first two rules compare against.
5. `./mvnw enforcer:help` lists the plugin's own documentation.

## Mistakes people make

- **Letting Maven choose plugin versions.** The build then changes when Maven is upgraded.
- **Silencing `dependencyConvergence` by excluding libraries** without understanding the conflict. Pin one version in `dependencyManagement` and run the tests.
- **Running the enforcer only in CI.** It is in the normal lifecycle, so it runs for everyone.
- **Over-tight Java ranges** like `[21,22)`, which would reject the Java 25 this repository is also tested with.
- **Forgetting that `dependency:list` and similar goals skip the lifecycle,** so they do not run the enforcer. Run `./mvnw validate` to check.

## Check yourself

1. *Does the Enforcer check source code?* No, it checks build rules: versions, plugins, dependencies.
2. *What does `[3.9.0,)` mean?* 3.9.0 or any higher version.
3. *Why must every plugin have a version?* So builds are repeatable; a default can change when Maven changes.
4. *What does `dependencyConvergence` catch?* Two dependency paths that need different versions of one library.
5. *When does it run?* At the `validate` phase, before compilation, on every build.

## Further reading

- Maven Enforcer Plugin 3.6.3: <https://maven.apache.org/enforcer/maven-enforcer-plugin/>
- Built-in rules: <https://maven.apache.org/enforcer/enforcer-rules/index.html>
