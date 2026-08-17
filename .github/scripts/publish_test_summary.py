#!/usr/bin/env python3
"""Publish Gradle JUnit XML results to a GitHub Actions check summary.

This in-repository parser avoids a third-party test-reporting action. It
combines unit and integration reports and lists failing test names and details
directly in the GitHub Actions Summary tab.
"""

from __future__ import annotations

import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

COUNT_KEYS = ("tests", "failures", "errors", "skipped")


def markdown_cell(value: str) -> str:
    """Escape text for a compact Markdown table cell."""
    return (
        value.replace("|", "\\|")
        .replace("`", "'")
        .replace("\r", " ")
        .replace("\n", " ")
        .strip()
    )


def task_name(results_root: Path, report: Path) -> str:
    """Return the Gradle test task directory containing a report."""
    try:
        return report.relative_to(results_root).parts[0]
    except (ValueError, IndexError):
        return report.parent.name


def problem_detail(problem: ET.Element) -> str:
    """Return the most useful bounded failure/error detail."""
    message = problem.attrib.get("message", "").strip()
    body = (problem.text or "").strip()
    return message or body or problem.tag


def main() -> int:
    results_root = Path(sys.argv[1] if len(sys.argv) > 1 else "build/test-results")
    summary_path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not summary_path:
        print("GITHUB_STEP_SUMMARY is not set; no GitHub summary to publish.")
        return 0

    suites: list[tuple[str, str, dict[str, int]]] = []
    failures: list[tuple[str, str, str, str]] = []
    totals = {key: 0 for key in COUNT_KEYS}

    reports = sorted(results_root.rglob("TEST-*.xml"))
    for report in reports:
        root = ET.parse(report).getroot()
        task = task_name(results_root, report)
        suite_name = root.attrib.get("name", report.stem)
        suite_counts = {key: int(root.attrib.get(key, "0")) for key in COUNT_KEYS}
        for key in COUNT_KEYS:
            totals[key] += suite_counts[key]
        suites.append((task, suite_name, suite_counts))

        for case in root.findall(".//testcase"):
            failure = case.find("failure")
            problem = failure if failure is not None else case.find("error")
            if problem is not None:
                failures.append(
                    (
                        task,
                        case.attrib.get("classname", suite_name),
                        case.attrib.get("name", "unnamed test"),
                        problem_detail(problem),
                    )
                )

    lines = ["## Test results", ""]
    if not suites:
        lines.extend(
            [
                "> [!WARNING]",
                f"No JUnit XML reports were found under `{results_root}`.",
                "",
            ]
        )
    else:
        outcome = "✅ Passed" if totals["failures"] + totals["errors"] == 0 else "❌ Failed"
        passed = totals["tests"] - totals["failures"] - totals["errors"] - totals["skipped"]
        lines.extend(
            [
                f"**{outcome}** — {totals['tests']} tests; {passed} passed; "
                f"{totals['failures']} failures; {totals['errors']} errors; "
                f"{totals['skipped']} skipped.",
                "",
                "| Task | Suite | Tests | Failures | Errors | Skipped |",
                "| --- | --- | ---: | ---: | ---: | ---: |",
            ]
        )
        for task, suite_name, counts in suites:
            lines.append(
                f"| `{markdown_cell(task)}` | `{markdown_cell(suite_name)}` | "
                f"{counts['tests']} | {counts['failures']} | {counts['errors']} | "
                f"{counts['skipped']} |"
            )
        lines.append("")

    if failures:
        lines.extend(
            [
                "### Failing tests",
                "",
                "| Task | Test class | Test | Failure |",
                "| --- | --- | --- | --- |",
            ]
        )
        for task, class_name, test_name, detail in failures:
            lines.append(
                f"| `{markdown_cell(task)}` | `{markdown_cell(class_name)}` | "
                f"`{markdown_cell(test_name)}` | {markdown_cell(detail)[:500]} |"
            )
        lines.append("")

    with Path(summary_path).open("a", encoding="utf-8") as summary:
        summary.write("\n".join(lines))

    if not suites:
        print(f"No JUnit XML reports found under {results_root}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
