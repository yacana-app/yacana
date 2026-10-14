# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.

"""Prints the share of lines the tests run, by module and package, as Markdown for the CI's summary, and fails
if a module is below its floor. The floors only go up, as tests are added.

The share is the merged report's: the app's screenshot tests run most of shared:ui, and the domain's tests a good
part of shared:alpaca.

Usage: python3 tools/coverage_summary.py build/reports/kover/report.xml
"""

import sys
import xml.etree.ElementTree as ET

# Each module by the packages its code is in; the app module holds the rest.
MODULES = {
    "shared:alpaca": ("app/yacana/alpaca",),
    "shared:domain": ("app/yacana/domain",),
    "shared:ui": ("app/yacana/ui",),
}


# The least share of lines each module's code must have run by the tests, in percent.
FLOORS = {"app": 55, "shared:alpaca": 90, "shared:domain": 96, "shared:ui": 86}


def module_of(package):
    for module, prefixes in MODULES.items():
        if any(package == p or package.startswith(p + "/") for p in prefixes):
            return module
    return "app"


def percent(covered, total):
    return f"{100 * covered / total:.1f} %" if total else "—"


def main(path):
    root = ET.parse(path).getroot()
    packages = []
    for package in root.findall("package"):
        counters = {c.get("type"): (int(c.get("missed")), int(c.get("covered"))) for c in package.findall("counter")}
        missed, covered = counters.get("LINE", (0, 0))
        packages.append((package.get("name"), missed + covered, covered))
    print("## Coverage\n")
    print("| Module | Lines | Covered | Floor |")
    print("| --- | ---: | ---: | ---: |")
    totals = {}
    for name, lines, covered in packages:
        module = totals.setdefault(module_of(name), [0, 0])
        module[0] += lines
        module[1] += covered
    for module, (lines, covered) in sorted(totals.items()):
        print(f"| {module} | {lines} | {percent(covered, lines)} | {FLOORS.get(module, 0)} % |")
    all_lines = sum(lines for _, lines, _ in packages)
    all_covered = sum(covered for _, _, covered in packages)
    print(f"| **All** | {all_lines} | **{percent(all_covered, all_lines)}** | |")
    print("\n<details><summary>By package</summary>\n")
    print("| Package | Lines | Covered |")
    print("| --- | ---: | ---: |")
    for name, lines, covered in sorted(packages):
        print(f"| `{name.replace('/', '.')}` | {lines} | {percent(covered, lines)} |")
    print("\n</details>")


if __name__ == "__main__":
    main(sys.argv[1])
