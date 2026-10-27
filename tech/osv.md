# OSV vulnerability database (`api.osv.dev`)

## What it is

OSV (Open Source Vulnerabilities) is a free public database that lists known security problems in open-source libraries, each with an id such as `GHSA-4265-ccf5-phj5` and the versions it affects. It has a web service you can ask: "does version 1.24.0 of this library have any known problems?" The service speaks **JSON** (a text format for structured data: `{"name": "value", "list": [1, 2]}`) over **HTTPS** (web requests protected by encryption).

## Why this repository uses it

The production-grade rules require a dependency audit in CI. OSV needs no account or API key, answers a whole list in one request, and covers Maven libraries. The audit found a real problem here: Testcontainers brought in `commons-compress` 1.24.0, which had two advisories, so the build now pins version 1.28.0.

## Where it appears

| Path | What it does with OSV |
| --- | --- |
| `scripts/audit.py` | Sends the question and reads the answer (`python.md`). |
| `target/dependencies.txt` | The list of libraries the question is built from. Written by `mvn dependency:list`; not committed. |
| `Makefile` (`audit`) and `.github/workflows/ci.yml` (`audit` job) | Run the audit. |
| `pom.xml` (`dependencyManagement`) | The pin that fixed the advisory OSV found. |

## How it works underneath

The script sends one **HTTP POST** request to `https://api.osv.dev/v1/querybatch`. A POST carries a body. The body is JSON:

```json
{"queries": [
  {"package": {"ecosystem": "Maven", "name": "org.junit.jupiter:junit-jupiter"}, "version": "6.0.3"},
  {"package": {"ecosystem": "Maven", "name": "org.assertj:assertj-core"}, "version": "3.27.7"}
]}
```

`ecosystem` says where the library comes from (Maven Central here), so OSV matches names and version rules correctly. The reply is JSON too:

```json
{"results": [ {}, {"vulns": [{"id": "GHSA-..."}]} ]}
```

`results` has one entry per question, in the same order. An empty `{}` means "no known problems". An entry with `vulns` lists the advisories. The service looks at the exact version you give it; it does not guess.

The libraries come from Maven. `mvn dependency:list` prints every library the build resolved, including the ones other libraries bring along (**transitive** dependencies), because a vulnerable library is a problem no matter who asked for it. That is why the audit counts more libraries than `pom.xml` lists.

## Walkthrough of this repository's use

```python
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
COORDINATE = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):[\w.\-]+:(?:[\w.\-]+:)?([\w.\-]+):\w+")
```
```python
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

`OSV_BATCH` is the address. `body` builds the JSON structure above, one `queries` entry per library found in `target/dependencies.txt`. The request has a 60-second timeout. `["results"]` is the list of answers. (`python.md` explains each line of the script; this file is about the service.)

```python
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

The script pairs each library with its answer (`zip`), keeps those with a non-empty `vulns`, prints `VULNERABLE name version: ids`, then a summary, and returns exit status 1 if anything was found. A real run of this repository:

```text
audited 40 dependencies, 0 with known advisories
```

When the audit first ran on this repository it printed `VULNERABLE org.apache.commons:commons-compress 1.24.0: GHSA-4265-ccf5-phj5, GHSA-4g9r-vxhx-9pgx`. The fix is in `pom.xml`: a `dependencyManagement` entry that forces `commons-compress` 1.28.0 (`maven.md`).

## Try it yourself

1. `make audit` runs the whole thing and prints the summary line.
2. `cat target/dependencies.txt | head` shows the library list the questions are built from (after `make audit` created it).
3. `curl -s -X POST https://api.osv.dev/v1/query -d '{"package":{"ecosystem":"Maven","name":"org.apache.commons:commons-compress"},"version":"1.24.0"}'` asks one question directly; the reply lists advisory ids. (This needs the network.)
4. Change `1.24.0` to `1.28.0` in that command: the reply is `{}`.
5. Turn off Wi-Fi and run `make audit`: it prints `cannot reach the OSV database` and exits with status 2.

## Mistakes people make

- **Auditing only the libraries you wrote down.** Most problems hide in transitive dependencies. This audit uses the resolved list.
- **Treating "no network" as "no problems".** The script exits 2, not 0.
- **Ignoring test-scope libraries.** A vulnerable test library can still run on developers' machines and CI, so the audit includes every scope.
- **Fixing by deleting the library.** Often the right fix is pinning a patched version, as `dependencyManagement` does here.
- **Reading "advisory" as "exploitable here".** An advisory says a version has a known flaw; whether your use is affected needs a human to judge.

## Check yourself

1. *What does the `ecosystem` field tell OSV?* Where the package comes from (Maven), so it applies the right naming and version rules.
2. *How do you know which answer belongs to which library?* `results` is in the same order as `queries`.
3. *Why does the audit count more libraries than `pom.xml` lists?* It includes transitive dependencies.
4. *What exit status means "found a problem"?* `1`. Status `2` means the audit could not run.
5. *What was fixed after the first run?* `commons-compress` was pinned to 1.28.0 because Testcontainers had brought 1.24.0.

## Further reading

- OSV documentation and API: <https://google.github.io/osv.dev/api/> (the `querybatch` endpoint)
- OSV schema: <https://ossf.github.io/osv-schema/>
