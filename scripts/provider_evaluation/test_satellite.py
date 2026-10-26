import hashlib
import json
import tempfile
from types import SimpleNamespace
import unittest
from pathlib import Path
from unittest.mock import patch

from provider_evaluation.satellite import (
	cloud_fraction_in_footprint,
	closest_satellite,
	decode_grib_file,
	eligible_capture,
	extract_mask,
	load_observations,
	parse_grib_valid_time,
	parse_observation,
	read_station,
	summarize_satellite
)


STATION = {'icaoId': 'LIRN', 'lat': 40.884, 'lon': 14.291, 'country': 'IT'}
STAMP = 1792886400


def satellite_observation(stamp=STAMP, icao='LIRN', clouds=2, clear=8, unprocessed=0):
	valid = clouds + clear
	total = valid + unprocessed
	return {
		'schema_version': 1,
		'source': {'product_id_declared': 'EO:EUM:DAT:MSG:CLM', 'sha256': 'a' * 64},
		'station': {'icaoId': icao, 'lat': STATION['lat'], 'lon': STATION['lon']},
		'observed_at_epoch_seconds': stamp,
		'footprint': {
			'status': 'USABLE',
			'radius_km': 15.0,
			'pixel_counts': {'clear_land': clear, 'clear_water': 0, 'cloud': clouds, 'not_processed': unprocessed},
			'total_pixels': total,
			'valid_pixels': valid,
			'cloud_fraction_percent': 100 * clouds / valid
		}
	}


def routine_capture(stamp=STAMP, station='LIRN', samples=None):
	if samples is None:
		samples = [
			{'provider': 'ITALIA_METEO', 'status': 'SUCCESS', 'cloud_total_percent': 90.0, 'valid_at_epoch_seconds': stamp},
			{'provider': 'OPEN_METEO', 'status': 'SUCCESS', 'cloud_total_percent': 25.0, 'valid_at_epoch_seconds': stamp},
			{'provider': 'MET_NORWAY', 'status': 'FAILURE', 'cloud_total_percent': None, 'valid_at_epoch_seconds': stamp}
		]

	return {
		'collection': 'ROUTINE',
		'station_id': station,
		'location_source': 'MANUAL_STATION',
		'device_location': False,
		'location_verified': True,
		'simulator_active': False,
		'latitude': STATION['lat'],
		'longitude': STATION['lon'],
		'captured_at_epoch_seconds': stamp,
		'samples': samples
	}


