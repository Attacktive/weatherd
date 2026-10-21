"""Local, opt-in collection on an isolated Android emulator. No production scheduler."""

import argparse
import hashlib
import json
import math
import os
import re
# Android SDK tools require direct process execution; no host shell is used.
import subprocess  # nosec B404
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path


CLONE_NAME = 'Pixel_Weatherd_Provider_Evaluation'
PACKAGE = 'xyz.attacktive.weatherd'
COLLECTOR = f'{PACKAGE}.data.provider.ProviderEvaluationCaptureTest'
INSTRUMENTATION = f'{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner'

_ALLOWED_SDK_TOOLS = {
	'adb': Path('platform-tools/adb'),
	'emulator': Path('emulator/emulator'),
}

MATCH_RETRY_BOUND_HOURS = 48
MATCH_RETRY_BOUND_SECONDS = MATCH_RETRY_BOUND_HOURS * 3600
MATCH_RETRY_BOUND_ATTEMPTS = 96
AWC_RETENTION_HOURS = 24 * 30
AWC_RETENTION_SECONDS = AWC_RETENTION_HOURS * 3600


def utc_now():
	return datetime.now(timezone.utc).isoformat()


def write_once(path, content):
	path = Path(path)
	mode = 'x'
	if isinstance(content, bytes):
		mode = 'xb'

	with path.open(mode) as stream:
		stream.write(content)


def write_json(path, value):
	write_once(path, json.dumps(value, indent=2, allow_nan=False) + '\n')


def _should_skip_config_key(key):
	return key.endswith('.path') and key.startswith(('disk.', 'sdcard.', 'snapshot.'))


def _parse_config_values(source):
	values = {}
	for line in source.splitlines():
		stripped = line.lstrip()
		if '=' in line and not stripped.startswith('#'):
			key, value = line.split('=', 1)
			key = key.strip()
			if not _should_skip_config_key(key):
				values[key] = value.strip()

	return values


def clone_config(source, name):
	if name != CLONE_NAME:
		raise ValueError(f'Evaluation clone name must be {CLONE_NAME}')

	values = _parse_config_values(source)
	values.update({
		'AvdId': name,
		'avd.ini.displayname': name,
		'fastboot.chosenSnapshotFile': '',
		'fastboot.forceChosenSnapshotBoot': 'no',
		'fastboot.forceColdBoot': 'yes',
		'fastboot.forceFastBoot': 'no',
	})
	return ''.join(f'{key}={value}\n' for key, value in values.items())


def _resolve_system_image(image_path, sdk):
	image = Path(image_path)
	if not image.is_absolute():
		image = Path(sdk) / image

	if not image.is_dir():
		raise FileNotFoundError(f'Source system image unavailable: {image}')

	return image


def _record_clone_ledger(output, name, source_path, source, generated, target, registry):
	ledger = Path(output) / 'emulator' / name
	ledger.mkdir(parents=True, exist_ok=False)
	write_once(ledger / 'source-config.ini', source)
	write_once(ledger / 'generated-config.ini', generated)
	write_json(ledger / 'creation.json', {
		'created_at_utc': utc_now(),
		'source_config': str(source_path),
		'source_sha256': hashlib.sha256(source).hexdigest(),
		'generated_sha256': hashlib.sha256(generated.encode()).hexdigest(),
		'output_avd': str(target),
		'output_registry': str(registry),
		'baseline': 'source-config.ini',
		'build_input': 'generated-config.ini',
		'changes': ['New AVD identity', 'Cold boot', 'No disk images, snapshots, accounts, or photos copied'],
		'rebuild': 'With the clone stopped and absent, recreate output_avd/config.ini from build_input and output_registry from registry.ini. Never restore over the source AVD.',
	})
	return ledger


def clone_avd(source_name, name, output, avd_root, sdk):
	if not re.fullmatch(r'[A-Za-z0-9_-]+', source_name) or source_name == name:
		raise ValueError('Source must be a different, plain AVD name')

	avd_root = Path(avd_root)
	target = avd_root / f'{name}.avd'
	registry = avd_root / f'{name}.ini'
	if target.exists() or registry.exists():
		raise FileExistsError(f'Refusing to replace existing AVD: {target}')

	source_path = avd_root / f'{source_name}.avd/config.ini'
	source = source_path.read_bytes()
	generated = clone_config(source.decode(), name)
	values = dict(line.split('=', 1) for line in generated.splitlines())
	_resolve_system_image(values['image.sysdir.1'], sdk)
	ledger = _record_clone_ledger(output, name, source_path, source, generated, target, registry)
	registry_text = f'avd.ini.encoding=UTF-8\npath={target.resolve()}\npath.rel=avd/{name}.avd\ntarget={values.get("target", "")}\n'
	write_once(ledger / 'registry.ini', registry_text)
	target.mkdir()
	write_once(target / 'config.ini', generated)
	write_once(registry, registry_text)
	return target


