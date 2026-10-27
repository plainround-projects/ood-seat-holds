# Maven Dependency Plugin 3.11.0

## What it is

The Dependency Plugin is a Maven plugin for **looking at and handling the libraries** a project uses. Its goals include `dependency:list` (print every library the build resolved), `dependency:tree` (print them as a tree showing who brought whom in) and `dependency:copy-dependencies` (copy the library jars into a folder). Library names here follow the Maven coordinates `group:artifact:type:version:scope` (`maven.md`).

## Why this repository uses it

Two jobs. The audit (`osv.md`) needs the complete list of libraries, and `dependency:list` produces it. And a plain Java program needs its libraries in one folder to start with `java -cp`, so `copy-dependencies` copies them to `target/lib` during packaging (the `Makefile` and the `Dockerfile` use that folder).

## Where it appears

| Path | What it does with the plugin |
| --- | --- |
| `Makefile` (`audit`) | `./mvnw -B -q dependency:list -DoutputFile=target/dependencies.txt -DappendOutput=false`. |
| `target/dependencies.txt` | The list it writes (not committed). |
| `pom.xml` | Binds `copy-dependencies` to the `package` phase. |
| `target/lib/` | The copied runtime jars (not committed). |
| `Makefile` (`run`, `serve`, ...) | Use `-cp "target/classes:target/lib/*"`. |
| `Dockerfile` | Copies `target/lib` into the final image. |

## How it works underneath

`dependency:list` asks Maven for the project's **resolved** dependencies: the libraries named in the POM plus the **transitive** ones (the libraries those libraries need), after conflicts are settled. Each line is `group:artifact:type:version:scope`. With `-DoutputFile=FILE` it writes to a file; `-DappendOutput=false` overwrites the file instead of adding to it.

`copy-dependencies` takes those same resolved jars from `~/.m2/repository` and copies them into the chosen folder. `includeScope` `runtime` limits it to what is needed to run (compile and runtime scopes, not test libraries).

## Walkthrough of this repository's use

```make
audit: ## check the resolved dependencies for known vulnerabilities (OSV database)
	$(MVN) dependency:list -DoutputFile=target/dependencies.txt -DappendOutput=false
	python3 scripts/audit.py
```

The first recipe line writes the list; the second reads it. `-q` (quiet) comes from `$(MVN)`. A line of the file looks like this:

```text
   org.junit.jupiter:junit-jupiter-api:jar:6.0.3:test
```

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

- `<phase>package</phase>`: run when Maven packages the project, right after the jar is built.
- `<goal>copy-dependencies</goal>`: the copying goal.
- `<includeScope>runtime</includeScope>`: only libraries needed at run time (HikariCP, the PostgreSQL driver, slf4j). Test libraries are not copied.
- `<outputDirectory>${project.build.directory}/lib</outputDirectory>`: `${project.build.directory}` is `target`, so the folder is `target/lib`.

After `make build` the folder holds jars such as `HikariCP-7.1.0.jar`, `postgresql-42.7.14.jar`, `slf4j-api-2.0.17.jar`, `slf4j-jdk14-2.0.17.jar` and `checker-qual-3.55.1.jar` (a small library the PostgreSQL driver needs).

## Try it yourself

1. `./mvnw -q dependency:list -DoutputFile=/tmp/deps.txt -DappendOutput=false && cat /tmp/deps.txt` writes and shows the list.
2. `./mvnw dependency:tree` shows the same libraries as a tree: indentation means "brought in by".
3. `./mvnw dependency:tree -Dincludes=org.junit.platform` shows only the matching branches.
4. `./mvnw -q dependency:list -DincludeScope=runtime -DoutputFile=/tmp/runtime.txt -DappendOutput=false && wc -l /tmp/runtime.txt` counts what the running program needs.
5. `ls target/lib` after `make build` shows the copied jars.

## Mistakes people make

- **Auditing only direct dependencies.** Use the resolved list (`dependency:list`), which includes transitive ones.
- **Forgetting `-DappendOutput=false`,** so the file grows with each run and old libraries linger.
- **Copying test libraries into the production image.** `includeScope` `runtime` avoids it.
- **Reading `dependency:tree` as the class path.** It shows the relationship, not the order Java searches.
- **Running `dependency:*` goals and expecting the enforcer to run.** Goals run alone skip the lifecycle (`maven-enforcer.md`).

## Check yourself

1. *What does `dependency:list` print?* Every library the build resolved, including transitive ones, as `group:artifact:type:version:scope`.
2. *Why `-DappendOutput=false`?* To overwrite the output file instead of appending.
3. *What is a transitive dependency?* A library needed by one of your libraries.
4. *What does `includeScope` `runtime` leave out?* Test-scope libraries.
5. *Where does the audit read the list from?* `target/dependencies.txt`.

## Further reading

- Maven Dependency Plugin 3.11.0: <https://maven.apache.org/plugins/maven-dependency-plugin/> (`list`: <https://maven.apache.org/plugins/maven-dependency-plugin/list-mojo.html>)
