# Docker and the `Dockerfile`

## What it is

Docker packages a program together with everything it needs to run (the Java runtime, its files, its settings) into an **image**. An image is a read-only template. When you start an image you get a **container**: a running, isolated process that sees only the files of the image, not the rest of your computer. A `Dockerfile` is the recipe: a text file of instructions that tell Docker how to build the image, one instruction at a time. Each instruction adds a **layer** (a saved set of file changes). Docker keeps layers in a **cache**, so a rebuild repeats only the steps after the first change.

## Why this repository uses it

Template and lesson rules say a Java repository carries a multi-stage `Dockerfile` with a non-root user and a pinned base image. It gives a reader a way to run the program with no Java installed (`docker run`), and it shows the usual way to ship a Java program: build with a full JDK, run with a small JRE.
This repository's program is a command-line tool, not a server, so the image runs the scenario and exits; it reads its settings from environment variables, so the same image can point at a database.

## Where it appears

| Path | What it does with Docker |
| --- | --- |
| `Dockerfile` | The two-stage recipe. |
| `.dockerignore` | Lists files that are never sent to Docker when building. |
| `Makefile` (`docker-build`) | Runs `docker build -t ood-seat-holds:dev .`. |
| `docker-compose.yml` | Runs PostgreSQL (see `docker-compose.md`). |
| `src/test/.../support/PostgresFixture.java` | Starts a container from Java code through Testcontainers (`testcontainers.md`). |

## How it works underneath

`docker build -t ood-seat-holds:dev .` sends the **build context** (the current folder `.`, minus anything in `.dockerignore`) to the Docker engine, a background program. The engine reads the `Dockerfile` and runs each instruction in order. For `RUN`, it starts a temporary container from the layers so far, runs the command in it, and saves the changed files as a new layer. `-t NAME:dev` gives the finished image a **tag** (a name and version label).

A **multi-stage build** has more than one `FROM`. Each `FROM` starts a new stage from a new base image. Later stages can copy files out of earlier stages (`COPY --from=build`). Only the last stage becomes the final image, so the big JDK and the Maven download cache of the first stage are left behind.

When you run `docker run --rm ood-seat-holds:dev`, Docker creates a container from the image, starts the **entrypoint** command inside it, and removes the container when the process ends (`--rm`). The container has its own file system and its own process list, and it runs as the user the image names.

## Walkthrough of this repository's use

### The build stage

```docker
# Build stage: compile and package with the Maven wrapper; tests run in CI, not here.
FROM eclipse-temurin:21.0.9_10-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY config config
COPY src src
# Maven Central occasionally drops a connection; retry the whole step a few times before failing.
RUN for attempt in 1 2 3 4; do ./mvnw -B -q -DskipTests package && exit 0; echo "package failed (attempt $attempt), retrying"; sleep 10; done; exit 1
```

- The first line is a comment (`#`).
- `FROM eclipse-temurin:21.0.9_10-jdk AS build` starts from the base image `eclipse-temurin`, tag `21.0.9_10-jdk`: Eclipse Temurin's build of OpenJDK **21.0.9** with the full JDK (the compiler and tools). The exact tag is the **pin**: a build next year starts from the same Java. `AS build` names the stage.
- `WORKDIR /src` sets the folder for the next instructions (created if missing).
- `COPY .mvn .mvn` and `COPY mvnw pom.xml ./` copy the Maven wrapper and the project file in first. `COPY config config` copies the checkstyle settings and `COPY src src` copies the code. Copying the rarely changed files before the often changed `src` keeps the earlier layers valid in the cache.
- The comment explains the `RUN` below it, which is the shell retry loop explained in `bash-and-shell.md`: up to four tries of `./mvnw -B -q -DskipTests package`, ten seconds apart. `package` compiles and builds the jar; `-DskipTests` leaves the tests to CI.

### The run stage

