# YAML (`.yml` files)

## What it is

YAML is a text format for structured settings. It describes three shapes of data: **mappings** (named values, like `name: ci`), **lists** (items that start with `- `) and plain **scalars** (text, numbers, true/false). Structure comes from **indentation with spaces** (never tabs). Many tools use it because it is easy for people to read: GitHub Actions, Docker Compose and Kubernetes are the best known.

## Why this repository uses it

Two tools this repository relies on read their settings in YAML: GitHub Actions (`.github/workflows/ci.yml`) and Docker Compose (`docker-compose.yml`).

## Where it appears

| Path | What it does with YAML |
| --- | --- |
| `.github/workflows/ci.yml` | The CI workflow (`github-actions.md`). |
| `docker-compose.yml` | The PostgreSQL service (`docker-compose.md`). |

## How it works underneath

A YAML **parser** reads the text and builds the same kind of data a program would build itself: dictionaries, lists, strings, numbers. The reading rules, with examples from this repository:

- `key: value` is a mapping entry. A mapping can hold other mappings by indenting the inner keys under the outer key.
- `- item` is a list entry; the dash and a space begin each item. `[a, b]` is the same list written on one line.
- Indentation must use spaces and be consistent. Two spaces per level is the habit here (`editorconfig.md`).
- `#` starts a comment that runs to the end of the line.
- Text without quotes is read by guessing the type: `21` becomes a number, `true` a boolean, `ubuntu-latest` text. Quotes (`"21"`) force text.
- A value starting with `${{` (GitHub's expression syntax) is kept as text for GitHub to evaluate later.

## Walkthrough of this repository's use

### A mapping, a list and a nested mapping

```yaml
name: ci

on: [push, pull_request]

jobs:
  check:
    name: Format, lint, unit and end-to-end tests with coverage gate
    runs-on: ubuntu-latest
```

- `name: ci` is a mapping entry with the text `ci`.
- `on: [push, pull_request]` is a key `on` whose value is a one-line list of two items.
- `jobs:` has no value on its line; the **indented** lines below are its value, a mapping whose only key is `check`. That key's value is another mapping with keys `name` and `runs-on`.

### A list of mappings

```yaml
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

`steps:` holds a list. Each item starts with `- `. The first item is `uses: actions/checkout@v4`: one entry. The second item has two lines, `uses:` and a nested mapping `with:`: a dash starts the item, and the lines indented to the same column as `uses` belong to the same item. The items `- run: make check` and `- run: make run` are mappings of one entry each.

### Quoting a version

```yaml
matrix:
  java: ["21", "25"]
```

`java: ["21", "25"]` is a list of two **strings**. Without the quotes YAML would read two numbers; that works for 21 but breaks for a version such as `3.10` (it would become `3.1`). Quoting is the safe habit.

### A Compose file

```yaml
# PostgreSQL for the JDBC adapter. Data lives only as long as the container: `make down` deletes it.
services:
  db:
    image: postgres:18
    environment:
      POSTGRES_USER: seatholds
      POSTGRES_DB: seatholds
      POSTGRES_PASSWORD: ${DATABASE_PASSWORD:-seatholds-local-only}
    ports:
      - "127.0.0.1:${POSTGRES_PORT:-55468}:5432"
    healthcheck:
      # TCP, not the socket: during first start the image runs a temporary server that listens only on
      # the socket, so a socket check can pass before the database exists.
      test: ["CMD-SHELL", "pg_isready -h 127.0.0.1 -U seatholds -d seatholds"]
      interval: 2s
      timeout: 3s
      retries: 30
```

Here the top-level key `services:` contains one service, `db`. Under it `image:` is text, `environment:` is a mapping of three variables, `ports:` is a list with one text item (quoted because it contains a colon), and `healthcheck:` is a mapping whose `test:` is a list (`["CMD-SHELL", "..."]`). The `${DATABASE_PASSWORD:-seatholds-local-only}` is Compose's own variable syntax ("this variable or this default"). Every key is explained in `docker-compose.md`.

## Try it yourself

1. `grep -n "^  *- " .github/workflows/ci.yml | head` lists the list items; count the leading spaces to see nesting.
2. `ruby -ryaml -e 'puts YAML.load_file(".github/workflows/ci.yml").dig("jobs","check","strategy","matrix")'` (macOS ships Ruby). It prints `{"java"=>["21", "25"]}`.
3. Edit a copy of the file, replace two spaces with a tab on one line, and try the Ruby command again: it fails to parse. Delete the copy.
4. Remove the quotes around `"21"` in the copy and run the Ruby command: `21` is now an integer (`{"java"=>[21, "25"]}`).
5. `git diff --stat` after any edit confirms you only changed what you meant to.

## Mistakes people make

- **Tabs.** Not allowed for indentation; the parser reports an error.
- **Wrong indentation level.** One missing space moves an entry to another parent. Use an editor that shows indentation guides.
- **Unquoted values that look like something else:** `yes`, `no`, `on` (in YAML 1.1 parsers `on` can mean true), `1.10`, `0123`. Quote them. GitHub Actions' own `on:` key is accepted as written.
- **Forgetting the space after the colon.** `key:value` is one piece of text, not a mapping.
- **Mixing lists and mappings** in one level.

## Check yourself

1. *How does YAML show that one entry belongs inside another?* By indenting it with spaces under the parent.
2. *What does `[push, pull_request]` mean?* A list of two items written on one line.
3. *Why is `"21"` in quotes?* To force the text `21` instead of the number, which keeps versions like `3.10` from changing.
4. *Can you indent YAML with tabs?* No, only spaces.
5. *What starts a list item?* A dash followed by a space.

## Further reading

- YAML 1.2 specification: <https://yaml.org/spec/1.2.2/>
- GitHub's YAML notes for workflows: <https://docs.github.com/actions/reference/workflows-and-actions/workflow-syntax>
