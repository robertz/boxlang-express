#!/usr/bin/env bash
# Phase 5 load comparison: runs the same ApacheBench (ab) load against
# app.bxs served by the JDK adapter, then the Undertow adapter, and prints
# both results back to back. hey/wrk aren't installed on this machine — ab
# ships with macOS, so it's what's used here instead.
set -euo pipefail

cd "$(dirname "$0")"
PORT=4700
N=${LOAD_TEST_REQUESTS:-20000}
C=${LOAD_TEST_CONCURRENCY:-100}

run_engine() {
	local engine="$1"
	echo "=================================================================="
	echo "Engine: $engine   (requests=$N concurrency=$C)"
	echo "=================================================================="

	BOXEXPRESS_LOAD_ENGINE="$engine" BOXEXPRESS_LOAD_PORT="$PORT" boxlang app.bxs > "/tmp/load-test-$engine.log" 2>&1 &
	local pid=$!

	for i in $(seq 1 50); do
		if curl -s -o /dev/null "http://localhost:$PORT/"; then
			break
		fi
		sleep 0.2
	done

	echo "--- GET / (static text) ---"
	ab -n "$N" -c "$C" -q "http://localhost:$PORT/" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Non-2xx"

	echo "--- GET /users/42 (route param + JSON) ---"
	ab -n "$N" -c "$C" -q "http://localhost:$PORT/users/42" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Non-2xx"

	echo "--- POST /echo (JSON body parse) ---"
	echo -n '{"a":1,"b":"two"}' > /tmp/load-test-body.json
	ab -n "$N" -c "$C" -q -p /tmp/load-test-body.json -T application/json "http://localhost:$PORT/echo" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Non-2xx"

	echo "--- GET /public/hello.txt (static file) ---"
	ab -n "$N" -c "$C" -q "http://localhost:$PORT/public/hello.txt" 2>&1 | grep -E "Requests per second|Time per request|Failed requests|Non-2xx"

	kill "$pid" 2>/dev/null || true
	wait "$pid" 2>/dev/null || true
	sleep 1
	echo
}

run_engine jdk
run_engine undertow
