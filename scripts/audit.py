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

LISTING = pathlib.Path("target/dependencies.txt")
OSV_BATCH = "https://api.osv.dev/v1/querybatch"
COORDINATE = re.compile(r"^\s*([\w.\-]+):([\w.\-]+):[\w.\-]+:(?:[\w.\-]+:)?([\w.\-]+):\w+")


def read_coordinates() -> list[tuple[str, str]]:
    found = set()
    for line in LISTING.read_text().splitlines():
        match = COORDINATE.match(line)
        if match:
            found.add((f"{match.group(1)}:{match.group(2)}", match.group(3)))
    return sorted(found)


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


if __name__ == "__main__":
    sys.exit(main())