class SatelliteTest(unittest.TestCase):
	def test_grib_valid_time_is_utc_and_requires_real_metadata(self):
		self.assertEqual(parse_grib_valid_time(lambda k: {'validityDate': 20261025, 'validityTime': 1600}[k]), 1792944000)
		with self.assertRaises(ValueError):
			parse_grib_valid_time(lambda k: {'dataDate': 20261025}[k])

	def test_grib_decoder_uses_python_array_accessors_and_verifies_parameter(self):
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / 'cloud-mask.grib2'
			path.write_bytes(b'test message')
			keys = {
				'edition': 2,
				'discipline': 3,
				'parameterCategory': 0,
				'parameterNumber': 7,
				'validityDate': 20261025,
				'validityTime': 1600,
				'gridType': 'space_view'
			}
			seen_keys = []
			def fake_eccodes():
				messages = iter([object(), None])
				return SimpleNamespace(
					codes_grib_new_from_file=lambda stream: next(messages),
					codes_get=lambda message, key: keys[key],
					codes_get_array=lambda message, key: seen_keys.append(key) or [STATION['lat'] if key == 'latitudes' else STATION['lon']],
					codes_get_values=lambda message: [2],
					codes_release=lambda message: None
				)

			with patch.dict('sys.modules', {'eccodes': fake_eccodes()}):
				stamp, latitudes, longitudes, values, metadata = decode_grib_file(path)

			self.assertEqual(stamp, 1792944000)
			self.assertEqual(latitudes, [STATION['lat']])
			self.assertEqual(longitudes, [STATION['lon']])
			self.assertEqual(values, [2])
			self.assertEqual(seen_keys, ['latitudes', 'longitudes'])
			self.assertEqual(metadata['parameterNumber'], 7)

			keys['parameterNumber'] = 8
			with patch.dict('sys.modules', {'eccodes': fake_eccodes()}):
				with self.assertRaisesRegex(ValueError, 'not WMO cloud-mask'):
					decode_grib_file(path)

	def test_cloud_fraction_uses_nearby_pixels_and_ignores_unprocessed(self):
		latitudes = [STATION['lat']] * 13
		longitudes = [STATION['lon']] * 12 + [STATION['lon'] + 2]
		values = [2] * 4 + [0] * 5 + [1] * 1 + [3] * 2 + [2]
		result = cloud_fraction_in_footprint(latitudes, longitudes, values, STATION, 15)
		self.assertEqual(result['total_pixels'], 12)
		self.assertEqual(result['valid_pixels'], 10)
		self.assertEqual(result['cloud_fraction_percent'], 40)
		self.assertEqual(result['pixel_counts']['not_processed'], 2)

	def test_too_few_valid_pixels_are_unscorable(self):
		result = cloud_fraction_in_footprint([STATION['lat']] * 9, [STATION['lon']] * 9, [2] * 8 + [3], STATION, 15)
		self.assertEqual(result['status'], 'INSUFFICIENT_COVERAGE')
		self.assertIsNone(result['cloud_fraction_percent'])

	def test_unprocessed_nonfinite_pixels_preserve_consistent_counts(self):
		result = cloud_fraction_in_footprint([STATION['lat']] * 10, [STATION['lon']] * 10, [2] * 9 + [float('nan')], STATION, 15)
		self.assertEqual(result['total_pixels'], 10)
		self.assertEqual(result['valid_pixels'], 9)
		self.assertEqual(result['pixel_counts']['not_processed'], 1)
		self.assertEqual(result['status'], 'USABLE')

	def test_class_mapping_is_fail_closed(self):
		with self.assertRaisesRegex(ValueError, 'Unexpected MSG CLM'):
			cloud_fraction_in_footprint([STATION['lat']], [STATION['lon']], [11], STATION, 15)

	def test_extract_writes_provenance_without_overwriting(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			station_path = root / 'station.json'
			station_path.write_text(json.dumps({'records': [STATION]}))
			grib_path = root / 'clm.grib2'
			grib_path.write_bytes(b'fake satellite grib')
			coords = [STATION['lat']] * 10
			lons = [STATION['lon']] * 10
			with patch('provider_evaluation.satellite.decode_grib_file', return_value=(STAMP, coords, lons, [2] * 2 + [0] * 8, {'grid_type': 'space_view'})):
				path = extract_mask(grib_path, station_path, root / 'satellite')
				observation = parse_observation(path)
				self.assertEqual(observation['source']['sha256'], hashlib.sha256(grib_path.read_bytes()).hexdigest())
				self.assertFalse(observation['source']['classification_verified_against_file'])
				with self.assertRaises(FileExistsError):
					extract_mask(grib_path, station_path, root / 'satellite')

	def test_nearest_scan_matches_at_boundary_and_earlier_tie_wins(self):
		capture = routine_capture()
		near_before = satellite_observation(STAMP - 600)
		near_after = satellite_observation(STAMP + 600)
		self.assertIs(closest_satellite(capture, STATION, [near_after, near_before]), near_before)
		self.assertIsNone(closest_satellite(capture, STATION, [satellite_observation(STAMP + 601)]))

	def test_conflicting_scans_fail_instead_of_silently_selecting_one(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			(root / '1.json').write_text(json.dumps(satellite_observation()))
			other = satellite_observation()
			other['source']['sha256'] = 'b' * 64
			(root / '2.json').write_text(json.dumps(other))
			with self.assertRaisesRegex(ValueError, 'Conflicting'):
				load_observations(root)

	def test_tampered_cloud_fraction_is_rejected(self):
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / 'fake.json'
			observation = satellite_observation()
			observation['footprint']['cloud_fraction_percent'] = 99
			path.write_text(json.dumps(observation))
			with self.assertRaisesRegex(ValueError, 'does not match'):
				parse_observation(path)


	def test_station_validation_rejects_invalid_metadata_without_coercing_types(self):
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / 'station.json'
			path.write_text(json.dumps({'records': [{**STATION, 'icaoId': 'lirn'}]}))
			self.assertEqual(read_station(path)['icaoId'], 'LIRN')

			for invalid in ({**STATION, 'country': 'FR'}, {**STATION, 'icaoId': 'LIRÉ'}, {**STATION, 'lat': True}, {**STATION, 'lon': float('nan')}, 'not an object'):
				with self.subTest(invalid=invalid):
					path.write_text(json.dumps({'records': [invalid]}))
					with self.assertRaises(ValueError):
						read_station(path)

	def test_footprint_rejects_misaligned_arrays_and_ignores_invalid_distant_pixels(self):
		with self.assertRaisesRegex(ValueError, 'aligned 1D'):
			cloud_fraction_in_footprint([STATION['lat']], [], [2], STATION, 15)

		latitudes = [STATION['lat']] * 10 + [STATION['lat'] + 2]
		longitudes = [STATION['lon']] * 11
		values = [2] * 9 + [0, 77]
		result = cloud_fraction_in_footprint(latitudes, longitudes, values, STATION, 15)
		self.assertEqual(result['status'], 'USABLE')
		self.assertEqual(result['total_pixels'], 10)
		self.assertEqual(result['cloud_fraction_percent'], 90)

	def test_satellite_record_rejects_boolean_negative_and_forged_pixel_counts(self):
		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / 'observation.json'
			for cloud_count in (True, -1, 2.0):
				with self.subTest(cloud_count=cloud_count):
					observation = satellite_observation()
					observation['footprint']['pixel_counts']['cloud'] = cloud_count
					path.write_text(json.dumps(observation))
					with self.assertRaisesRegex(ValueError, 'class counts'):
						parse_observation(path)

	def test_capture_eligibility_keeps_strict_location_requirements(self):
		capture = routine_capture()
		self.assertTrue(eligible_capture(capture, STATION, 'ROUTINE'))
		for changes in ({'device_location': True}, {'location_verified': False}, {'latitude': STATION['lat'] + 0.002}, {'latitude': float('nan')}, {'captured_at_epoch_seconds': True}):
			with self.subTest(changes=changes):
				self.assertFalse(eligible_capture({**capture, **changes}, STATION, 'ROUTINE'))

	def test_duplicate_routine_capture_is_not_counted_twice(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			(root / 'satellite').mkdir()
			(root / 'satellite/scan.json').write_text(json.dumps(satellite_observation()))
			for name in ('first', 'second'):
				run = root / 'runs' / name
				run.mkdir(parents=True)
				(run / 'capture.json').write_text(json.dumps(routine_capture()))
				(run / 'station.json').write_text(json.dumps({'records': [STATION]}))
				(run / 'run.json').write_text(json.dumps({'collection': 'ROUTINE'}))

			result = summarize_satellite(root)
			self.assertEqual(result['eligible_unique_routine_captures'], 1)
			self.assertEqual(result['matched_captures'], 1)
			self.assertEqual(result['matched_unique_satellite_scans'], 1)
			self.assertEqual(result['paired_against_italia_meteo']['OPEN_METEO']['shared_capture_count'], 1)

	def test_failed_routine_capture_with_retained_files_does_not_affect_satellite_scores(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			(root / 'satellite').mkdir()
			(root / 'satellite/scan.json').write_text(json.dumps(satellite_observation()))
			failed = root / 'runs' / '000-failed'
			failed.mkdir(parents=True)
			(failed / 'capture.json').write_text(json.dumps(routine_capture()))
			(failed / 'station.json').write_text(json.dumps({'records': [STATION]}))
			(failed / 'run.json').write_text(json.dumps({'collection': 'ROUTINE'}))
			failure = {'type': 'ValueError', 'message': 'Collector report is not an UNSURE capture'}
			(failed / 'failure.json').write_text(json.dumps(failure))

			failed_only = summarize_satellite(root)
			self.assertEqual(failed_only['satellite_observations_loaded'], 1)
			self.assertEqual(failed_only['eligible_unique_routine_captures'], 0)
			self.assertEqual(failed_only['matched_captures'], 0)
			self.assertEqual(failed_only['provider_differences'], {})
			self.assertEqual(failed_only['paired_against_italia_meteo'], {})

			valid = root / 'runs' / '001-valid'
			valid.mkdir()
			capture = routine_capture()
			capture['samples'][0]['cloud_total_percent'] = 45.0
			capture['samples'][1]['cloud_total_percent'] = 55.0
			(valid / 'capture.json').write_text(json.dumps(capture))
			(valid / 'station.json').write_text(json.dumps({'records': [STATION]}))
			(valid / 'run.json').write_text(json.dumps({'collection': 'ROUTINE'}))

			result = summarize_satellite(root)
			self.assertEqual(result['eligible_unique_routine_captures'], 1)
			self.assertEqual(result['matched_captures'], 1)
			self.assertEqual(result['matches'][0]['run_id'], '001-valid')
			self.assertEqual(result['provider_differences']['ITALIA_METEO']['mean_absolute_difference_percentage_points'], 25)
			self.assertEqual(result['paired_against_italia_meteo']['OPEN_METEO']['shared_capture_count'], 1)
			self.assertEqual(result['paired_against_italia_meteo']['OPEN_METEO']['candidate_mean_absolute_difference_percentage_points'], 35)
			self.assertEqual(json.loads((failed / 'failure.json').read_text()), failure)

	def test_paired_summary_uses_shared_capture_denominator_and_keeps_originals(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			(root / 'satellite').mkdir()
			(root / 'satellite/one.json').write_text(json.dumps(satellite_observation()))
			run = root / 'runs' / 'first'
			run.mkdir(parents=True)
			capture = routine_capture()
			(run / 'capture.json').write_text(json.dumps(capture))
			(run / 'station.json').write_text(json.dumps({'records': [STATION]}))
			(run / 'run.json').write_text(json.dumps({'collection': 'ROUTINE'}))
			(run / 'match-test.json').write_text('{"match":{"status":"MATCHED"}}')
			second = root / 'runs' / 'second'
			second.mkdir()
			(second / 'capture.json').write_text(json.dumps(routine_capture(STAMP + 1800)))
			(second / 'station.json').write_text(json.dumps({'records': [STATION]}))
			(second / 'run.json').write_text(json.dumps({'collection': 'ROUTINE'}))
			result = summarize_satellite(root)
			self.assertEqual(result['matched_captures'], 1)
			self.assertEqual(result['unmatched_captures'], 1)
			self.assertEqual(result['paired_against_italia_meteo']['OPEN_METEO']['shared_capture_count'], 1)
			self.assertEqual(result['paired_against_italia_meteo']['OPEN_METEO']['candidate_mean_absolute_difference_percentage_points'], 5)
			self.assertEqual(result['paired_against_italia_meteo']['OPEN_METEO']['italia_meteo_mean_absolute_difference_percentage_points'], 70)
			self.assertNotIn('MET_NORWAY', result['provider_differences'])
			self.assertEqual((run / 'match-test.json').read_text(), '{"match":{"status":"MATCHED"}}')
			self.assertEqual({p.name for p in run.iterdir()}, {'capture.json', 'station.json', 'run.json', 'match-test.json'})

	def test_diagnostic_and_unverified_location_are_excluded(self):
		with tempfile.TemporaryDirectory() as directory:
			root = Path(directory)
			(root / 'satellite').mkdir()
			(root / 'satellite/one.json').write_text(json.dumps(satellite_observation()))
			for name, field, value in [('diagnostic', 'collection', 'DIAGNOSTIC'), ('device', 'device_location', True), ('station', 'station_id', 'LIRF')]:
				run = root / 'runs' / name
				run.mkdir(parents=True)
				(run / 'capture.json').write_text(json.dumps({**routine_capture(), field: value}))
				(run / 'station.json').write_text(json.dumps({'records': [STATION]}))
				(run / 'run.json').write_text(json.dumps({'collection': value if field == 'collection' else 'ROUTINE'}))

			result = summarize_satellite(root)
			self.assertEqual(result['matched_captures'], 0)
			self.assertEqual(result['skipped_captures'], 3)


if __name__ == '__main__':
	unittest.main()
