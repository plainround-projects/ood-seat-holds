# Maven Wrapper (`mvnw`, `.mvn/wrapper/`)

## What it is

The Maven Wrapper is a small shell script (`mvnw`) plus one settings file (`.mvn/wrapper/maven-wrapper.properties`) that you commit next to your code. When you run `./mvnw`, the script finds the Maven version the project wants, downloads it if it is missing, and runs it. This repository uses wrapper version **3.3.4** in the `only-script` style: no extra jar, only the script.

## Why this repository uses it

The lesson says a reader should only need `make run` and `make test`. If the reader had to install Maven first, with the right version, that promise would break. The wrapper means the only prerequisites are a Java JDK and `make`. It also pins Maven itself: every machine and the CI run the same Maven **3.9.16**.

## Where it appears

| Path | What it does with the wrapper |
| --- | --- |
| `mvnw` | The Unix shell script. Every `Makefile` target and the `Dockerfile` run Maven through it. |
| `mvnw.cmd` | The same job for Windows' command prompt. This repository's commands never run it, but it ships with the wrapper. |
| `.mvn/wrapper/maven-wrapper.properties` | Says which Maven to download. |
| `Dockerfile` | Copies `.mvn/` and `mvnw` first, then runs `./mvnw` inside the build image, so the image needs no Maven install. |

## How it works underneath

A **properties file** is a text file of `key=value` lines. Here it holds three.

1. You run `./mvnw verify`. The shell starts the script `mvnw` and passes it the arguments (`verify`).
2. The script reads `.mvn/wrapper/maven-wrapper.properties` and takes out the `distributionUrl` value: the address of a zip file of Maven.
3. It computes a folder name under `~/.m2/wrapper/dists/` from that address (a hash of the URL), so different Maven versions never mix.
4. If the folder exists, the script starts Maven from it with `exec`. `exec` replaces the script's own process with Maven, so there is one process, not two, and Maven's exit code becomes the script's exit code.
5. If the folder does not exist, the script makes a temporary folder, downloads the zip with `wget` or `curl` (or, if neither exists, with a tiny Java program it writes and compiles on the spot), unpacks it, moves it into place, deletes the temporary folder, and then does step 4.

So the first `./mvnw` call on a new machine needs the network and takes a few seconds longer. Every later call goes straight to step 4.

## Walkthrough of this repository's use

### The settings file

```properties
wrapperVersion=3.3.4
distributionType=only-script
distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip
```

- `wrapperVersion=3.3.4`: which version of the wrapper wrote this file. It is information for the wrapper tooling.
- `distributionType=only-script`: use the script only, no helper jar. (The older style stored a jar in the repository.)
- `distributionUrl=...apache-maven-3.9.16-bin.zip`: the exact Maven to use, on Maven Central. `-bin` means the ready-to-run build, not the source. **This one line is how the repository pins Maven.** Changing the number changes Maven for everybody.

### The scripts

`mvnw` (macOS and Linux) and `mvnw.cmd` (Windows) are long generated scripts. You do not edit them, but nothing in them is hidden: `mvnw-script.md` reads `mvnw` line by line (all 295 lines, with the shell ideas explained where they first appear), and `mvnw-cmd-powershell.md` reads `mvnw.cmd` the same way. In short, `mvnw` does these things, in this order:

1. Turns on strict mode (`set -euf`) so a failure stops it.
2. Reads `distributionUrl` from the settings file above.
3. Works out the cache folder `~/.m2/wrapper/dists/apache-maven-3.9.16/<hash>`.
4. If that folder exists, replaces itself with Maven (`exec`) and is done.
5. Otherwise downloads the zip into a temporary folder (with `wget`, else `curl`, else a tiny Java program), optionally checks a SHA-256 checksum, unpacks it, moves it into the cache folder, deletes the temporary folder, and then does step 4.

## Try it yourself

1. `./mvnw -v` prints `Apache Maven 3.9.16`, then the Java version. That is the pinned Maven, not whatever is on your `PATH`.
2. `ls ~/.m2/wrapper/dists/` shows the folder the script created, named `apache-maven-3.9.16`.
3. `MVNW_VERBOSE=true ./mvnw -v` prints the script's own messages, such as "found existing MAVEN_HOME at ...".
4. `cat .mvn/wrapper/maven-wrapper.properties` shows the three lines that pin Maven.
5. `which mvn` may print nothing (Maven not installed) and `./mvnw -v` still works: that is the whole point.

## Mistakes people make

- **Forgetting the executable bit.** `./mvnw` fails with "permission denied" if the file is not marked executable. Git stores the bit; this repository's file has it (`ls -l mvnw` shows `x`).
- **Running `mvn` instead of `./mvnw`.** You then get whatever Maven (or none) is installed. The `Makefile` always uses `./mvnw`.
- **Committing a changed `distributionUrl` without checking CI.** A new Maven may change plugin behavior. Change it deliberately and run `make check`.
- **Behind a firewall.** The first run needs `repo.maven.apache.org`. The script supports `MVNW_REPOURL` to point at a mirror.
- **Editing the generated script.** Regenerate it with the wrapper plugin instead (`mvn wrapper:wrapper -Dmaven=<version>`).

## Check yourself

1. *Which line chooses the Maven version?* `distributionUrl` in `.mvn/wrapper/maven-wrapper.properties`.
2. *Where does the downloaded Maven live?* In `~/.m2/wrapper/dists/apache-maven-3.9.16/<hash>`.
3. *What does `exec` do at the end of the script?* It replaces the script's process with Maven, so Maven's exit code is the script's exit code and there is no leftover shell.
4. *What does `set -euf` do?* Stops at the first failing command (`-e`), errors on unset variables (`-u`), and disables wildcard expansion (`-f`).
5. *Why does the script delete the temporary folder with a trap?* So the download folder is removed even if the script fails or is interrupted.

## Further reading

- Apache Maven Wrapper: <https://maven.apache.org/tools/wrapper/> (wrapper 3.3.x, `only-script` type)
- Maven 3.9.16 download page: <https://maven.apache.org/download.cgi>
