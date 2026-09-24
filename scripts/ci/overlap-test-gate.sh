#!/usr/bin/env bash
set -euo pipefail

static_log="${RUNNER_TEMP:?}/weatherd-static-gate.log"
static_pid_file="${RUNNER_TEMP:?}/weatherd-static-gate.pid"
static_status_file="${RUNNER_TEMP:?}/weatherd-static-gate.status"

start_static_gate() {
	rm -f "$static_log" "$static_pid_file" "$static_status_file"

	nohup bash -c '
		set +e
		./gradlew test :app:lint :app:detekt
		status=$?
		printf "%s\n" "$status" > "$1.tmp"
		mv "$1.tmp" "$1"
		exit "$status"
	' _ "$static_status_file" > "$static_log" 2>&1 &

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

	cat "$static_log"

	local status
	status="$(cat "$static_status_file")"
	if (( status != 0 )); then
		echo "Static gate failed with exit code $status." >&2
		return "$status"
	fi

	echo "Static gate passed."
}

run_instrumentation() {
	if ./gradlew :app:connectedDebugAndroidTest; then
		return
	fi

	find app/build/outputs/androidTest-results/connected -type f -name '*.xml' -print0 | xargs -0 -r cat
	return 1
}

case "${1:-}" in
	start)
		start_static_gate
		;;
	instrumentation)
		wait_for_static_gate
		run_instrumentation
		;;
	*)
		echo "Usage: $0 {start|instrumentation}" >&2
		exit 2
		;;
esac
