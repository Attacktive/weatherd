from datetime import datetime, timezone
import json
import unittest
from unittest.mock import MagicMock, patch
import urllib.error

from provider_evaluation.observations import (
	classify_metar_clouds,
	fetch_observations,
	fetch_station,
	match_capture,
	summarize
)


def sample_station(icao_id='LIRF', lat=41.8, lon=12.239, country='IT', elev=2):
	return {
		'id': icao_id,
		'icaoId': icao_id,
		'iataId': 'FCO',
		'faaId': None,
		'wmoId': '16242',
		'site': 'Rome/Fiumicino',
		'lat': lat,
		'lon': lon,
		'elev': elev,
		'state': 'RM',
		'country': country,
		'priority': 0,
		'siteType': ['METAR', 'TAF']
	}


def sample_capture(
	captured_at=1792556400,
	lat=41.8,
	lon=12.239,
	collection='ROUTINE',
	station_id='LIRF',
	location_source='MANUAL_STATION',
	samples=None
):
	if samples is None:
		samples = [
			{
				'provider': 'ITALIA_METEO',
				'status': 'SUCCESS',
				'valid_at_epoch_seconds': captured_at,
				'condition': 'PARTLY_CLOUDY',
				'cloud_total_percent': 50.0,
				'cloud_low_percent': 25.0,
				'cloud_mid_percent': 25.0,
				'cloud_high_percent': 0.0,
				'temperature_celsius': 19.0,
				'precipitation_millimeters': 0.0
			},
			{
				'provider': 'OPEN_METEO',
				'status': 'SUCCESS',
				'valid_at_epoch_seconds': captured_at,
				'condition': 'PARTLY_CLOUDY',
				'cloud_total_percent': 45.0,
				'cloud_low_percent': 20.0,
				'cloud_mid_percent': 25.0,
				'cloud_high_percent': 0.0,
				'temperature_celsius': 19.5,
				'precipitation_millimeters': 0.1
			}
		]

	return {
		'station_id': station_id,
		'location_source': location_source,
		'capture_started_at_epoch_seconds': captured_at - 10,
		'captured_at_epoch_seconds': captured_at,
		'app_version': '3.8.37',
		'device_location': False,
		'simulator_active': False,
		'latitude': lat,
		'longitude': lon,
		'location_verified': True,
		'collection': collection,
		'samples': samples
	}


def sample_observation(
	obs_time=1792556400,
	raw_ob=None,
	temp=19.0,
	icao_id='LIRF',
	lat=41.8,
	lon=12.239,
	receipt_time='2026-10-21T04:25:06.680Z'
):
	if raw_ob is None:
		dt = datetime.fromtimestamp(obs_time, tz=timezone.utc)
		time_str = f'{dt.day:02d}{dt.hour:02d}{dt.minute:02d}Z'
		raw_ob = f'METAR {icao_id} {time_str} 14010KT 9999 SCT025 19/18 Q1011 NOSIG'

	return {
		'icaoId': icao_id,
		'receiptTime': receipt_time,
		'obsTime': obs_time,
		'reportTime': '2026-10-21T04:20:00.000Z',
		'temp': temp,
		'dewp': 18.0,
		'wdir': 140,
		'wspd': 10,
		'visib': '6+',
		'altim': 1011,
		'qcField': 0,
		'metarType': 'METAR',
		'rawOb': raw_ob,
		'lat': lat,
		'lon': lon,
		'elev': 2,
		'name': 'Rome/Fiumicino, RM, IT'
	}


