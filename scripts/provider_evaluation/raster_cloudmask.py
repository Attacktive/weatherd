"""Optional raster-backed decoder for EUMETSAT MSG cloud-mask GRIB2 files.

Read only a station-sized window instead of materializing a full geostationary
raster as per-pixel latitude/longitude arrays.
"""

from datetime import datetime, timezone
import math
from pathlib import Path
import struct


EXPECTED_SECTIONS = ([1, 3, 4, 5, 6, 7], [1, 2, 3, 4, 5, 6, 7])


def grib_envelope(data):
	"""Locate a complete embedded GRIB2 message, allowing a product wrapper."""
	start = data.find(b'GRIB')
	if start < 0 or start > 4096 or len(data) - start < 20:
		raise ValueError('Missing or unsupported GRIB2 message')

	edition = data[start + 7]
	discipline = data[start + 6]
	message_length = struct.unpack_from('>Q', data, start + 8)[0]
	if edition != 2 or start + message_length != len(data) or data[-4:] != b'7777':
		raise ValueError('Invalid GRIB2 message bounds, edition, or trailer')

	return start, message_length, discipline


def grib_sections(data, start):
	"""Read section headers while retaining strict message bounds."""
	position = start + 16
	sections = []
	while position < len(data) - 4:
		if position + 5 > len(data) - 4:
			raise ValueError('Truncated GRIB2 section')

		section_length = struct.unpack_from('>I', data, position)[0]
		if section_length < 5 or position + section_length > len(data) - 4:
			raise ValueError('Invalid GRIB2 section bounds')

		section_number = data[position + 4]
		sections.append((section_number, data[position:position + section_length]))
		position += section_length

	if position != len(data) - 4 or [number for number, _ in sections] not in EXPECTED_SECTIONS:
		raise ValueError('Expected one complete GRIB2 message')

	return dict(sections)


def identification_center(section):
	if len(section) < 7:
		raise ValueError('Truncated GRIB2 identification section')

	return struct.unpack_from('>H', section, 5)[0]


def product_parameter(section):
	if len(section) < 11:
		raise ValueError('Truncated GRIB2 product definition')

	return struct.unpack_from('>H', section, 7)[0], section[9], section[10]


def parse_cloud_mask_grib_header(data):
	"""Verify the embedded GRIB2 message and WMO cloud-mask parameter."""
	start, message_length, discipline = grib_envelope(data)
	sections = grib_sections(data, start)
	center = identification_center(sections[1])
	template, category, number = product_parameter(sections[4])
	if (discipline, category, number, template, center) != (3, 0, 7, 30, 254):
		raise ValueError('Not the EUMETSAT cloud-mask parameter/template/center')

	return {
		'edition': 2,
		'discipline': discipline,
		'parameterCategory': category,
		'parameterNumber': number,
		'productDefinitionTemplate': template,
		'center': center,
		'wrapper_bytes': start,
		'message_bytes': message_length
	}


def pixel_window(dataset, station, radius_km, geod, transformer, rasterio):
	"""Find a conservative raster window enclosing the station-centered disc."""
	latitudes = [station['lat']]
	longitudes = [station['lon']]
	for bearing in range(0, 360, 10):
		longitude, latitude, _ = geod.fwd(station['lon'], station['lat'], bearing, radius_km * 1000)
		longitudes.append(longitude)
		latitudes.append(latitude)

	x, y = transformer.transform(longitudes, latitudes)
	if not all(math.isfinite(value) for value in (*x, *y)):
		raise ValueError('Station radius falls outside geostationary projection')

	rows, cols = rasterio.transform.rowcol(dataset.transform, x, y)
	left = max(0, min(cols) - 2)
	top = max(0, min(rows) - 2)
	right = min(dataset.width, max(cols) + 3)
	bottom = min(dataset.height, max(rows) + 3)
	return rasterio.windows.Window(left, top, right - left, bottom - top)


def observation_timestamp(tags):
	"""Require a zero-lead-time GRIB observation."""
	try:
		observed_at = int(tags['GRIB_VALID_TIME'])
		reference_at = int(tags['GRIB_REF_TIME'])
		forecast_seconds = int(tags['GRIB_FORECAST_SECONDS'])
	except (KeyError, ValueError, TypeError) as error:
		raise ValueError('Missing or invalid GRIB observation timestamp') from error

	if observed_at != reference_at or forecast_seconds != 0:
		raise ValueError('Expected an observation, not a forecast GRIB')

	return observed_at


def validate_raster(dataset):
	if dataset.driver != 'GRIB' or dataset.count != 1 or dataset.crs is None:
		raise ValueError('Expected one georeferenced cloud-mask GRIB raster')

	tags = dataset.tags(1)
	if tags.get('GRIB_ELEMENT') != 'CLOUDM' or tags.get('GRIB_DISCIPLINE', '').split('(')[0] != '3':
		raise ValueError('Raster metadata disagrees with WMO cloud-mask parameter')

	return observation_timestamp(tags)


def raster_footprint(dataset, station, radius_km, np, rasterio, CRS, Geod, Transformer):
	"""Project only a small raster window's pixel centers to WGS84."""
	from .satellite import cloud_fraction_in_footprint

	geod = Geod(ellps='WGS84')
	forward = Transformer.from_crs(CRS.from_epsg(4326), dataset.crs, always_xy=True)
	window = pixel_window(dataset, station, radius_km, geod, forward, rasterio)
	values = dataset.read(1, window=window, masked=True).filled(np.nan)
	rows = np.arange(window.row_off, window.row_off + window.height)
	cols = np.arange(window.col_off, window.col_off + window.width)
	cols_grid, rows_grid = np.meshgrid(cols, rows)
	transform = dataset.transform
	x = transform.c + (cols_grid + 0.5) * transform.a + (rows_grid + 0.5) * transform.b
	y = transform.f + (cols_grid + 0.5) * transform.d + (rows_grid + 0.5) * transform.e
	inverse = Transformer.from_crs(dataset.crs, CRS.from_epsg(4326), always_xy=True)
	longitudes, latitudes = inverse.transform(x, y)
	return cloud_fraction_in_footprint(latitudes.ravel(), longitudes.ravel(), values.ravel(), station, radius_km)


def extract_raster_cloud_mask(path, station, radius_km):
	"""Return (valid time, existing-library footprint, verified GRIB metadata)."""
	try:
		import numpy as np
		import rasterio
		from pyproj import CRS, Geod, Transformer
	except ImportError as error:
		raise RuntimeError('Rasterio decoder requires numpy, rasterio, and pyproj') from error

	if not math.isfinite(radius_km) or not 1 <= radius_km <= 100:
		raise ValueError('Sampling radius must be between 1 and 100 km')

	metadata = parse_cloud_mask_grib_header(Path(path).read_bytes())
	with rasterio.open(path) as dataset:
		observed_at = validate_raster(dataset)
		footprint = raster_footprint(dataset, station, radius_km, np, rasterio, CRS, Geod, Transformer)
		stamp = datetime.fromtimestamp(observed_at, timezone.utc)
		metadata.update({
			'grid_type': dataset.crs.to_proj4(),
			'validity_date': int(stamp.strftime('%Y%m%d')),
			'validity_time': int(stamp.strftime('%H%M')),
			'decoder': 'rasterio/GDAL'
		})

	return observed_at, footprint, metadata
