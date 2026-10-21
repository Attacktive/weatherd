"""Independent METAR observations retrieval, conservative matching, and evaluation summaries."""

from datetime import datetime, timezone
import http.client
import json
import math
import ssl
import urllib.error


AWC_HOST = 'aviationweather.gov'
AWC_BASE_PATH = '/api/data'
AWC_BASE_URL = f'https://{AWC_HOST}{AWC_BASE_PATH}'
DEFAULT_USER_AGENT = 'Weatherd-Provider-Evaluation/1.0'
DEFAULT_TIMEOUT_SECONDS = 15.0
MATCH_TOLERANCE_SECONDS = 900
MAX_COORDINATE_DELTA_DEGREES = 0.001
TREND_INDICATORS = {'TEMPO', 'BECMG', 'NOSIG', 'TREND', 'RMK'}

AMBIGUOUS_SKY_COVER_REASONS = {
	'CAVOK': 'cavok_ambiguous_sky_cover',
	'NSC': 'nsc_ambiguous_sky_cover',
	'CLR': 'clr_ambiguous_sky_cover',
	'NCD': 'ncd_ambiguous_sky_cover'
}

FROZEN_SCORING_RULES = {
	'description': 'Frozen provider evaluation scoring rules',
	'cloud_condition_mapping': {
		'CLEAR_SKY': 'CLEAR',
		'MAINLY_CLEAR': 'PARTLY_CLOUDY',
		'PARTLY_CLOUDY': 'PARTLY_CLOUDY',
		'OVERCAST': 'OVERCAST',
		'precipitation_and_fog_enums': None
	},
	'cloud_mapping_rationale': 'MAINLY_CLEAR is conservatively mapped to PARTLY_CLOUDY under the provider evaluation mapping, never assumed to be all-sky CLEAR.',
	'metar_cloud_rules': {
		'SKC': 'CLEAR (manual observer with no cloud layers)',
		'OVC': 'OVERCAST',
		'FEW_SCT_BKN': 'PARTLY_CLOUDY (BKN is broken 5-7 oktas, not overcast)',
		'AUTO': 'unscorable (automated report cannot establish full-sky category)',
		'AO1_AO2': 'unscorable (automated station flags in RMK prevent full-sky category even if body lacks AUTO)',
		'COR': 'unscorable for clouds (correction modifier can replace AUTO; manual provenance is uncertain)',
		'CAVOK_NSC_CLR_NCD': 'unscorable (visibility/ceiling OK or sensor limit, does not establish full-sky clear)',
		'VV': 'unscorable (vertical visibility or surface-based obscuration)',
		'trends_ignored': 'TEMPO, BECMG, NOSIG trend forecasts ignored; raw observed part only'
	},
	'matching_tolerance_seconds': MATCH_TOLERANCE_SECONDS,
	'nearest_observation_tie_rule': 'Nearest observation candidate is selected by minimum absolute offset, with earlier observation timestamp breaking ties deterministically.',
	'precipitation_scoring': 'NONE',
	'paired_denominator_rule': 'Shared candidate vs ITALIA_METEO comparisons require identical observation times.'
}


def utc_now():
	return datetime.now(timezone.utc).isoformat()


def is_finite_number(value) -> bool:
	return isinstance(value, (int, float)) and math.isfinite(value)


def extract_finite_float(value) -> float | None:
	if isinstance(value, (int, float)) and math.isfinite(value):
		return float(value)

	return None


def validate_station_id(station_id: str) -> str:
	if not isinstance(station_id, str):
		raise ValueError('Station ID must be a non-empty string')

	if len(station_id) != 4 or not station_id.isalpha() or not station_id.isascii():
		raise ValueError(f'Station ID must be 4 ICAO letters, got: {station_id!r}')

	return station_id.upper()


def is_valid_icao_id(icao_id) -> bool:
	if not isinstance(icao_id, str) or len(icao_id) != 4:
		return False

	return icao_id.isalpha() and icao_id.isascii()


