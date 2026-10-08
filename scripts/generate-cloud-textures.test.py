# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.3", "pillow==12.3.0", "scipy==1.18.1"]
# ///
"""Run artwork regressions with `uv run scripts/generate-cloud-textures.test.py`."""
import importlib.util
import tempfile
import unittest
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

spec = importlib.util.spec_from_file_location('cloud_generator', Path(__file__).with_name('generate-cloud-textures.py'))
generator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(generator)


class HeroSilhouetteTest(unittest.TestCase):
	def test_near_variants_preserve_authored_end_lobes(self):
		"""Narrowing a bank must not crop away its natural crown or trailing wisp."""
		source = Image.new('RGBA', (1000, 300), (255, 255, 255, 0))
		draw = ImageDraw.Draw(source)
		draw.ellipse((100, 100, 900, 210), fill=(255, 255, 255, 255))
		draw.ellipse((10, 200, 130, 295), fill=(255, 255, 255, 255))
		draw.ellipse((870, 5, 990, 95), fill=(255, 255, 255, 255))
		original_output = generator.OUTPUT
		with tempfile.TemporaryDirectory() as directory:
			generator.OUTPUT = Path(directory)
			try:
				for filename in {item[1] for item in generator.HERO_SOURCES}:
					source.save(generator.OUTPUT / filename, 'WEBP', lossless=True)

				for name, filename, shape in generator.HERO_SOURCES:
					with self.subTest(variant=name):
						image = generator.hero_texture(filename, shape)
						alpha = np.asarray(image)[:, :, 3]
						self.assertGreater(int(alpha[:48].max()), 128, 'The authored outer crown was cropped away')
						self.assertGreater(int(alpha[192:].max()), 128, 'The authored trailing lobe was cropped away')

			finally:
				generator.OUTPUT = original_output


if __name__ == '__main__':
	unittest.main()
