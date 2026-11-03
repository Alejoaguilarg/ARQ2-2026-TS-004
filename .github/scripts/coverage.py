"""Fails when a service's line coverage is below the minimum.

Usage: coverage.py <service-dir> <minimum-percent>

Reads the JaCoCo report of every module (build/reports/jacoco.xml) and adds up their lines.
applications/app-service is left out: it only holds the Spring Boot main class and wiring.
Writes a per-module table to the GitHub job summary when GITHUB_STEP_SUMMARY is set.
"""
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

EXCLUDED = {"applications/app-service"}


def line_counts(report):
    for counter in ET.parse(report).getroot().findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered")), int(counter.get("missed"))
    return 0, 0


def main(service_dir, minimum):
    root = Path(service_dir)
    rows = []
    for report in sorted(root.glob("**/build/reports/jacoco.xml")):
        module = report.parent.parent.parent.relative_to(root).as_posix()
        if module in EXCLUDED:
            continue
        covered, missed = line_counts(report)
        if covered + missed:
            rows.append((module, covered, missed))
    if not rows:
        print(f"::error::No JaCoCo reports found under {service_dir}/**/build/reports/jacoco.xml")
        return 1

    covered = sum(r[1] for r in rows)
    total = covered + sum(r[2] for r in rows)
    percent = 100 * covered / total
    passed = percent >= minimum

    lines = [f"### Coverage: {root.name} {'✅' if passed else '❌'} {percent:.1f}% (minimum {minimum:g}%)", "",
             "| Module | Lines | Coverage |", "|---|---|---|"]
    lines += [f"| {m} | {c}/{c + mi} | {100 * c / (c + mi):.1f}% |" for m, c, mi in rows]
    report = "\n".join(lines)
    print(report)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as out:
            out.write(report + "\n\n")

    if not passed:
        print(f"::error::{root.name} line coverage {percent:.1f}% is below the minimum {minimum:g}%")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], float(sys.argv[2])))
