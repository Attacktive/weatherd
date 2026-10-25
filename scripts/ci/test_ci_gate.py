import json
import os
import shlex
import shutil
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


class CiGateTest(unittest.TestCase):
	def test_canceled_report_does_not_wait_for_unfinished_static_process(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			scripts = root / "scripts/ci"
			scripts.mkdir(parents=True)
			for name in ("overlap-test-gate.sh", "ci_metrics.py"):
				shutil.copy2(Path(__file__).parent / name, scripts / name)

			diagnostics = root / "weatherd-ci"
			diagnostics.mkdir()
			(diagnostics / "static.pid").write_text(str(os.getpid()))
			environment = dict(os.environ, RUNNER_TEMP=str(root), CI_CANCELLED="true", PATH=str(Path(sys.executable).parent) + os.pathsep + os.environ["PATH"], GITHUB_STEP_SUMMARY=str(root / "step-summary.md"))
			result = subprocess.run(["bash", "scripts/ci/overlap-test-gate.sh", "report"], cwd=root, env=environment, capture_output=True, timeout=3)
			self.assertEqual(result.returncode, 0, (result.stdout, result.stderr))
			summary = json.loads((diagnostics / "summary.json").read_text())
			self.assertIsNone(summary["phases_seconds"]["Gradle static/build gate"])

	def test_monitor_and_logcat_are_reaped_on_success_and_failure(self):
		for connected_status in (0, 17):
			with self.subTest(connected_status=connected_status), tempfile.TemporaryDirectory() as directory:
				root = Path(directory)
				scripts = root / "scripts/ci"
				scripts.mkdir(parents=True)
				for name in ("overlap-test-gate.sh", "ci_metrics.py"):
					shutil.copy2(Path(__file__).parent / name, scripts / name)

				bin_directory = root / "bin"
				bin_directory.mkdir()
				python = bin_directory / "python3"
				python.write_text(f'#!/bin/sh\nexec {shlex.quote(sys.executable)} "$@"\n')
				python.chmod(0o755)
				adb = bin_directory / "adb"
				adb.write_text(
					'#!/usr/bin/env python3\n'
					'import os, pathlib, sys, time\n'
					'if "logcat" in sys.argv:\n'
					'    (pathlib.Path(os.environ["RUNNER_TEMP"]) / "logcat.pid").write_text(str(os.getpid()))\n'
					'    time.sleep(60)\n'
					'else:\n'
					'    print("boot=1")\n'
				)

				adb.chmod(0o755)
				gradle = root / "gradlew"
				gradle.write_text(
					'#!/usr/bin/env python3\n'
					'import os, pathlib, sys, time\n'
					'if ":app:connectedDebugAndroidTest" in sys.argv:\n'
					'    for attempt in range(250):\n'
					'        if (pathlib.Path(os.environ["RUNNER_TEMP"]) / "logcat.pid").exists():\n'
					'            sys.exit(int(os.environ["CONNECTED_STATUS"]))\n'
					'        time.sleep(0.02)\n'
					'    sys.exit(99)\n'
				)

				gradle.chmod(0o755)
				environment = dict(os.environ, RUNNER_TEMP=str(root), PATH=str(bin_directory) + os.pathsep + os.environ["PATH"], CONNECTED_STATUS=str(connected_status))
				subprocess.run(["bash", "scripts/ci/overlap-test-gate.sh", "start"], cwd=root, env=environment, check=True, capture_output=True, timeout=10)
				invocation = subprocess.Popen(["bash", "scripts/ci/overlap-test-gate.sh", "instrumentation"], cwd=root, env=environment, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
				try:
					stdout, stderr = invocation.communicate(timeout=15)
					self.assertEqual(invocation.returncode, connected_status, (stdout, stderr))
					logcat_pid = int((root / "logcat.pid").read_text())
					with self.assertRaises(ProcessLookupError):
						os.kill(logcat_pid, 0)
				finally:
					try:
						os.killpg(invocation.pid, signal.SIGTERM)
					except ProcessLookupError:
						pass

					invocation.wait(timeout=10)


if __name__ == "__main__":
	unittest.main()
