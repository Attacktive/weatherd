"""Run with `uv run --with numpy --with pillow --with scipy python -m unittest discover -s scripts/tests -p test_treescape_artwork.py`."""

import importlib.util
from pathlib import Path
import unittest

import numpy as np

SPEC = importlib.util.spec_from_file_location('treescape', Path(__file__).resolve().parents[1] / 'prepare-treescape.py')
TREESCAPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TREESCAPE)


class TreescapeArtworkTest(unittest.TestCase):
	def test_meadow_horizon_removes_pale_source_sky_instead_of_making_it_opaque(self):
		rgb, alpha, _ = TREESCAPE.meadow_layer()
		horizon = rgb[:40]
		visible = alpha[:40] > 0.05
		contaminated = visible & (horizon.min(axis=2) > 170)
		self.assertEqual(0, np.count_nonzero(contaminated), 'Pale source sky remains in the grass horizon')
		self.assertTrue(np.all(alpha[-1] == 1), 'Matting must not uncover the bottom edge')

	def test_non_green_seasons_do_not_retain_bright_photographed_grass_at_roots(self):
		rgb, alpha, foliage = TREESCAPE.tree_layer()
		for season in ('autumn', 'winter'):
			graded = TREESCAPE.seasonal(rgb, foliage, season, True)[178:]
			visible = alpha[178:] > 0.5
			red, green, blue = np.moveaxis(graded, -1, 0)
			grass = visible & (green > 95) & (green > red + 12) & (green > blue + 12)
			with self.subTest(season=season):
				self.assertEqual(0, np.count_nonzero(grass), 'Bright photographed grass remains around the roots')
				for left, right in ((260, 310), (570, 610)):
					self.assertGreater(np.count_nonzero(visible[:, left:right] & (green[:, left:right] < 95)), 100, 'The trunk must remain visible')


if __name__ == '__main__':
	unittest.main()