def request_awc_api(endpoint: str, query_params: str) -> tuple[str, str, list[dict]]:
	path = f'{AWC_BASE_PATH}{endpoint}?{query_params}'
	source_url = f'https://{AWC_HOST}{path}'
	retrieved_at = utc_now()
	connection = http.client.HTTPSConnection(
		AWC_HOST,
		timeout=DEFAULT_TIMEOUT_SECONDS,
		context=ssl.create_default_context()
	)

	try:
		connection.request('GET', path, headers={'User-Agent': DEFAULT_USER_AGENT})
		response = connection.getresponse()
		status = response.status
		body = response.read()
		if status >= 400:
			error = urllib.error.HTTPError(
				url=source_url,
				code=status,
				msg=response.reason,
				hdrs=response.headers,
				fp=None
			)

			error.close()
			raise error

		if status == 204 or not body:
			return (source_url, retrieved_at, [])

		records = json.loads(body.decode('utf-8'))
		return (source_url, retrieved_at, records)
	finally:
		connection.close()


def is_valid_italian_station_record(record: dict, station_icao: str) -> bool:
	if record.get('country') != 'IT':
		return False

	if str(record.get('icaoId', '')).upper() != station_icao:
		return False

	return is_finite_number(record.get('lat')) and is_finite_number(record.get('lon'))


def fetch_station(station_id: str):
	station_icao = validate_station_id(station_id)
	source_url, retrieved_at, raw_records = request_awc_api(
		'/stationinfo',
		f'ids={station_icao}&format=json'
	)
	if not raw_records:
		return {
			'source_url': source_url,
			'retrieved_at_utc': retrieved_at,
			'records': []
		}

	valid_records = [
		record for record in raw_records
		if is_valid_italian_station_record(record, station_icao)
	]

	if not valid_records:
		raise ValueError(f'Station {station_id} is not an Italian station or has invalid coordinates')

	return {
		'source_url': source_url,
		'retrieved_at_utc': retrieved_at,
		'records': valid_records
	}


def fetch_observations(station_id: str, hours: int = 2):
	station_icao = validate_station_id(station_id)
	source_url, retrieved_at, records = request_awc_api(
		'/metar',
		f'ids={station_icao}&format=json&hours={int(hours)}'
	)
	return {
		'source_url': source_url,
		'retrieved_at_utc': retrieved_at,
		'records': records
	}


def check_metar_automation_flags(tokens: list[str]) -> str | None:
	if 'AO1' in tokens or 'AO2' in tokens:
		return 'automated_observation_ao1_ao2'

	if 'AUTO' in tokens:
		return 'automated_observation_unscorable'

	return None


def extract_observed_metar_tokens(tokens: list[str]) -> list[str]:
	observed = []
	for token in tokens:
		if token in TREND_INDICATORS:
			break

		observed.append(token)

	return observed


def check_unscorable_observed_tokens(observed_tokens: list[str]) -> str | None:
	if 'COR' in observed_tokens:
		return 'correction_automation_uncertain'

	for token in observed_tokens:
		if token in AMBIGUOUS_SKY_COVER_REASONS:
			return AMBIGUOUS_SKY_COVER_REASONS[token]

		if token.startswith('VV'):
			return 'vertical_visibility_obscuration'

	return None


def extract_metar_cloud_layer(token: str) -> str | None:
	if len(token) < 6:
		return None

	prefix = token[:3]
	if prefix not in ('FEW', 'SCT', 'BKN', 'OVC'):
		return None

	suffix = token[3:6]
	if suffix.isdigit() or suffix == '///':
		return prefix

	return None


def evaluate_metar_cloud_layers(cloud_layers: list[str], observed_tokens: list[str]) -> tuple[str | None, str]:
	if 'SKC' in observed_tokens:
		return ('CLEAR', 'manual_skc')

	if 'OVC' in cloud_layers:
		return ('OVERCAST', 'ovc_layer')

	if cloud_layers:
		return ('PARTLY_CLOUDY', 'few_sct_bkn_layers')

	return (None, 'no_cloud_layers_reported')