def require_emulator_serial(serial):
	if not re.fullmatch(r'emulator-[0-9]+', serial):
		raise ValueError('An explicit emulator-NNNN serial is required; physical devices are forbidden')

	port = int(serial.removeprefix('emulator-'))
	if port < 5554 or port > 5682 or port % 2:
		raise ValueError('Emulator console port must be even and between 5554 and 5682')


def next_slot(now, interval):
	if not math.isfinite(interval) or interval <= 0:
		raise ValueError('Interval must be positive')

	return (math.floor(now / interval) + 1) * interval


def _sdk_dir_from_properties(path):
	if not path.is_file():
		return None

	for line in path.read_text().splitlines():
		if line.strip().startswith('sdk.dir') and '=' in line:
			return Path(line.split('=', 1)[1].strip())

	return None


def sdk_path():
	for name in ('ANDROID_HOME', 'ANDROID_SDK_ROOT'):
		if os.environ.get(name):
			return Path(os.environ[name]).expanduser()

	properties_sdk = _sdk_dir_from_properties(Path(__file__).resolve().parents[2] / 'local.properties')
	if properties_sdk is not None:
		return properties_sdk

	return Path.home() / 'Library/Android/sdk'


def _sdk_tool(tool_name):
	relative_path = _ALLOWED_SDK_TOOLS.get(tool_name)
	if relative_path is None:
		raise ValueError(f'Untrusted SDK tool: {tool_name}')

	tool_path = sdk_path() / relative_path
	return str(tool_path)


def _run_sdk_tool(tool_name, arguments, timeout=30):
	tool_path = _sdk_tool(tool_name)
	# SDK root is trusted developer configuration; executable names are allowlisted and no host shell is used.
	# nosemgrep: python.lang.security.audit.dangerous-subprocess-use-tainted-env-args.dangerous-subprocess-use-tainted-env-args
	return subprocess.run([tool_path, *map(str, arguments)], capture_output=True, timeout=timeout, check=True)  # nosec B603


def _spawn_sdk_tool(tool_name, arguments, stdout=None, stderr=None, start_new_session=False):
	tool_path = _sdk_tool(tool_name)
	# SDK root is trusted developer configuration; executable names are allowlisted and no host shell is used.
	# nosemgrep: python.lang.security.audit.dangerous-subprocess-use-tainted-env-args.dangerous-subprocess-use-tainted-env-args
	return subprocess.Popen([tool_path, *map(str, arguments)], stdout=stdout, stderr=stderr, start_new_session=start_new_session)  # nosec B603


def adb(serial, *arguments, timeout=30):
	require_emulator_serial(serial)
	result = _run_sdk_tool('adb', ['-s', serial, *arguments], timeout=timeout)
	return result.stdout


def require_successful_instrumentation(output):
	if re.search(rb'^OK \(1 test\)\s*$', output, re.MULTILINE) is None or b'FAILURES!!!' in output or b'INSTRUMENTATION_FAILED' in output:
		raise RuntimeError('Android collector did not complete successfully; inspect instrumentation.txt')


def _validate_station_source(captured, station_id):
	if captured.get('station_id') != station_id or captured.get('location_source') != 'MANUAL_STATION':
		raise ValueError('Collector did not capture the requested manual station')


def _validate_capture_flags(captured):
	if captured.get('device_location') is not False or captured.get('location_verified') is not True or captured.get('simulator_active') is not False:
		raise ValueError('Collector did not verify manual coordinates and live weather')


def _matches_coordinate(value, expected):
	if not isinstance(value, (int, float)) or not math.isfinite(value):
		return False

	return abs(value - expected) <= 0.00001


def _validate_coordinates(captured, station):
	for field, station_field in (('latitude', 'lat'), ('longitude', 'lon')):
		if not _matches_coordinate(captured.get(field), station[station_field]):
			raise ValueError('Collector coordinates do not match station metadata')


def validate_capture(captured, station):
	_validate_station_source(captured, station['icaoId'])
	_validate_capture_flags(captured)
	_validate_coordinates(captured, station)


