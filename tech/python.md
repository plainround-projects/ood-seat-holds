# Python 3 (`scripts/audit.py`)

## What it is

Python is a programming language. You write instructions in a text file that ends in `.py`, and a program called the **interpreter** reads the file and carries the instructions out. This repository does **not** use Python for its product (that is Java). It uses one small Python script, `scripts/audit.py`, that checks the project's libraries for known security problems. It uses only Python's **standard library** (the modules that come with Python), so nothing needs installing. Any Python 3.9 or newer works; this was run with the `python3` on a Mac.

## Why this repository uses it

The production-grade rules ask the CI to run a dependency audit. The usual Java tool for that (OWASP dependency-check) needs a large database download and an API key to be fast. A 100-line script that asks a free public service for each library is smaller and easier to read. Python is installed on Macs and on GitHub's Linux machines, and its standard library can make web requests and read JSON.

## Where it appears

| Path | What it does with Python |
| --- | --- |
| `scripts/audit.py` | The whole script: read the list of libraries, ask the OSV service about each, print and set the exit code. |
| `Makefile` (`audit` target) | Runs `python3 scripts/audit.py` after Maven writes the library list. |
| `.github/workflows/ci.yml` | Runs `make audit` in its own job. |

## How it works underneath

A **module** is a Python file you can bring into your program with `import`. The **standard library** is a set of modules that ships with Python (`json` reads and writes JSON, `urllib.request` makes web requests, `re` does pattern matching, `pathlib` handles file paths, `sys` talks to the interpreter).

Python runs a file from top to bottom. A line that starts with `def` does not run the function; it only creates it. Code inside runs when someone **calls** the function. `if __name__ == "__main__":` at the end means "run this only when the file is started directly, not when another file imports it". `sys.exit(main())` ends the process with the number `main` returns, which becomes the **exit status** (0 = success, other = failure) that `make` and CI look at.

Python does not check **type hints** such as `-> list[tuple[str, str]]` when the program runs. They are notes for the reader and for tools.

## Walkthrough of this repository's use

### The header and imports

```python
#!/usr/bin/env python3
"""Check every resolved Maven dependency against the OSV vulnerability database.

Reads target/dependencies.txt (written by `mvn dependency:list`), asks api.osv.dev for known
advisories and exits 1 if any dependency has one. Standard library only; no API key needed.
"""

import json
import pathlib
import re
import sys
import urllib.error
import urllib.request
```

- Line 1, `#!/usr/bin/env python3`, is a **shebang**: if you run the file directly (`./scripts/audit.py`), the system uses it to find the interpreter. The `Makefile` runs `python3 scripts/audit.py` explicitly, so it is a courtesy.
- The text between `"""` marks is a **docstring**: the file's own description.
- Each `import` brings in a standard-library module (listed above). `urllib.error` holds the exception types for failed web requests.

### Constants

```python
LISTING = pathlib.Path("target/dependencies.txt")
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
COORDINATE = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):[\w.\-]+:(?:[\w.\-]+:)?([\w.\-]+):\w+")
```

- `LISTING` is the path of the file that `mvn dependency:list` wrote. `pathlib.Path(...)` wraps a path so you can call `.read_text()` on it.
- `OSV_BATCH` is the web address of the OSV batch query service (`osv.md`).
- `COORDINATE` is a **regular expression**, a pattern for text. `re.compile` prepares it. It matches one line of Maven's list, which looks like `   org.junit.jupiter:junit-jupiter:jar:6.0.3:test`. The parts in `( )` are **groups** that capture text: group 1 is the `groupId`, group 2 the `artifactId`, group 3 the version. `[\w.\-]+` means "one or more word characters, dots or hyphens". `(?:[\w.\-]+:)?` is an optional piece (for the *classifier* some jars have) that is matched but not captured. `\s*` is optional leading spaces and `\w+` is the scope word at the end.

### `read_coordinates`

```python
def read_coordinates() -> list[tuple[str, str]]:
    found = set()
    for line in LISTING.read_text().splitlines():
        match = COORDINATE.match(line)
        if match:
            found.add((f"{match.group(1)}:{match.group(2)}", match.group(3)))
    return sorted(found)
```

- `-> list[tuple[str, str]]` says it returns a list of pairs of text.
- `found = set()` makes an empty **set**, a collection with no duplicates. The same library can appear twice in the list, and the set keeps one.
- `LISTING.read_text().splitlines()` reads the whole file as text and cuts it into lines. The `for` loop visits each line.
- `COORDINATE.match(line)` tries the pattern at the start of the line. It returns a match object, or `None` (nothing) if the line is not a library line (for example a header). `if match:` is true only for a real match.
- `found.add((f"{match.group(1)}:{match.group(2)}", match.group(3)))` adds one pair: `("org.junit.jupiter:junit-jupiter", "6.0.3")`. The `f"..."` is an **f-string**: `{ }` inside it are replaced by values. `OSV` names Maven packages as `group:artifact`.
- `sorted(found)` turns the set into a list in alphabetical order, so the output is stable.

### `query`

