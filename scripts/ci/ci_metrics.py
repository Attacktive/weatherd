"""CI-only timing, bounded device probes, and summaries of retained Gradle/JUnit evidence."""

import argparse
import json
import math
import os
import re
import signal
import subprocess
import threading
import time
from collections import Counter
from pathlib import Path

from defusedxml import ElementTree as ET
from defusedxml.common import DefusedXmlException


def elapsed(markers, start, end):
	if start not in markers or end not in markers:
		return None

	seconds = markers[end]["monotonic"] - markers[start]["monotonic"]
	if seconds < 0:
		return None

	return seconds


def task_statistics(log):
	text = ""
	if log.exists():
		text = log.read_text(errors="replace")

	outcomes = dict(re.findall(r"^> Task (:\S+?)(?: (FROM-CACHE|UP-TO-DATE|NO-SOURCE|SKIPPED|FAILED))?$", text, re.MULTILINE))
	cache_keys = set(re.findall(r"Build cache key for task '([^']+)' is", text))
	hits = sum(outcome == "FROM-CACHE" for outcome in outcomes.values())
	misses = sum(task in cache_keys and outcome == "" for task, outcome in outcomes.items())
	rate = None
	if hits + misses:
		rate = hits / (hits + misses)

	return {
		"outcomes": dict(Counter(outcome or "EXECUTED" for outcome in outcomes.values())),
		"cache_hits": hits,
		"cache_misses": misses,
		"cache_hit_rate": rate,
	}


def android_tests(results):
	tests = []
	errors = []
	files = sorted(results.rglob("*.xml"))
	for path in files:
		try:
			root = ET.parse(path, forbid_dtd=True).getroot()
		except (ET.ParseError, DefusedXmlException, OSError) as error:
			errors.append(f"{path}: {error}")
			continue

		for case in root.iter("testcase"):
			seconds = None
			try:
				value = float(case.attrib["time"])
				if math.isfinite(value) and value >= 0:
					seconds = value
			except (KeyError, ValueError):
				pass

			outcome = "passed"
			if case.find("failure") is not None or case.find("error") is not None:
				outcome = "failed"
			elif case.find("skipped") is not None:
				outcome = "skipped"

			tests.append({"class": case.get("classname", ""), "name": case.get("name", ""), "seconds": seconds, "outcome": outcome})

	return {
		"xml_files": len(files),
		"xml_errors": errors,
		"counts": {outcome: sum(test["outcome"] == outcome for test in tests) for outcome in ("passed", "failed", "skipped")},
		"tests": tests,
	}


def mark(directory, name):
	marker = {"epoch": time.time(), "monotonic": time.monotonic()}
	pending = directory / f"{name}.json.tmp"
	pending.write_text(json.dumps(marker) + "\n")
	pending.replace(directory / f"{name}.json")


def monitor(directory, serial):
	stop = threading.Event()
	signal.signal(signal.SIGTERM, lambda *_: stop.set())
	signal.signal(signal.SIGINT, lambda *_: stop.set())
	with (directory / "test-runner.log").open("w") as runner_log, (directory / "device-samples.jsonl").open("w") as samples:
		logcat = subprocess.Popen(
			["adb", "-s", serial, "logcat", "-v", "epoch", "-T", "1", "-s", "TestRunner:I", "AndroidJUnitRunner:I", "MonitoringInstr:I", "InstrConnection:I", "*:S"],
			stdout=runner_log,
			stderr=runner_log,
		)

		try:
			while not stop.is_set():
				sample = {"epoch": time.time(), "runner_load": os.getloadavg(), "runner_cpus": os.cpu_count()}
				memory = Path("/proc/meminfo")
				if memory.exists():
					sample["runner_memory_kib"] = {name: int(value) for name, value in re.findall(r"^(MemTotal|MemAvailable|SwapFree):\s+(\d+) kB", memory.read_text(), re.MULTILINE)}

				started = time.monotonic()
				try:
					probe = subprocess.run(
						["adb", "-s", serial, "shell", "printf 'boot='; getprop sys.boot_completed; cat /proc/uptime /proc/loadavg; pidof xyz.attacktive.weatherd xyz.attacktive.weatherd.test; true"],
						capture_output=True,
						text=True,
						timeout=5,
					)

					sample.update({"adb_exit": probe.returncode, "device": probe.stdout, "adb_error": probe.stderr})
				except subprocess.TimeoutExpired:
					sample["adb_error"] = "Diagnostic ADB probe exceeded 5 seconds; test invocation remains unrestricted."

				sample["adb_seconds"] = time.monotonic() - started
				samples.write(json.dumps(sample) + "\n")
				samples.flush()
				stop.wait(15)
		finally:
			logcat.terminate()
			try:
				logcat.wait(timeout=5)
			except subprocess.TimeoutExpired:
				logcat.kill()
				logcat.wait()