def require_clone(serial):
	name = adb(serial, 'emu', 'avd', 'name').decode().splitlines()[0].strip()
	if name != CLONE_NAME:
		raise ValueError(f'Refusing {serial}: AVD is {name!r}, not {CLONE_NAME!r}')


def _is_device_attached(serial):
	devices = _run_sdk_tool('adb', ['devices']).stdout.decode()
	for line in devices.splitlines()[1:]:
		parts = line.split()
		if parts and parts[0] == serial:
			return True

	return False


def _start_emulator_process(port, ledger):
	stamp = time.time_ns()
	log_path = ledger / f'boot-{stamp}.log'
	arguments = [
		'-avd',
		CLONE_NAME,
		'-port',
		str(port),
		'-no-window',
		'-no-audio',
		'-no-boot-anim',
		'-no-snapshot-load',
		'-no-snapshot-save',
	]

	with log_path.open('xb') as log:
		process = _spawn_sdk_tool(
			'emulator',
			arguments,
			stdout=log,
			stderr=subprocess.STDOUT,
			start_new_session=True,
		)

	return process, stamp, log_path


def _is_boot_completed(serial):
	try:
		return adb(serial, 'shell', 'getprop', 'sys.boot_completed', timeout=5).strip() == b'1'
	except (subprocess.SubprocessError, ValueError, IndexError):
		return False


def _wait_for_boot(serial, process, log_path, timeout=240):
	deadline = time.monotonic() + timeout
	while time.monotonic() < deadline:
		if process.poll() is not None:
			raise RuntimeError(f'Emulator exited with {process.returncode}; inspect {log_path}')

		if _is_boot_completed(serial):
			require_clone(serial)
			return serial

		time.sleep(2)

	raise TimeoutError(f'Clone boot timed out; inspect {log_path}. Process retained for diagnosis.')


def boot(port, output):
	serial = f'emulator-{port}'
	require_emulator_serial(serial)
	if _is_device_attached(serial):
		require_clone(serial)
		return serial

	ledger = Path(output) / 'emulator' / CLONE_NAME
	if not (ledger / 'creation.json').is_file():
		raise ValueError('Create the clone and its evidence ledger before booting')

	process, stamp, log_path = _start_emulator_process(port, ledger)
	write_json(ledger / f'boot-{stamp}.json', {
		'started_at_utc': utc_now(),
		'pid': process.pid,
		'serial': serial,
		'log': str(log_path),
	})
	return _wait_for_boot(serial, process, log_path)


def install(serial, app_apk, test_apk):
	require_clone(serial)
	for apk in (app_apk, test_apk):
		print(adb(serial, 'install', '-r', '-t', str(Path(apk).resolve()), timeout=120).decode(), end='', flush=True)


def _check_pre_fetch_expiry(capture, matches, latest_match, previous_attempts):
	capture_age = time.time() - capture['captured_at_epoch_seconds']
	if capture_age > AWC_RETENTION_SECONDS:
		attempts = previous_attempts if previous_attempts >= MATCH_RETRY_BOUND_ATTEMPTS else previous_attempts + 1
		original_reason = latest_match.get('reason') if matches else None
		return ('retention_exceeded', 'Capture is outside AWC recent retention; an archived observation source is required', original_reason, attempts)

	if capture_age > MATCH_RETRY_BOUND_SECONDS:
		original_reason = latest_match.get('reason') if matches else 'Capture age exceeds 48 hours'
		attempts = previous_attempts if previous_attempts >= MATCH_RETRY_BOUND_ATTEMPTS else previous_attempts + 1
		return ('max_age_exceeded', original_reason, original_reason, attempts)

	if previous_attempts >= MATCH_RETRY_BOUND_ATTEMPTS:
		return ('max_attempts_exceeded', latest_match.get('reason'), latest_match.get('reason'), previous_attempts)

	return None


def _apply_match_bounds(match, current_attempt, captured_at):
	match['attempts'] = current_attempt
	if match.get('status') != 'PENDING':
		return

	if current_attempt >= MATCH_RETRY_BOUND_ATTEMPTS:
		original_reason = match.get('reason')
		match['status'] = 'EXPIRED'
		match['cause'] = 'max_attempts_exceeded'
		match['original_reason'] = original_reason
	elif (time.time() - captured_at) > MATCH_RETRY_BOUND_SECONDS:
		original_reason = match.get('reason')
		match['status'] = 'EXPIRED'
		match['cause'] = 'max_age_exceeded'
		match['original_reason'] = original_reason


