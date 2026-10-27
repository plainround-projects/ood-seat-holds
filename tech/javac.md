# The Java compiler, `javac` (and `maven-compiler-plugin` 3.15.0)

## What it is

`javac` is the program that turns Java source code (`.java` files, text people write) into **class files** (`.class` files, **bytecode**: compact instructions for the Java Virtual Machine, or **JVM**). The JVM is the program, started by the command `java`, that runs bytecode. `maven-compiler-plugin` is the Maven plugin that calls `javac` for you with the right settings, on every file under `src/main/java` and `src/test/java`. The compiler is also this repository's **type check**: Java checks every type at compile time, so a wrong type or a missing case is an error before anything runs.

## Why this repository uses it

The template says "typed code" and "a linter configured and run in CI". For Java, the strictest first line of defense is the compiler itself with **all warnings switched on and treated as errors**. The repository also compiles for Java **21** even though it is built and tested on Java 25: the lessons are written for Java 21, and code compiled with `--release 21` cannot accidentally use a newer standard library method.

## Where it appears

| Path | What it does with `javac` |
| --- | --- |
| `pom.xml` (`maven-compiler-plugin`) | Chooses the Java release and the warning flags. |
| `src/main/java/` | The source `javac` compiles into `target/classes/`. |
| `src/test/java/` | The tests, compiled into `target/test-classes/`. |
| `target/classes/` | The result: one `.class` file per Java class, record or interface. |
| `Makefile` | `build`, `run` and `lint` run Maven, which runs the compiler. `run` then starts the compiled code with `java -cp target/classes ...`. |
| `src/test/java/.../BookResultExhaustivenessTest.java` | Not used. |

## How it works underneath

When Maven reaches the `compile` phase it:

1. Collects the list of `.java` files in `src/main/java`.
2. Starts `javac` with those files, the class path (the compiled libraries it needs) and flags.
3. `javac` **parses** each file, **checks** names and types (using the standard library of the chosen release), and writes `.class` files into `target/classes`, in folders matching the package names: `io/plainround/.../Main.class`.
4. If `javac` reports an **error**, no build continues. A **warning** is a message about code that compiles but looks risky.

Then `java -cp target/classes io.plainround.seatholds.interfaces.cli.Main` starts a JVM. The JVM finds `Main.class` on the **class path**, loads it, and runs its `main` method. It loads other classes the first time they are used.

`--release 21` means two things: compile as if the language is Java 21, **and** only allow the standard-library API that exists in Java 21. This is stricter than the older `-source 21 -target 21` pair, which did not check the library.

## Walkthrough of this repository's use

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.15.0</version>
  <configuration>
    <release>${maven.compiler.release}</release>
    <showWarnings>true</showWarnings>
    <compilerArgs>
      <arg>-Xlint:all</arg>
      <arg>-Werror</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

- `<version>3.15.0</version>` pins the plugin.
- `<release>${maven.compiler.release}</release>` reads the property `21` from the top of the POM: the `--release 21` flag.
- `<showWarnings>true</showWarnings>` makes Maven print the compiler's warnings (it hides some by default).
- `<compilerArgs>` passes extra flags straight to `javac`:
  - `-Xlint:all` turns on **every** optional warning category: unchecked casts, deprecated calls, a resource that is never used, a missing `serialVersionUID`, fall-through in a `switch`, and more.
  - `-Werror` turns every warning into an error. A single warning fails the build, so warnings cannot pile up.

A real example from this repository's history: `-Xlint:all -Werror` stopped the build on a `try (SomeResource x = ...)` whose variable was never used in the body (the `try` lint category: "auto-closeable resource ... is never referenced in body of corresponding try statement"). The code was rewritten to close the resource in a `finally` block instead. The compiler pointed at a real design smell.

The compiler is also what makes two of the central ideas of this repository real:

- A `switch` over a **sealed** type with no `default` branch must cover every permitted subtype or it does not compile (`java.md`).
- A **record** with a compact constructor compiles into a class with the validation inside it, so no object exists in an invalid state.

## Try it yourself

1. `./mvnw -q -DskipTests compile` then `ls target/classes/io/plainround` shows the compiled packages.
2. `javap -cp target/classes io.plainround.seatholds.domain.SeatTransitions` (`javap` is a JDK tool) prints the methods the compiled class has; add `-c` to see the bytecode.
3. Break it on purpose, in a scratch copy of the repository: add the line `java.util.List raw = new java.util.ArrayList();` inside any method and run `make build`. With `-Xlint:all -Werror` the build fails on a raw-type warning. Undo the change.
4. `java -version` and `javac -version` print the JDK versions (Java 25 here, compiling for 21).
5. `file target/classes/io/plainround/*/interfaces/cli/Main.class` says `compiled Java class data, version 65.0`: class file version 65 is Java 21 (Java 25 would be 69).

## Mistakes people make

- **Ignoring warnings** until there are hundreds. Here `-Werror` makes the first one fail the build.
- **Compiling with a newer JDK without `--release`** and then running on an older Java: `NoSuchMethodError` at run time. `--release 21` prevents it.
- **Mixing up the compiler and the runtime.** `javac` makes `.class` files; `java` runs them. Having a JRE without a JDK means no `javac`.
- **Stale classes.** After renaming a file, old `.class` files can linger. `make clean` removes `target/`.
- **Silencing a warning with an annotation** instead of fixing it. This repository suppresses none.

## Check yourself

1. *What does `javac` produce?* `.class` files containing bytecode.
2. *What does `-Werror` do?* Turns every compiler warning into an error, so the build fails.
3. *What does `--release 21` guarantee beyond the language level?* The code uses only standard-library methods that exist in Java 21.
4. *Which program runs bytecode?* The JVM, started with `java`.
5. *Why is the compiler called this repository's type check?* Java checks all types at compile time, including that a `switch` over a sealed type is complete.

## Further reading

- `javac` (Java 21 tool reference): <https://docs.oracle.com/en/java/javase/21/docs/specs/man/javac.html>
- `-Xlint` categories: same page, option `-Xlint`
- Maven Compiler Plugin 3.15.0: <https://maven.apache.org/plugins/maven-compiler-plugin/>
