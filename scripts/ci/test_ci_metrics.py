import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ci_metrics import android_tests, elapsed, report, task_statistics


class CiMetricsTest(unittest.TestCase):
	def test_overlapping_intervals_are_measured_independently(self):
		markers = {
			"static-start": {"monotonic": 10},
			"static-end": {"monotonic": 100},
			"emulator-start": {"monotonic": 12},
			"emulator-ready": {"monotonic": 42},
			"instrumentation-start": {"monotonic": 101},
			"instrumentation-end": {"monotonic": 121},
		}

		self.assertEqual(elapsed(markers, "static-start", "static-end"), 90)
		self.assertEqual(elapsed(markers, "emulator-start", "emulator-ready"), 30)
		self.assertEqual(elapsed(markers, "instrumentation-start", "instrumentation-end"), 20)

	def test_interrupted_marker_does_not_discard_other_phases_or_test_results(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			(root / "static-start.json").write_text('{"monotonic": 10}')
			(root / "static-end.json").write_text('{"monotonic": 13}')
			(root / "setup-start.json").write_text('{"monotonic":')
			results = root / "results"
			results.mkdir()
			(results / "test.xml").write_text('<testsuite><testcase classname="Suite" name="done" time="2"/></testsuite>')
			with contextlib.redirect_stdout(io.StringIO()), patch.dict("os.environ", {"GITHUB_STEP_SUMMARY": ""}):
				report(root, results)

			summary = json.loads((root / "summary.json").read_text())
			test_timings = json.loads((root / "android-test-timings.json").read_text())

		self.assertEqual(summary["phases_seconds"]["Gradle static/build gate"], 3)
		self.assertIsNone(summary["phases_seconds"]["Dependency/cache setup"])
		self.assertIn("setup-start.json", summary["marker_errors"][0])
		self.assertEqual(test_timings["tests"][0]["seconds"], 2)

	def test_unfinished_or_reversed_interval_is_unknown_not_zero(self):
		markers = {"start": {"monotonic": 20}, "end": {"monotonic": 10}}
		self.assertIsNone(elapsed(markers, "start", "missing"))
		self.assertIsNone(elapsed(markers, "missing", "end"))
		self.assertIsNone(elapsed(markers, "start", "end"))

	def test_cache_rate_excludes_uncacheable_and_up_to_date_tasks(self):
		with tempfile.TemporaryDirectory() as directory:
			log = Path(directory) / "static.log"
			log.write_text(
				"Build cache key for task ':app:compile' is abc\n"
				"> Task :app:compile FROM-CACHE\n"
				"Build cache key for task ':app:test' is def\n"
				"> Task :app:test\n"
				"> Task :app:package\n"
				"> Task :app:assemble UP-TO-DATE\n"
				"> Task :app:empty NO-SOURCE\n"
				"> Task :app:excluded SKIPPED\n"
			)

			stats = task_statistics(log)

		self.assertEqual(stats["cache_hits"], 1)
		self.assertEqual(stats["cache_misses"], 1)
		self.assertEqual(stats["cache_hit_rate"], 0.5)
		self.assertEqual(stats["outcomes"], {"FROM-CACHE": 1, "EXECUTED": 2, "UP-TO-DATE": 1, "NO-SOURCE": 1, "SKIPPED": 1})

	def test_disabled_or_unobserved_cache_has_no_invented_hit_rate(self):
		with tempfile.TemporaryDirectory() as directory:
			log = Path(directory) / "static.log"
			self.assertIsNone(task_statistics(log)["cache_hit_rate"])
			log.write_text("> Task :app:test\n")
			self.assertIsNone(task_statistics(log)["cache_hit_rate"])

	def test_xml_exports_each_test_without_treating_skips_as_passes(self):
		with tempfile.TemporaryDirectory() as directory:
			results = Path(directory)
			(results / "TEST-suite.xml").write_text(
				'<testsuite tests="4" time="15">'
				'<testcase classname="Suite" name="fast" time="0.25"/>'
				'<testcase classname="Suite" name="slow" time="10.5"><failure message="failure"/></testcase>'
				'<testcase classname="Suite" name="skipped" time="0"><skipped/></testcase>'
				'<testcase classname="Suite" name="unknown"/>'
				'</testsuite>'
			)

			report = android_tests(results)

		self.assertEqual(report["counts"], {"passed": 2, "failed": 1, "skipped": 1})
		self.assertEqual(report["tests"], [
			{"class": "Suite", "name": "fast", "seconds": 0.25, "outcome": "passed"},
			{"class": "Suite", "name": "slow", "seconds": 10.5, "outcome": "failed"},
			{"class": "Suite", "name": "skipped", "seconds": 0.0, "outcome": "skipped"},
			{"class": "Suite", "name": "unknown", "seconds": None, "outcome": "passed"},
		])

		self.assertEqual(report["xml_errors"], [])

	def test_entity_definitions_are_rejected_without_hiding_other_results(self):
		with tempfile.TemporaryDirectory() as directory:
			results = Path(directory)
			(results / "unsafe.xml").write_text('<!DOCTYPE testsuite [<!ENTITY injected "not-a-test">]><testsuite><testcase name="&injected;" time="1"/></testsuite>')
			(results / "valid.xml").write_text('<testsuite><testcase classname="Suite" name="done" time="2"/></testsuite>')
			report = android_tests(results)

		self.assertEqual(report["counts"], {"passed": 1, "failed": 0, "skipped": 0})
		self.assertEqual(report["tests"][0]["name"], "done")
		self.assertIn("unsafe.xml", report["xml_errors"][0])

	def test_incomplete_xml_is_reported_without_discarding_complete_suites(self):
		with tempfile.TemporaryDirectory() as directory:
			results = Path(directory)
			(results / "complete.xml").write_text('<testsuite><testcase classname="Suite" name="done" time="2"/></testsuite>')
			(results / "partial.xml").write_text('<testsuite><testcase')
			report = android_tests(results)

		self.assertEqual(report["counts"], {"passed": 1, "failed": 0, "skipped": 0})
		self.assertEqual(len(report["xml_errors"]), 1)
		self.assertIn("partial.xml", report["xml_errors"][0])

	def test_missing_results_are_distinguished_from_an_empty_suite(self):
		with tempfile.TemporaryDirectory() as directory:
			results = Path(directory)
			self.assertEqual(android_tests(results)["xml_files"], 0)
			(results / "empty.xml").write_text('<testsuite tests="0"/>')
			self.assertEqual(android_tests(results)["xml_files"], 1)


if __name__ == "__main__":
	unittest.main()
