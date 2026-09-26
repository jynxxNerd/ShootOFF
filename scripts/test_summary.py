#!/usr/bin/env python3
"""Summarize JUnit XML results and compare them against a saved baseline.

  summarize RESULTS_DIR...          print "PASS|FAIL|SKIP class.method" lines
  combine FILE...                   merge summaries; PASS only if PASS in every file
  compare RESULTS_DIR... BASELINE   exit 1 if any baseline PASS test no longer passes

RESULTS_DIR may be given several times (one per Gradle module, e.g. */build/test-results/test).
Directories that don't exist are skipped; a test reported FAIL in any directory counts as FAIL.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def collect(results_dirs):
    outcomes = {}
    for results_dir in results_dirs:
        for path in glob.glob(os.path.join(results_dir, "**", "TEST-*.xml"), recursive=True):
            for case in ET.parse(path).getroot().iter("testcase"):
                name = f"{case.get('classname')}.{case.get('name')}"
                if case.find("skipped") is not None:
                    outcome = "SKIP"
                elif case.find("failure") is not None or case.find("error") is not None:
                    outcome = "FAIL"
                else:
                    outcome = "PASS"
                if outcomes.get(name) != "FAIL":
                    outcomes[name] = outcome
    if not outcomes:
        sys.exit(f"No test results found in {', '.join(results_dirs)}")
    return outcomes


def read_summary(path):
    outcomes = {}
    with open(path) as f:
        for line in f:
            if line.strip():
                outcome, name = line.rstrip("\n").split(" ", 1)
                outcomes[name] = outcome
    return outcomes


def print_summary(outcomes):
    for name in sorted(outcomes):
        print(f"{outcomes[name]} {name}")


def main(argv):
    if len(argv) >= 2 and argv[0] == "summarize":
        print_summary(collect(argv[1:]))
    elif len(argv) >= 2 and argv[0] == "combine":
        runs = [read_summary(p) for p in argv[1:]]
        names = set().union(*runs)
        print_summary({n: "PASS" if all(r.get(n) == "PASS" for r in runs) else "FAIL" for n in names})
    elif len(argv) >= 3 and argv[0] == "compare":
        current = collect(argv[1:-1])
        baseline = read_summary(argv[-1])
        regressions = sorted(n for n, o in baseline.items() if o == "PASS" and current.get(n) != "PASS")
        new_failures = sorted(n for n, o in current.items() if o == "FAIL" and n not in baseline)
        for n in regressions:
            print(f"REGRESSION {n}: {current.get(n, 'MISSING')}")
        for n in new_failures:
            print(f"NEW TEST FAILING {n}")
        passed = sum(1 for o in current.values() if o == "PASS")
        print(f"{passed}/{len(current)} passing; {len(regressions)} regressions; {len(new_failures)} new failures")
        return 1 if regressions or new_failures else 0
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
