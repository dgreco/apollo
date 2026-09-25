#!/usr/bin/env python3

# SPDX-FileCopyrightText: 2026 David Greco <greco@acm.org>
#
# SPDX-License-Identifier: Apache-2.0

"""Summarises scoverage's XML report for the two forges' coverage badges.

    coverage-summary.py markdown <scoverage.xml> [heading] > Coverage.md
    coverage-summary.py badge    <scoverage.xml>           > badge.json

`markdown` is the report behind the GitLab badge. That instance has no Pages,
so it cannot display an HTML artifact in a browser at all; a wiki page is
Markdown, which it renders natively at a stable URL (the `coverage-wiki` job).
It loses scoverage's clickable source view and keeps the part anybody reads:
the totals, and which packages are dark.

`badge` is a shields.io endpoint document (https://shields.io/badges/endpoint-badge),
published next to the HTML report on GitHub Pages so the GitHub badge shows the
live figure rather than a number someone has to remember to edit.

Figures are recomputed from the individual statements, not read from the
package elements, because scoverage only rolls statement counts up to packages:
branch and line figures exist per statement alone. The statement total agrees
with the "Statement coverage" line sbt prints.
"""

import json
import sys
import xml.etree.ElementTree as ET
from collections import defaultdict


def pct(hit, total):
    return 100.0 * hit / total if total else 100.0


def bar(value):
    """A ten-cell bar. Markdown has no charts, and a number in a table is easy to skim past."""
    filled = int(round(value / 10.0))
    return "█" * filled + "·" * (10 - filled)


class Totals:
    def __init__(self):
        self.statements = [0, 0]   # [invoked, total]
        self.branches = [0, 0]
        self.lines = {}            # (source, line) -> covered by any statement on it

    def add(self, stmt):
        hit = int(stmt.get("invocation-count", "0")) > 0
        self.statements[0] += hit
        self.statements[1] += 1
        if stmt.get("branch") == "true":
            self.branches[0] += hit
            self.branches[1] += 1
        key = (stmt.get("source"), stmt.get("line"))
        self.lines[key] = self.lines.get(key, False) or hit

    @property
    def statement_pct(self):
        return pct(*self.statements)

    def row(self, name):
        s = self.statement_pct
        lines_hit = sum(self.lines.values())
        return (f"| {name} | {bar(s)} {s:.1f}% | {pct(*self.branches):.1f}% | "
                f"{pct(lines_hit, len(self.lines)):.1f}% | {self.statements[1]:,} |")


def load(path):
    overall, by_package = Totals(), defaultdict(Totals)
    for stmt in ET.parse(path).iter("statement"):
        if stmt.get("ignored") == "true":
            continue
        overall.add(stmt)
        by_package[stmt.get("package")].add(stmt)
    return overall, by_package


def markdown(path, heading):
    overall, by_package = load(path)
    header = "| | statements | branches | lines | statements (count) |\n|---|---|---|---|---|"
    print(f"# {heading}\n")
    print(header)
    print(overall.row("**whole tree**"))
    print("\n## By package\n")
    print(header)
    for name in sorted(by_package, key=lambda n: -by_package[n].statement_pct):
        print(by_package[name].row(f"`{name}`"))
    print("""
JVM suite only (`ci/test-jvm.sh`): scoverage does not instrument Scala Native,
so code reached only from the Native suite reads as uncovered here. Methods too
large for the instrumenter (it warns at 3000 tree nodes) are left out of the
figures entirely rather than counted as uncovered.
""")


def badge(path):
    overall, _ = load(path)
    s = overall.statement_pct
    color = ("brightgreen" if s >= 80 else "green" if s >= 70 else
             "yellowgreen" if s >= 60 else "yellow" if s >= 50 else "red")
    json.dump({"schemaVersion": 1, "label": "coverage", "message": f"{s:.1f}%",
               "color": color, "namedLogo": "scala"}, sys.stdout)
    print()


def main(argv):
    if len(argv) < 3 or argv[1] not in ("markdown", "badge"):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    if argv[1] == "markdown":
        markdown(argv[2], argv[3] if len(argv) > 3 else "Coverage")
    else:
        badge(argv[2])
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
