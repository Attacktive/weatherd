#!/usr/bin/env python3
"""Prepare approved storm-cloud sprites once for a normal Android debug build."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/debug/assets/storm-clouds"
VENV = ROOT / ".local/storm-cloud-venv"
SOURCES = {
	"sheet": "9d359d853dfffd5e5d361091444a98666ae740feb692f920b9c0c38d94405e00",
	"hero": "517e689b9bcd882deeb77c42fa152c6f43f59a7e4650235fcdc51cd58581eff3",
}
LAYERS = ("upper", "hero", "distant", "veil")
PROCESSING_VERSION = 4
MODERN_PACKAGES = ("pillow==13.0.0", "numpy==2.5.4", "scipy==1.18.1")
LEGACY_PACKAGES = ("pillow==11.3.0", "numpy==2.0.2", "scipy==1.13.1")


def packages_for_version(version: tuple[int, int]) -> tuple[str, ...]:
	if version < (3, 9):
		raise RuntimeError("Storm-cloud asset preparation requires Python 3.9 or newer")

	if version < (3, 12):
		return LEGACY_PACKAGES

	return MODERN_PACKAGES


def venv_python_version(python: Path) -> tuple[int, int]:
	version = subprocess.check_output(
		[str(python), "-c", "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"],
		text=True,
	).strip()

	return tuple(map(int, version.split(".")))


def verified_layer(output: Path, name: str, details: dict) -> bool:
	path = output / f"{name}.png"
	if not path.is_file():
		return False

	return hashlib.sha256(path.read_bytes()).hexdigest() == details["sha256"]


def prepared(output: Path = OUTPUT) -> bool:
	try:
		manifest = json.loads((output / "manifest.json").read_text(encoding="utf-8"))
		return (
			manifest["evaluation_only"] is True
			and manifest["processing_version"] == PROCESSING_VERSION
			and all(manifest["sources"][name]["sha256"] == digest for name, digest in SOURCES.items())
			and all(verified_layer(output, name, manifest["layers"][name]) for name in LAYERS)
		)
	except (OSError, KeyError, TypeError, ValueError):
		return False


def prepare() -> None:
	if prepared():
		return

	print("Preparing verified storm-cloud artwork for the debug build", flush=True)
	python = VENV / ("Scripts/python.exe" if os.name == "nt" else "bin/python")
	if not python.is_file():
		subprocess.run([sys.executable, "-m", "venv", str(VENV)], check=True)

	packages = packages_for_version(venv_python_version(python))
	subprocess.run([str(python), "-m", "pip", "install", "--disable-pip-version-check", "--only-binary=:all:", *packages], check=True)
	subprocess.run([str(python), str(ROOT / "scripts/prepare-storm-cloud-prototype.py"), "--download", "--output-dir", str(OUTPUT)], cwd=ROOT, check=True)
	if not prepared():
		raise RuntimeError("Storm-cloud asset preparation completed without four verified PNGs")


if __name__ == "__main__":
	prepare()
