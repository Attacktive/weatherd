"""Asset preparation regressions that do not require network access or Android."""
import importlib.util
from pathlib import Path
import tempfile
import unittest

import numpy as np
from PIL import Image

MODULE_PATH = Path(__file__).resolve().parents[1] / "prepare-storm-cloud-prototype.py"
spec = importlib.util.spec_from_file_location("storm_assets", MODULE_PATH)
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class StormCloudAssetTest(unittest.TestCase):
	def fixture(self):
		pixels = np.zeros((180, 240, 4), dtype=np.uint8)
		pixels[15:65, 20:220] = [110, 130, 150, 220]
		pixels[110:155, 25:215] = [120, 140, 160, 230]
		pixels[12:15, 20:220] = [110, 130, 150, 24]
		pixels[80:85, 25:215] = [0, 255, 255, 2]
		return pixels

	def test_separates_clouds_without_row_cutting_or_rgb_repainting(self):
		upper, lower = assets.isolate(self.fixture(), 2)
		self.assertTrue(np.any(np.all(upper == [110, 130, 150, 220], axis=2)))
		self.assertTrue(np.any(np.all(lower == [120, 140, 160, 230], axis=2)))
		self.assertFalse(np.any(np.all(upper == [120, 140, 160, 230], axis=2)))
		self.assertFalse(np.any(np.all(lower == [110, 130, 150, 220], axis=2)))

	def test_padding_is_transparent_and_noise_is_removed(self):
		for image in assets.isolate(self.fixture(), 2):
			self.assertFalse(image[:4].any())
			self.assertFalse(image[-4:].any())
			self.assertFalse(image[:, :4].any())
			self.assertFalse(image[:, -4:].any())
			self.assertFalse(np.any(np.all(image[:, :, :3] == [0, 255, 255], axis=2)))
			self.assertGreater(np.count_nonzero(image[:, :, 3]), 1000)

	def test_retains_soft_alpha_and_is_deterministic(self):
		first = assets.isolate(self.fixture(), 2)
		second = assets.isolate(self.fixture(), 2)
		self.assertTrue(np.any((first[0][:, :, 3] > 0) & (first[0][:, :, 3] < 24)))
		self.assertTrue(all(np.array_equal(a, b) for a, b in zip(first, second)))

	def test_rejects_missing_clouds_and_non_rgba_sources(self):
		with self.assertRaises(ValueError):
			assets.isolate(np.zeros((180, 240, 4), dtype=np.uint8), 2)

		with tempfile.TemporaryDirectory() as directory:
			path = Path(directory) / "rgb.png"
			Image.new("RGB", (32, 32)).save(path)
			with self.assertRaises(ValueError):
				assets.read_rgba(path)


	def test_atmospheric_veil_is_soft_translucent_and_keeps_source_unchanged(self):
		original = self.fixture()
		before = original.copy()
		veil = assets.atmospheric_veil(original)
		self.assertTrue(np.array_equal(original, before))
		self.assertEqual((552, 912, 4), veil.shape)
		self.assertLess(veil[:, :, 3].max(), original[:, :, 3].max())
		self.assertGreater(veil[130:290, 120:790, 3].mean(), 0)
		self.assertFalse(veil[:24].any())
		self.assertFalse(veil[:, :24].any())
		self.assertTrue(np.array_equal(veil, assets.atmospheric_veil(original)))

	def test_lower_cloud_feather_preserves_upper_detail_and_dissolves_the_bottom(self):
		original = np.empty((101, 50, 4), dtype=np.uint8)
		original[:] = [108, 123, 142, 220]
		unchanged = original.copy()
		output = assets.soften_lower_cloud_edge(original, 0.77)

		self.assertTrue(np.array_equal(original, unchanged))
		self.assertTrue(np.array_equal(output[:76], original[:76]))
		self.assertTrue(np.array_equal(output[88, :, :3], original[88, :, :3]))
		self.assertTrue(0 < int(output[88, 0, 3]) < 220)
		self.assertTrue(np.all(np.diff(output[:, 0, 3].astype(np.int16)) <= 0))
		self.assertFalse(output[-1].any())
		self.assertTrue(np.array_equal(output, assets.soften_lower_cloud_edge(original, 0.77)))

	def test_lower_feather_does_not_increase_transparency_or_repaint_rgb(self):
		pixels = self.fixture()
		output = assets.soften_lower_cloud_edge(pixels, 0.72)
		self.assertTrue(np.all(output[:, :, 3] <= pixels[:, :, 3]))
		self.assertTrue(np.array_equal(output[output[:, :, 3] > 0, :3], pixels[output[:, :, 3] > 0, :3]))

	def test_atmospheric_veil_bridges_separated_alpha_bodies(self):
		original = self.fixture()
		veil = assets.atmospheric_veil(original)
		self.assertEqual(0, int(original[90, 120, 3]))
		self.assertGreater(int(veil[276, 456, 3]), 10)

	def test_download_url_rejects_unsafe_schemes_and_hosts(self):
		for url in (
			"file:///etc/passwd",
			"http://github.com/user-attachments/assets/example",
			"https://evil.example/user-attachments/assets/example",
			"https://github.com.evil.example/user-attachments/assets/example",
			"https://user:password@github.com/user-attachments/assets/example",
			"https://github.com:444/user-attachments/assets/example",
			"https://github.com/Attacktive/weatherd",
		):
			with self.subTest(url=url), self.assertRaises(ValueError):
				assets.validate_asset_url(url)

	def test_redirects_reject_insecure_destinations(self):
		from urllib.request import Request

		handler = assets.SecureAssetRedirectHandler()
		request = Request(assets.SOURCES["sheet"])
		with self.assertRaises(ValueError):
			handler.redirect_request(request, None, 302, "Found", {}, "file:///etc/passwd")
		with self.assertRaises(ValueError):
			handler.redirect_request(request, None, 302, "Found", {}, "http://github.com/user-attachments/assets/example")
		redirect = handler.redirect_request(request, None, 302, "Found", {}, "https://github-production-user-asset-6210df.s3.amazonaws.com/sample.png?signature=123")
		self.assertEqual("https", redirect.type)


	def test_repeat_tile_is_seamless_without_reflected_symmetry(self):
		pixels = np.zeros((64, 160, 4), dtype=np.uint8)
		pixels[8:54, 20:76] = [100, 115, 130, 220]
		pixels[15:48, 76:143] = [80, 94, 108, 185]
		original = pixels.copy()

		result = assets.seamless_repeat(pixels)
		self.assertTrue(np.array_equal(pixels, original))
		self.assertEqual(pixels.shape, result.shape)
		self.assertTrue(np.array_equal(result[:, 0], pixels[:, 80]))
		self.assertTrue(np.array_equal(result[:, -1], pixels[:, 79]))
		self.assertGreater(int(result[25, 80, 3]), 0)
		self.assertFalse(np.array_equal(result, result[:, ::-1]))
		self.assertTrue(np.array_equal(result, assets.seamless_repeat(pixels)))

	def test_repeat_tile_preserves_opaque_cloud_edges_without_a_hard_join(self):
		pixels = np.zeros((24, 128, 4), dtype=np.uint8)
		pixels[4:20, 20:107] = [90, 110, 130, 200]
		result = assets.seamless_repeat(pixels)
		self.assertEqual(200, int(result[10, 0, 3]))
		self.assertEqual(200, int(result[10, -1, 3]))
		self.assertEqual(200, int(result[10, 64, 3]))


	def test_seam_coverage_uses_cloud_detail_instead_of_cutting_a_hole(self):
		pixels = np.zeros((96, 160, 4), dtype=np.uint8)
		pixels[14:86, 30:130] = [125, 140, 160, 230]
		pixels[24:75, 51:110] = [140, 155, 175, 255]
		original = pixels.copy()
		rolled = np.roll(pixels, pixels.shape[1] // 2, axis=1)
		repeated = assets.seamless_repeat(pixels)

		self.assertTrue(np.array_equal(pixels, original))
		self.assertGreater(float(repeated[30:72, 76:84, 3].mean()), 180)
		self.assertGreater(float(repeated[30:72, 76:84, 3].mean()), float(rolled[30:72, 76:84, 3].mean()) + 120)
		self.assertTrue(np.array_equal(repeated[:, 0], pixels[:, 80]))
		self.assertTrue(np.array_equal(repeated[:, -1], pixels[:, 79]))
		self.assertTrue(np.array_equal(repeated, assets.seamless_repeat(pixels)))

	def test_seam_fill_keeps_existing_opaque_cloud_detail(self):
		pixels = np.zeros((24, 128, 4), dtype=np.uint8)
		pixels[4:20, 20:107] = [90, 110, 130, 200]
		repeated = assets.seamless_repeat(pixels)

		self.assertTrue(np.array_equal(repeated[10, 64], pixels[10, 64]))
		self.assertEqual(0, int(repeated[0, 64, 3]))


	def test_repeat_tile_preserves_cloud_center_without_symmetric_gap(self):
		pixels = np.zeros((96, 160, 4), dtype=np.uint8)
		pixels[12:84, 32:126] = [125, 141, 158, 230]
		pixels[24:72, 53:110] = [150, 164, 180, 255]
		original = pixels.copy()
		repeated = assets.seamless_repeat(pixels)
		self.assertTrue(np.array_equal(pixels, original))
		self.assertTrue(np.array_equal(repeated[:, 56:104], original[:, 56:104]))
		self.assertGreater(float(repeated[30:72, 76:84, 3].mean()), 220)
		self.assertTrue(np.array_equal(repeated[:, 0], original[:, 80]))
		self.assertTrue(np.array_equal(repeated[:, -1], original[:, 79]))
		self.assertFalse(np.array_equal(repeated, repeated[:, ::-1]))


if __name__ == "__main__":
	unittest.main()
