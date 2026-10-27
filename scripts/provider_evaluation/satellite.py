"""
Offline EUMETSAT MSG cloud-mask extraction and independent provider diagnostics.

This intentionally does not modify the Android collector, METAR matches, or provider defaults.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path


PRODUCT_ID = 'EO:EUM:DAT:MSG:CLM'
MATCH_TOLERANCE_SECONDS = 600
DEFAULT_RADIUS_KM = 15.0
MIN_VALID_PIXELS = 9
MIN_VALID_FRACTION = 0.8
EARTH_RADIUS_KM = 6371.0
CLOUD_CLASSES = {0: 'clear_water', 1: 'clear_land', 2: 'cloud', 3: 'not_processed'}


def read_station(path):
	payload = json.loads(Path(path).read_text())
	records = payload.get('records', [])
	if not isinstance(records, list) or len(records) != 1:
		raise ValueError('Station file must contain exactly one verified station record')

	station = records[0]
	icao = italian_station_id(station)
	if icao is None:
		raise ValueError('Cloud-mask evaluation requires an Italian ICAO station')

	lat = station.get('lat')
	lon = station.get('lon')
	if not valid_coordinate(lat, -90, 90) or not valid_coordinate(lon, -180, 180):
		raise ValueError('Station coordinates must be finite geographic coordinates')

	return {'icaoId': icao, 'lat': float(lat), 'lon': float(lon)}


def italian_station_id(station):
	if not isinstance(station, dict) or station.get('country') != 'IT':
		return None

	icao = station.get('icaoId')
	if not isinstance(icao, str) or len(icao) != 4:
		return None

	if not icao.isascii() or not icao.isalpha():
		return None

	return icao.upper()


def valid_coordinate(value, lower, upper):
	return type(value) in (int, float) and math.isfinite(value) and lower <= value <= upper


def parse_grib_valid_time(get_value):
	"""Use the product's validity timestamp, never the host clock or file name."""
	try:
		date = int(get_value('validityDate'))
		time = int(get_value('validityTime'))
		stamp = datetime.strptime(f'{date:08d}{time:04d}', '%Y%m%d%H%M')
	except (ValueError, TypeError, KeyError) as error:
		raise ValueError('GRIB validityDate/validityTime are missing or invalid') from error

	return stamp.replace(tzinfo=timezone.utc).timestamp()


def decode_grib_file(path):
	"""Return a single GRIB message's timestamp, coordinates, classes, and metadata."""
	try:
		import eccodes
	except ImportError as error:
		raise RuntimeError('Install the optional eccodes Python package and ecCodes native library to extract GRIB cloud masks') from error

	with Path(path).open('rb') as source:
		message = eccodes.codes_grib_new_from_file(source)
		if message is None:
			raise ValueError('No GRIB message found')

		try:
			classification_keys = {
				'edition': eccodes.codes_get(message, 'edition'),
				'discipline': eccodes.codes_get(message, 'discipline'),
				'parameterCategory': eccodes.codes_get(message, 'parameterCategory'),
				'parameterNumber': eccodes.codes_get(message, 'parameterNumber')
			}
			if tuple(classification_keys.values()) != (2, 3, 0, 7):
				raise ValueError('GRIB parameter is not WMO cloud-mask code table 4.217')

			observed_at = parse_grib_valid_time(lambda key: eccodes.codes_get(message, key))
			latitudes = eccodes.codes_get_array(message, 'latitudes')
			longitudes = eccodes.codes_get_array(message, 'longitudes')
			values = eccodes.codes_get_values(message)
			metadata = {
				**classification_keys,
				'grid_type': eccodes.codes_get(message, 'gridType'),
				'validity_date': eccodes.codes_get(message, 'validityDate'),
				'validity_time': eccodes.codes_get(message, 'validityTime')
			}
		finally:
			eccodes.codes_release(message)

		# A multi-message input could silently replace the cloud mask with another product.
		another = eccodes.codes_grib_new_from_file(source)
		if another is not None:
			eccodes.codes_release(another)
			raise ValueError('Expected exactly one MSG CLM GRIB message per file')

	return observed_at, latitudes, longitudes, values, metadata


