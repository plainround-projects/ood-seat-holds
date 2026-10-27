# Maven 3.9.16 (`pom.xml`)

## What it is

Maven is a **build tool** for Java. A build tool is a program that turns source code into something you can run, and that runs the checks around it: compiling, testing, packaging. You tell Maven what your project is in one XML file called `pom.xml` (POM means Project Object Model). Maven reads the file and does the rest. This repository pins Maven **3.9.16** through the Maven wrapper (see `maven-wrapper.md`).

Maven works by **convention**. Java source goes in `src/main/java`, test code in `src/test/java`, and results land in `target/`. Because everybody follows the same layout, you do not write instructions for compiling each folder.

## Why this repository uses it

The lesson asks for plain Java with no framework. Java needs a tool to compile the code, download the test libraries and run the tests. Maven is the most widely used one, and every check this repository runs (compile with warnings as errors, format check, lint, tests, coverage gate, dependency audit) is a Maven **plugin** configured in one file. The `Makefile` then needs only a few short commands.

## Where it appears

| Path | What it does with Maven |
| --- | --- |
| `pom.xml` | The project description: name, Java version, libraries, plugins and their settings. |
| `mvnw`, `mvnw.cmd`, `.mvn/wrapper/` | The wrapper that downloads and runs the right Maven. See `maven-wrapper.md`. |
| `Makefile` | Calls `./mvnw` for build, test, lint, format and audit. |
| `config/checkstyle.xml` | A settings file the checkstyle plugin reads. |
| `target/` | Where Maven puts compiled classes, test reports and the jar. Not committed: `.gitignore` lists it. |
| `.github/workflows/ci.yml` | Runs the same `make` targets, which call Maven. |

## How it works underneath

Maven is a Java program. When you run `./mvnw verify` this happens:

1. The wrapper makes sure Maven 3.9.16 is on your disk, then starts it as a process.
2. Maven reads `pom.xml` and builds a model of the project in memory.
3. You named a **phase** (`verify`). Maven has a fixed list of phases, always in this order: `validate`, `compile`, `test`, `package`, `verify`, `install`, `deploy`. Running a phase runs every phase before it. So `verify` runs `validate`, `compile`, `test` and `package` first.
4. A phase does nothing by itself. **Plugins** do the work. A plugin is a Java library that offers **goals**, and the POM or Maven's defaults **bind** goals to phases. For example `maven-compiler-plugin:compile` is bound to the `compile` phase.
5. Plugins and libraries are **artifacts**: files (usually `.jar`) named by three **coordinates**: `groupId` (who made it), `artifactId` (what it is) and `version`. Maven downloads each artifact from **Maven Central** (the public repository at `repo.maven.apache.org`) the first time and keeps a copy in `~/.m2/repository`. Later builds reuse the copy, so only the first build needs the network.
6. Each library has a **scope**: `compile` (needed to build and run), `runtime` (needed only to run), `test` (needed only for tests). A test library never ends up in the packaged program.

Two kinds of command exist. `./mvnw verify` runs a **phase**. `./mvnw spotless:check` runs one **goal** of one plugin (`plugin-prefix:goal`) without the rest of the lifecycle.

## Walkthrough of this repository's use

### The coordinates and metadata

```xml
<groupId>io.plainround</groupId>
<artifactId>ood-seat-holds</artifactId>
<version>0.1.0</version>
<name>ood-seat-holds</name>
<description>The seat-hold core of an event ticketing system: a seat state machine behind ports, with sold-at-most-once enforced by the store.</description>
<packaging>jar</packaging>
```

- `groupId` `io.plainround` and `artifactId` `ood-seat-holds` plus `version` `0.1.0` name this project. They are the coordinates of the jar it produces.
- `name` and `description` are labels for humans.
- `packaging` `jar` says the result is a jar, a zip file of compiled classes. (`jar` is also the default.)

### Properties: names for values

```xml
<properties>
  <maven.compiler.release>21</maven.compiler.release>
  <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
  <main.class>io.plainround.seatholds.interfaces.cli.Main</main.class>
  <junit.version>6.0.3</junit.version>
  <assertj.version>3.27.7</assertj.version>
  <archunit.version>1.5.1</archunit.version>
  <jacoco.version>0.8.16</jacoco.version>
  <checkstyle.version>13.0.0</checkstyle.version>
  <google-java-format.version>1.35.0</google-java-format.version>
  <postgresql.version>42.7.14</postgresql.version>
  <hikari.version>7.1.0</hikari.version>
  <slf4j.version>2.0.17</slf4j.version>
  <testcontainers.version>1.21.4</testcontainers.version>
</properties>
```

