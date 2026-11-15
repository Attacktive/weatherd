# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.4", "pillow==13.0.0", "scipy==1.18.1"]
# ///
"""Prepare contributor-authorized #394 artwork with `uv run scripts/prepare-treescape.py`.

The original JPEGs remain untouched in scripts/assets/treescape.
Permission: https://github.com/Attacktive/weatherd/issues/394#issuecomment-6891507685
"""

import argparse
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import distance_transform_edt, gaussian_filter

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / 'scripts/assets/treescape'
OUTPUT = ROOT / 'app/src/main/res/drawable-nodpi'
ORIGINALS = {
	'scene': ('7bb0835b-271c-4281-8028-38138850b492', 'e7a703906662f86b07963684ecf354703ecabdb42549b967b5dc67dd3372c3ff'),
	'meadow': ('8932dcd8-5256-4286-ae0f-0d7739c4e5f6', 'c472cb63622306a58067ef3528cc0904191c00dd1ac3fa0da357ec2f1a780308'),
}
SEASONS = ('spring', 'summer', 'autumn', 'winter')


def original(name):
	asset, expected = ORIGINALS[name]
	path = SOURCES / f'{name}.jpg'
	if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
		raise ValueError(f'Original changed: {path}; source https://github.com/user-attachments/assets/{asset}')

	with Image.open(path) as image:
		if image.size != (688, 1512):
			raise ValueError(f'Unexpected original dimensions: {image.size}')

		return np.array(image.convert('RGB'), dtype=np.float32)


def tree_layer():
	# Crop below all captured sky effects and above the phone's navigation indicator.
	rgb = original('scene')[820:1060].copy()
	red, green, blue = np.moveaxis(rgb, -1, 0)
	sky = np.clip((blue - red - 6) / 16, 0, 1) * np.clip((blue - 95) / 45, 0, 1)
	alpha = 1 - sky
	# Below the photographed horizon, retain only the two trunks, not baked ground shadows.
	roots = Image.new('L', (688, 240))
	draw = ImageDraw.Draw(roots)
	draw.rectangle((0, 0, 687, 177), fill=255)
	draw.polygon(((272, 173), (294, 173), (303, 195), (298, 214), (285, 232), (266, 231), (277, 210), (281, 196)), fill=255)
	draw.polygon(((589, 173), (607, 173), (603, 193), (589, 217), (573, 217), (583, 193)), fill=255)
	root_mask = gaussian_filter(np.array(roots, dtype=np.float32) / 255, sigma=0.8)
	brightness = np.clip((95 - green) / 35, 0, 1)
	grass_color = np.clip((24 - (green - blue)) / 18, 0, 1)
	rows = np.arange(alpha.shape[0])[:, None]
	contact = np.ones_like(alpha)
	contact[:, :440] = np.clip((232 - rows) / 8, 0, 1)
	contact[:, 440:] = np.clip((218 - rows) / 8, 0, 1)
	alpha[178:] *= (root_mask * brightness * grass_color * contact)[178:]
	# Replace only partially keyed edge RGB with nearby solid material to remove blue JPEG fringes.
	solid = alpha > 0.98
	_, nearest = distance_transform_edt(~solid, return_indices=True)
	edge = (alpha > 0) & ~solid
	rgb[edge] = rgb[nearest[0][edge], nearest[1][edge]]
	foliage = np.clip((red - green - 2) / 12, 0, 1) * np.clip((red - blue - 4) / 15, 0, 1)
	foliage[178:] = 0

	return rgb, alpha, foliage


