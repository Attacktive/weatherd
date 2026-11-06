"""Check offline debug asset readiness without downloading the artwork."""

import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "ensure-storm-cloud-debug-assets.py"
spec = importlib.util.spec_from_file_location("debug_cloud_assets", MODULE)
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)


class DebugCloudAssetsTest(unittest.TestCase):
	def create_assets(self, output: Path) -> None:
		output.mkdir(parents=True, exist_ok=True)
		layers = {}
		for name in assets.LAYERS:
			data = f"{name}-image".encode()
			(output / f"{name}.png").write_bytes(data)
			layers[name] = {"sha256": hashlib.sha256(data).hexdigest()}

		manifest = {"evaluation_only": True, "processing_version": assets.PROCESSING_VERSION, "sources": {name: {"sha256": digest} for name, digest in assets.SOURCES.items()}, "layers": layers}
		(output / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

	def test_clean_checkout_requires_assets(self):
		with tempfile.TemporaryDirectory() as directory:
			self.assertFalse(assets.prepared(Path(directory)))

	def test_hash_pinned_sources_and_four_sprites(self):
		with tempfile.TemporaryDirectory() as directory:
			output = Path(directory)
			self.create_assets(output)
			self.assertTrue(assets.prepared(output))
			(output / "hero.png").write_bytes(b"wrong")
			self.assertFalse(assets.prepared(output))

	def test_incomplete_and_wrong_source_manifests(self):
		with tempfile.TemporaryDirectory() as directory:
			output = Path(directory)
			self.create_assets(output)
			(output / "upper.png").unlink()
			self.assertFalse(assets.prepared(output))
			self.create_assets(output)
			manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
			manifest["sources"]["hero"]["sha256"] = "different"
			(output / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
			self.assertFalse(assets.prepared(output))


	def test_old_mirrored_assets_require_regeneration(self):
		with tempfile.TemporaryDirectory() as directory:
			output = Path(directory)
			self.create_assets(output)
			manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
			manifest["processing_version"] = assets.PROCESSING_VERSION - 1
			(output / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
			self.assertFalse(assets.prepared(output))


	def test_python_39_uses_compatible_dependencies(self):
		self.assertEqual(
			("pillow==11.3.0", "numpy==2.0.2", "scipy==1.13.1"),
			assets.packages_for_version((3, 9)),
		)
		self.assertEqual(assets.LEGACY_PACKAGES, assets.packages_for_version((3, 11)))

	def test_modern_python_uses_current_dependencies(self):
		self.assertEqual(
			("pillow==13.0.0", "numpy==2.5.4", "scipy==1.18.1"),
			assets.packages_for_version((3, 12)),
		)
		self.assertEqual(assets.MODERN_PACKAGES, assets.packages_for_version((3, 13)))

	def test_rejects_unsupported_python(self):
		with self.assertRaisesRegex(RuntimeError, "Python 3.9 or newer"):
			assets.packages_for_version((3, 8))

	def test_detects_existing_virtualenv_python_version(self):
		with tempfile.TemporaryDirectory() as directory:
			python = Path(directory) / "old-venv" / "bin" / "python"
			with patch.object(assets.subprocess, "check_output", return_value="3.9") as command:
				self.assertEqual((3, 9), assets.venv_python_version(python))
				command.assert_called_once_with(
					[str(python), "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"],
					text=True,
				)



if __name__ == "__main__":
	unittest.main()