def validate_footprint_inputs(latitudes, longitudes, values, radius_km):
	if not math.isfinite(radius_km) or not 1 <= radius_km <= 100:
		raise ValueError('Sampling radius must be between 1 and 100 km')

	if any(getattr(items, 'ndim', 1) != 1 for items in (latitudes, longitudes, values)):
		raise ValueError('GRIB latitude, longitude, and class arrays must be aligned 1D pixels')

	if len(latitudes) != len(longitudes) or len(latitudes) != len(values):
		raise ValueError('GRIB latitude, longitude, and class arrays must be aligned 1D pixels')


def pixel_within_radius(latitude, longitude, station_lat, station_lon, radius_km, latitude_limit):
	if not math.isfinite(latitude) or not math.isfinite(longitude):
		return False

	if abs(latitude - station_lat) > latitude_limit:
		return False

	lat_r = math.radians(latitude)
	lon_r = math.radians(longitude)
	lat_diff = lat_r - math.radians(station_lat)
	lon_diff = (lon_r - math.radians(station_lon) + math.pi) % (2 * math.pi) - math.pi
	haversine = math.sin(lat_diff / 2) ** 2 + math.cos(math.radians(station_lat)) * math.cos(lat_r) * math.sin(lon_diff / 2) ** 2
	distance = 2 * EARTH_RADIUS_KM * math.asin(math.sqrt(min(1.0, max(0.0, haversine))))
	return distance <= radius_km


def count_cloud_pixel(counts, value):
	if not math.isfinite(value):
		counts[3] += 1
	elif value in CLOUD_CLASSES:
		counts[int(value)] += 1
	else:
		raise ValueError('Unexpected MSG CLM pixel class: verify the GRIB product and class mapping')


def footprint_from_counts(counts, total, radius_km):
	valid = counts[0] + counts[1] + counts[2]
	valid_fraction = valid / total if total else 0.0
	quality_ok = valid >= MIN_VALID_PIXELS and valid_fraction >= MIN_VALID_FRACTION
	return {
		'status': 'USABLE' if quality_ok else 'INSUFFICIENT_COVERAGE',
		'cloud_fraction_percent': 100.0 * counts[2] / valid if quality_ok else None,
		'pixel_counts': {CLOUD_CLASSES[key]: count for key, count in counts.items()},
		'total_pixels': total,
		'valid_pixels': valid,
		'valid_pixel_fraction': valid_fraction,
		'radius_km': radius_km,
		'quality_thresholds': {'minimum_valid_pixels': MIN_VALID_PIXELS, 'minimum_valid_fraction': MIN_VALID_FRACTION}
	}


def cloud_fraction_in_footprint(latitudes, longitudes, values, station, radius_km):
	"""Count distinct cloud-mask pixels in a geographic disc around the station."""
	validate_footprint_inputs(latitudes, longitudes, values, radius_km)
	latitude_limit = math.degrees(radius_km / EARTH_RADIUS_KM)
	counts = {key: 0 for key in CLOUD_CLASSES}
	total = 0
	for latitude, longitude, value in zip(latitudes, longitudes, values):
		if not pixel_within_radius(latitude, longitude, station['lat'], station['lon'], radius_km, latitude_limit):
			continue

		total += 1
		count_cloud_pixel(counts, value)

	return footprint_from_counts(counts, total, radius_km)


def extract_mask(grib_file, station_file, output, radius_km=DEFAULT_RADIUS_KM, decoder='eccodes'):
	station = read_station(station_file)
	if decoder == 'eccodes':
		observed_at, latitudes, longitudes, classes, metadata = decode_grib_file(grib_file)
		footprint = cloud_fraction_in_footprint(latitudes, longitudes, classes, station, radius_km)
	elif decoder == 'rasterio':
		from provider_evaluation.raster_cloudmask import extract_raster_cloud_mask

		observed_at, footprint, metadata = extract_raster_cloud_mask(grib_file, station, radius_km)
	else:
		raise ValueError('Unsupported satellite decoder')
	sha256_digest = hashlib.sha256()
	with Path(grib_file).open('rb') as source:
		for chunk in iter(lambda: source.read(1024 * 1024), b''):
			sha256_digest.update(chunk)
	sha256 = sha256_digest.hexdigest()
	result = {
		'schema_version': 1,
		'source': {
			'product_id_declared': PRODUCT_ID,
			'source_file': Path(grib_file).name,
			'sha256': sha256,
			'grib_metadata': metadata,
			'classification': CLOUD_CLASSES,
			'classification_source': 'EUMETSAT MSG CLM product; 0/1 clear, 2 cloud, 3 unprocessed',
			'classification_verified_against_file': False
		},
		'station': station,
		'observed_at_epoch_seconds': observed_at,
		'observed_at_utc': datetime.fromtimestamp(observed_at, timezone.utc).isoformat(),
		'footprint': footprint
	}

	output_directory = Path(output)
	output_directory.mkdir(parents=True, exist_ok=True)
	output_path = output_directory / f'{station["icaoId"]}-{int(observed_at)}-{sha256[:12]}.json'
	with output_path.open('x', encoding='utf-8') as target:
		json.dump(result, target, indent=2, allow_nan=False)
		target.write('\n')

	return output_path


