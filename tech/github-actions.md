# GitHub Actions (`.github/workflows/ci.yml`)

## What it is

GitHub Actions runs commands on a fresh machine every time you push code to GitHub. This is called **continuous integration** (CI): every change is built and tested automatically, so a broken change is noticed at once and by everyone. You describe what to run in a **workflow** file written in YAML (`yaml.md`) inside `.github/workflows/`. This repository has not been pushed yet, so the workflow has not run on GitHub; its commands are the same `make` targets that were run locally.

## Why this repository uses it

The template requires CI to run format, lint, type check, tests and the audit on every push. The workflow does that by calling `make` targets, so CI and a developer's laptop run exactly the same commands.

## Where it appears

| Path | What it does with Actions |
| --- | --- |
| `.github/workflows/ci.yml` | The only workflow. |
| `Makefile` | The targets the workflow calls (`check`, `run`, `audit`, `test-integration`). |

## How it works underneath

- A **workflow** starts when an **event** happens. `on: [push, pull_request]` means "when someone pushes commits, or opens or updates a pull request".
- A workflow has **jobs**. Jobs run at the same time, each on its own fresh virtual machine (a **runner**). `runs-on: ubuntu-latest` picks a Linux runner.
- A job has **steps**, run in order on that machine. A step is either `uses:` (run a ready-made **action**, a reusable piece published on GitHub) or `run:` (run a shell command). A step that exits with a non-zero status fails the job.
- A **matrix** repeats a job with different values. Each value becomes its own job.
- The runner starts empty. Every job begins by fetching the code (`actions/checkout`) and installing Java (`actions/setup-java`).

## Walkthrough of this repository's use

### Name and trigger

```yaml
name: ci

on: [push, pull_request]
```

`name: ci` is the label shown on GitHub. `on: [push, pull_request]` lists the events (a YAML list in `[ ]`).

### The `check` job

```yaml
jobs:
  check:
    name: Format, lint, unit and end-to-end tests with coverage gate
    runs-on: ubuntu-latest
    strategy:
      matrix:
        java: ["21", "25"]
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: ${{ matrix.java }}
          cache: maven
      - run: make check
      - run: make run
```

- `jobs:` begins the jobs; `check:` is this job's id. `name:` is the longer label shown in the web page.
- `strategy: matrix: java: ["21", "25"]` makes two jobs, one with `matrix.java` equal to `"21"` and one with `"25"`. The numbers are quoted so YAML keeps them as text, not numbers (`"21"` stays `21`, and a version like `1.10` would not turn into `1.1`).
- `actions/checkout@v4` copies the repository onto the runner. `@v4` is the version of that action.
- `actions/setup-java@v4` installs Java. `distribution: temurin` chooses the Eclipse Temurin build, `java-version: ${{ matrix.java }}` fills in 21 or 25 (`${{ ... }}` is GitHub's expression syntax), and `cache: maven` saves the `~/.m2` folder between runs so libraries are not downloaded every time.
- `run: make check` runs the lint and the tests with the coverage gate. `run: make run` runs the scenario to prove the program starts. Each `- run:` is a separate step; if the first fails, the second does not run.

### The `integration` job

```yaml
integration:
  name: Contract and race tests against PostgreSQL (Testcontainers)
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: "21"
        cache: maven
    - run: make test-integration
      env:
        TESTCONTAINERS_RYUK_DISABLED: "false"
```

This job runs `make test-integration`, which needs Docker. GitHub's Ubuntu runners have Docker installed, so Testcontainers (`testcontainers.md`) can start PostgreSQL. `env:` under a step sets an environment variable for that step only. Here `TESTCONTAINERS_RYUK_DISABLED: "false"` overrides the `Makefile`'s default of `true`, because on GitHub's runners the clean-up helper container (Ryuk) works and is useful.

### The `audit` job

```yaml
audit:
  name: Dependency audit
  runs-on: ubuntu-latest
  steps:
    - uses: actions/checkout@v4
    - uses: actions/setup-java@v4
      with:
        distribution: temurin
        java-version: "21"
        cache: maven
    - run: make audit
```

A separate job so a security finding is visible on its own and does not hide behind the test results. It runs `make audit` (`osv.md`) once, on Java 21.

## Try it yourself

1. `cat .github/workflows/ci.yml` and find the three things GitHub needs: the trigger (`on`), a job (`jobs`) and its steps (`steps`).
2. Run the same commands the workflow runs: `make check && make run`, then `make audit`.
3. `python3 -c "import yaml,sys; print(yaml.safe_load(open('.github/workflows/ci.yml'))['jobs'].keys())"` prints the job ids, if you have PyYAML installed. Without it, `grep -n '^  [a-z]*:$' .github/workflows/ci.yml` lists them.
4. Change `"21"` to `21` in the matrix, think about what YAML would do with `21.0`, then change it back.
5. After pushing to GitHub, open the repository's **Actions** tab: each job and step from this file appears there with its log.

## Mistakes people make

- **A workflow that runs different commands from the developer's.** Here every step is a `make` target.
- **Unquoted version numbers in YAML.** `java: [21, 25]` works, but `3.10` would become `3.1`. This file quotes them.
- **No cache.** Re-downloading the libraries on every run is slow; `cache: maven` fixes it.
- **Tabs in YAML.** YAML allows only spaces for indentation.
- **Secrets in the file.** There are none here; the workflow needs no credentials.

## Check yourself

1. *When does this workflow run?* On every push and every pull request event (`on: [push, pull_request]`).
2. *What does the `matrix` do?* It runs the `check` job twice, once per Java version.
3. *What happens if a `run:` step exits non-zero?* The step fails, the job fails, and later steps in that job do not run.
4. *Why does CI call `make`?* So CI and a laptop run identical commands.
5. *What does `actions/setup-java` with `cache: maven` do?* Installs Java and caches Maven's downloaded libraries between runs.

## Further reading

- Workflow syntax: <https://docs.github.com/actions/reference/workflows-and-actions/workflow-syntax>
- `actions/checkout` v4: <https://github.com/actions/checkout>
- `actions/setup-java` v4: <https://github.com/actions/setup-java>