class ObservationsTest(unittest.TestCase):
	def test_time_boundary_inclusive_at_900_seconds_and_exclusive_at_901_seconds(self):
		target_time = 1792556400
		station = sample_station()
		obs_within_lead = sample_observation(obs_time=target_time - 900)
		obs_within_lag = sample_observation(obs_time=target_time + 900)
		obs_beyond_lead = sample_observation(obs_time=target_time - 901)
		obs_beyond_lag = sample_observation(obs_time=target_time + 901)

		capture = sample_capture(captured_at=target_time)

		match_lead = match_capture(capture, station, [obs_within_lead])
		self.assertEqual(match_lead['status'], 'MATCHED')
		self.assertTrue(match_lead['capture_matched'])
		self.assertEqual(match_lead['capture_offset_seconds'], -900)
		self.assertEqual(match_lead['samples'][0]['offset_seconds'], -900)

		match_lag = match_capture(capture, station, [obs_within_lag])
		self.assertEqual(match_lag['status'], 'MATCHED')
		self.assertTrue(match_lag['capture_matched'])
		self.assertEqual(match_lag['capture_offset_seconds'], 900)
		self.assertEqual(match_lag['samples'][0]['offset_seconds'], 900)

		match_out_lead = match_capture(capture, station, [obs_beyond_lead])
		self.assertEqual(match_out_lead['status'], 'PENDING')
		self.assertFalse(match_out_lead['capture_matched'])
		self.assertIsNone(match_out_lead['capture_offset_seconds'])
		self.assertFalse(match_out_lead['samples'][0]['matched'])
		self.assertEqual(
			match_out_lead['samples'][0]['unmatched_reason'],
			'no_observation_within_tolerance'
		)

		match_out_lag = match_capture(capture, station, [obs_beyond_lag])
		self.assertEqual(match_out_lag['status'], 'PENDING')
		self.assertFalse(match_out_lag['capture_matched'])
		self.assertIsNone(match_out_lag['capture_offset_seconds'])
		self.assertFalse(match_out_lag['samples'][0]['matched'])
		self.assertEqual(
			match_out_lag['samples'][0]['unmatched_reason'],
			'no_observation_within_tolerance'
		)

	def test_separate_capture_time_and_forecast_valid_time_matching(self):
		capture_time = 1792556400
		forecast_valid_time = capture_time + 600
		obs_at_capture = sample_observation(obs_time=capture_time)
		obs_at_forecast = sample_observation(obs_time=forecast_valid_time)

		sample = {
			'provider': 'OPEN_METEO',
			'status': 'SUCCESS',
			'valid_at_epoch_seconds': forecast_valid_time,
			'condition': 'PARTLY_CLOUDY',
			'cloud_total_percent': 40.0,
			'cloud_low_percent': 0.0,
			'cloud_mid_percent': 40.0,
			'cloud_high_percent': 0.0,
			'temperature_celsius': 20.0,
			'precipitation_millimeters': 0.0
		}
		capture = sample_capture(captured_at=capture_time, samples=[sample])
		station = sample_station()

		match_result = match_capture(capture, station, [obs_at_capture, obs_at_forecast])
		self.assertEqual(match_result['capture_observation_time'], capture_time)
		self.assertEqual(match_result['capture_offset_seconds'], 0)

		sample_match = match_result['samples'][0]
		self.assertEqual(sample_match['capture_time_match']['observation_time'], capture_time)
		self.assertEqual(sample_match['capture_time_match']['offset_seconds'], 0)
		self.assertEqual(sample_match['forecast_valid_match']['observation_time'], forecast_valid_time)
		self.assertEqual(sample_match['forecast_valid_match']['offset_seconds'], 0)

	def test_station_and_coordinate_mismatch_marked_excluded(self):
		station = sample_station(lat=41.8, lon=12.239, country='IT')

		mismatched_coords_capture = sample_capture(lat=45.0, lon=9.0)
		result_mismatch = match_capture(mismatched_coords_capture, station, [sample_observation()])
		self.assertEqual(result_mismatch['status'], 'EXCLUDED')
		self.assertIn('coordinate_mismatch', result_mismatch['reason'])

		non_italian_station = sample_station(country='FR')
		valid_capture = sample_capture(lat=41.8, lon=12.239)
		result_non_it = match_capture(valid_capture, non_italian_station, [sample_observation()])
		self.assertEqual(result_non_it['status'], 'EXCLUDED')
		self.assertIn('station_metadata_invalid', result_non_it['reason'])

		missing_id_station = sample_station(icao_id='')
		result_missing_id = match_capture(valid_capture, missing_id_station, [sample_observation()])
		self.assertEqual(result_missing_id['status'], 'EXCLUDED')

	def test_missing_or_invalid_evidence_flags_marked_excluded(self):
		station = sample_station()

		capture_missing_location_source = sample_capture()
		del capture_missing_location_source['location_source']
		result = match_capture(capture_missing_location_source, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertIn('location_source', result['reason'])

		capture_invalid_location_source = sample_capture(location_source='DEVICE')
		result = match_capture(capture_invalid_location_source, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertIn('location_source', result['reason'])

		capture_missing_station_id = sample_capture()
		del capture_missing_station_id['station_id']
		result = match_capture(capture_missing_station_id, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertIn('station_id', result['reason'])

		capture_mismatched_station_id = sample_capture(station_id='LIMC')
		result = match_capture(capture_mismatched_station_id, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertIn('station_id', result['reason'])

		capture_missing_device_location = sample_capture()
		del capture_missing_device_location['device_location']
		result = match_capture(capture_missing_device_location, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertIn('device_location', result['reason'])

		capture_device_location_true = sample_capture()
		capture_device_location_true['device_location'] = True
		result = match_capture(capture_device_location_true, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertIn('device_location', result['reason'])

		capture_missing_location_verified = sample_capture()
		del capture_missing_location_verified['location_verified']
		result = match_capture(capture_missing_location_verified, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertEqual(result['reason'], 'location_not_verified')

		capture_location_verified_false = sample_capture()
		capture_location_verified_false['location_verified'] = False
		result = match_capture(capture_location_verified_false, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertEqual(result['reason'], 'location_not_verified')

		capture_simulator_active_true = sample_capture()
		capture_simulator_active_true['simulator_active'] = True
		result = match_capture(capture_simulator_active_true, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertEqual(result['reason'], 'simulator_active')

		capture_simulator_none = sample_capture()
		capture_simulator_none['simulator_active'] = None
		result = match_capture(capture_simulator_none, station, [sample_observation()])
		self.assertEqual(result['status'], 'EXCLUDED')
		self.assertEqual(result['reason'], 'simulator_active')

	def test_malformed_capture_coordinates_marked_excluded_without_type_error(self):
		station = sample_station()

		for bad_lat in (None, 'bad_coord', float('nan'), float('inf')):
			with self.subTest(bad_lat=bad_lat):
				capture = sample_capture()
				capture['latitude'] = bad_lat
				result = match_capture(capture, station, [sample_observation()])
				self.assertEqual(result['status'], 'EXCLUDED')
				self.assertEqual(result['reason'], 'invalid_capture_coordinates')

	def test_observation_with_missing_or_mismatched_station_or_raw_header_rejected(self):
		station = sample_station(icao_id='LIRF')
		capture = sample_capture()

		obs_missing_icao = sample_observation()
		del obs_missing_icao['icaoId']
		result = match_capture(capture, station, [obs_missing_icao])
		self.assertEqual(result['status'], 'PENDING')
		self.assertEqual(result['reason'], 'no_observation_within_tolerance')

		obs_wrong_icao = sample_observation(icao_id='LIMC')
		result = match_capture(capture, station, [obs_wrong_icao])
		self.assertEqual(result['status'], 'PENDING')

		obs_wrong_raw_header = sample_observation(raw_ob='METAR LIMC 210420Z 14010KT 9999 SCT025 19/18 Q1011 NOSIG')
		result = match_capture(capture, station, [obs_wrong_raw_header])
		self.assertEqual(result['status'], 'PENDING')

	def test_observation_with_conflicting_coordinates_rejected(self):
		station = sample_station(icao_id='LIRF', lat=41.8, lon=12.239)
		capture = sample_capture()

		obs_conflicting_coords = sample_observation(lat=45.0, lon=9.0)
		result = match_capture(capture, station, [obs_conflicting_coords])
		self.assertEqual(result['status'], 'PENDING')
		self.assertEqual(result['reason'], 'no_observation_within_tolerance')

	def test_observation_with_malformed_time_header_rejected(self):
		station = sample_station(icao_id='LIRF')
		capture = sample_capture(captured_at=1792556400)

		malformed_time_obs = sample_observation(
			obs_time=1792556400,
			raw_ob='METAR LIRF nonsense 14010KT 9999 OVC010 19/18 Q1011'
		)
		result = match_capture(capture, station, [malformed_time_obs])
		self.assertEqual(result['status'], 'PENDING')
		self.assertEqual(result['reason'], 'no_observation_within_tolerance')

	def test_observation_with_mismatched_valid_time_header_rejected(self):
		station = sample_station(icao_id='LIRF')
		capture = sample_capture(captured_at=1792556400)

		mismatched_time_obs = sample_observation(
			obs_time=1792556400,
			raw_ob='METAR LIRF 210425Z 14010KT 9999 OVC010 19/18 Q1011'
		)
		result = match_capture(capture, station, [mismatched_time_obs])
		self.assertEqual(result['status'], 'PENDING')
		self.assertEqual(result['reason'], 'no_observation_within_tolerance')

	def test_corrected_metar_resolved_over_original_regardless_of_order(self):
		target_time = 1792556400
		station = sample_station()
		capture = sample_capture(captured_at=target_time)

		orig_obs = sample_observation(
			obs_time=target_time,
			raw_ob='METAR LIRF 210420Z 14010KT 9999 OVC010 18/17 Q1011',
			temp=18.0,
			receipt_time='2026-10-21T04:20:00.000Z'
		)
		cor_obs = sample_observation(
			obs_time=target_time,
			raw_ob='METAR COR LIRF 210420Z 14010KT 9999 SKC 20/17 Q1011',
			temp=20.0,
			receipt_time='2026-10-21T04:25:00.000Z'
		)

		match_orig_first = match_capture(capture, station, [orig_obs, cor_obs])
		self.assertEqual(match_orig_first['status'], 'MATCHED')
		self.assertIsNone(match_orig_first['capture_observed_cloud_category'])
		self.assertEqual(match_orig_first['samples'][0]['observed_temperature'], 20.0)

		match_cor_first = match_capture(capture, station, [cor_obs, orig_obs])
		self.assertEqual(match_cor_first['status'], 'MATCHED')
		self.assertIsNone(match_cor_first['capture_observed_cloud_category'])
		self.assertEqual(match_cor_first['samples'][0]['observed_temperature'], 20.0)

	def test_conflicting_observations_without_reliable_correction_unscored(self):
		target_time = 1792556400
		station = sample_station()
		capture = sample_capture(captured_at=target_time)

		obs_a = sample_observation(
			obs_time=target_time,
			raw_ob='METAR LIRF 210420Z 14010KT 9999 OVC010 18/17 Q1011',
			temp=18.0,
			receipt_time='2026-10-21T04:20:00.000Z'
		)
		obs_b = sample_observation(
			obs_time=target_time,
			raw_ob='METAR LIRF 210420Z 14010KT 9999 SKC 20/17 Q1011',
			temp=20.0,
			receipt_time='2026-10-21T04:20:00.000Z'
		)

		match_conflicting = match_capture(capture, station, [obs_a, obs_b])
		self.assertEqual(match_conflicting['status'], 'PENDING')
		self.assertEqual(match_conflicting['reason'], 'no_observation_within_tolerance')

	def test_conflicting_corrections_with_undated_revision_are_unscored(self):
		capture = sample_capture()
		dated = sample_observation(raw_ob='METAR COR LIRF 210420Z 14010KT 9999 OVC010 18/17 Q1011', temp=18.0)
		undated = sample_observation(raw_ob='METAR COR LIRF 210420Z 14010KT 9999 SKC 20/17 Q1011', temp=20.0, receipt_time=None)
		for records in ([dated, undated], [undated, dated]):
			with self.subTest(first=records[0]['temp']):
				result = match_capture(capture, sample_station(), records)
				self.assertEqual(result['status'], 'PENDING')
				self.assertFalse(result['capture_matched'])

	def test_correction_modifier_does_not_establish_manual_sky_certainty(self):
		for raw in ('METAR COR LIRF 210420Z 14010KT 9999 SKC 20/17 Q1011', 'METAR LIRF 210420Z COR 14010KT 9999 OVC010 20/17 Q1011'):
			with self.subTest(raw=raw):
				category, reason = classify_metar_clouds(raw)
				self.assertIsNone(category)
				self.assertEqual(reason, 'correction_automation_uncertain')

	def test_pending_provider_is_preserved_with_reason(self):
		capture_time = 1792556400
		station = sample_station()
		obs = sample_observation(obs_time=capture_time)

		pending_sample = {
			'provider': 'OPEN_METEO',
			'status': 'PENDING',
			'valid_at_epoch_seconds': None,
			'condition': None,
			'cloud_total_percent': None,
			'cloud_low_percent': None,
			'cloud_mid_percent': None,
			'cloud_high_percent': None,
			'temperature_celsius': None,
			'precipitation_millimeters': None
		}
		capture = sample_capture(captured_at=capture_time, samples=[pending_sample])

		match_result = match_capture(capture, station, [obs])
		sample_match = match_result['samples'][0]
		self.assertEqual(sample_match['status'], 'PENDING')
		self.assertFalse(sample_match['matched'])
		self.assertEqual(sample_match['unmatched_reason'], 'provider_pending')
		self.assertIsNone(sample_match['cloud_agreed'])

	def test_metar_trend_does_not_contaminate_observed_cloud_classification(self):
		tempo_overcast = 'METAR LIRF 210350Z 13009KT 9999 FEW025 SCT040 18/17 Q1011 TEMPO 4000 OVC010'
		category, reason = classify_metar_clouds(tempo_overcast)
		self.assertEqual(category, 'PARTLY_CLOUDY')
		self.assertEqual(reason, 'few_sct_bkn_layers')

		becmg_bkn = 'METAR LIRF 210350Z 13009KT 9999 SKC 18/17 Q1011 BECMG BKN020'
		category, reason = classify_metar_clouds(becmg_bkn)
		self.assertEqual(category, 'CLEAR')
		self.assertEqual(reason, 'manual_skc')

		nosig_ovc = 'METAR LIRF 210350Z 13009KT 9999 OVC015 18/17 Q1011 NOSIG'
		category, reason = classify_metar_clouds(nosig_ovc)
		self.assertEqual(category, 'OVERCAST')
		self.assertEqual(reason, 'ovc_layer')

	def test_ambiguous_and_automated_cloud_codes_are_conservative(self):
		auto_metar = 'METAR LIRF 210420Z AUTO 14010KT 9999 CLR 19/18 Q1011'
		category, reason = classify_metar_clouds(auto_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'automated_observation_unscorable')

		ao1_metar = 'METAR LIRF 210420Z 14010KT 9999 CLR 19/18 Q1011 RMK AO1'
		category, reason = classify_metar_clouds(ao1_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'automated_observation_ao1_ao2')

		ao2_metar = 'METAR LIRF 210420Z 14010KT 9999 OVC010 19/18 Q1011 RMK AO2'
		category, reason = classify_metar_clouds(ao2_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'automated_observation_ao1_ao2')

		cor_ao2_metar = 'METAR COR LIRF 210420Z 14010KT 9999 CLR 19/18 Q1011 RMK AO2'
		category, reason = classify_metar_clouds(cor_ao2_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'automated_observation_ao1_ao2')

		cavok_metar = 'METAR LIRF 210420Z 14010KT CAVOK 19/18 Q1011'
		category, reason = classify_metar_clouds(cavok_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'cavok_ambiguous_sky_cover')

		nsc_metar = 'METAR LIRF 210420Z 14010KT 9999 NSC 19/18 Q1011'
		category, reason = classify_metar_clouds(nsc_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'nsc_ambiguous_sky_cover')

		clr_metar = 'METAR LIRF 210420Z 14010KT 9999 CLR 19/18 Q1011'
		category, reason = classify_metar_clouds(clr_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'clr_ambiguous_sky_cover')

		ncd_metar = 'METAR LIRF 210420Z 14010KT 9999 NCD 19/18 Q1011'
		category, reason = classify_metar_clouds(ncd_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'ncd_ambiguous_sky_cover')

		vv_metar = 'METAR LIRF 210420Z 14010KT 0300 VV002 19/18 Q1011'
		category, reason = classify_metar_clouds(vv_metar)
		self.assertIsNone(category)
		self.assertEqual(reason, 'vertical_visibility_obscuration')

		bkn_metar = 'METAR LIRF 210420Z 14010KT 9999 BKN030 19/18 Q1011'
		category, reason = classify_metar_clouds(bkn_metar)
		self.assertEqual(category, 'PARTLY_CLOUDY')
		self.assertNotEqual(category, 'OVERCAST')

		ovc_metar = 'METAR LIRF 210420Z 14010KT 9999 OVC010 19/18 Q1011'
		category, reason = classify_metar_clouds(ovc_metar)
		self.assertEqual(category, 'OVERCAST')

		skc_metar = 'METAR LIRF 210420Z 14010KT 9999 SKC 19/18 Q1011'
		category, reason = classify_metar_clouds(skc_metar)
		self.assertEqual(category, 'CLEAR')

	def test_automated_metar_allows_temperature_scoring_while_cloud_unscorable(self):
		capture_time = 1792556400
		station = sample_station()
		auto_obs = sample_observation(
			obs_time=capture_time,
			raw_ob='METAR LIRF 210420Z AUTO 14010KT 9999 CLR 19/18 Q1011',
			temp=19.0
		)

		capture = sample_capture(
			captured_at=capture_time,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': capture_time,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 19.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': capture_time,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				}
			]
		)

		match_result = match_capture(capture, station, [auto_obs])
		self.assertEqual(match_result['status'], 'MATCHED')
		self.assertIsNone(match_result['capture_observed_cloud_category'])

		summary = summarize([{'capture': capture, 'station': station, 'match': match_result}])
		comparison = summary['comparisons_against_italia_meteo']['OPEN_METEO']

		self.assertEqual(comparison['cloud']['shared_eligible_denominator'], 0)
		self.assertEqual(comparison['temperature']['shared_eligible_denominator'], 1)
		self.assertAlmostEqual(comparison['temperature']['provider_mean_absolute_error_celsius'], 1.0)
		self.assertAlmostEqual(comparison['temperature']['italia_meteo_mean_absolute_error_celsius'], 0.0)

	def test_rain_and_fog_conditions_are_not_mapped_to_clear(self):
		capture_time = 1792556400
		station = sample_station()
		obs = sample_observation(obs_time=capture_time, raw_ob='METAR LIRF 210420Z 14010KT 9999 SKC 19/18 Q1011')

		rain_sample = {
			'provider': 'OPEN_METEO',
			'status': 'SUCCESS',
			'valid_at_epoch_seconds': capture_time,
			'condition': 'RAIN',
			'cloud_total_percent': 100.0,
			'cloud_low_percent': 50.0,
			'cloud_mid_percent': 50.0,
			'cloud_high_percent': 0.0,
			'temperature_celsius': 18.0,
			'precipitation_millimeters': 2.5
		}
		capture = sample_capture(captured_at=capture_time, samples=[rain_sample])
		match_result = match_capture(capture, station, [obs])
		sample_match = match_result['samples'][0]
		self.assertIsNone(sample_match['provider_cloud_category'])
		self.assertIsNone(sample_match['cloud_agreed'])

	def test_paired_denominators_and_independent_availability_in_summarize(self):
		station = sample_station()
		entries = []

		capture_1 = sample_capture(
			captured_at=1000,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 1000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 1000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 21.0
				}
			]
		)
		obs_1 = sample_observation(obs_time=1000, temp=20.0)
		entries.append({
			'capture': capture_1,
			'station': station,
			'match': match_capture(capture_1, station, [obs_1])
		})

		capture_2 = sample_capture(
			captured_at=2000,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 2000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 2000,
					'condition': 'OVERCAST',
					'temperature_celsius': 22.0
				}
			]
		)
		obs_2 = sample_observation(obs_time=2000, temp=20.0)
		entries.append({
			'capture': capture_2,
			'station': station,
			'match': match_capture(capture_2, station, [obs_2])
		})

		capture_3 = sample_capture(
			captured_at=3000,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 3000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'PENDING',
					'valid_at_epoch_seconds': None,
					'condition': None,
					'temperature_celsius': None
				}
			]
		)
		obs_3 = sample_observation(obs_time=3000, temp=20.0)
		entries.append({
			'capture': capture_3,
			'station': station,
			'match': match_capture(capture_3, station, [obs_3])
		})

		capture_4 = sample_capture(
			captured_at=4000,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 4000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 4000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				}
			]
		)
		obs_4 = sample_observation(obs_time=4000, raw_ob='METAR LIRF 010106Z 14010KT CAVOK 20/18 Q1011', temp=20.0)
		entries.append({
			'capture': capture_4,
			'station': station,
			'match': match_capture(capture_4, station, [obs_4])
		})

		summary = summarize(entries)

		om_avail = summary['providers']['OPEN_METEO']['availability']
		self.assertEqual(om_avail['available_count'], 3)
		self.assertEqual(om_avail['total_count'], 4)
		self.assertAlmostEqual(om_avail['rate'], 0.75)

		im_avail = summary['providers']['ITALIA_METEO']['availability']
		self.assertEqual(im_avail['available_count'], 4)
		self.assertEqual(im_avail['total_count'], 4)
		self.assertAlmostEqual(im_avail['rate'], 1.0)

		comparison = summary['comparisons_against_italia_meteo']['OPEN_METEO']
		cloud_stats = comparison['cloud']
		self.assertEqual(cloud_stats['shared_eligible_denominator'], 2)
		self.assertEqual(cloud_stats['provider_agreed_count'], 1)
		self.assertAlmostEqual(cloud_stats['provider_agreement_rate'], 0.5)
		self.assertEqual(cloud_stats['italia_meteo_agreed_count'], 2)
		self.assertAlmostEqual(cloud_stats['italia_meteo_agreement_rate'], 1.0)

		temp_stats = comparison['temperature']
		self.assertEqual(temp_stats['shared_eligible_denominator'], 3)

		self.assertNotIn('precipitation_scored', summary)
		self.assertNotIn('rankings', summary)

		self.assertIn('scoring_rules', summary)
		rules = summary['scoring_rules']
		self.assertEqual(rules['precipitation_scoring'], 'NONE')
		self.assertEqual(rules['cloud_condition_mapping']['MAINLY_CLEAR'], 'PARTLY_CLOUDY')

	def test_different_observation_times_excluded_from_paired_denominator(self):
		station = sample_station()
		capture = sample_capture(
			captured_at=1000,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 1000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 1600,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				}
			]
		)
		obs_1 = sample_observation(obs_time=1000, temp=20.0)
		obs_2 = sample_observation(obs_time=1600, temp=20.0)

		match_result = match_capture(capture, station, [obs_1, obs_2])
		summary = summarize([{'capture': capture, 'station': station, 'match': match_result}])
		diagnostics = summary['comparisons_against_italia_meteo']['OPEN_METEO']['forecast_valid_diagnostics']

		self.assertEqual(diagnostics['cloud']['shared_eligible_denominator'], 0)
		self.assertEqual(diagnostics['temperature']['shared_eligible_denominator'], 0)

	def test_nonfinite_temperatures_excluded_from_temperature_scoring(self):
		station = sample_station()
		capture = sample_capture(
			captured_at=1000,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 1000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': float('nan')
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': 1000,
					'condition': 'PARTLY_CLOUDY',
					'temperature_celsius': 20.0
				}
			]
		)
		obs = sample_observation(obs_time=1000, temp=20.0)
		match_result = match_capture(capture, station, [obs])

		im_match = match_result['samples'][0]
		self.assertIsNone(im_match['temperature_difference_celsius'])

		om_match = match_result['samples'][1]
		self.assertAlmostEqual(om_match['temperature_difference_celsius'], 0.0)

		summary = summarize([{'capture': capture, 'station': station, 'match': match_result}])
		temp_stats = summary['comparisons_against_italia_meteo']['OPEN_METEO']['temperature']
		self.assertEqual(temp_stats['shared_eligible_denominator'], 0)
		self.assertIsNone(temp_stats['provider_mean_absolute_error_celsius'])

	def test_duplicate_station_observation_deduplicated_in_summarize(self):
		station = sample_station()
		obs = sample_observation(obs_time=1000, temp=20.0)

		capture_1 = sample_capture(captured_at=1000)
		capture_2 = sample_capture(captured_at=1010)

		match_1 = match_capture(capture_1, station, [obs])
		match_2 = match_capture(capture_2, station, [obs])

		entries = [
			{'capture': capture_1, 'station': station, 'match': match_1},
			{'capture': capture_2, 'station': station, 'match': match_2}
		]

		summary = summarize(entries)
		self.assertEqual(summary['total_routine_entries_evaluated'], 1)
		self.assertEqual(summary['deduplicated_entry_count'], 1)
		self.assertEqual(summary['providers']['OPEN_METEO']['availability']['total_count'], 2)
		self.assertEqual(summary['providers']['OPEN_METEO']['availability']['available_count'], 2)

	def test_availability_counts_routine_capture_attempts_while_accuracy_deduplicates(self):
		station = sample_station()
		obs = sample_observation(obs_time=1000, temp=20.0)

		failed_sample = {
			'provider': 'OPEN_METEO',
			'status': 'REQUEST_FAILED',
			'valid_at_epoch_seconds': None,
			'condition': None,
			'cloud_total_percent': None,
			'cloud_low_percent': None,
			'cloud_mid_percent': None,
			'cloud_high_percent': None,
			'temperature_celsius': None,
			'precipitation_millimeters': None
		}
		im_sample_1 = {
			'provider': 'ITALIA_METEO',
			'status': 'SUCCESS',
			'valid_at_epoch_seconds': 1000,
			'condition': 'PARTLY_CLOUDY',
			'temperature_celsius': 20.0
		}
		capture_1 = sample_capture(captured_at=1000, samples=[im_sample_1, failed_sample])

		success_sample = {
			'provider': 'OPEN_METEO',
			'status': 'SUCCESS',
			'valid_at_epoch_seconds': 1010,
			'condition': 'PARTLY_CLOUDY',
			'temperature_celsius': 20.0
		}
		im_sample_2 = {
			'provider': 'ITALIA_METEO',
			'status': 'SUCCESS',
			'valid_at_epoch_seconds': 1010,
			'condition': 'PARTLY_CLOUDY',
			'temperature_celsius': 20.0
		}
		capture_2 = sample_capture(captured_at=1010, samples=[im_sample_2, success_sample])

		match_1 = match_capture(capture_1, station, [obs])
		match_2 = match_capture(capture_2, station, [obs])

		entries = [
			{'capture': capture_1, 'station': station, 'match': match_1},
			{'capture': capture_2, 'station': station, 'match': match_2}
		]

		summary = summarize(entries)
		om_avail = summary['providers']['OPEN_METEO']['availability']
		self.assertEqual(om_avail['available_count'], 1)
		self.assertEqual(om_avail['total_count'], 2)
		self.assertAlmostEqual(om_avail['rate'], 0.5)

		comparison = summary['comparisons_against_italia_meteo']['OPEN_METEO']
		self.assertEqual(comparison['temperature']['shared_eligible_denominator'], 1)

	def test_diagnostic_collection_excluded_from_routine_summary(self):
		station = sample_station()
		obs = sample_observation(obs_time=1000)

		diagnostic_capture = sample_capture(captured_at=1000, collection='DIAGNOSTIC')
		match_diag = match_capture(diagnostic_capture, station, [obs])

		summary = summarize([{'capture': diagnostic_capture, 'station': station, 'match': match_diag}])
		self.assertEqual(summary['total_routine_entries_evaluated'], 0)

	@patch('http.client.HTTPSConnection')
	def test_fetch_station_returns_records(self, mock_https_conn_class):
		mock_conn = MagicMock()
		mock_https_conn_class.return_value = mock_conn
		mock_response = MagicMock()
		mock_response.status = 200
		mock_response.read.return_value = json.dumps([sample_station()]).encode('utf-8')
		mock_conn.getresponse.return_value = mock_response

		result = fetch_station('LIRF')
		self.assertEqual(len(result['records']), 1)
		self.assertEqual(result['records'][0]['icaoId'], 'LIRF')
		self.assertEqual(result['source_url'], 'https://aviationweather.gov/api/data/stationinfo?ids=LIRF&format=json')

	@patch('http.client.HTTPSConnection')
	def test_fetch_station_rejects_non_italian_station(self, mock_https_conn_class):
		mock_conn = MagicMock()
		mock_https_conn_class.return_value = mock_conn
		mock_response = MagicMock()
		mock_response.status = 200
		mock_response.read.return_value = json.dumps([sample_station(country='DE', icao_id='EDDF')]).encode('utf-8')
		mock_conn.getresponse.return_value = mock_response

		with self.assertRaises(ValueError):
			fetch_station('EDDF')

	@patch('http.client.HTTPSConnection')
	def test_fetch_station_handles_http_204_as_empty_records(self, mock_https_conn_class):
		mock_conn = MagicMock()
		mock_https_conn_class.return_value = mock_conn
		mock_response = MagicMock()
		mock_response.status = 204
		mock_response.read.return_value = b''
		mock_conn.getresponse.return_value = mock_response

		result = fetch_station('LIRF')
		self.assertEqual(result['records'], [])

	@patch('http.client.HTTPSConnection')
	def test_fetch_observations_surfaces_http_error(self, mock_https_conn_class):
		mock_conn = MagicMock()
		mock_https_conn_class.return_value = mock_conn
		mock_response = MagicMock()
		mock_response.status = 500
		mock_response.reason = 'Internal Server Error'
		mock_response.headers = {}
		mock_response.read.return_value = b'Server Error'
		mock_conn.getresponse.return_value = mock_response

		with self.assertRaises(urllib.error.HTTPError) as ctx:
			fetch_observations('LIRF', hours=2)

		self.assertEqual(ctx.exception.code, 500)

	@patch('http.client.HTTPSConnection')
	def test_empty_http_error_body_is_not_treated_as_missing_observations(self, connection_class):
		response = MagicMock()
		response.status = 500
		response.reason = 'Internal Server Error'
		response.headers = {}
		response.read.return_value = b''
		connection_class.return_value.getresponse.return_value = response
		with self.assertRaises(urllib.error.HTTPError) as error:
			fetch_observations('LIRF')

		self.assertEqual(error.exception.code, 500)

	def test_fetch_station_rejects_malformed_and_injection_station_ids(self):
		for bad_id in (
			'',
			None,
			1234,
			'LIR',
			'LIRFF',
			'LIR1',
			'1234',
			'LIR;',
			'$(reboot)',
			'`id`',
			'LIR\n',
			'LI RF',
			'LIR/F',
			'../LIRF'
		):
			with self.subTest(bad_id=bad_id), self.assertRaises(ValueError):
				fetch_station(bad_id)

	def test_fetch_observations_rejects_malformed_and_injection_station_ids(self):
		for bad_id in (
			'',
			None,
			1234,
			'LIR',
			'LIRFF',
			'LIR1',
			'1234',
			'LIR;',
			'$(reboot)',
			'`id`',
			'LIR\n',
			'LI RF',
			'LIR/F',
			'../LIRF'
		):
			with self.subTest(bad_id=bad_id), self.assertRaises(ValueError):
				fetch_observations(bad_id, hours=2)

	@patch('http.client.HTTPSConnection')
	def test_fetch_station_normalizes_lowercase_station_id(self, mock_https_conn_class):
		mock_conn = MagicMock()
		mock_https_conn_class.return_value = mock_conn
		mock_response = MagicMock()
		mock_response.status = 200
		mock_response.read.return_value = json.dumps([sample_station()]).encode('utf-8')
		mock_conn.getresponse.return_value = mock_response

		result = fetch_station('lirf')
		self.assertEqual(result['records'][0]['icaoId'], 'LIRF')

	def test_match_capture_rejects_malformed_station_metadata_icao_id(self):
		capture = sample_capture()
		for bad_icao in ('', 'LIR', 'LIRFF', 'LIR1', 'LIR;', '$(reboot)', 'LI RF', None, 1234):
			with self.subTest(bad_icao=bad_icao):
				bad_station = sample_station(icao_id=bad_icao)
				result = match_capture(capture, bad_station, [sample_observation()])
				self.assertEqual(result['status'], 'EXCLUDED')
				self.assertEqual(result['reason'], 'station_metadata_invalid')

	def test_equidistant_observations_earlier_wins_invariant_to_order(self):
		target_time = 1792556400
		station = sample_station()
		obs_earlier = sample_observation(
			obs_time=target_time - 300,
			raw_ob='METAR LIRF 210415Z 14010KT 9999 OVC010 18/17 Q1011',
			temp=18.0
		)
		obs_later = sample_observation(
			obs_time=target_time + 300,
			raw_ob='METAR LIRF 210425Z 14010KT 9999 SKC 22/17 Q1011',
			temp=22.0
		)
		capture = sample_capture(
			captured_at=target_time,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': target_time,
					'condition': 'OVERCAST',
					'temperature_celsius': 18.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': target_time,
					'condition': 'CLEAR_SKY',
					'temperature_celsius': 22.0
				}
			]
		)

		forward_match = match_capture(capture, station, [obs_earlier, obs_later])
		reversed_match = match_capture(capture, station, [obs_later, obs_earlier])

		for match_result in (forward_match, reversed_match):
			self.assertEqual(match_result['status'], 'MATCHED')
			self.assertEqual(match_result['capture_observation_time'], target_time - 300)
			self.assertEqual(match_result['capture_offset_seconds'], -300)
			self.assertEqual(match_result['capture_observed_cloud_category'], 'OVERCAST')
			im_sample = match_result['samples'][0]
			cand_sample = match_result['samples'][1]
			self.assertEqual(im_sample['capture_time_match']['observation_time'], target_time - 300)
			self.assertEqual(im_sample['forecast_valid_match']['observation_time'], target_time - 300)
			self.assertTrue(im_sample['capture_time_match']['cloud_agreed'])
			self.assertEqual(im_sample['capture_time_match']['observed_temperature'], 18.0)
			self.assertEqual(cand_sample['capture_time_match']['observation_time'], target_time - 300)
			self.assertEqual(cand_sample['forecast_valid_match']['observation_time'], target_time - 300)
			self.assertFalse(cand_sample['capture_time_match']['cloud_agreed'])
			self.assertEqual(cand_sample['capture_time_match']['observed_temperature'], 18.0)

	def test_metadata_cor_record_without_raw_cor_unscorable_cloud_retains_temperature(self):
		target_time = 1792556400
		station = sample_station()
		obs_meta_cor = sample_observation(
			obs_time=target_time,
			raw_ob='METAR LIRF 210420Z 14010KT 9999 SKC 21/17 Q1011',
			temp=21.0
		)
		obs_meta_cor['metarType'] = 'COR'

		capture = sample_capture(
			captured_at=target_time,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': target_time,
					'condition': 'CLEAR_SKY',
					'temperature_celsius': 21.0
				},
				{
					'provider': 'OPEN_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': target_time,
					'condition': 'CLEAR_SKY',
					'temperature_celsius': 23.0
				}
			]
		)

		match_result = match_capture(capture, station, [obs_meta_cor])
		self.assertEqual(match_result['status'], 'MATCHED')
		self.assertIsNone(match_result['capture_observed_cloud_category'])
		self.assertEqual(
			match_result['capture_observed_cloud_reason'],
			'correction_automation_uncertain'
		)
		im_sample = match_result['samples'][0]
		self.assertIsNone(im_sample['capture_time_match']['observed_cloud_category'])
		self.assertEqual(
			im_sample['capture_time_match']['observed_cloud_reason'],
			'correction_automation_uncertain'
		)
		self.assertIsNone(im_sample['capture_time_match']['cloud_agreed'])
		self.assertEqual(im_sample['capture_time_match']['observed_temperature'], 21.0)
		self.assertEqual(im_sample['capture_time_match']['temperature_difference_celsius'], 0.0)

		summary = summarize([{'capture': capture, 'station': station, 'match': match_result}])
		comparison = summary['comparisons_against_italia_meteo']['OPEN_METEO']
		self.assertEqual(comparison['cloud']['shared_eligible_denominator'], 0)
		self.assertEqual(comparison['temperature']['shared_eligible_denominator'], 1)
		self.assertAlmostEqual(comparison['temperature']['provider_mean_absolute_error_celsius'], 2.0)
		self.assertAlmostEqual(comparison['temperature']['italia_meteo_mean_absolute_error_celsius'], 0.0)

	def test_same_time_duplicate_metadata_cor_resolved_over_plain_metar_regardless_of_order(self):
		target_time = 1792556400
		station = sample_station()
		raw_skc = 'METAR LIRF 210420Z 14010KT 9999 SKC 21/17 Q1011'
		plain_obs = sample_observation(
			obs_time=target_time,
			raw_ob=raw_skc,
			temp=21.0,
			receipt_time='2026-10-21T04:25:00.000Z'
		)
		plain_obs['metarType'] = 'METAR'

		cor_obs = sample_observation(
			obs_time=target_time,
			raw_ob=raw_skc,
			temp=21.0,
			receipt_time='2026-10-21T04:25:00.000Z'
		)
		cor_obs['metarType'] = 'COR'

		capture = sample_capture(
			captured_at=target_time,
			samples=[
				{
					'provider': 'ITALIA_METEO',
					'status': 'SUCCESS',
					'valid_at_epoch_seconds': target_time,
					'condition': 'CLEAR_SKY',
					'temperature_celsius': 21.0
				}
			]
		)

		for order in ([plain_obs, cor_obs], [cor_obs, plain_obs]):
			with self.subTest(first_type=order[0]['metarType']):
				result = match_capture(capture, station, order)
				self.assertEqual(result['status'], 'MATCHED')
				self.assertIsNone(result['capture_observed_cloud_category'])
				self.assertEqual(
					result['capture_observed_cloud_reason'],
					'correction_automation_uncertain'
				)
				im_match = result['samples'][0]['capture_time_match']
				self.assertIsNone(im_match['observed_cloud_category'])
				self.assertEqual(
					im_match['observed_cloud_reason'],
					'correction_automation_uncertain'
				)
				self.assertIsNone(im_match['cloud_agreed'])
				self.assertEqual(im_match['observed_temperature'], 21.0)
				self.assertEqual(im_match['temperature_difference_celsius'], 0.0)