def valid_class_count(value):
	return isinstance(value, int) and not isinstance(value, bool) and value >= 0


def validate_observation_location_time(observation, path):
	station = observation.get('station', {})
	if station.get('icaoId') is None or not valid_coordinate(station.get('lat'), -90, 90) or not valid_coordinate(station.get('lon'), -180, 180):
		raise ValueError(f'Invalid satellite station location: {path}')

	stamp = observation.get('observed_at_epoch_seconds')
	if not valid_coordinate(stamp, 1, 4102444800):
		raise ValueError(f'Invalid satellite observation timestamp: {path}')


def validate_observation_fraction(footprint, counts, valid, quality_ok, path):
	reported = footprint.get('cloud_fraction_percent')
	if not quality_ok:
		if reported is not None:
			raise ValueError(f'Satellite record with insufficient coverage must not provide a cloud score: {path}')
		return

	fraction = 100 * counts['cloud'] / valid
	if not valid_coordinate(reported, 0, 100) or not math.isclose(fraction, reported, abs_tol=1e-9):
		raise ValueError(f'Satellite fraction does not match original pixel counts: {path}')


def validate_observation_quality(footprint, counts, total, valid, path):
	radius = footprint.get('radius_km')
	if not valid_coordinate(radius, 1, 100):
		raise ValueError(f'Invalid satellite sampling radius: {path}')

	quality_ok = valid >= MIN_VALID_PIXELS and total > 0 and valid / total >= MIN_VALID_FRACTION
	if footprint.get('status') != ('USABLE' if quality_ok else 'INSUFFICIENT_COVERAGE'):
		raise ValueError(f'Incorrect satellite quality status: {path}')

	validate_observation_fraction(footprint, counts, valid, quality_ok, path)


def validate_observation_footprint(observation, path):
	footprint = observation.get('footprint', {})
	counts = footprint.get('pixel_counts', {})
	if set(counts) != set(CLOUD_CLASSES.values()) or not all(valid_class_count(value) for value in counts.values()):
		raise ValueError(f'Invalid satellite class counts: {path}')

	total = sum(counts.values())
	valid = total - counts['not_processed']
	if footprint.get('total_pixels') != total or footprint.get('valid_pixels') != valid:
		raise ValueError(f'Inconsistent satellite pixel totals: {path}')

	validate_observation_quality(footprint, counts, total, valid, path)


def validate_observation_source(observation, path):
	source = observation.get('source', {})
	sha256 = source.get('sha256')
	if not isinstance(sha256, str) or len(sha256) != 64 or any(char not in '0123456789abcdef' for char in sha256):
		raise ValueError(f'Missing or invalid source SHA-256: {path}')

	if source.get('product_id_declared') != PRODUCT_ID:
		raise ValueError(f'Unsupported satellite collection: {path}')


def parse_observation(path):
	observation = json.loads(path.read_text())
	if observation.get('schema_version') != 1:
		raise ValueError(f'Unsupported satellite record schema: {path}')

	validate_observation_location_time(observation, path)
	validate_observation_footprint(observation, path)
	validate_observation_source(observation, path)
	return observation


def load_observations(directory):
	unique = {}
	for path in sorted(Path(directory).glob('*.json')):
		observation = parse_observation(path)
		key = (observation['station']['icaoId'], observation['observed_at_epoch_seconds'])
		previous = unique.get(key)
		if previous is not None:
			if previous['source']['sha256'] != observation['source']['sha256'] or previous['footprint']['radius_km'] != observation['footprint']['radius_km']:
				raise ValueError(f'Conflicting satellite observations for station/time: {key}')
			continue

		unique[key] = observation

	return list(unique.values())