def classify_metar_clouds(raw_ob: str):
	if not isinstance(raw_ob, str) or not raw_ob.strip():
		return (None, 'missing_raw_observation')

	tokens = raw_ob.strip().split()
	automation_reason = check_metar_automation_flags(tokens)
	if automation_reason is not None:
		return (None, automation_reason)

	observed_tokens = extract_observed_metar_tokens(tokens)
	unscorable_reason = check_unscorable_observed_tokens(observed_tokens)
	if unscorable_reason is not None:
		return (None, unscorable_reason)

	cloud_layers = [
		layer for layer in (extract_metar_cloud_layer(t) for t in observed_tokens)
		if layer is not None
	]

	return evaluate_metar_cloud_layers(cloud_layers, observed_tokens)


def extract_raw_metar_station(raw_ob: str):
	if not isinstance(raw_ob, str) or not raw_ob.strip():
		return None

	tokens = raw_ob.strip().split()
	idx = 0
	if tokens[0] in ('METAR', 'SPECI'):
		idx = 1

	if idx < len(tokens) and tokens[idx] == 'COR':
		idx += 1

	if idx < len(tokens):
		return tokens[idx].upper()

	return None


def skip_metar_header_tokens(tokens: list[str]) -> int:
	idx = 0
	if tokens and tokens[0] in ('METAR', 'SPECI'):
		idx = 1

	if idx < len(tokens) and tokens[idx] == 'COR':
		idx += 1

	return idx


def find_raw_metar_time_token(tokens: list[str]) -> str | None:
	idx = skip_metar_header_tokens(tokens) + 1
	if idx < len(tokens) and tokens[idx] == 'COR':
		idx += 1

	if idx < len(tokens):
		return tokens[idx]

	return None


def is_valid_metar_time_components(day: int, hour: int, minute: int) -> bool:
	if not (1 <= day <= 31):
		return False

	if not (0 <= hour <= 23):
		return False

	return 0 <= minute <= 59


def parse_metar_time_token(token: str) -> tuple[int, int, int] | None:
	if len(token) != 7 or not token.endswith(('Z', 'z')):
		return None

	digits = token[:6]
	if not digits.isdigit():
		return None

	day = int(digits[:2])
	hour = int(digits[2:4])
	minute = int(digits[4:6])
	if not is_valid_metar_time_components(day, hour, minute):
		return None

	return (day, hour, minute)


def extract_raw_metar_time(raw_ob: str):
	if not isinstance(raw_ob, str) or not raw_ob.strip():
		return None

	token = find_raw_metar_time_token(raw_ob.strip().split())
	if token is None:
		return None

	return parse_metar_time_token(token)


def validate_raw_metar_time(raw_ob: str, obs_time: int | float) -> bool:
	time_parts = extract_raw_metar_time(raw_ob)
	if time_parts is None or not is_finite_number(obs_time):
		return False

	try:
		obs_dt = datetime.fromtimestamp(obs_time, tz=timezone.utc)
	except (OverflowError, OSError, ValueError):
		return False

	day, hour, minute = time_parts
	return obs_dt.day == day and obs_dt.hour == hour and obs_dt.minute == minute


def is_raw_metar_cor(raw_ob: str) -> bool:
	if not isinstance(raw_ob, str) or not raw_ob.strip():
		return False

	return 'COR' in raw_ob.strip().split()[:5]


def parse_receipt_time(receipt_time) -> float | None:
	if not isinstance(receipt_time, str) or not receipt_time.strip():
		return None

	try:
		clean_str = receipt_time.strip()
		if clean_str.endswith('Z'):
			clean_str = clean_str[:-1] + '+00:00'

		return datetime.fromisoformat(clean_str).timestamp()
	except (ValueError, TypeError):
		return None


def are_records_identical(record_a: dict, record_b: dict) -> bool:
	return (
		record_a.get('rawOb') == record_b.get('rawOb') and
		record_a.get('temp') == record_b.get('temp') and
		is_cor_record(record_a) == is_cor_record(record_b)
	)


def all_records_identical(records: list[dict]) -> bool:
	first = records[0]
	return all(are_records_identical(first, r) for r in records[1:])