A **property** is a named value. Elsewhere in the file `${junit.version}` is replaced by `6.0.3`. Putting versions here means each version is written once and is easy to find.

- `maven.compiler.release` `21`: compile for Java 21 (see `javac.md`).
- `project.build.sourceEncoding` `UTF-8`: read source files as UTF-8 text, so the result does not depend on the computer's language settings.
- `main.class`: the class with the `main` method. The jar plugin writes it into the jar so `java -jar` knows where to start.
- The `...version` entries pin each library and plugin tool to an exact version. Nothing floats to "latest", so a build today and a build next year use the same code.

### Dependencies: the libraries

```xml
<dependencies>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>${postgresql.version}</version>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>${hikari.version}</version>
  </dependency>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-api</artifactId>
    <version>${slf4j.version}</version>
  </dependency>
  <dependency>
    <groupId>org.slf4j</groupId>
    <artifactId>slf4j-jdk14</artifactId>
    <version>${slf4j.version}</version>
    <scope>runtime</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>postgresql</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.junit.platform</groupId>
    <artifactId>junit-platform-launcher</artifactId>
    <version>${junit.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <version>${assertj.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <version>${archunit.version}</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

Each `<dependency>` is one library, named by coordinates, with a scope.

- `postgresql` (scope `runtime`): the JDBC driver, the code that speaks PostgreSQL's network protocol. The program never names its classes; Java finds it at run time (see `jdbc.md`).
- `HikariCP`: the connection pool (see `hikaricp.md`). It is used by the code, so its scope is the default `compile`.
- `slf4j-api` and `slf4j-jdk14`: the logging facade HikariCP writes to, and the piece that forwards it to the Java logger (see `slf4j.md`).
- `org.testcontainers:postgresql` and `junit-jupiter` (scope `test`): start a throwaway PostgreSQL in Docker for the integration tests (see `testcontainers.md`).
- `junit-jupiter`, `junit-platform-launcher`, `assertj-core`, `archunit-junit5` (scope `test`): the test tools (`junit.md`, `assertj.md`, `archunit.md`).

### Dependency management: forcing one version

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <!-- One slf4j-api for HikariCP and Testcontainers, which ask for different lines. -->
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-api</artifactId>
      <version>${slf4j.version}</version>
    </dependency>
    <dependency>
      <!-- Testcontainers 1.21 brings 1.24.0, which has known advisories (make audit). -->
      <groupId>org.apache.commons</groupId>
      <artifactId>commons-compress</artifactId>
      <version>1.28.0</version>
    </dependency>
  </dependencies>
</dependencyManagement>
```

A library can depend on other libraries (**transitive dependencies**). Two of them can ask for different versions of the same third library. `dependencyManagement` says "whatever asks for it, use this version." Here it does two jobs, each explained by its comment: one `slf4j-api` for HikariCP and Testcontainers, and `commons-compress` 1.28.0 because the version Testcontainers brings (1.24.0) has known security advisories that `make audit` found.

### A profile: optional build settings

```xml
<profiles>
  <profile>
    <!-- make test-integration: the contract and race tests against PostgreSQL in a container. -->
    <id>integration</id>
    <build>
      <plugins>
        <plugin>
          <groupId>org.apache.maven.plugins</groupId>
          <artifactId>maven-failsafe-plugin</artifactId>
          <version>3.6.1</version>
          <executions>
            <execution>
              <goals>
                <goal>integration-test</goal>
                <goal>verify</goal>
              </goals>
            </execution>
          </executions>
        </plugin>
      </plugins>
    </build>
  </profile>
</profiles>
```

A **profile** is a block of settings that switches on only when you ask. `./mvnw -Pintegration verify` (`-P` means profile) turns on `integration`, which adds the failsafe plugin so tests whose names end in `IT` run (see `maven-failsafe.md`). Without `-P`, those tests do not run, so `make test` needs no Docker.

### Plugins: the build steps

Each `<plugin>` below is configured in `<build><plugins>`. The first entries only pin a version.

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-clean-plugin</artifactId>
  <version>3.5.0</version>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-resources-plugin</artifactId>
  <version>3.5.0</version>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-install-plugin</artifactId>
  <version>3.1.4</version>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-site-plugin</artifactId>
  <version>3.12.1</version>