def verified_manual_location(capture):
	return (
		capture.get('location_source') == 'MANUAL_STATION' and
		capture.get('device_location') is False and
		capture.get('location_verified') is True and
		capture.get('simulator_active') is False
	)


def matches_station_coordinates(capture, station):
	latitude = capture.get('latitude')
	longitude = capture.get('longitude')
	if not valid_coordinate(latitude, -90, 90) or not valid_coordinate(longitude, -180, 180):
		return False

	return abs(latitude - station['lat']) <= 0.001 and abs(longitude - station['lon']) <= 0.001


def eligible_capture(capture, station, collection):
	if collection != 'ROUTINE' or not verified_manual_location(capture):
		return False

	if capture.get('station_id') != station.get('icaoId') or station.get('country') != 'IT':
		return False

	if not matches_station_coordinates(capture, station):
		return False

	return valid_coordinate(capture.get('captured_at_epoch_seconds'), 1, 4102444800)


def closest_satellite(capture, station, observations):
	matched = []
	for observation in observations:
		location = observation['station']
		if location['icaoId'] != capture['station_id'] or abs(location['lat'] - station['lat']) > 0.001 or abs(location['lon'] - station['lon']) > 0.001:
			continue

		if observation['footprint']['status'] != 'USABLE':
			continue

		delta = observation['observed_at_epoch_seconds'] - capture['captured_at_epoch_seconds']
		if abs(delta) <= MATCH_TOLERANCE_SECONDS:
			matched.append((abs(delta), observation['observed_at_epoch_seconds'], observation))

	if not matched:
		return None

	return min(matched, key=lambda match: (match[0], match[1]))[2]


def capture_provider_differences(capture, observation):
	fraction = observation['footprint']['cloud_fraction_percent']
	result = {}
	for sample in capture.get('samples', []):
		provider = sample.get('provider')
		cloud = sample.get('cloud_total_percent')
		if not isinstance(provider, str) or sample.get('status') != 'SUCCESS' or not valid_coordinate(cloud, 0, 100):
			continue

		valid_at = sample.get('valid_at_epoch_seconds')
		if not valid_coordinate(valid_at, 1, 4102444800) or abs(valid_at - capture['captured_at_epoch_seconds']) > 3600:
			continue

		result[provider] = {
			'forecast_cloud_total_percent': cloud,
			'forecast_valid_at_epoch_seconds': valid_at,
			'forecast_to_capture_offset_seconds': valid_at - capture['captured_at_epoch_seconds'],
			'absolute_difference_percentage_points': abs(cloud - fraction)
		}

	return result


def read_capture_candidates(root):
	for directory in sorted((root / 'runs').glob('*')):
		if (directory / 'failure.json').is_file():
			continue

		if not all((directory / filename).is_file() for filename in ('capture.json', 'station.json', 'run.json')):
			continue

		capture = json.loads((directory / 'capture.json').read_text())
		run_metadata = json.loads((directory / 'run.json').read_text())
		station_file = json.loads((directory / 'station.json').read_text())
		stations = station_file.get('records', [])
		if len(stations) != 1 or not eligible_capture(capture, stations[0], run_metadata.get('collection')):
			yield directory, capture, None
			continue

		yield directory, capture, stations[0]


def matched_satellite_capture(directory, capture, station, observation, differences):
	return {
		'run_id': directory.name,
		'station_id': station['icaoId'],
		'capture_time': capture['captured_at_epoch_seconds'],
		'satellite_time': observation['observed_at_epoch_seconds'],
		'satellite_offset_seconds': observation['observed_at_epoch_seconds'] - capture['captured_at_epoch_seconds'],
		'source_sha256': observation['source']['sha256'],
		'satellite_fraction_percent': observation['footprint']['cloud_fraction_percent'],
		'providers': differences
	}


def accumulate_satellite_differences(differences, per_provider, paired):
	for provider, sample in differences.items():
		per_provider.setdefault(provider, []).append(sample['absolute_difference_percentage_points'])

	baseline = differences.get('ITALIA_METEO')
	if baseline is None:
		return

	for provider, sample in differences.items():
		if provider == 'ITALIA_METEO':
			continue

		stat = paired.setdefault(provider, {'shared_count': 0, 'candidate_abs_error_sum': 0, 'italia_meteo_abs_error_sum': 0})
		stat['shared_count'] += 1
		stat['candidate_abs_error_sum'] += sample['absolute_difference_percentage_points']
		stat['italia_meteo_abs_error_sum'] += baseline['absolute_difference_percentage_points']


