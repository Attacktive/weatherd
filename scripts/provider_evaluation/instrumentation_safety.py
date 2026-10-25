"""Read-only preflight for ordinary connected tests; the provider AVD is reserved."""

import argparse
# Only SDK-tool exception types are handled here; execution uses the existing allowlisted helper.
import subprocess  # nosec B404
import sys

from provider_evaluation.runner import CLONE_NAME, _run_sdk_tool


def select_ordinary_device(devices, requested):
	if len(requested) != 1:
		raise ValueError('Set ANDROID_SERIAL to exactly one disposable device; ordinary tests must not target all connected devices')

	serial = requested[0]

	if serial not in devices:
		raise ValueError(f'Selected device is not online: {serial}')

	name = devices[serial]
	if serial.startswith('emulator-') and not name:
		raise ValueError(f'Cannot identify AVD for {serial}; refusing ordinary instrumentation')

	if name == CLONE_NAME:
		raise ValueError(f'Refusing ordinary instrumentation on {serial}: {CLONE_NAME} is reserved for scheduled provider collection')

	return serial


def read_devices():
	def query(*arguments):
		return _run_sdk_tool('adb', arguments, timeout=15).stdout.decode()

	devices = {}
	for line in query('devices').splitlines()[1:]:
		parts = line.split()
		if len(parts) < 2 or parts[1] != 'device':
			continue

		serial = parts[0]
		name = None
		if serial.startswith('emulator-'):
			output = query('-s', serial, 'emu', 'avd', 'name').splitlines()
			name = ''
			if output:
				name = output[0].strip()

		devices[serial] = name

	return devices


def main():
	parser = argparse.ArgumentParser(description=__doc__)
	parser.add_argument('--serial', action='append', default=[])
	arguments = parser.parse_args()
	try:
		serial = select_ordinary_device(read_devices(), arguments.serial)
	except (ValueError, OSError, subprocess.SubprocessError) as error:
		print(f'Instrumentation preflight: {error}', file=sys.stderr)
		return 1

	print(f'Ordinary instrumentation device: {serial}')
	return 0


if __name__ == '__main__':
	sys.exit(main())
