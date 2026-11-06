#!/usr/bin/env python3
"""Prepare reporter-supplied #405 artwork for local debug evaluation, not distribution.

Requires Pillow, NumPy, and SciPy. Fetching is explicit, hash-pinned, and never runs
at application startup. Originals are retained outside the Android source sets.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

import numpy as np
from PIL import Image
from scipy.ndimage import distance_transform_edt, gaussian_filter, label

SOURCES = {
	"sheet": "https://github.com/user-attachments/assets/2670610f-5af1-447b-a184-640462b266b0",
	"hero": "https://github.com/user-attachments/assets/6c61e13c-f11a-4ae7-a03a-386636b1d612",
}


EXPECTED_SHA256 = {
	"sheet": "9d359d853dfffd5e5d361091444a98666ae740feb692f920b9c0c38d94405e00",
	"hero": "517e689b9bcd882deeb77c42fa152c6f43f59a7e4650235fcdc51cd58581eff3",
}


MAX_ASSET_BYTES = 12 * 1024 * 1024


def trusted_asset_host(hostname: str | None, path: str, query: str) -> bool:
	if hostname == "github.com":
		return path.startswith("/user-attachments/assets/") and not query

	return hostname == "github-production-user-asset-6210df.s3.amazonaws.com"


def validate_asset_url(url: str) -> None:
	parts = urlsplit(url)
	if parts.scheme != "https":
		raise ValueError("Cloud artwork URLs must use HTTPS")

	if parts.username or parts.password or parts.port or parts.fragment:
		raise ValueError("Cloud artwork URLs may not contain credentials, ports or fragments")

	if not trusted_asset_host(parts.hostname, parts.path, parts.query):
		raise ValueError("Unexpected cloud artwork download URL")


class SecureAssetRedirectHandler(HTTPRedirectHandler):
	def redirect_request(self, req, fp, code, msg, headers, newurl):
		validate_asset_url(newurl)
		return super().redirect_request(req, fp, code, msg, headers, newurl)


def download_asset(url: str) -> bytes:
	validate_asset_url(url)
	# An opener and a redirect policy restrict both the initial URL and GitHub's S3 redirect.
	opener = build_opener(SecureAssetRedirectHandler())
	with opener.open(Request(url, headers={"User-Agent": "Weatherd-405-Asset-Preparation"}), timeout=30) as response:
		validate_asset_url(response.geturl())
		data = response.read(MAX_ASSET_BYTES + 1)

	if len(data) > MAX_ASSET_BYTES:
		raise ValueError("Unexpectedly large input asset")

	return data


def read_rgba(path: Path) -> np.ndarray:
	with Image.open(path) as image:
		if image.mode != "RGBA":
			raise ValueError(f"Expected original RGBA artwork: {path}")
		return np.array(image)


def isolate(array: np.ndarray, layer_count: int) -> list[np.ndarray]:
	"""Assign soft alpha to its nearest opaque component rather than cutting fixed rows."""
	rgb = array[:, :, :3].astype(np.int16)
	alpha = array[:, :, 3]
	chroma = rgb.max(axis=2) - rgb.min(axis=2)
	valid = (alpha > 8) & (chroma < 100)
	core = valid & (alpha >= 96)
	labels, _ = label(core)
	sizes = np.bincount(labels.ravel())
	sizes[0] = 0
	ids = sorted(np.argsort(sizes)[-layer_count:], key=lambda item: np.where(labels == item)[0].mean())
	if len(ids) != layer_count or any(sizes[item] < 1000 for item in ids):
		raise ValueError("The supplied image does not contain the expected cloud bodies")

	seeds = np.where(np.isin(labels, ids), labels, 0)
	distance, indices = distance_transform_edt(seeds == 0, return_indices=True)
	nearest = seeds[indices[0], indices[1]]
	result = []
	for item in ids:
		keep = valid & (nearest == item) & (distance <= 14)
		output = array.copy()
		output[~keep] = 0
		# Suppress only the lowest-alpha export noise; preserve dense cloud RGB and alpha.
		fade = np.clip((alpha.astype(np.float32) - 8) / 24, 0, 1)
		output[:, :, 3] = np.rint(output[:, :, 3] * fade).astype(np.uint8)
		output[output[:, :, 3] == 0] = 0
		y, x = np.where(output[:, :, 3] > 0)
		if x.size == 0:
			raise ValueError("A cloud layer became empty")

		crop = output[y.min():y.max() + 1, x.min():x.max() + 1]
		# Explicit transparent padding makes filtered sampling safe at every texture edge.
		result.append(np.pad(crop, ((4, 4), (4, 4), (0, 0))))

	return result


def soften_lower_cloud_edge(pixels: np.ndarray, fade_start: float) -> np.ndarray:
	"""Feather the derived sprite's bottom without repainting any surviving cloud pixels."""
	result = pixels.copy()
	y = np.arange(pixels.shape[0], dtype=np.float32) / max(pixels.shape[0] - 1, 1)
	progress = np.clip((y - fade_start) / (1.0 - fade_start), 0.0, 1.0)
	alpha_weight = 1.0 - progress * progress * (3.0 - 2.0 * progress)
	result[:, :, 3] = np.rint(pixels[:, :, 3] * alpha_weight[:, None]).astype(np.uint8)
	result[result[:, :, 3] == 0] = 0
	return result