def provider_difference_summary(per_provider):
	return {
		name: {'sample_count': len(errors), 'mean_absolute_difference_percentage_points': sum(errors) / len(errors)}
		for name, errors in sorted(per_provider.items())
	}


def paired_difference_summary(paired):
	return {
		name: {
			'shared_capture_count': stats['shared_count'],
			'candidate_mean_absolute_difference_percentage_points': stats['candidate_abs_error_sum'] / stats['shared_count'],
			'italia_meteo_mean_absolute_difference_percentage_points': stats['italia_meteo_abs_error_sum'] / stats['shared_count']
		}
		for name, stats in sorted(paired.items())
	}


def matched_station_day_count(matched):
	return len({(item['station_id'], datetime.fromtimestamp(item['satellite_time'], timezone.utc).date()) for item in matched})


def unique_satellite_scan_count(matched):
	return len({(item['station_id'], item['satellite_time']) for item in matched})


def summarize_satellite(capture_root):
	root = Path(capture_root)
	observations = load_observations(root / 'satellite')
	per_provider = {}
	paired = {}
	matched = []
	unmatched_count = 0
	skipped_count = 0
	seen_captures = set()
	for directory, capture, station in read_capture_candidates(root):
		if station is None:
			skipped_count += 1
			continue

		key = (station['icaoId'], capture['captured_at_epoch_seconds'])
		if key in seen_captures:
			continue

		seen_captures.add(key)
		observation = closest_satellite(capture, station, observations)
		if observation is None:
			unmatched_count += 1
			continue

		differences = capture_provider_differences(capture, observation)
		matched.append(matched_satellite_capture(directory, capture, station, observation, differences))
		accumulate_satellite_differences(differences, per_provider, paired)

	return {
		'comparison_type': 'SATELLITE_CLOUD_MASK_DIAGNOSTIC_NOT_FORECAST_ACCURACY',
		'source_product': PRODUCT_ID,
		'temporal_tolerance_seconds': MATCH_TOLERANCE_SECONDS,
		'notes': 'Satellite pixel cloud fraction inside a spatial footprint is not the same quantity as model point total cloud cover; forecast-valid times can differ by up to one hour; neither capture-level nor repeated satellite samples are independent. Source products and class mapping require external verification before any provider decision.',
		'quality_rules': {'minimum_valid_pixels': MIN_VALID_PIXELS, 'minimum_valid_fraction': MIN_VALID_FRACTION},
		'satellite_observations_loaded': len(observations),
		'eligible_unique_routine_captures': len(seen_captures),
		'matched_captures': len(matched),
		'matched_station_days': matched_station_day_count(matched),
		'matched_unique_satellite_scans': unique_satellite_scan_count(matched),
		'unmatched_captures': unmatched_count,
		'skipped_captures': skipped_count,
		'provider_differences': provider_difference_summary(per_provider),
		'paired_against_italia_meteo': paired_difference_summary(paired),
		'matches': matched
	}


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	subparsers = parser.add_subparsers(dest='command', required=True)
	extract = subparsers.add_parser('extract', help='Extract a station-centered independent cloud-mask sample from a downloaded EUMETSAT MSG CLM GRIB file')
	extract.add_argument('--grib', type=Path, required=True)
	extract.add_argument('--station-file', type=Path, required=True, help='Original station.json from a provider evaluation run')
	extract.add_argument('--output', type=Path, default=Path('captures/provider-evaluation/satellite'))
	extract.add_argument('--radius-km', type=float, default=DEFAULT_RADIUS_KM)
	extract.add_argument('--decoder', choices=('eccodes', 'rasterio'), default='eccodes', help='Optional satellite GRIB decoder backend')
	summary = subparsers.add_parser('summarize', help='Compare extracted satellite masks with routine provider captures, without rewriting them')
	summary.add_argument('--output', type=Path, default=Path('captures/provider-evaluation'))
	args = parser.parse_args()
	if args.command == 'extract':
		print(extract_mask(args.grib, args.station_file, args.output, args.radius_km, decoder=args.decoder))
	else:
		print(json.dumps(summarize_satellite(args.output), indent=2, allow_nan=False))
