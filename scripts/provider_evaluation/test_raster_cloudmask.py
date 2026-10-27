"""Dependency-free checks of the raster decoder's fail-closed GRIB metadata guard."""

import struct
import unittest

from provider_evaluation.raster_cloudmask import parse_cloud_mask_grib_header


def make_grib(category=0, number=7, wrapper=b''):
	sections = []
	for section_number in (1, 3, 4, 5, 6, 7):
		payload = (b'\x00\xfe' + bytes(4) if section_number == 1 else (b'\x00\x00\x00\x1e' + bytes([category, number]) if section_number == 4 else bytes(6)))
		sections.append(struct.pack('>IB', len(payload) + 5, section_number) + payload)

	body = b''.join(sections)
	message_length = 16 + len(body) + 4
	indicator = b'GRIB' + b'\x00\x00' + bytes([3, 2]) + struct.pack('>Q', message_length)
	return wrapper + indicator + body + b'7777'


class RasterCloudMaskHeaderTest(unittest.TestCase):
	def test_accepts_cloud_mask_with_eumetsat_wrapper(self):
		metadata = parse_cloud_mask_grib_header(make_grib(wrapper=bytes(272)))
		self.assertEqual(metadata['wrapper_bytes'], 272)
		self.assertEqual(metadata['edition'], 2)
		self.assertEqual(metadata['discipline'], 3)
		self.assertEqual(metadata['parameterCategory'], 0)
		self.assertEqual(metadata['parameterNumber'], 7)

	def test_accepts_unwrapped_grib2_message(self):
		self.assertEqual(parse_cloud_mask_grib_header(make_grib())['wrapper_bytes'], 0)

	def test_rejects_other_parameters(self):
		with self.assertRaisesRegex(ValueError, 'cloud-mask parameter'):
			parse_cloud_mask_grib_header(make_grib(category=0, number=8))

	def test_rejects_corrupted_embedded_message(self):
		with self.assertRaisesRegex(ValueError, 'bounds'):
			parse_cloud_mask_grib_header(make_grib()[:-1])

	def test_rejects_multiple_grib_messages(self):
		with self.assertRaises(ValueError):
			parse_cloud_mask_grib_header(make_grib() + make_grib())


if __name__ == '__main__':
	unittest.main()