def is_uniform_or_single_record(records: list[dict]) -> bool:
	if len(records) <= 1:
		return True

	return all_records_identical(records)


def is_cor_record(record: dict) -> bool:
	return is_raw_metar_cor(record.get('rawOb')) or record.get('metarType') == 'COR'


def partition_observation_records(records: list[dict]) -> tuple[list[dict], list[dict]]:
	cor_records = []
	non_cor_records = []
	for r in records:
		if is_cor_record(r):
			cor_records.append(r)
		else:
			non_cor_records.append(r)

	return (cor_records, non_cor_records)


def get_dated_cor_records(cor_records: list[dict]) -> list[tuple[float, dict]] | None:
	dated = []
	for r in cor_records:
		ts = parse_receipt_time(r.get('receiptTime'))
		if ts is None:
			return None

		dated.append((ts, r))

	return dated


def find_unambiguous_newest_cor(dated: list[tuple[float, dict]]) -> tuple[dict | None, float | None]:
	if not dated:
		return (None, None)

	max_ts = max(ts for ts, _ in dated)
	top_records = [r for ts, r in dated if ts == max_ts]
	if not all_records_identical(top_records):
		return (None, None)

	return (top_records[0], max_ts)


def select_best_cor_record(cor_records: list[dict]) -> tuple[dict | None, float | None]:
	if not cor_records:
		return (None, None)

	dated = get_dated_cor_records(cor_records)
	if dated is None:
		return (None, None)

	return find_unambiguous_newest_cor(dated)


def has_newer_non_cor_record(non_cor_records: list[dict], threshold_ts: float) -> bool:
	for r in non_cor_records:
		ts = parse_receipt_time(r.get('receiptTime'))
		if ts is not None and ts > threshold_ts:
			return True

	return False


def resolve_observations_at_timestamp(records: list[dict]) -> dict | None:
	"""Resolve same-time revisions conservatively, excluding conflicts without reliable correction ordering."""
	if not records:
		return None

	if is_uniform_or_single_record(records):
		return records[0]

	cor_records, non_cor_records = partition_observation_records(records)
	best_cor, best_ts = select_best_cor_record(cor_records)
	if best_cor is None:
		return None

	if has_newer_non_cor_record(non_cor_records, best_ts):
		return None

	return best_cor


def map_condition_to_cloud_category(condition: str | None):
	if condition == 'CLEAR_SKY':
		return 'CLEAR'

	if condition in ('MAINLY_CLEAR', 'PARTLY_CLOUDY'):
		return 'PARTLY_CLOUDY'

	if condition == 'OVERCAST':
		return 'OVERCAST'

	return None


def make_unmatched_target_result(reason: str) -> dict:
	return {
		'matched': False,
		'unmatched_reason': reason,
		'observation_time': None,
		'offset_seconds': None,
		'observed_cloud_category': None,
		'observed_cloud_reason': reason,
		'cloud_agreed': None,
		'observed_temperature': None,
		'temperature_difference_celsius': None,
		'raw_observation': None
	}


def make_excluded_capture_result(reason: str, station_id: str | None, capture: dict) -> dict:
	return {
		'status': 'EXCLUDED',
		'reason': reason,
		'station_id': station_id,
		'collection': capture.get('collection', 'ROUTINE'),
		'captured_at_epoch_seconds': capture.get('captured_at_epoch_seconds'),
		'capture_matched': False,
		'capture_unmatched_reason': reason,
		'capture_observation_time': None,
		'capture_offset_seconds': None,
		'capture_observation_raw': None,
		'capture_observed_cloud_category': None,
		'capture_observed_cloud_reason': reason,
		'samples': []
	}


def find_closest_observation(target_time: float, valid_obs_records: list[dict]) -> dict | None:
	best_obs = None
	best_diff = None
	for obs in valid_obs_records:
		diff = abs(obs['obsTime'] - target_time)
		if diff <= MATCH_TOLERANCE_SECONDS:
			if best_obs is None or diff < best_diff or (diff == best_diff and obs['obsTime'] < best_obs['obsTime']):
				best_diff = diff
				best_obs = obs

	return best_obs


