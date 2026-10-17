# /// script
# requires-python = ">=3.12"
# dependencies = ["numpy==2.5.4", "pillow==13.0.0"]
# ///
"""Generate original optical glare with `uv run scripts/generate-sun-glare.py`."""

# Sixfold symmetry comes from a hexagonal pupil, not independently drawn spokes.
# Squared Fourier amplitude is convolved with an incoherent finite solar disc.
# Wavelength averaging softens fringes; exposure and edge taper are artistic choices.
# This is an idealized camera-style approximation, not a calibrated imaging system.
# No photographs or third-party artwork are sampled.

from pathlib import Path

import numpy as np
from PIL import Image

SAMPLE_SIZE = 2048
CROP_SIZE = 1024
TEXTURE_SIZE = 512
PUPIL_APOTHEM = 96.0
SOLAR_RADIUS = 24.0
EXPOSURE = 900.0
OUTPUT = Path(__file__).resolve().parents[1] / 'app/src/main/res/drawable-nodpi/sun_aperture_glare.png'


def glare_texture():
	"""Integrate three diffraction intensities before tone mapping to a neutral mask."""
	axis = np.arange(SAMPLE_SIZE, dtype=np.float64) - SAMPLE_SIZE // 2
	xx, yy = np.meshgrid(axis, axis)
	radius = np.hypot(xx, yy)
	source = (radius <= SOLAR_RADIUS).astype(np.float64)
	source /= source.sum()
	source_transform = np.fft.fft2(np.fft.ifftshift(source))
	intensity = np.zeros_like(source)
	for wavelength in (460.0, 550.0, 650.0):
		apothem = PUPIL_APOTHEM * 550.0 / wavelength
		pupil = np.ones_like(source)
		for normal in np.deg2rad((15.0, 75.0, 135.0)):
			distance = np.abs(xx * np.cos(normal) + yy * np.sin(normal))
			pupil *= np.clip(apothem + 0.5 - distance, 0.0, 1.0)

		amplitude = np.fft.fft2(np.fft.ifftshift(pupil))
		point_spread = np.abs(amplitude) ** 2
		point_spread /= point_spread.sum()
		intensity += np.fft.fftshift(np.fft.ifft2(np.fft.fft2(point_spread) * source_transform).real) / 3.0

	intensity = np.maximum(intensity, 0.0)
	intensity /= intensity.max()
	alpha = 1.0 - np.exp(-EXPOSURE * intensity)
	# The existing solar disc and compact corona own the center; add only outer glare.
	entrance = np.clip((radius - SOLAR_RADIUS * 1.5) / (SOLAR_RADIUS * 1.5), 0.0, 1.0)
	entrance = entrance * entrance * (3.0 - 2.0 * entrance)
	remaining = np.clip((CROP_SIZE * 0.5 - radius) / (CROP_SIZE * 0.2), 0.0, 1.0)
	edge = remaining * remaining * (3.0 - 2.0 * remaining)
	alpha *= entrance * edge
	start = (SAMPLE_SIZE - CROP_SIZE) // 2
	alpha = alpha[start:start + CROP_SIZE, start:start + CROP_SIZE]
	mask = Image.fromarray(np.rint(alpha * 255.0).astype(np.uint8)).resize((TEXTURE_SIZE, TEXTURE_SIZE), Image.Resampling.LANCZOS)
	image = Image.new('RGBA', mask.size, (255, 255, 255, 0))
	image.putalpha(mask)
	return image


def main():
	OUTPUT.parent.mkdir(parents=True, exist_ok=True)
	image = glare_texture()
	image.save(OUTPUT, optimize=True)
	print(f'{OUTPUT.name}: {image.width}x{image.height}, {OUTPUT.stat().st_size} bytes')


if __name__ == '__main__':
	main()
