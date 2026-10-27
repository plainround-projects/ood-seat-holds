# SLF4J 2.0.17 (`slf4j-api` and `slf4j-jdk14`)

## What it is

SLF4J (Simple Logging Facade for Java) is a thin layer that libraries use to write log messages without choosing a logging system. A **facade** is a front door: the library calls `slf4j-api`, and one **binding** library on the class path decides where the messages go. This repository uses the binding `slf4j-jdk14`, which sends everything to Java's built-in logging (`java.util.logging`, `java-logging.md`). Version **2.0.17**.

## Why this repository uses it

HikariCP (`hikaricp.md`) and Testcontainers (`testcontainers.md`) both write log messages through SLF4J. Without a binding SLF4J prints a warning and drops the messages. With `slf4j-jdk14` their lines come out in the same stream and the same `ts=... level=...` format as this repository's own log lines. The program's own code does not call SLF4J: it uses `System.Logger`.

## Where it appears

| Path | What it does with SLF4J |
| --- | --- |
| `pom.xml` | Declares `slf4j-api` 2.0.17 (compile) and `slf4j-jdk14` 2.0.17 (runtime), and pins `slf4j-api` in `dependencyManagement`. |
| HikariCP, Testcontainers (inside their jars) | Call the SLF4J API. |
| Run time | The binding forwards to `java.util.logging`, configured by `Logging.java`. |

## How it works underneath

At start-up `slf4j-api` looks for exactly one binding on the class path (a jar that registers itself as an SLF4J provider). It routes every logger call to it. Two bindings would conflict, none would drop messages. `slf4j-jdk14` converts each SLF4J level to the matching `java.util.logging` level (`INFO` stays `INFO`, `DEBUG` becomes `FINE`).

Libraries are built against a particular SLF4J **line** (1.7 or 2.x). HikariCP 7.1 asks for 2.0.17; Testcontainers 1.21 brings an older line. Two versions on the class path break things, so the build forces one.

## Walkthrough of this repository's use

```xml
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

`dependencyManagement` says: whatever library asks for `slf4j-api`, use `${slf4j.version}` (2.0.17). The Maven Enforcer rule `dependencyConvergence` had failed the build with "Dependency convergence error for org.slf4j:slf4j-api:jar:2.0.17 ... and ... 2.0.20" when the versions disagreed (`maven-enforcer.md`).

```xml
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

<dependencies>
  <dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>${postgresql.version}</version>
    <scope>runtime</scope>
  </dependency>
```

The two dependencies: the API (needed to compile against HikariCP) and the binding with scope `runtime` (needed only to run).

Result at run time: HikariCP's start-up messages appear in the same format as the program's own lines:

```text
ts=2026-10-27T02:14:09.231+0000 level=INFO seat-holds - Starting...
ts=2026-10-27T02:14:11.604+0000 level=INFO seat-holds - Start completed.
```

## Try it yourself

1. `./mvnw dependency:tree | grep slf4j` shows both artifacts at 2.0.17.
2. `make up && make race RUNS=1 STORE=jdbc 2>&1 | head -5` shows the pool's `Starting...` lines in the repository's log format.
3. In a scratch copy, remove the `slf4j-jdk14` dependency and run the same `make race` command: SLF4J prints a "no provider found" notice and Hikari's lines disappear.
4. `./mvnw dependency:tree -Dverbose | grep slf4j-api` shows which libraries ask for which version.
5. `grep -rn "slf4j" src/main` prints nothing: the program's own code does not use it.

## Mistakes people make

- **Two bindings** (for example `slf4j-jdk14` and another) on one class path.
- **No binding,** and then wondering where a library's log lines went.
- **Mixed API and binding versions.** Keep both on the same version.
- **Using SLF4J in domain code** and spreading a library through the layers. This repository keeps the domain free of it.
- **Excluding the conflict instead of pinning.** Forcing one version in `dependencyManagement` is explicit and checked.

## Check yourself

1. *What is a facade?* A front door that code calls without knowing the real system behind it.
2. *Which binding does this repository use, and where does it send messages?* `slf4j-jdk14`, to `java.util.logging`.
3. *Why is the binding `runtime` scope?* The code compiles against the API only; the binding is needed only to run.
4. *Who calls SLF4J here?* HikariCP and Testcontainers, not this program's own code.
5. *Why pin `slf4j-api` in `dependencyManagement`?* So both libraries get one version and the convergence check passes.

## Further reading

- SLF4J manual: <https://www.slf4j.org/manual.html> (2.0.x); bindings: <https://www.slf4j.org/manual.html#swapping>
