#!/usr/bin/env python3
"""Verify that a release APK and AAB actually contain matching #405 artwork."""

import argparse
import hashlib
from pathlib import Path
import struct
import zipfile

MINIMUM_DIMENSIONS = {
	"upper": (1200, 256),
	"hero": (1500, 700),
	"distant": (1200, 160),
	"veil": (720, 360),
}
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
SOURCE_ISSUE = "https://github.com/Attacktive/weatherd/issues/405"


def archive_entry(bundle: zipfile.ZipFile, path: str, missing_message: str) -> bytes:
	try:
		return bundle.read(path)
	except KeyError as error:
		raise ValueError(f"{missing_message}: {path}") from error


def check_artwork_permission(bundle: zipfile.ZipFile, prefix: str) -> None:
	path = f"{prefix}storm-clouds/ARTWORK_PERMISSION.md"
	permission = archive_entry(bundle, path, "Missing release artwork permission record").decode("utf-8")
	if SOURCE_ISSUE not in permission or len(permission.strip()) < 80:
		raise ValueError("Release artwork permission record is missing its provenance and grant")


def png_metadata(data: bytes, path: str) -> tuple[int, int, int, int]:
	if len(data) < 33 or data[:8] != PNG_SIGNATURE or data[12:16] != b"IHDR":
		raise ValueError(f"Invalid PNG header: {path}")

	return struct.unpack(">IIBB", data[16:26])


def cloud_digest(bundle: zipfile.ZipFile, path: str, minimum_width: int, minimum_height: int) -> str:
	data = archive_entry(bundle, path, "Missing release storm cloud")
	width, height, bit_depth, color_type = png_metadata(data, path)
	if width < minimum_width or height < minimum_height or bit_depth != 8 or color_type != 6:
		raise ValueError(f"Expected high-definition 8-bit RGBA cloud: {path} ({width}x{height}, type {color_type})")

	return hashlib.sha256(data).hexdigest()


def packaged_assets(archive: Path, prefix: str) -> dict[str, str]:
	with zipfile.ZipFile(archive) as bundle:
		check_artwork_permission(bundle, prefix)
		return {
			layer: cloud_digest(bundle, f"{prefix}storm-clouds/{layer}.png", minimum_width, minimum_height)
			for layer, (minimum_width, minimum_height) in MINIMUM_DIMENSIONS.items()
		}


def verify(apk: Path, aab: Path) -> dict[str, str]:
	apk_assets = packaged_assets(apk, "assets/")
	aab_assets = packaged_assets(aab, "base/assets/")
	if apk_assets != aab_assets:
		raise ValueError("Release APK and AAB contain different cloud PNG bytes")

	return apk_assets


def main() -> None:
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument("--apk", type=Path, default=Path("app/build/outputs/apk/release/app-release.apk"))
	parser.add_argument("--aab", type=Path, default=Path("app/build/outputs/bundle/release/app-release.aab"))
	args = parser.parse_args()
	for layer, digest in verify(args.apk, args.aab).items():
		print(f"{layer}: SHA-256 {digest}")


if __name__ == "__main__":
	main()