def meadow_layer():
	# Exclude the pale lower export edge and unmix the neutral photographed sky from the grass tips.
	rgb = original('meadow')[744:1350].copy()
	chroma = rgb.max(axis=2) - rgb.min(axis=2)
	background = np.median(rgb[:12], axis=0)[None, :, :]
	solid = (chroma > 70) & (rgb[:, :, 2] < 145)
	_, nearest = distance_transform_edt(~solid, return_indices=True)
	foreground = rgb[nearest[0], nearest[1]]
	direction = foreground - background
	alpha = np.clip(np.sum((rgb - background) * direction, axis=2) / np.sum(direction * direction, axis=2), 0, 1)
	alpha[chroma <= 8] = 0
	alpha[64:] = 1
	edge = (alpha > 0) & (alpha < 1)
	unmixed = (rgb - background * (1 - alpha[:, :, None])) / np.maximum(alpha[:, :, None], 0.001)
	rgb[edge] = np.clip(unmixed[edge], 0, 255)
	red, green, blue = np.moveaxis(rgb, -1, 0)
	grass = np.clip((green - red - 3) / 16, 0, 1) * np.clip((green - blue - 5) / 22, 0, 1)

	return rgb, alpha, grass


def seasonal(rgb, material, season, trees):
	light = rgb @ np.array((0.2126, 0.7152, 0.0722))
	if trees:
		if season == 'spring':
			target = np.stack((light * 0.72, light * 1.42 + 3, light * 0.53), axis=2)
		elif season == 'summer':
			target = np.stack((light * 0.52, light * 1.22, light * 0.40), axis=2)
		elif season == 'winter':
			frost = np.clip((light - 18) / 60, 0, 1)
			base = light * 0.7 + frost * 105
			target = np.stack((base * 0.95, base, base * 1.05), axis=2)
		else:
			return rgb.copy()
	elif season == 'summer':
		target = rgb * np.array((0.88, 0.96, 0.82))
	elif season == 'autumn':
		target = np.stack((light * 1.12, light * 0.89, light * 0.52), axis=2)
	elif season == 'winter':
		base = light * 0.94 + 83
		target = np.stack((base * 0.96, base, base * 1.04), axis=2)
	else:
		return rgb.copy()

	return rgb * (1 - material[:, :, None]) + target * material[:, :, None]


def prepare(trees_only=False):
	layers = {'trees': tree_layer()}
	if not trees_only:
		layers['meadow'] = meadow_layer()
	for name, (rgb, alpha, material) in layers.items():
		for season in SEASONS:
			graded = seasonal(rgb, material, season, name == 'trees')
			rgba = np.concatenate((np.clip(graded, 0, 255), alpha[:, :, None] * 255), axis=2).round().astype(np.uint8)
			rgba[rgba[:, :, 3] == 0] = 0
			path = OUTPUT / f'treescape_{name}_{season}.webp'
			Image.fromarray(rgba).save(path, lossless=True, method=6, exact=True)
			print(path.relative_to(ROOT), hashlib.sha256(path.read_bytes()).hexdigest())


def contact_sheet(path):
	width, height = 344, 640
	sheet = Image.new('RGB', (width * 4, height * 2))
	for row, sky in enumerate(((98, 165, 222), (19, 26, 43))):
		for column, season in enumerate(SEASONS):
			frame = Image.new('RGBA', (width, height), (*sky, 255))
			with Image.open(OUTPUT / f'treescape_meadow_{season}.webp') as source:
				meadow = source.resize((width, round(source.height * width / source.width)), Image.Resampling.LANCZOS)
				frame.alpha_composite(meadow, (0, height - meadow.height))

			with Image.open(OUTPUT / f'treescape_trees_{season}.webp') as source:
				trees = source.resize((width, round(source.height * width / source.width)), Image.Resampling.LANCZOS)
				frame.alpha_composite(trees, (0, height - meadow.height - 77))

			ImageDraw.Draw(frame).text((12, 12), season, fill='white')
			sheet.paste(frame.convert('RGB'), (column * width, row * height))

	sheet.save(path)
	print(path)


if __name__ == '__main__':
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument('--contact-sheet', type=Path)
	parser.add_argument('--trees-only', action='store_true')
	args = parser.parse_args()
	prepare(trees_only=args.trees_only)
	if args.contact_sheet:
		contact_sheet(args.contact_sheet)