def _build_match_result(capture, station, match):
	return {'capture': capture, 'station': station, 'match': match, 'matched_at_utc': utc_now()}


def _record_match_attempt(directory, stamp, result, latest):
	match = result['match']
	if latest.get('match', {}).get('status') == 'MATCHED' and match.get('status') in ('PENDING', 'EXPIRED'):
		if match['status'] == 'EXPIRED':
			match['status'] = 'SKIPPED'

		result['retained_matched_at_utc'] = latest.get('matched_at_utc')
		write_json(directory / f'rematch-{stamp}.json', result)
		return latest

	write_json(directory / f'match-{stamp}.json', result)
	return result


def match_run(directory):
	from provider_evaluation.observations import fetch_observations, match_capture

	directory = Path(directory)
	capture = json.loads((directory / 'capture.json').read_text())
	run = json.loads((directory / 'run.json').read_text())
	station = json.loads((directory / 'station.json').read_text())['records'][0]
	capture['collection'] = run['collection']
	matches = sorted(directory.glob('match-*.json'))
	latest = {}
	if matches:
		latest = json.loads(matches[-1].read_text())
		if latest.get('match', {}).get('status') == 'EXPIRED':
			return latest

	latest_match = latest.get('match', {})

	previous_attempts = max(len(matches), latest_match.get('attempts', 0))
	rematches = sorted(directory.glob('rematch-*.json'))
	if rematches:
		previous_attempts = max(previous_attempts, json.loads(rematches[-1].read_text())['match'].get('attempts', 0))

	pre_expiry = _check_pre_fetch_expiry(capture, matches, latest_match, previous_attempts)
	stamp = time.time_ns()
	if pre_expiry:
		cause, reason, original_reason, attempts = pre_expiry
		match = {
			'status': 'EXPIRED',
			'cause': cause,
			'attempts': attempts,
			'reason': reason,
			'original_reason': original_reason
		}
		result = _build_match_result(capture, station, match)
		return _record_match_attempt(directory, stamp, result, latest)

	current_attempt = previous_attempts + 1
	elapsed_hours = max(2, math.ceil((time.time() - capture['captured_at_epoch_seconds']) / 3600) + 1)
	try:
		observations = fetch_observations(station['icaoId'], hours=elapsed_hours)
		write_json(directory / f'observations-{stamp}.json', observations)
		match = match_capture(capture, station, observations['records'])
	except Exception as error:
		match = {'status': 'PENDING', 'reason': f'{type(error).__name__}: {error}'}

	_apply_match_bounds(match, current_attempt, capture['captured_at_epoch_seconds'])
	result = _build_match_result(capture, station, match)
	return _record_match_attempt(directory, stamp, result, latest)


def _record_capture_failure(directory, error):
	details = {'at_utc': utc_now(), 'type': type(error).__name__, 'message': str(error)}
	if isinstance(error, (subprocess.CalledProcessError, subprocess.TimeoutExpired)):
		details['stdout'] = (error.stdout or b'').decode(errors='replace')
		details['stderr'] = (error.stderr or b'').decode(errors='replace')

	write_json(directory / 'failure.json', details)
	print(f'Capture failed; evidence retained at {directory}', flush=True)


def _require_station_and_clock(serial, station_id, directory):
	from provider_evaluation.observations import fetch_station

	metadata = fetch_station(station_id)
	write_json(directory / 'station.json', metadata)
	if not metadata.get('records'):
		raise ValueError(f'No station metadata available for {station_id}; capture deferred')

	station = metadata['records'][0]
	clock = int(adb(serial, 'shell', 'date', '+%s').strip())
	if abs(clock - time.time()) > 60:
		raise ValueError('Emulator clock differs from host by more than 60 seconds; live capture refused')

	return station


def _run_collector_instrumentation(serial, station, run_id, directory):
	try:
		result = adb(
			serial,
			'shell',
			'am',
			'instrument',
			'-w',
			'-e',
			'class',
			COLLECTOR,
			'-e',
			'providerEvaluation',
			'true',
			'-e',
			'stationId',
			station['icaoId'],
			'-e',
			'stationLatitude',
			station['lat'],
			'-e',
			'stationLongitude',
			station['lon'],
			'-e',
			'outputName',
			run_id,
			INSTRUMENTATION,
			timeout=180,
		)
	except subprocess.TimeoutExpired as error:
		write_once(directory / 'instrumentation.txt', error.stdout or b'')
		raise

	write_once(directory / 'instrumentation.txt', result)
	require_successful_instrumentation(result)