```python
def query(coordinates: list[tuple[str, str]]) -> list[dict]:
    body = {
        "queries": [
            {"package": {"ecosystem": "Maven", "name": name}, "version": version}
            for name, version in coordinates
        ]
    }
    request = urllib.request.Request(
        OSV_BATCH,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)["results"]
```

- The `body` is a **dictionary** (Python's `{key: value}` mapping). It has one key, `queries`, whose value is a **list comprehension**: `[ {...} for name, version in coordinates ]` builds one small dictionary per library. Each says: package ecosystem `Maven`, name, version.
- `urllib.request.Request(url, data=..., headers=...)` prepares a web request. `json.dumps(body)` turns the dictionary into JSON text and `.encode()` turns the text into bytes, which is what a request body is. Supplying `data` makes it an HTTP **POST** (a request that sends a body). The header `Content-Type: application/json` tells the server how to read the body.
- `with urllib.request.urlopen(request, timeout=60) as response:` sends the request and waits at most 60 seconds (a **timeout**, so a dead network cannot hang the build forever). `with` closes the connection at the end of the block, even on an error.
- `json.load(response)` reads the JSON reply and turns it into Python lists and dictionaries; `["results"]` picks the part we need: one result per question, in the same order.

### `main`

```python
def main() -> int:
    coordinates = read_coordinates()
    if not coordinates:
        print(f"no dependencies found in {LISTING}; run `make audit` instead", file=sys.stderr)
        return 2
    try:
        results = query(coordinates)
    except (urllib.error.URLError, TimeoutError) as problem:
        print(f"cannot reach the OSV database: {problem}", file=sys.stderr)
        return 2
    vulnerable = [
        (name, version, [entry["id"] for entry in result.get("vulns", [])])
        for (name, version), result in zip(coordinates, results)
        if result.get("vulns")
    ]
    for name, version, ids in vulnerable:
        print(f"VULNERABLE {name} {version}: {', '.join(ids)}")
    print(f"audited {len(coordinates)} dependencies, {len(vulnerable)} with known advisories")
    return 1 if vulnerable else 0
```

- If no libraries were found (`not coordinates` is true for an empty list), it prints an error to **standard error** (`file=sys.stderr`) and returns `2`.
- `try: ... except (urllib.error.URLError, TimeoutError) as problem:` runs the query and, if the network fails, prints "cannot reach the OSV database" and returns `2`. A distinct code `2` tells CI "the check could not run", different from `1`, "the check ran and found a problem".
- The `vulnerable` list comprehension walks the libraries and results together with `zip` and keeps only those whose result has a non-empty `vulns` list (`result.get("vulns")` returns `None` when the key is missing). Each entry is `(name, version, [advisory ids])`.
- The loop prints one `VULNERABLE name version: ids` line per problem, then a summary line with the counts.
- `return 1 if vulnerable else 0` returns 1 (failure) when any library has an advisory, otherwise 0.

### The entry point

```python
if __name__ == "__main__":
```
```python
sys.exit(main())
```

When the file is run directly, call `main()` and exit with its result.

## Try it yourself

1. `python3 --version` shows your interpreter (3.9 or newer is enough).
2. `make audit` prints `audited N dependencies, 0 with known advisories` and exits 0.
3. `python3 -c "import re; print(re.match(r'(\w+):(\w+)', 'a:b').groups())"` prints `('a', 'b')`: how capture groups work.
4. `python3 scripts/audit.py` before any build (delete `target/dependencies.txt` first) exits with 2 and the message `no dependencies found`; run `echo $?` to see the code. (It reads a path relative to the current folder, so run it from the repository root.)
5. `python3 -c "import json; print(json.dumps({'queries': []}))"` prints the JSON text the script would send for an empty list.

## Mistakes people make

- **Treating a failed check as a passed check.** If the network is down, a careless script prints nothing and exits 0. This one exits 2 and says why.
- **No timeout on a web request.** The call can wait forever. Here it is 60 seconds.
- **Parsing text with string splitting.** A regular expression with named parts survives extra spaces and optional fields.
- **Relying on the working directory.** The script uses a relative path, so it assumes it is started from the repository root, which is how the `Makefile` and CI run it.
- **Installing packages for a ten-line job.** The standard library is enough here, which keeps CI simple.

## Check yourself

1. *What does exit status `2` mean in this script?* The audit could not run (no list of libraries, or the service was unreachable).
2. *Why use a `set` in `read_coordinates`?* To drop duplicates: one library can appear more than once.
3. *What does `with urllib.request.urlopen(...) as response:` guarantee?* The connection is closed when the block ends, even if an error happens.
4. *What do type hints like `-> int` change at run time?* Nothing; they are notes for readers and checking tools.
5. *Why does the script send one batch request instead of one per library?* One round trip is faster and gentler on the service than dozens.

## Further reading

- Python 3 standard library: <https://docs.python.org/3/library/> (`json`, `re`, `urllib.request`, `pathlib`)
- Python tutorial: <https://docs.python.org/3/tutorial/>
- Regular expression guide: <https://docs.python.org/3/howto/regex.html>