</plugin>
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-deploy-plugin</artifactId>
  <version>3.1.4</version>
</plugin>
```

These are the plugins of Maven's built-in lifecycles: `maven-clean-plugin` (deletes `target/`), `maven-resources-plugin` (copies non-code files in the `process-resources` phase), `maven-install-plugin` (the `install` phase), `maven-site-plugin` (the separate `site` lifecycle) and `maven-deploy-plugin` (the `deploy` phase). Maven would use a built-in default version of each if the POM said nothing. The POM states them so that the enforcer rule `requirePluginVersions` (see `maven-enforcer.md`) can insist every plugin has an explicit version.

The plugins that matter have their own notes: `javac.md` (`maven-compiler-plugin`), `maven-surefire.md`, `jacoco.md`, `spotless-google-java-format.md`, `checkstyle.md`, `maven-enforcer.md`. The jar plugin is configured like this:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-jar-plugin</artifactId>
  <version>3.5.0</version>
  <configuration>
    <archive>
      <manifest>
        <mainClass>${main.class}</mainClass>
      </manifest>
    </archive>
  </configuration>
</plugin>
```

`manifest` / `mainClass` writes the entry `Main-Class: io.plainround.seatholds.interfaces.cli.Main` into the jar's `META-INF/MANIFEST.MF` file, so `java -jar target/ood-seat-holds-0.1.0.jar` starts the program.

### Copying the libraries next to the jar

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <version>3.11.0</version>
  <executions>
    <execution>
      <id>copy-runtime-libraries</id>
      <phase>package</phase>
      <goals>
        <goal>copy-dependencies</goal>
      </goals>
      <configuration>
        <includeScope>runtime</includeScope>
        <outputDirectory>${project.build.directory}/lib</outputDirectory>
      </configuration>
    </execution>
  </executions>
</plugin>
```

`copy-dependencies` copies every `runtime`-scope jar into `target/lib` during the `package` phase. A plain Java program does not carry its libraries inside its own jar, so `java -cp "target/classes:target/lib/*" ...` (used by the `Makefile` and the `Dockerfile`) needs them in one folder.

## Try it yourself

1. `./mvnw -v` prints the Maven version (3.9.16), the Java version and the operating system.
2. `./mvnw -q -DskipTests compile` (`-q` is quiet, `-D` sets a property, `skipTests` is read by the test plugin). With no output, it worked. Look in `target/classes` for the `.class` files.
3. `./mvnw dependency:tree` prints the libraries and the libraries they bring along, as an indented tree with scopes.
4. `./mvnw help:effective-pom | head -40` prints the POM after Maven applies its defaults, so you can see what Maven added that the file did not say.
5. `./mvnw -B verify`: the whole lifecycle with the tests. `-B` is batch mode (plain output for logs and CI).

## Mistakes people make

- **Letting versions float** (`LATEST`, ranges). The build then changes without a commit. Here every version is explicit and the enforcer fails the build on an unpinned plugin.
- **Putting a test library in `compile` scope,** so it ships inside the program. Here every test library says `<scope>test</scope>`.
- **Editing `target/` by hand.** It is rebuilt output; `make clean` removes it.
- **Two versions of one library on the class path,** which fails in confusing ways at run time. The enforcer rule `dependencyConvergence` fails the build when two paths ask for different versions.
- **Running Maven from a different folder.** Maven looks for `pom.xml` in the current folder. The `Makefile` always runs it from the repository root.

## Check yourself

1. *Which phases run when you run `./mvnw verify`?* All phases up to and including `verify`: `validate`, `compile`, `test`, `package`, `verify`.
2. *What are a library's coordinates?* `groupId`, `artifactId` and `version`.
3. *Why does `junit-jupiter` have `<scope>test</scope>`?* The program does not need it to run; only the tests do, so it stays out of the packaged program.
4. *What is the difference between `./mvnw verify` and `./mvnw spotless:check`?* The first runs a lifecycle phase (and everything before it); the second runs one goal of one plugin.
5. *Where does Maven keep downloaded libraries?* In `~/.m2/repository`; the first build downloads, later builds reuse them.

## Further reading

- Maven 3.9 documentation: <https://maven.apache.org/guides/getting-started/>
- The build lifecycle: <https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html>
- The POM reference: <https://maven.apache.org/pom.html>
- Dependency scopes and management: <https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html>