def report(directory, results):
	markers = {}
	marker_errors = []
	phases = {
		"Dependency/cache setup": ("setup-start", "setup-end"),
		"Gradle static/build gate": ("static-start", "static-end"),
		"Emulator provisioning/boot": ("emulator-start", "emulator-ready"),
		"Wait for static/build after boot": ("emulator-ready", "instrumentation-start"),
		"Connected Gradle invocation": ("instrumentation-start", "instrumentation-end"),
	}

	for name in sorted({endpoint for bounds in phases.values() for endpoint in bounds}):
		path = directory / f"{name}.json"
		if not path.exists():
			continue

		try:
			markers[name] = json.loads(path.read_text())
		except (ValueError, OSError) as error:
			marker_errors.append(f"{path}: {error}")

	summary = {
		"marker_errors": marker_errors,
		"phases_seconds": {name: elapsed(markers, *bounds) for name, bounds in phases.items()},
		"gradle": {gate: task_statistics(directory / f"{gate}.log") for gate in ("static", "instrumentation")},
	}

	tests = android_tests(results)
	(directory / "android-test-timings.json").write_text(json.dumps(tests, indent=2) + "\n")
	summary["android_tests"] = {key: value for key, value in tests.items() if key != "tests"}
	(directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
	lines = ["## Test critical path", "", "Overlapping phases are independent, not additive. Unknown means an endpoint was not reached.", "", "| Phase | Seconds |", "| --- | ---: |"]
	for name, seconds in summary["phases_seconds"].items():
		value = "Unknown"
		if seconds is not None:
			value = f"{seconds:.2f}"

		lines.append(f"| {name} | {value} |")

	for error in marker_errors:
		lines.append(f"\nUnavailable marker: {error}")

	lines.extend(["", "### Gradle task outcomes", "", "Cache hit rate counts FROM-CACHE / (FROM-CACHE + executed cache-keyed tasks); UP-TO-DATE is same-runner reuse, not a cache hit.", ""])
	for gate, stats in summary["gradle"].items():
		rate = "Unknown (no cache-keyed executions observed)"
		if stats["cache_hit_rate"] is not None:
			rate = f'{stats["cache_hit_rate"]:.1%}'

		lines.append(f'- {gate}: {json.dumps(stats["outcomes"], sort_keys=True)}; task cache hit rate: {rate}.')

	lines.extend(["", "### Android test timings", "", f'XML files: {tests["xml_files"]}; outcomes: {json.dumps(tests["counts"], sort_keys=True)}.', "", "Individual durations are in android-test-timings.json; test-time sums are not connected-task wall time.", "", "| Slowest test | Seconds | Outcome |", "| --- | ---: | --- |"])
	known = [test for test in tests["tests"] if test["seconds"] is not None]
	for test in sorted(known, key=lambda test: test["seconds"], reverse=True)[:10]:
		name = f'{test["class"]}.{test["name"]}'.replace("|", "\\|").replace("\n", " ")
		lines.append(f'| {name} | {test["seconds"]:.3f} | {test["outcome"]} |')

	if not tests["xml_files"]:
		lines.extend(["", "No Android result XML was produced; do not interpret this as a passing or zero-duration suite."])

	for error in tests["xml_errors"]:
		lines.append(f"\nIncomplete/unreadable XML: {error}")

	lines.extend(["", "Artifacts retain Gradle logs/profiles, result XML, runner-tagged logcat, and 15-second numeric host/device samples. A probe timeout diagnoses ADB health only; it never limits the tests. Workflow/job wall time and post-action cache restore/save overhead come from the Actions job/step timestamps.", ""])
	text = "\n".join(lines)
	(directory / "summary.md").write_text(text)
	print(text)
	if os.environ.get("GITHUB_STEP_SUMMARY"):
		with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as output:
			output.write(text)


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("command", choices=("mark", "monitor", "report"))
	parser.add_argument("value", nargs="?")
	parser.add_argument("--directory", type=Path, default=Path(os.environ.get("RUNNER_TEMP", "build")) / "weatherd-ci")
	parser.add_argument("--results", type=Path, default=Path("app/build/outputs/androidTest-results/connected"))
	args = parser.parse_args()
	args.directory.mkdir(parents=True, exist_ok=True)
	if args.command in ("mark", "monitor") and not args.value:
		parser.error(f"{args.command} requires a marker name or device serial")

	if args.command == "mark":
		mark(args.directory, args.value)
	elif args.command == "monitor":
		monitor(args.directory, args.value)
	else:
		report(args.directory, args.results)


if __name__ == "__main__":
	main()