def compute_cloud_agreement(provider_cat: str | None, obs_cat: str | None) -> bool | None:
	if provider_cat is not None and obs_cat is not None:
		return provider_cat == obs_cat

	return None


def compute_temp_difference(provider_temp: float | None, obs_temp: float | None) -> float | None:
	if provider_temp is not None and obs_temp is not None:
		return provider_temp - obs_temp

	return None


def match_single_target(target_time, valid_obs_records, provider_cloud_category, provider_temp):
	if target_time is None or not is_finite_number(target_time):
		return make_unmatched_target_result('missing_target_timestamp')

	best_obs = find_closest_observation(target_time, valid_obs_records)
	if best_obs is None:
		return make_unmatched_target_result('no_observation_within_tolerance')

	if is_cor_record(best_obs):
		obs_cloud_cat, obs_cloud_reason = (None, 'correction_automation_uncertain')
	else:
		obs_cloud_cat, obs_cloud_reason = classify_metar_clouds(best_obs.get('rawOb', ''))
	obs_temp = extract_finite_float(best_obs.get('temp'))

	return {
		'matched': True,
		'unmatched_reason': None,
		'observation_time': int(best_obs['obsTime']),
		'offset_seconds': int(best_obs['obsTime'] - target_time),
		'observed_cloud_category': obs_cloud_cat,
		'observed_cloud_reason': obs_cloud_reason,
		'cloud_agreed': compute_cloud_agreement(provider_cloud_category, obs_cloud_cat),
		'observed_temperature': obs_temp,
		'temperature_difference_celsius': compute_temp_difference(provider_temp, obs_temp),
		'raw_observation': best_obs
	}


def validate_station_metadata(station: dict) -> bool:
	if station.get('country') != 'IT':
		return False

	if not is_valid_icao_id(station.get('icaoId')):
		return False

	return is_finite_number(station.get('lat')) and is_finite_number(station.get('lon'))


def validate_capture_evidence_flags(capture: dict, station_icao: str) -> str | None:
	if not is_finite_number(capture.get('captured_at_epoch_seconds')):
		return 'invalid_capture_timestamp'

	if capture.get('location_source') != 'MANUAL_STATION':
		return 'invalid_location_source'

	if capture.get('station_id') != station_icao:
		return 'station_id_mismatch'

	if capture.get('device_location') is not False:
		return 'device_location_not_false'

	if capture.get('location_verified') is not True:
		return 'location_not_verified'

	if capture.get('simulator_active') is not False:
		return 'simulator_active'

	return None


def validate_capture_coordinates(capture: dict, station: dict) -> str | None:
	lat = capture.get('latitude')
	lon = capture.get('longitude')
	if not is_finite_number(lat) or not is_finite_number(lon):
		return 'invalid_capture_coordinates'

	delta_lat = abs(lat - station['lat'])
	delta_lon = abs(lon - station['lon'])
	if delta_lat > MAX_COORDINATE_DELTA_DEGREES or delta_lon > MAX_COORDINATE_DELTA_DEGREES:
		return 'coordinate_mismatch'

	return None


def get_capture_exclusion_reason(capture: dict, station: dict) -> str | None:
	if not validate_station_metadata(station):
		return 'station_metadata_invalid'

	flag_reason = validate_capture_evidence_flags(capture, station.get('icaoId'))
	if flag_reason is not None:
		return flag_reason

	return validate_capture_coordinates(capture, station)


def check_obs_coordinates(obs: dict, station_lat: float, station_lon: float) -> bool:
	obs_lat = obs.get('lat')
	obs_lon = obs.get('lon')
	if obs_lat is None and obs_lon is None:
		return True

	if not is_finite_number(obs_lat) or not is_finite_number(obs_lon):
		return False

	delta_lat = abs(obs_lat - station_lat)
	delta_lon = abs(obs_lon - station_lon)
	return delta_lat <= MAX_COORDINATE_DELTA_DEGREES and delta_lon <= MAX_COORDINATE_DELTA_DEGREES