def _pull_and_validate_capture(serial, station, run_id, directory):
	for filename in ('report.txt', 'capture.json'):
		content = adb(serial, 'exec-out', 'run-as', PACKAGE, 'cat', f'files/provider-evaluation/{run_id}/{filename}')
		write_once(directory / filename, content)

	captured = json.loads((directory / 'capture.json').read_text())
	validate_capture(captured, station)
	report = (directory / 'report.txt').read_text()
	if 'Tester observed: UNSURE\n' not in report:
		raise ValueError('Collector report is not an UNSURE capture')


def _execute_capture(serial, station_id, directory, run_id):
	station = _require_station_and_clock(serial, station_id, directory)
	_run_collector_instrumentation(serial, station, run_id, directory)
	_pull_and_validate_capture(serial, station, run_id, directory)
	matched = match_run(directory)
	result_payload = {
		'run_directory': str(directory.resolve()),
		'observation_status': matched['match'].get('status'),
		'reason': matched['match'].get('reason'),
	}
	print(json.dumps(result_payload, indent=2), flush=True)


def capture_run(serial, station_id, output, collection, scheduled_at=None):
	require_clone(serial)
	run_id = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8]
	directory = Path(output) / 'runs' / run_id
	directory.mkdir(parents=True, exist_ok=False)
	run_metadata = {
		'created_at_utc': utc_now(),
		'serial': serial,
		'avd': CLONE_NAME,
		'station_id': station_id,
		'collection': collection,
		'scheduled_at_epoch_seconds': scheduled_at,
	}
	write_json(directory / 'run.json', run_metadata)
	try:
		_execute_capture(serial, station_id, directory, run_id)
	except Exception as error:
		_record_capture_failure(directory, error)
		raise

	return directory


def _run_collection_type(directory):
	run_file = directory / 'run.json'
	if run_file.is_file():
		return json.loads(run_file.read_text()).get('collection')

	capture_file = directory / 'capture.json'
	if capture_file.is_file():
		return json.loads(capture_file.read_text()).get('collection')

	return None


def _is_routine_success(directory, matches):
	return (directory / 'report.txt').is_file() and (directory / 'capture.json').is_file() and len(matches) > 0


def _evaluate_routine_runs(run_directories):
	attempts = 0
	successes = 0
	failures = 0
	incompletes = 0
	for directory, matches in run_directories:
		if _run_collection_type(directory) != 'ROUTINE':
			continue

		attempts += 1
		if (directory / 'failure.json').is_file():
			failures += 1
		elif _is_routine_success(directory, matches):
			successes += 1
		else:
			incompletes += 1

	rate = 0.0
	if attempts > 0:
		rate = successes / attempts
	return {
		'attempt_count': attempts,
		'success_count': successes,
		'failure_count': failures,
		'incomplete_count': incompletes,
		'success_rate': rate,
		'denominator': 'Total routine collection attempts'
	}


def summary(output):
	from provider_evaluation.observations import summarize

	entries = []
	failures = []
	run_directories = []
	for directory in sorted((Path(output) / 'runs').glob('*')):
		if not directory.is_dir():
			continue

		matches = sorted(directory.glob('match-*.json'))
		run_directories.append((directory, matches))
		if (directory / 'failure.json').is_file():
			failures.append({'run_directory': str(directory), 'failure': json.loads((directory / 'failure.json').read_text())})
		elif matches:
			entries.append(json.loads(matches[-1].read_text()))

	result = summarize(entries)
	result['collection_failures'] = failures
	result['routine_collection'] = _evaluate_routine_runs(run_directories)
	result['provider_availability_denominator'] = 'Total obtained provider samples in routine runs with non-EXCLUDED match revisions (collection failures excluded, no observation deduplication)'
	return result


def pending_runs(output):
	pending = []
	for directory in sorted((Path(output) / 'runs').glob('*')):
		matches = sorted(directory.glob('match-*.json'))
		if matches and (directory / 'capture.json').is_file():
			latest = json.loads(matches[-1].read_text())
			if latest['match'].get('status') == 'PENDING':
				pending.append(directory)

	return pending


def _wait_until_scheduled(scheduled):
	while time.time() < scheduled:
		time.sleep(min(1, scheduled - time.time()))


def _rematch_pending(pending):
	for directory in pending:
		matched = match_run(directory)
		print(f'Rematched {directory.name}: {matched["match"].get("status")}', flush=True)


