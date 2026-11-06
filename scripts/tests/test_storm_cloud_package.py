"""Release packaging must contain authorized, matching HD cloud sprites."""

import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile

MODULE_PATH = Path(__file__).resolve().parents[1] / "verify-storm-cloud-package.py"
spec = importlib.util.spec_from_file_location("release_clouds", MODULE_PATH)
clouds = importlib.util.module_from_spec(spec)
spec.loader.exec_module(clouds)


class StormCloudPackageTest(unittest.TestCase):
	def png(self, width: int, height: int, color_type: int = 6) -> bytes:
		return clouds.PNG_SIGNATURE + b"\x00\x00\x00\x0dIHDR" + struct.pack(">IIBBBBB", width, height, 8, color_type, 0, 0, 0) + b"\x00" * 4

	def archive(self, path: Path, prefix: str, omit: str = "", color_type: int = 6, corrupt: bool = False) -> None:
		with zipfile.ZipFile(path, "w") as output:
			if omit != "permission":
				output.writestr(f"{prefix}storm-clouds/ARTWORK_PERMISSION.md", clouds.SOURCE_ISSUE + "\nPermission expressly granted by the artwork rights holder to redistribute with Weatherd.\n")

			for layer, (width, height) in clouds.MINIMUM_DIMENSIONS.items():
				if omit != layer:
					output.writestr(f"{prefix}storm-clouds/{layer}.png", self.png(width, height, color_type if layer == "hero" else 6) + (b"changed" if corrupt and layer == "hero" else b""))

	def test_matching_release_packages_pass(self):
		with tempfile.TemporaryDirectory() as directory:
			apk, aab = Path(directory) / "app.apk", Path(directory) / "app.aab"
			self.archive(apk, "assets/")
			self.archive(aab, "base/assets/")
			self.assertEqual(set(clouds.MINIMUM_DIMENSIONS), set(clouds.verify(apk, aab)))

	def test_missing_cloud_fails(self):
		with tempfile.TemporaryDirectory() as directory:
			apk, aab = Path(directory) / "app.apk", Path(directory) / "app.aab"
			self.archive(apk, "assets/", omit="hero")
			self.archive(aab, "base/assets/")
			with self.assertRaisesRegex(ValueError, "Missing release storm cloud"):
				clouds.verify(apk, aab)

	def test_missing_permission_record_fails(self):
		with tempfile.TemporaryDirectory() as directory:
			apk, aab = Path(directory) / "app.apk", Path(directory) / "app.aab"
			self.archive(apk, "assets/", omit="permission")
			self.archive(aab, "base/assets/")
			with self.assertRaisesRegex(ValueError, "permission record"):
				clouds.verify(apk, aab)

	def test_rgb_instead_of_rgba_fails(self):
		with tempfile.TemporaryDirectory() as directory:
			apk, aab = Path(directory) / "app.apk", Path(directory) / "app.aab"
			self.archive(apk, "assets/", color_type=2)
			self.archive(aab, "base/assets/")
			with self.assertRaisesRegex(ValueError, "RGBA"):
				clouds.verify(apk, aab)

	def test_mismatched_cloud_bytes_fail(self):
		with tempfile.TemporaryDirectory() as directory:
			apk, aab = Path(directory) / "app.apk", Path(directory) / "app.aab"
			self.archive(apk, "assets/", corrupt=True)
			self.archive(aab, "base/assets/")
			with self.assertRaisesRegex(ValueError, "different cloud PNG"):
				clouds.verify(apk, aab)


if __name__ == "__main__":
	unittest.main()