def is_valid_observation_for_capture(obs: dict, station_icao: str, station: dict, captured_at: float) -> bool:
	obs_time = obs.get('obsTime')
	if not is_finite_number(obs_time) or obs_time > captured_at + MATCH_TOLERANCE_SECONDS:
		return False

	if str(obs.get('icaoId', '')).upper() != station_icao:
		return False

	if extract_raw_metar_station(obs.get('rawOb')) != station_icao:
		return False

	if not check_obs_coordinates(obs, station['lat'], station['lon']):
		return False

	return validate_raw_metar_time(obs.get('rawOb'), obs_time)


def collect_resolved_observations(valid_obs: list[dict]) -> list[dict]:
	obs_by_time = {}
	for obs in valid_obs:
		obs_by_time.setdefault(obs['obsTime'], []).append(obs)

	resolved = []
	for recs in obs_by_time.values():
		item = resolve_observations_at_timestamp(recs)
		if item is not None:
			resolved.append(item)

	return resolved


def build_sample_match_record(
	provider, status, valid_at, condition, cloud_cat, temp, cap_match, fc_match
) -> dict:
	return {
		'provider': provider,
		'status': status,
		'matched': cap_match['matched'],
		'unmatched_reason': cap_match['unmatched_reason'],
		'valid_at_epoch_seconds': valid_at,
		'observation_time': cap_match['observation_time'],
		'offset_seconds': cap_match['offset_seconds'],
		'provider_condition': condition,
		'provider_cloud_category': cloud_cat,
		'observed_cloud_category': cap_match['observed_cloud_category'],
		'observed_cloud_reason': cap_match['observed_cloud_reason'],
		'cloud_agreed': cap_match['cloud_agreed'],
		'provider_temperature': temp,
		'observed_temperature': cap_match['observed_temperature'],
		'temperature_difference_celsius': cap_match['temperature_difference_celsius'],
		'raw_observation': cap_match['raw_observation'],
		'capture_time_match': cap_match,
		'forecast_valid_match': fc_match
	}


def match_capture_sample(sample: dict, captured_at: float, resolved_obs: list[dict]) -> dict:
	provider = sample.get('provider')
	status = sample.get('status')
	condition = sample.get('condition')
	provider_cloud_cat = map_condition_to_cloud_category(condition)
	provider_temp = extract_finite_float(sample.get('temperature_celsius'))
	valid_at = sample.get('valid_at_epoch_seconds')

	if status != 'SUCCESS':
		if status == 'PENDING':
			reason = 'provider_pending'
		else:
			reason = f'provider_{str(status).lower()}'

		failure = make_unmatched_target_result(reason)
		return build_sample_match_record(
			provider,
			status,
			valid_at,
			condition,
			provider_cloud_cat,
			provider_temp,
			failure,
			failure
		)

	cap_match = match_single_target(captured_at, resolved_obs, provider_cloud_cat, provider_temp)
	fc_match = match_single_target(valid_at, resolved_obs, provider_cloud_cat, provider_temp)
	return build_sample_match_record(
		provider,
		status,
		valid_at,
		condition,
		provider_cloud_cat,
		provider_temp,
		cap_match,
		fc_match
	)


def match_capture(capture: dict, station: dict, observation_records: list[dict]):
	station_icao = station.get('icaoId')
	exclusion = get_capture_exclusion_reason(capture, station)
	if exclusion is not None:
		return make_excluded_capture_result(exclusion, station_icao, capture)

	captured_at = capture['captured_at_epoch_seconds']
	norm_icao = str(station_icao).upper()
	valid_obs = [
		obs for obs in observation_records
		if is_valid_observation_for_capture(obs, norm_icao, station, captured_at)
	]

	resolved_obs = collect_resolved_observations(valid_obs)
	cap_match = match_single_target(captured_at, resolved_obs, None, None)

	samples = [
		match_capture_sample(s, captured_at, resolved_obs)
		for s in capture.get('samples', [])
	]

	if cap_match['matched']:
		root_status = 'MATCHED'
		root_reason = None
	else:
		root_status = 'PENDING'
		root_reason = cap_match['unmatched_reason']

	return {
		'status': root_status,
		'reason': root_reason,
		'station_id': station_icao,
		'collection': capture.get('collection', 'ROUTINE'),
		'captured_at_epoch_seconds': captured_at,
		'capture_matched': cap_match['matched'],
		'capture_unmatched_reason': cap_match['unmatched_reason'],
		'capture_observation_time': cap_match['observation_time'],
		'capture_offset_seconds': cap_match['offset_seconds'],
		'capture_observation_raw': cap_match['raw_observation'],
		'capture_observed_cloud_category': cap_match['observed_cloud_category'],
		'capture_observed_cloud_reason': cap_match['observed_cloud_reason'],
		'samples': samples
	}


