import json
import urllib.error
# Only TimeoutExpired fixtures are constructed here; this module does not launch processes.
import subprocess  # nosec B404
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

from provider_evaluation.runner import capture_run, clone_avd, clone_config, match_run, next_slot, pending_runs, require_emulator_serial, require_successful_instrumentation, summary, validate_capture, write_once
from provider_evaluation.observations import match_capture
from provider_evaluation.test_observations import sample_capture, sample_observation, sample_station


class RunnerTest(unittest.TestCase):
	def test_clone_preserves_hardware_without_reusing_source_userdata(self):
		source = 'AvdId=Pixel\navd.ini.displayname=Pixel\nhw.gps=no\nhw.lcd.width=2560\nimage.sysdir.1=system-images/android-37.2/test/arm64-v8a/\ndisk.dataPartition.path=/private/live.img\nsdcard.path=/private/live.sdcard\n'
		result = clone_config(source, 'Pixel_Weatherd_Provider_Evaluation')
		self.assertIn('hw.gps=no\n', result)
		self.assertIn('hw.lcd.width=2560\n', result)
		self.assertIn('AvdId=Pixel_Weatherd_Provider_Evaluation\n', result)
		self.assertNotIn('/private/live', result)
		self.assertIn('fastboot.forceColdBoot=yes\n', result)

	def test_physical_or_ambiguous_adb_targets_are_rejected(self):
		for serial in ('', 'R3CW70JG1XV', 'adb-phone._tcp', 'emulator-5556; reboot', 'emulator-5555'):
			with self.subTest(serial=serial), self.assertRaises(ValueError):
				require_emulator_serial(serial)

		require_emulator_serial('emulator-5556')

	def test_evidence_write_cannot_replace_previous_capture(self):
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / 'report.txt'
			write_once(path, 'original report')
			with self.assertRaises(FileExistsError):
				write_once(path, 'replacement')

			self.assertEqual(path.read_text(), 'original report')

	def test_schedule_uses_next_utc_boundary_not_capture_duration(self):
		self.assertEqual(next_slot(1800, 1800), 3600)
		self.assertEqual(next_slot(1801, 1800), 3600)
		self.assertEqual(next_slot(3599.5, 1800), 3600)
		with self.assertRaises(ValueError):
			next_slot(1800, 0)

	def test_clone_records_baseline_before_creating_independent_output(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			avds = root / 'avd'
			source = avds / 'Pixel.avd'
			source.mkdir(parents=True)
			original = 'AvdId=Pixel\nhw.gps=no\nimage.sysdir.1=system-images/test/\ntarget=android-37\n'
			(source / 'config.ini').write_text(original)
			(source / 'userdata-qemu.img').write_bytes(b'private account data')
			(root / 'sdk/system-images/test').mkdir(parents=True)
			ledger = root / 'evidence'
			clone = clone_avd('Pixel', 'Pixel_Weatherd_Provider_Evaluation', ledger, avds, root / 'sdk')
			self.assertEqual((source / 'config.ini').read_text(), original)
			self.assertEqual((ledger / 'emulator/Pixel_Weatherd_Provider_Evaluation/source-config.ini').read_text(), original)
			self.assertTrue((ledger / 'emulator/Pixel_Weatherd_Provider_Evaluation/creation.json').is_file())
			self.assertEqual({item.name for item in clone.iterdir()}, {'config.ini'})
			with self.assertRaises(FileExistsError):
				clone_avd('Pixel', 'Pixel_Weatherd_Provider_Evaluation', ledger, avds, root / 'sdk')

	def test_instrumentation_failure_is_rejected_even_when_adb_exits_zero(self):
		for output in (b'FAILURES!!!\nTests run: 1, Failures: 1\n', b'INSTRUMENTATION_FAILED: Process crashed.\n', b''):
			with self.subTest(output=output), self.assertRaises(RuntimeError):
				require_successful_instrumentation(output)

	def test_pending_selection_respects_latest_match_and_retains_old_evidence(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			pending = root / 'runs/pending'
			matched = root / 'runs/matched'
			for run in (pending, matched):
				run.mkdir(parents=True)
				(run / 'capture.json').write_text('{}')
				(run / 'match-1.json').write_text('{"match":{"status":"PENDING"}}')

			(matched / 'match-2.json').write_text('{"match":{"status":"MATCHED"}}')
			self.assertEqual(pending_runs(root), [pending])
			self.assertEqual((matched / 'match-1.json').read_text(), '{"match":{"status":"PENDING"}}')

	def test_capture_rejects_wrong_station_location_mode_or_unverified_coordinates(self):
		station = {'icaoId': 'LIRF', 'lat': 41.8, 'lon': 12.239}
		capture = {'station_id': 'LIRF', 'location_source': 'MANUAL_STATION', 'device_location': False, 'location_verified': True, 'simulator_active': False, 'latitude': 41.8, 'longitude': 12.239}
		for field, value in (('station_id', 'LIMC'), ('location_source', 'DEVICE'), ('device_location', True), ('location_verified', None), ('simulator_active', True), ('latitude', 41.9), ('longitude', float('nan'))):
			with self.subTest(field=field), self.assertRaises(ValueError):
				validate_capture({**capture, field: value}, station)

	def test_empty_station_response_is_a_retained_recoverable_collection_error(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			with patch('provider_evaluation.runner.adb', return_value=b'Pixel_Weatherd_Provider_Evaluation\nOK\n'), patch('provider_evaluation.observations.fetch_station', return_value={'records': []}):
				with self.assertRaises(ValueError):
					capture_run('emulator-5556', 'LIRF', root, 'ROUTINE')

			run = next((root / 'runs').iterdir())
			self.assertEqual(json.loads((run / 'failure.json').read_text())['type'], 'ValueError')
			self.assertEqual(json.loads((run / 'station.json').read_text())['records'], [])

	def test_instrumentation_timeout_preserves_partial_output_without_fake_report(self):
		def fake_adb(serial, *arguments, **kwargs):
			if arguments == ('emu', 'avd', 'name'):
				return b'Pixel_Weatherd_Provider_Evaluation\nOK\n'

			if arguments == ('shell', 'date', '+%s'):
				return str(int(time.time())).encode()

			if arguments[:3] == ('shell', 'am', 'instrument'):
				raise subprocess.TimeoutExpired(['adb', 'shell', 'am', 'instrument'], 180, output=b'collector started\n', stderr=b'blocked response\n')

			raise AssertionError(f'Unexpected device operation: {arguments}')

		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			with patch('provider_evaluation.runner.adb', side_effect=fake_adb), patch('provider_evaluation.observations.fetch_station', return_value={'records': [{'icaoId': 'LIRF', 'lat': 41.8, 'lon': 12.239}]}):
				with self.assertRaises(subprocess.TimeoutExpired):
					capture_run('emulator-5556', 'LIRF', root, 'ROUTINE')

			run = next((root / 'runs').iterdir())
			self.assertEqual((run / 'instrumentation.txt').read_bytes(), b'collector started\n')
			failure = json.loads((run / 'failure.json').read_text())
			self.assertEqual(failure['stdout'], 'collector started\n')
			self.assertEqual(failure['stderr'], 'blocked response\n')
			self.assertFalse((run / 'report.txt').exists())

	def _create_run_fixtures(self, directory, collection='ROUTINE', captured_at=None, provider_status='SUCCESS'):
		directory = Path(directory)
		directory.mkdir(parents=True, exist_ok=True)
		if captured_at is None:
			captured_at = int(time.time()) - 3600

		run_payload = {
			'created_at_utc': '2026-10-21T00:00:00Z',
			'serial': 'emulator-5556',
			'avd': 'Pixel_Weatherd_Provider_Evaluation',
			'station_id': 'LIRF',
			'collection': collection,
			'scheduled_at_epoch_seconds': captured_at
		}
		(directory / 'run.json').write_text(json.dumps(run_payload))

		station_payload = {
			'records': [
				{
					'icaoId': 'LIRF',
					'lat': 41.8,
					'lon': 12.239,
					'country': 'IT'
				}
			]
		}
		(directory / 'station.json').write_text(json.dumps(station_payload))

		capture_payload = {
			'station_id': 'LIRF',
			'location_source': 'MANUAL_STATION',
			'device_location': False,
			'location_verified': True,
			'simulator_active': False,
			'latitude': 41.8,
			'longitude': 12.239,
			'captured_at_epoch_seconds': captured_at,
			'collection': collection,
			'samples': [
				{
					'provider': 'OPEN_METEO',
					'status': provider_status,
					'condition': 'CLEAR_SKY',
					'temperature_celsius': 18.0
				}
			]
		}
		(directory / 'capture.json').write_text(json.dumps(capture_payload))
		(directory / 'report.txt').write_text('Tester observed: UNSURE\n')

	def _write_match_fixture(self, run, status, reason, attempts=None, index=1):
		payload = {
			'capture': json.loads((run / 'capture.json').read_text()),
			'station': json.loads((run / 'station.json').read_text())['records'][0],
			'match': {'status': status, 'reason': reason, 'samples': []},
			'matched_at_utc': '2026-10-21T00:00:00Z'
		}
		if attempts is not None:
			payload['match']['attempts'] = attempts

		(run / f'match-{index:04d}.json').write_text(json.dumps(payload))

	def test_pending_bound_expires_after_96_attempts_and_retains_cause_and_original_reason(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/run-96-bound'
			self._create_run_fixtures(run, captured_at=int(time.time()) - 3600)
			for index in range(1, 96):
				self._write_match_fixture(run, 'PENDING', 'no_observation_within_tolerance', attempts=index, index=index)

			with patch('provider_evaluation.observations.fetch_observations', return_value={'source_url': 'https://example.com', 'retrieved_at_utc': '2026-10-21T00:00:00Z', 'records': []}):
				result = match_run(run)

			matches = sorted(run.glob('match-*.json'))
			self.assertEqual(len(matches), 96)
			self.assertEqual(result['match']['status'], 'EXPIRED')
			self.assertEqual(result['match']['cause'], 'max_attempts_exceeded')
			self.assertEqual(result['match']['attempts'], 96)
			self.assertEqual(result['match']['original_reason'], 'no_observation_within_tolerance')
			self.assertEqual(result['match']['reason'], 'no_observation_within_tolerance')
			self.assertEqual(pending_runs(root), [])
			self.assertEqual(json.loads((run / 'match-0001.json').read_text())['match']['status'], 'PENDING')

			second_call = match_run(run)
			self.assertEqual(second_call['match']['status'], 'EXPIRED')
			self.assertEqual(len(sorted(run.glob('match-*.json'))), 96)

	def test_pending_bound_legacy_96_revisions_expires_immediately_without_fetch(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/run-legacy-96'
			self._create_run_fixtures(run, captured_at=int(time.time()) - 3600)
			for index in range(1, 97):
				self._write_match_fixture(run, 'PENDING', 'transient_network_error', index=index)

			with patch('provider_evaluation.observations.fetch_observations', side_effect=AssertionError('Network fetch must not be attempted when bound is already reached')):
				result = match_run(run)

			self.assertEqual(result['match']['status'], 'EXPIRED')
			self.assertEqual(result['match']['cause'], 'max_attempts_exceeded')
			self.assertEqual(result['match']['attempts'], 96)
			self.assertEqual(result['match']['original_reason'], 'transient_network_error')
			self.assertEqual(pending_runs(root), [])

	def test_pending_bound_expires_when_capture_age_exceeds_48_hours(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/run-age-bound'
			self._create_run_fixtures(run, captured_at=int(time.time()) - (50 * 3600))
			self._write_match_fixture(run, 'PENDING', 'no_observation_within_tolerance', attempts=1)

			with patch('provider_evaluation.observations.fetch_observations', side_effect=AssertionError('Network fetch must not be attempted when capture age exceeds 48 hours')):
				result = match_run(run)

			self.assertEqual(result['match']['status'], 'EXPIRED')
			self.assertEqual(result['match']['cause'], 'max_age_exceeded')
			self.assertEqual(result['match']['original_reason'], 'no_observation_within_tolerance')
			self.assertEqual(pending_runs(root), [])

	def test_retention_exceeded_expires_immediately_with_retention_reason(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/run-retention'
			self._create_run_fixtures(run, captured_at=int(time.time()) - (35 * 24 * 3600))
			self._write_match_fixture(run, 'PENDING', 'prior_pending_reason', attempts=1)

			with patch('provider_evaluation.observations.fetch_observations', side_effect=AssertionError('Network fetch must not be attempted when retention exceeded')):
				result = match_run(run)

			self.assertEqual(result['match']['status'], 'EXPIRED')
			self.assertEqual(result['match']['cause'], 'retention_exceeded')
			self.assertEqual(result['match']['original_reason'], 'prior_pending_reason')
			self.assertIn('AWC recent retention', result['match']['reason'])
			self.assertEqual(pending_runs(root), [])

	def test_transient_fetch_error_below_budget_remains_pending(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/run-transient'
			self._create_run_fixtures(run, captured_at=int(time.time()) - 3600)
			self._write_match_fixture(run, 'PENDING', 'initial_fetch_failed', attempts=1)

			with patch('provider_evaluation.observations.fetch_observations', side_effect=urllib.error.URLError('connection reset')):
				result = match_run(run)

			self.assertEqual(result['match']['status'], 'PENDING')
			self.assertEqual(result['match']['attempts'], 2)
			self.assertEqual(pending_runs(root), [run])

	def test_matched_run_can_rematch_and_expired_is_idempotent(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/run-matched'
			captured_at = 1792556400
			self._create_run_fixtures(run, captured_at=captured_at)
			self._write_match_fixture(run, 'MATCHED', None, attempts=1)

			updated_records = [{
				'icaoId': 'LIRF',
				'obsTime': captured_at,
				'temp': 21.0,
				'rawOb': 'METAR LIRF 210420Z 00000KT CAVOK 21/10 Q1013',
				'metarType': 'METAR'
			}]
			with patch('time.time', return_value=1792560000), patch('provider_evaluation.observations.fetch_observations', return_value={'records': updated_records}):
				result = match_run(run)

			self.assertEqual(result['match']['status'], 'MATCHED')
			self.assertEqual(result['match']['capture_observation_raw']['temp'], 21.0)
			self.assertEqual(len(sorted(run.glob('match-*.json'))), 2)

			expired_stamp = time.time_ns() + 1_000_000
			self._write_match_fixture(run, 'EXPIRED', 'no_observation_within_tolerance', attempts=96, index=expired_stamp)
			with patch('time.time', return_value=1792560000), patch('provider_evaluation.observations.fetch_observations', side_effect=AssertionError('Expired match must not fetch')):
				expired_call = match_run(run)

			self.assertEqual(expired_call['match']['status'], 'EXPIRED')
			self.assertEqual(len(sorted(run.glob('match-*.json'))), 3)

	def test_routine_collection_metrics_and_denominators_with_mixed_runs(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			runs_dir = root / 'runs'
			runs_dir.mkdir(parents=True)

			run_success = runs_dir / 'routine-success'
			self._create_run_fixtures(run_success, collection='ROUTINE', provider_status='SUCCESS')
			self._write_match_fixture(run_success, 'MATCHED', None)

			run_failure_only = runs_dir / 'routine-failure-only'
			self._create_run_fixtures(run_failure_only, collection='ROUTINE')
			(run_failure_only / 'report.txt').unlink()
			(run_failure_only / 'failure.json').write_text(json.dumps({
				'at_utc': '2026-10-21T00:00:00Z',
				'type': 'RuntimeError',
				'message': 'Device dropped offline'
			}))

			run_incomplete = runs_dir / 'routine-incomplete'
			self._create_run_fixtures(run_incomplete, collection='ROUTINE')

			run_diagnostic = runs_dir / 'diagnostic-run'
			self._create_run_fixtures(run_diagnostic, collection='DIAGNOSTIC')
			self._write_match_fixture(run_diagnostic, 'MATCHED', None)

			run_pending = runs_dir / 'routine-pending-obs'
			self._create_run_fixtures(run_pending, collection='ROUTINE', provider_status='SUCCESS')
			self._write_match_fixture(run_pending, 'PENDING', 'no_observation_within_tolerance')

			run_provider_fail = runs_dir / 'routine-provider-failure'
			self._create_run_fixtures(run_provider_fail, collection='ROUTINE', provider_status='REQUEST_FAILED')
			self._write_match_fixture(run_provider_fail, 'MATCHED', None)

			run_override = runs_dir / 'routine-failure-override'
			self._create_run_fixtures(run_override, collection='ROUTINE')
			self._write_match_fixture(run_override, 'MATCHED', None)
			(run_override / 'failure.json').write_text(json.dumps({
				'at_utc': '2026-10-21T00:00:00Z',
				'type': 'ValueError',
				'message': 'Post-collection validation failed'
			}))

			result = summary(root)

			routine = result['routine_collection']
			self.assertEqual(routine['attempt_count'], 6)
			self.assertEqual(routine['success_count'], 3)
			self.assertEqual(routine['failure_count'], 2)
			self.assertEqual(routine['incomplete_count'], 1)
			self.assertEqual(routine['success_rate'], 0.5)
			self.assertEqual(len(result['collection_failures']), 2)

			om_availability = result['providers']['OPEN_METEO']['availability']
			self.assertEqual(om_availability['total_count'], 3)
			self.assertEqual(om_availability['available_count'], 2)

	def _create_evaluable_run(self, run, attempts=1):
		self._create_run_fixtures(run, captured_at=1792556400)
		capture = sample_capture()
		station = sample_station()
		(run / 'capture.json').write_text(json.dumps(capture))
		(run / 'station.json').write_text(json.dumps({'records': [station]}))
		result = {
			'capture': capture,
			'station': station,
			'match': match_capture(capture, station, [sample_observation()]),
			'matched_at_utc': '2026-10-21T04:21:00Z'
		}
		result['match']['attempts'] = attempts
		(run / 'match-0001.json').write_text(json.dumps(result))
		return result

	def _assert_retained_accuracy(self, root):
		comparison = summary(root)['comparisons_against_italia_meteo']['OPEN_METEO']
		self.assertEqual(comparison['cloud']['shared_eligible_denominator'], 1)
		self.assertEqual(comparison['cloud']['provider_agreed_count'], 1)
		self.assertEqual(comparison['temperature']['shared_eligible_denominator'], 1)
		self.assertEqual(comparison['temperature']['provider_mean_absolute_error_celsius'], 0.5)
		self.assertEqual(comparison['temperature']['italia_meteo_mean_absolute_error_celsius'], 0.0)

	def test_matched_accuracy_survives_rematch_age_retention_and_attempt_bounds(self):
		for age_hours, attempts in ((49, 1), (35 * 24, 1), (1, 96)):
			with self.subTest(age_hours=age_hours, attempts=attempts), tempfile.TemporaryDirectory() as directory:
				root = Path(directory)
				run = root / 'runs/matched'
				original = self._create_evaluable_run(run, attempts)
				original_bytes = (run / 'match-0001.json').read_bytes()
				self._assert_retained_accuracy(root)
				with patch('time.time', return_value=1792556400 + age_hours * 3600), patch('provider_evaluation.observations.fetch_observations', side_effect=AssertionError('Bounded rematch must not fetch')):
					result = match_run(run)

				self._assert_retained_accuracy(root)
				self.assertEqual(result, original)
				self.assertEqual(pending_runs(root), [])
				self.assertEqual(list(run.glob('match-*.json')), [run / 'match-0001.json'])
				self.assertEqual((run / 'match-0001.json').read_bytes(), original_bytes)
				diagnostic = json.loads(next(run.glob('rematch-*.json')).read_text())
				self.assertEqual(diagnostic['match']['status'], 'SKIPPED')

	def test_matched_accuracy_survives_failed_or_unmatched_optional_fetch(self):
		for error in (urllib.error.URLError('connection reset'), None):
			with self.subTest(error=error), tempfile.TemporaryDirectory() as directory:
				root = Path(directory)
				run = root / 'runs/matched'
				original = self._create_evaluable_run(run)
				self._assert_retained_accuracy(root)
				with patch('time.time', return_value=1792560000), patch('provider_evaluation.observations.fetch_observations', side_effect=error, return_value={'records': []}):
					result = match_run(run)

				self._assert_retained_accuracy(root)
				self.assertEqual(result, original)
				self.assertEqual(pending_runs(root), [])
				self.assertEqual(list(run.glob('match-*.json')), [run / 'match-0001.json'])
				diagnostic = json.loads(next(run.glob('rematch-*.json')).read_text())
				self.assertEqual(diagnostic['match']['status'], 'PENDING')
				if error is not None:
					self.assertIn('connection reset', diagnostic['match']['reason'])

	def test_successful_corrected_rematch_replaces_prior_accuracy(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/matched'
			self._create_evaluable_run(run)
			self._assert_retained_accuracy(root)
			correction = sample_observation(temp=20.0, raw_ob='METAR LIRF 210420Z 14010KT 9999 SCT025 20/18 Q1011 NOSIG')
			correction['metarType'] = 'COR'
			with patch('time.time', return_value=1792560000), patch('provider_evaluation.observations.fetch_observations', return_value={'records': [correction]}):
				result = match_run(run)

			self.assertEqual(result['match']['status'], 'MATCHED')
			comparison = summary(root)['comparisons_against_italia_meteo']['OPEN_METEO']
			self.assertEqual(comparison['cloud']['shared_eligible_denominator'], 0)
			self.assertEqual(comparison['temperature']['shared_eligible_denominator'], 1)
			self.assertEqual(comparison['temperature']['italia_meteo_mean_absolute_error_celsius'], 1.0)

	def test_failed_optional_rematch_consumes_remaining_attempt_budget(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			run = root / 'runs/matched'
			original = self._create_evaluable_run(run, attempts=95)
			with patch('time.time', return_value=1792560000), patch('provider_evaluation.observations.fetch_observations', side_effect=urllib.error.URLError('connection reset')):
				self.assertEqual(match_run(run), original)

			update = sample_observation(temp=20.0, raw_ob='METAR LIRF 210420Z 14010KT 9999 SCT025 20/18 Q1011 NOSIG')
			with patch('time.time', return_value=1792560000), patch('provider_evaluation.observations.fetch_observations', return_value={'records': [update]}):
				result = match_run(run)

			self._assert_retained_accuracy(root)
			self.assertEqual(result, original)
			self.assertEqual(list(run.glob('match-*.json')), [run / 'match-0001.json'])
			latest_attempt = json.loads(sorted(run.glob('rematch-*.json'))[-1].read_text())
			self.assertEqual(latest_attempt['match']['cause'], 'max_attempts_exceeded')
			self.assertEqual(latest_attempt['match']['attempts'], 96)