def _record_missed_schedule(output, scheduled, next_scheduled):
	ledger = output / 'schedule'
	ledger.mkdir(parents=True, exist_ok=True)
	write_json(ledger / f'missed-{time.time_ns()}.json', {
		'last_scheduled_epoch_seconds': scheduled,
		'next_scheduled_epoch_seconds': next_scheduled,
		'reason': 'Collection exceeded a schedule boundary; no catch-up captures',
	})


def collect(args):
	if args.count < 0:
		raise ValueError('Count must be nonnegative; 0 runs until interrupted')

	completed = 0
	scheduled = next_slot(time.time(), args.interval_seconds)
	while args.count == 0 or completed < args.count:
		print(f'Next {args.collection} capture: {datetime.fromtimestamp(scheduled, timezone.utc).isoformat()}', flush=True)
		_wait_until_scheduled(scheduled)
		pending = pending_runs(args.output)
		try:
			capture_run(args.serial, args.station, args.output, args.collection, scheduled)
		except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
			print(f'{type(error).__name__}: {error}', flush=True)

		_rematch_pending(pending)
		completed += 1
		next_scheduled = next_slot(time.time(), args.interval_seconds)
		if next_scheduled > scheduled + args.interval_seconds:
			_record_missed_schedule(args.output, scheduled, next_scheduled)

		scheduled = next_scheduled


def _add_capture_parsers(sub):
	for command in ('capture', 'collect'):
		capture = sub.add_parser(command)
		capture.add_argument('--serial', required=True)
		capture.add_argument('--station', default='LIRF')
		capture.add_argument('--output', type=Path, default=Path('captures/provider-evaluation'))
		capture.add_argument('--collection', choices=['ROUTINE', 'DIAGNOSTIC'], default='DIAGNOSTIC')
		if command == 'collect':
			capture.set_defaults(collection='ROUTINE')
			capture.add_argument('--interval-seconds', type=int, default=1800)
			capture.add_argument('--count', type=int, default=1, help='0 runs until interrupted')


def _build_parser():
	parser = argparse.ArgumentParser(description=__doc__)
	sub = parser.add_subparsers(dest='command', required=True)
	clone = sub.add_parser('clone', help='Create a clean-data configuration clone; never copy live userdata')
	clone.add_argument('--source', default='Pixel')
	clone.add_argument('--name', default=CLONE_NAME, choices=[CLONE_NAME])
	clone.add_argument('--avd-root', type=Path, default=Path(os.environ.get('ANDROID_AVD_HOME', str(Path.home() / '.android/avd'))))
	clone.add_argument('--output', type=Path, default=Path('captures/provider-evaluation'))
	boot_parser = sub.add_parser('boot', help='Boot the dedicated clone headlessly')
	boot_parser.add_argument('--port', type=int, default=5556)
	boot_parser.add_argument('--output', type=Path, default=Path('captures/provider-evaluation'))
	installer = sub.add_parser('install', help='Update app and collector APKs on the verified clone only')
	installer.add_argument('--serial', required=True)
	installer.add_argument('--app-apk', type=Path, default=Path('app/build/outputs/apk/debug/app-debug.apk'))
	installer.add_argument('--test-apk', type=Path, default=Path('app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'))
	_add_capture_parsers(sub)
	matcher = sub.add_parser('match', help='Append a new observation fetch and match; never alter original report')
	matcher.add_argument('directory', type=Path)
	summarizer = sub.add_parser('summarize')
	summarizer.add_argument('--output', type=Path, default=Path('captures/provider-evaluation'))
	return parser


def _handle_clone(args):
	print(clone_avd(args.source, args.name, args.output, args.avd_root, sdk_path()))


def _handle_boot(args):
	print(boot(args.port, args.output))


def _handle_install(args):
	install(args.serial, args.app_apk, args.test_apk)


def _handle_capture(args):
	capture_run(args.serial, args.station, args.output, args.collection)


def _handle_collect(args):
	collect(args)


def _handle_match(args):
	print(json.dumps(match_run(args.directory), indent=2, allow_nan=False))


def _handle_summarize(args):
	print(json.dumps(summary(args.output), indent=2, allow_nan=False))


_COMMAND_HANDLERS = {
	'clone': _handle_clone,
	'boot': _handle_boot,
	'install': _handle_install,
	'capture': _handle_capture,
	'collect': _handle_collect,
	'match': _handle_match,
	'summarize': _handle_summarize,
}


def _dispatch_command(args):
	handler = _COMMAND_HANDLERS.get(args.command)
	if handler:
		handler(args)


def main():
	parser = _build_parser()
	args = parser.parse_args()
	_dispatch_command(args)