def find_provider_samples(samples: list[dict], candidate_name: str) -> tuple[dict | None, dict | None]:
	im_sample = None
	cand_sample = None
	for sample in samples:
		provider = sample.get('provider')
		if provider == 'ITALIA_METEO':
			im_sample = sample
		elif provider == candidate_name:
			cand_sample = sample

	return (im_sample, cand_sample)


def is_eligible_paired_axis(im_axis: dict, cand_axis: dict) -> bool:
	if not im_axis.get('matched') or not cand_axis.get('matched'):
		return False

	im_time = im_axis.get('observation_time')
	cand_time = cand_axis.get('observation_time')
	return im_time is not None and im_time == cand_time


def accumulate_cloud_pair(im_cat: str | None, cand_cat: str | None, obs_cat: str | None, stats: dict):
	if im_cat is None or cand_cat is None or obs_cat is None:
		return

	stats['cloud_denom'] += 1
	if cand_cat == obs_cat:
		stats['cand_cloud_agreed'] += 1

	if im_cat == obs_cat:
		stats['im_cloud_agreed'] += 1


def accumulate_temp_pair(cand_diff, im_diff, stats: dict):
	if not is_finite_number(cand_diff) or not is_finite_number(im_diff):
		return

	stats['temp_denom'] += 1
	stats['cand_temp_errors'].append(abs(cand_diff))
	stats['im_temp_errors'].append(abs(im_diff))


def format_paired_comparison_result(stats: dict) -> dict:
	cd = stats['cloud_denom']
	cand_rate = None
	im_rate = None
	if cd > 0:
		cand_rate = stats['cand_cloud_agreed'] / cd
		im_rate = stats['im_cloud_agreed'] / cd

	td = stats['temp_denom']
	cand_mae = None
	im_mae = None
	if td > 0:
		cand_mae = sum(stats['cand_temp_errors']) / td
		im_mae = sum(stats['im_temp_errors']) / td

	return {
		'cloud': {
			'shared_eligible_denominator': cd,
			'provider_agreed_count': stats['cand_cloud_agreed'],
			'provider_agreement_rate': cand_rate,
			'italia_meteo_agreed_count': stats['im_cloud_agreed'],
			'italia_meteo_agreement_rate': im_rate
		},
		'temperature': {
			'shared_eligible_denominator': td,
			'provider_mean_absolute_error_celsius': cand_mae,
			'italia_meteo_mean_absolute_error_celsius': im_mae
		}
	}


def compute_paired_comparison_for_axis(deduped_entries, candidate_name, match_key):
	stats = {
		'cloud_denom': 0,
		'cand_cloud_agreed': 0,
		'im_cloud_agreed': 0,
		'temp_denom': 0,
		'cand_temp_errors': [],
		'im_temp_errors': []
	}
	for entry in deduped_entries:
		im_sample, cand_sample = find_provider_samples(
			entry.get('match', {}).get('samples', []),
			candidate_name
		)
		if im_sample is None or cand_sample is None:
			continue

		im_axis = im_sample.get(match_key, im_sample)
		cand_axis = cand_sample.get(match_key, cand_sample)
		if not is_eligible_paired_axis(im_axis, cand_axis):
			continue

		accumulate_cloud_pair(
			im_sample.get('provider_cloud_category'),
			cand_sample.get('provider_cloud_category'),
			cand_axis.get('observed_cloud_category'),
			stats
		)
		accumulate_temp_pair(
			cand_axis.get('temperature_difference_celsius'),
			im_axis.get('temperature_difference_celsius'),
			stats
		)

	return format_paired_comparison_result(stats)


