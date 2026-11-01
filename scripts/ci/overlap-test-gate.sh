#!/usr/bin/env bash
set -euo pipefail

static_temp="${RUNNER_TEMP:-build}"
diagnostics="$static_temp/weatherd-ci"
static_log="$diagnostics/static.log"
static_pid_file="$diagnostics/static.pid"
static_status_file="$diagnostics/static.status"
static_published_file="$diagnostics/static-published"
monitor_pid=""

metrics() {
	python3 scripts/ci/ci_metrics.py "$@"
}

stop_monitor() {
	if [[ -n "$monitor_pid" ]]; then
		kill "$monitor_pid" 2>/dev/null || true
		wait "$monitor_pid" 2>/dev/null || true
		monitor_pid=""
	fi
}

run_static_gate() {
	metrics mark static-start
	local status
	if ./gradlew --console=plain --info --profile test :app:lint :app:detekt :app:assembleDebug :app:assembleDebugAndroidTest; then
		status=0
	else
		status=$?
	fi

	metrics mark static-end

	printf '%s\n' "$status" > "$static_status_file.tmp"
	mv "$static_status_file.tmp" "$static_status_file"
	return "$status"
}

start_static_gate() {
	mkdir -p "$diagnostics"
	rm -f "$static_log" "$static_pid_file" "$static_status_file" "$static_status_file.tmp" "$static_published_file"

	nohup bash "$0" run-static > "$static_log" 2>&1 &

	printf '%s\n' "$!" > "$static_pid_file"
	echo "Static gate started as PID $(cat "$static_pid_file")."
}

wait_for_static_gate() {
	local pid
	pid="$(cat "$static_pid_file")"

	while [[ ! -f "$static_status_file" ]]; do
		if ! kill -0 "$pid" 2>/dev/null; then
			sleep 1
			if [[ ! -f "$static_status_file" ]]; then
				cat "$static_log"
				echo "Static gate exited before publishing its status." >&2
				return 1
			fi
		fi

		sleep 1
	done

	if [[ ! -f "$static_published_file" ]]; then
		cat "$static_log"
		touch "$static_published_file"
	fi

	local status
	status="$(cat "$static_status_file")"
	if (( status != 0 )); then
		echo "Static gate failed with exit code $status." >&2
		return "$status"
	fi

	echo "Static gate passed."
}

capture_timeout_probe() {
	local serial="$1"
	local filename="$2"
	shift 2
	local output="$diagnostics/$filename"
	if ! timeout --signal=TERM --kill-after=2s 10s adb -s "$serial" "$@" > "$output" 2>&1; then
		printf '\nADB diagnostic probe failed or exceeded 10 seconds.\n' >> "$output"
	fi

	# Failure-only diagnostics are capped, keeping the uploaded artifact bounded.
	head -c 131072 "$output" > "$output.tmp"
	mv "$output.tmp" "$output"
}

capture_instrumentation_timeout() {
	local serial="$1"
	local seconds="$2"
	printf 'Connected instrumentation exceeded its %s-second wall-time budget on %s at %s.\n' "$seconds" "$serial" "$(date -u +%FT%TZ)" | tee "$diagnostics/instrumentation-timeout.txt" >&2
	# Restrict logcat to system ANR/crash event tags; do not collect general logs, provider data, or user settings.
	capture_timeout_probe "$serial" "timeout-anr-events.log" logcat -d -b events -v threadtime -t 2000 -s am_anr:I am_crash:I am_kill:I '*:S'
	capture_timeout_probe "$serial" "timeout-activity-anr.txt" shell dumpsys activity anr
	capture_timeout_probe "$serial" "timeout-window.txt" shell dumpsys window windows
	capture_timeout_probe "$serial" "timeout-processes.txt" shell pidof com.google.android.apps.nexuslauncher xyz.attacktive.weatherd xyz.attacktive.weatherd.test
}

run_instrumentation() {
	local serial="${ANDROID_SERIAL:-emulator-5554}"
	local timeout_seconds="${WEATHERD_INSTRUMENTATION_TIMEOUT_SECONDS:-1800}"
	local status
	metrics mark instrumentation-start
	python3 scripts/ci/ci_metrics.py monitor "$serial" > "$diagnostics/monitor.log" 2>&1 &
	monitor_pid="$!"
	trap stop_monitor EXIT
	if timeout --signal=TERM --kill-after=10s "${timeout_seconds}s" env ANDROID_SERIAL="$serial" ./gradlew --console=plain --info --profile :app:connectedDebugAndroidTest 2>&1 | tee "$diagnostics/instrumentation.log"; then
		status=0
	else
		status=$?
	fi

	metrics mark instrumentation-end
	if (( status == 124 || status == 137 )); then
		capture_instrumentation_timeout "$serial" "$timeout_seconds"
	fi

	stop_monitor
	trap - EXIT
	if (( status == 0 )); then
		# Keep the existing Rome measurement gate alongside the targeted runner diagnostics.
		if [[ -f app/src/androidTest/java/xyz/attacktive/weatherd/domain/render/RomeCloudLayerIsolationTest.kt ]]; then
			local measurements
			measurements="$(adb -s "$serial" logcat -d -v brief -s RomeCloudIsolation:I | grep -F 'Rome cloud isolation' || true)"
			if [[ -z "$measurements" ]]; then
				echo "Rome cloud isolation measurements missing from emulator logcat." >&2
				return 1
			fi
			printf '%s\n' "$measurements"
			if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
				{
					printf '\n### Rome cloud-layer isolation\n\n'
					printf '~~~text\n%s\n~~~\n' "$measurements"
				} >> "$GITHUB_STEP_SUMMARY"
			fi
		fi
		return
	fi

	if [[ -d app/build/outputs/androidTest-results/connected ]]; then
		find app/build/outputs/androidTest-results/connected -type f -name '*.xml' -exec cat {} +
	fi

	return "$status"
}

case "${1:-}" in
	start)
		start_static_gate
		;;
	run-static)
		run_static_gate
		;;
	instrumentation)
		metrics mark emulator-ready
		wait_for_static_gate
		run_instrumentation
		;;
	report)
		status=0
		if [[ "${CI_CANCELLED:-false}" == true ]]; then
			echo "Canceled run: publishing partial diagnostics without waiting for the static gate."
		elif [[ -f "$static_pid_file" ]]; then
			wait_for_static_gate || status=$?
		fi

		metrics report || status=$?
		exit "$status"
		;;
	*)
		echo "Usage: $0 {start|run-static|instrumentation|report}" >&2
		exit 2
		;;
esac