```docker
# Run stage: a JRE only and a non-root user. The container takes its settings from the environment.
FROM eclipse-temurin:21.0.9_10-jre
RUN useradd --system --uid 10001 --no-create-home seatholds
WORKDIR /app
COPY --from=build /src/target/ood-seat-holds-0.1.0.jar app.jar
COPY --from=build /src/target/lib lib
USER seatholds
ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "io.plainround.seatholds.interfaces.cli.Main"]
CMD ["scenario"]
```

- `FROM eclipse-temurin:21.0.9_10-jre` starts a new stage from the **JRE** (the Java runtime only, no compiler). It is smaller and has fewer tools an attacker could use.
- `RUN useradd --system --uid 10001 --no-create-home seatholds` creates a user with no password and no home folder. By default a container runs as **root**, the all-powerful user; a program that is attacked then has all that power inside the container. A fixed numeric id (`10001`) lets orchestration tools check "does not run as root".
- `WORKDIR /app` sets the folder.
- Two `COPY --from=build` lines copy the jar and the `lib` folder of libraries that the Maven dependency plugin collected (`maven.md`).
- `USER seatholds` makes the container run as that user.
- `ENTRYPOINT ["java", "-cp", "app.jar:lib/*", "io.plainround.seatholds.interfaces.cli.Main"]` runs the program with a class path made of the jar and every jar in `lib`. The list form (**exec form**) starts `java` directly, with no shell in between, so signals such as Ctrl-C and `docker stop`'s SIGTERM reach Java.
- `CMD ["scenario"]` supplies the default argument. `docker run IMAGE health` replaces `scenario` with `health`.

### `.dockerignore`

```text
target
.git
.github
```

Files listed here are left out of the build context: `target` (a previous build's output, which the build recreates), `.git` (history, large and not needed) and `.github` (CI settings). A smaller context uploads faster and avoids copying stale output into the image.

## Try it yourself

1. `docker build -t ood-seat-holds:dev .` builds the image. The first build downloads Maven and the libraries inside the build stage and takes minutes; a second build with no changes finishes in seconds because every layer is cached.
2. `docker run --rm ood-seat-holds:dev` runs the scenario and prints its output.
3. `docker run --rm --entrypoint id ood-seat-holds:dev` prints `uid=10001(...)`: the container does not run as root.
4. `docker image ls ood-seat-holds` shows the image and its size. Compare it with the size of `eclipse-temurin:21.0.9_10-jdk` (`docker image ls eclipse-temurin`): the run image is smaller.
5. `docker history ood-seat-holds:dev` lists the layers, one per instruction.

## Mistakes people make

- **Running as root** in the final image. Here a `USER` instruction switches to a numeric non-root user.
- **Shipping the build tools.** Without a second stage the image would carry the JDK, Maven and its cache. Here the run stage copies only the jar and libraries.
- **Using the `latest` tag.** It changes without notice. Here both images use an exact version tag.
- **Copying everything before installing dependencies,** so each code change reruns the slow download. Here the unchanging files are copied first.
- **Shell-form `ENTRYPOINT`** (`ENTRYPOINT java -jar app.jar`), which runs under `sh -c` and swallows signals. Here the exec form (a JSON list) is used.

## Check yourself

1. *What is the difference between an image and a container?* An image is a read-only template; a container is a running instance of it.
2. *Why two `FROM` lines?* The first stage builds with the full JDK; the second stage keeps only what is needed to run, so the final image is smaller and safer.
3. *What does `COPY --from=build` do?* Copies files from the earlier stage named `build` into the current stage.
4. *Why a non-root user?* If the program is compromised, it has fewer rights inside the container.
5. *What is the difference between `ENTRYPOINT` and `CMD`?* `ENTRYPOINT` is the command that always runs; `CMD` gives its default arguments, which `docker run IMAGE args` can replace.

## Further reading

- Dockerfile reference: <https://docs.docker.com/reference/dockerfile/>
- Multi-stage builds: <https://docs.docker.com/build/building/multi-stage/>
- Docker Engine 24 (the version this was tested with): <https://docs.docker.com/engine/release-notes/24.0/>
- Eclipse Temurin images (`21.0.9_10`): <https://hub.docker.com/_/eclipse-temurin>