def is_valid_routine_entry(entry: dict) -> bool:
	return (
		entry.get('capture', {}).get('collection') == 'ROUTINE' and
		entry.get('match', {}).get('status') != 'EXCLUDED'
	)


def filter_routine_entries(entries: list[dict]) -> list[dict]:
	return [entry for entry in entries if is_valid_routine_entry(entry)]


def get_entry_dedup_key(entry: dict) -> tuple:
	station = entry.get('station', {})
	icao = station.get('icaoId')
	match = entry.get('match', {})
	obs_time = match.get('capture_observation_time')
	if obs_time is not None:
		return (icao, obs_time)

	return (icao, entry.get('capture', {}).get('captured_at_epoch_seconds'))


def rank_entry_for_dedup(entry: dict) -> tuple:
	match = entry.get('match', {})
	is_matched = match.get('status') == 'MATCHED'
	success_count = sum(
		1 for s in match.get('samples', []) if s.get('status') == 'SUCCESS'
	)
	return (is_matched, success_count)


def deduplicate_routine_entries(routine_entries: list[dict]) -> list[dict]:
	entries_by_key = {}
	for entry in routine_entries:
		entries_by_key.setdefault(get_entry_dedup_key(entry), []).append(entry)

	deduped = []
	for group in entries_by_key.values():
		if len(group) == 1:
			deduped.append(group[0])
		else:
			deduped.append(max(group, key=rank_entry_for_dedup))

	return deduped


def compute_single_provider_availability(routine_entries: list[dict], provider: str) -> dict:
	total_count = 0
	available_count = 0
	for entry in routine_entries:
		for sample in entry.get('capture', {}).get('samples', []):
			if sample.get('provider') == provider:
				total_count += 1
				if sample.get('status') == 'SUCCESS':
					available_count += 1

	rate = 0.0
	if total_count > 0:
		rate = available_count / total_count

	return {
		'availability': {
			'available_count': available_count,
			'total_count': total_count,
			'rate': rate
		}
	}


def compute_provider_availabilities(routine_entries: list[dict], provider_names: list[str]) -> dict:
	return {
		provider: compute_single_provider_availability(routine_entries, provider)
		for provider in provider_names
	}


def compute_comparisons_against_italia_meteo(deduped_entries: list[dict], provider_names: list[str]) -> dict:
	comparisons = {}
	for candidate in provider_names:
		if candidate == 'ITALIA_METEO':
			continue

		cap_comp = compute_paired_comparison_for_axis(
			deduped_entries,
			candidate,
			'capture_time_match'
		)
		fc_comp = compute_paired_comparison_for_axis(
			deduped_entries,
			candidate,
			'forecast_valid_match'
		)
		comparisons[candidate] = {
			'cloud': cap_comp['cloud'],
			'temperature': cap_comp['temperature'],
			'capture_time': cap_comp,
			'forecast_valid_diagnostics': fc_comp
		}

	return comparisons


def summarize(entries: list[dict]):
	routine_entries = filter_routine_entries(entries)
	deduped_entries = deduplicate_routine_entries(routine_entries)

	provider_names = sorted({
		sample.get('provider')
		for entry in routine_entries
		for sample in entry.get('capture', {}).get('samples', [])
		if sample.get('provider') is not None
	})

	return {
		'scoring_rules': FROZEN_SCORING_RULES,
		'total_entries': len(entries),
		'total_routine_entries_received': len(routine_entries),
		'total_routine_entries_evaluated': len(deduped_entries),
		'deduplicated_entry_count': len(deduped_entries),
		'providers': compute_provider_availabilities(routine_entries, provider_names),
		'comparisons_against_italia_meteo': compute_comparisons_against_italia_meteo(deduped_entries, provider_names)
	}
