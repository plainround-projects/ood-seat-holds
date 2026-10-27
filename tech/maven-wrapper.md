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

### The script

`mvnw` is a long generated script. You do not edit it. These are its parts, in order, with the lines that matter. (Shell syntax such as `${x-}` and `case` is explained in `bash-and-shell.md`.)

**1. Strict mode and tracing.**

```sh
set -euf
[ "${MVNW_VERBOSE-}" != debug ] || set -x
```

`set -euf` turns on three safety switches: `-e` stop at the first command that fails, `-u` treat an unset variable as an error, `-f` turn off filename wildcards (so a `*` in a value is not expanded by accident). The second line turns on `set -x` (print every command) only if you set `MVNW_VERBOSE=debug`. The syntax `[ A ] || B` means "run B only if the test A is false".

**2. Windows path fix.** The `case "$(uname)"` block checks the operating system name (`uname` prints it). On Cygwin or MinGW (Unix-like layers on Windows) it converts paths to the Windows form. On macOS or Linux it does nothing.

**3. Finding Java (`set_java_home`).** The function looks for a Java to use in case it must compile the fallback downloader. If `JAVA_HOME` (an environment variable that points at a JDK folder) is set, it uses `$JAVA_HOME/bin/java`; otherwise it asks the shell where `java` is with `command -v java`. It prints an error and returns 1 if neither works.

**4. Helper functions.** `hash_string` reproduces Java's `String.hashCode` in shell arithmetic so the folder name is stable. `verbose` prints only when `MVNW_VERBOSE=true`. `die` prints a message to standard error (`>&2`) and exits with 1. `trim` strips spaces and line breaks (`tr -d '[:space:]'`) from a value read from the properties file.

**5. Reading the properties file.**

```sh
while IFS="=" read -r key value; do
  case "${key-}" in
  distributionUrl) distributionUrl=$(trim "${value-}") ;;
  distributionSha256Sum) distributionSha256Sum=$(trim "${value-}") ;;
  esac
done <"$scriptDir/.mvn/wrapper/maven-wrapper.properties"
```

A `while read` loop reads the file one line at a time. `IFS="="` makes `=` the separator, so each line splits into `key` and `value`. The `case` picks out the `distributionUrl` line (and an optional `distributionSha256Sum`). `<"$scriptDir/.mvn/wrapper/maven-wrapper.properties"` feeds the file into the loop. `$scriptDir` is the folder of the script, found with `dirname "$0"` (`$0` is the script's own path), so the wrapper works from any folder.

**6. Choosing the command and the cache folder.** A second `case` looks at the file name in the URL. For a plain Maven zip, `MVN_CMD` becomes `mvn`. Then:

```sh
MAVEN_USER_HOME="${MAVEN_USER_HOME:-${HOME}/.m2}"
MAVEN_HOME="${MAVEN_USER_HOME}/wrapper/dists/${distributionUrlNameMain-}/$(hash_string "$distributionUrl")"
```

`${MAVEN_USER_HOME:-${HOME}/.m2}` means "use `MAVEN_USER_HOME` if set, otherwise `~/.m2`". `MAVEN_HOME` becomes `~/.m2/wrapper/dists/apache-maven-3.9.16/<hash>`: the place this Maven lives.

**7. Run it if it is already there.**

```sh
exec_maven() {
  unset MVNW_VERBOSE MVNW_USERNAME MVNW_PASSWORD MVNW_REPOURL || :
  exec "$MAVEN_HOME/bin/$MVN_CMD" "$@" || die "cannot exec $MAVEN_HOME/bin/$MVN_CMD"
}

if [ -d "$MAVEN_HOME" ]; then
  verbose "found existing MAVEN_HOME at $MAVEN_HOME"
  exec_maven "$@"
```

`exec_maven` clears the wrapper's own settings from the environment (`unset`), then `exec`s the real Maven with `"$@"`, which means "all the arguments, each kept as one word". If the folder exists the script ends here.

**8. Download.** If not, `mktemp -d` makes a temporary folder; `trap clean HUP INT TERM EXIT` promises to delete it when the script ends in any way (a **trap** runs a command when a signal or exit happens). `mkdir -p` creates the parent folders. The script prefers `wget`, then `curl` (`-f` fail on HTTP errors, `-L` follow redirects, `-o` output file), then the Java fallback: a **here-document** (`cat >file <<-END ... END`) writes a small `Downloader.java`, `javac` compiles it and `java` runs it.

**9. Optional checksum.** If the properties file contains `distributionSha256Sum`, the script computes the file's SHA-256 fingerprint and refuses to continue if it differs. This repository does not set one, so this step is skipped.

**10. Unpack and move.** `unzip` (or `tar xzf`) unpacks into the temporary folder. The script looks for the folder that holds `bin/mvn`, writes the URL into a file `mvnw.url` inside it, and `mv`s it to `MAVEN_HOME`. `clean` removes the temporary folder and `exec_maven "$@"` runs Maven.

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
