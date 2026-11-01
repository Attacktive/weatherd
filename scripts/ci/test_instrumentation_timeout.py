import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


@unittest.skipUnless(shutil.which("timeout"), "GNU timeout is required by the Linux CI harness")
class InstrumentationTimeoutTest(unittest.TestCase):
	def run_fixture(self, exit_status, timeout_seconds=1):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			scripts = root / "scripts/ci"
			scripts.mkdir(parents=True)
			for name in ("overlap-test-gate.sh", "ci_metrics.py"):
				shutil.copy2(Path(__file__).parent / name, scripts / name)

			diagnostics = root / "weatherd-ci"
			diagnostics.mkdir()
			(diagnostics / "static.pid").write_text(str(os.getpid()))
			(diagnostics / "static.status").write_text("0\n")
			(diagnostics / "static-published").touch()

			binary = root / "bin"
			binary.mkdir()
			adb = binary / "adb"
			adb.write_text(
				"#!/usr/bin/env python3\n"
				"import sys\n"
				"print('adb-probe ' + ' '.join(sys.argv[1:]), flush=True)\n"
			)
			adb.chmod(0o755)

			gradle = root / "gradlew"
			gradle.write_text(
				"#!/usr/bin/env python3\n"
				"import os, pathlib, sys, time\n"
				"pathlib.Path(os.environ['RUNNER_TEMP'], 'gradle.pid').write_text(str(os.getpid()))\n"
				"print('test process started', flush=True)\n"
				"if os.environ['GRADLE_TEST_STATUS'] == 'timeout':\n"
				"    time.sleep(30)\n"
				"else:\n"
				"    sys.exit(int(os.environ['GRADLE_TEST_STATUS']))\n"
			)
			gradle.chmod(0o755)

			environment = dict(
				os.environ,
				RUNNER_TEMP=str(root),
				PATH=str(binary) + os.pathsep + os.environ["PATH"],
				GRADLE_TEST_STATUS=str(exit_status),
				WEATHERD_INSTRUMENTATION_TIMEOUT_SECONDS=str(timeout_seconds),
				ANDROID_SERIAL="emulator-5554"
			)
			result = subprocess.run(
				["bash", "scripts/ci/overlap-test-gate.sh", "instrumentation"],
				cwd=root,
				env=environment,
				capture_output=True,
				text=True,
				timeout=15
			)

			self.assertIn("test process started", result.stdout)
			self.assertTrue((diagnostics / "instrumentation-end.json").exists())
			self.assertEqual((diagnostics / "instrumentation.log").read_text().strip(), "test process started")

			if exit_status == "timeout":
				self.assertEqual(result.returncode, 124, (result.stdout, result.stderr))
				self.assertIn("exceeded its 1-second wall-time budget", result.stderr)
				self.assertTrue((diagnostics / "instrumentation-timeout.txt").exists())
				for name in ("timeout-anr-events.log", "timeout-activity-anr.txt", "timeout-window.txt", "timeout-processes.txt"):
					self.assertTrue((diagnostics / name).exists(), name)
					self.assertIn("emulator-5554", (diagnostics / name).read_text())
					self.assertLessEqual((diagnostics / name).stat().st_size, 131072)
			else:
				self.assertEqual(result.returncode, exit_status, (result.stdout, result.stderr))
				self.assertFalse((diagnostics / "instrumentation-timeout.txt").exists())

	def test_success_preserves_existing_result_without_diagnostic_capture(self):
		self.run_fixture(0)

	def test_test_failures_are_not_masked_as_timeouts(self):
		self.run_fixture(17)

	def test_timeout_fails_and_retains_bounded_anr_evidence(self):
		self.run_fixture("timeout")


if __name__ == "__main__":
	unittest.main()