def atmospheric_veil(hero: np.ndarray) -> np.ndarray:
	"""Produce a translucent, premultiplied blurred underpainting from the same source artwork."""
	with Image.fromarray(hero) as image:
		resized = np.asarray(image.resize((720, 360), Image.Resampling.LANCZOS), dtype=np.float32)

	padded = np.pad(resized, ((96, 96), (96, 96), (0, 0)))
	alpha = padded[:, :, 3] / 255.0
	blurred_alpha = gaussian_filter(alpha, sigma=26.0, mode="constant")
	blurred_color = gaussian_filter(padded[:, :, :3] * alpha[:, :, None], sigma=(26.0, 26.0, 0.0), mode="constant")
	result = np.zeros_like(padded, dtype=np.uint8)
	result[:, :, :3] = np.rint(np.clip(blurred_color / np.maximum(blurred_alpha[:, :, None], 0.001), 0, 255)).astype(np.uint8)
	result[:, :, 3] = np.rint(np.clip(blurred_alpha * 0.77 * 255, 0, 255)).astype(np.uint8)
	result[result[:, :, 3] == 0] = 0
	return result


def prepare(source_directory: Path, output_directory: Path) -> dict:
	for name, expected in EXPECTED_SHA256.items():
		actual = hashlib.sha256((source_directory / f"{name}.png").read_bytes()).hexdigest()
		if actual != expected:
			raise ValueError(f"Unexpected source bytes for {name}; expected the original attachment with SHA-256 {expected}")

	sheet = isolate(read_rgba(source_directory / "sheet.png"), 3)
	hero = isolate(read_rgba(source_directory / "hero.png"), 1)[0]
	layers = {"upper": sheet[0], "hero": soften_lower_cloud_edge(hero, 0.77), "distant": soften_lower_cloud_edge(sheet[2], 0.72), "veil": atmospheric_veil(hero)}
	output_directory.mkdir(parents=True, exist_ok=True)
	manifest = {"issue": "https://github.com/Attacktive/weatherd/issues/405", "evaluation_only": True, "sources": {}, "layers": {}}
	for name, url in SOURCES.items():
		manifest["sources"][name] = {"url": url, "sha256": hashlib.sha256((source_directory / f"{name}.png").read_bytes()).hexdigest()}

	for name, pixels in layers.items():
		path = output_directory / f"{name}.png"
		Image.fromarray(pixels).save(path, optimize=True)
		manifest["layers"][name] = {"width": pixels.shape[1], "height": pixels.shape[0], "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}

	(output_directory / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
	return manifest


def main() -> None:
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--source-dir", type=Path, default=Path(".local/storm-cloud-sources"))
	parser.add_argument("--output-dir", type=Path, default=Path("app/src/debug/assets/storm-clouds"))
	parser.add_argument("--download", action="store_true", help="Fetch the two original issue attachments into the local source directory")
	args = parser.parse_args()
	if args.download:
		args.source_dir.mkdir(parents=True, exist_ok=True)
		for name, url in SOURCES.items():
			data = download_asset(url)
			if hashlib.sha256(data).hexdigest() != EXPECTED_SHA256[name]:
				raise ValueError(f"Downloaded attachment does not match the expected SHA-256 for {name}")

			(args.source_dir / f"{name}.png").write_bytes(data)

	print(json.dumps(prepare(args.source_dir, args.output_dir), indent=2))


if __name__ == "__main__":
	main()
