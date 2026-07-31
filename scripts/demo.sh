#!/usr/bin/env bash
#
# Smart Home Energy Monitor — one command that brings the whole stack up.
#
#   ./scripts/demo.sh                      start MySQL, the server, the simulators, the dashboard
#   ./scripts/demo.sh --scenario incident  the same, replaying the scripted three-minute fault
#   ./scripts/demo.sh --failure <name>     run one failure-mode demonstration and exit
#   ./scripts/demo.sh --list               show the scenarios and failure demonstrations
#   ./scripts/demo.sh --stop               stop anything this script started
#
# Everything it starts is recorded in .demo-pids and stopped on Ctrl-C, so a demonstration
# that goes wrong does not leave a server holding port 5060 against the next attempt.
#
# The script deliberately does not build: `mvn -q compile` runs first and its output is the
# operator's, because a demo that silently ran a stale build is worse than one that refused
# to start.

set -euo pipefail

readonly PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly PID_FILE="${PROJECT_ROOT}/.demo-pids"
readonly LOG_DIR="${PROJECT_ROOT}/.demo-logs"

# Docker needs a leading sudo on hosts where the user is not in the docker group. Resolved
# once here rather than sprinkled through the script.
DOCKER="docker"
if ! docker info >/dev/null 2>&1; then
    if sudo -n docker info >/dev/null 2>&1; then
        DOCKER="sudo docker"
    fi
fi

SCENARIO=""
FAILURE=""
INTERVAL=""
NO_DASHBOARD=0

# --------------------------------------------------------------------------- helpers

say()  { printf '\033[1;36m[demo]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[demo]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[demo]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    cat <<'EOF'
Usage: ./scripts/demo.sh [options]

  --scenario NAME   replay a scripted fault timeline instead of random anomalies
  --failure NAME    run one failure-mode demonstration and exit (see --list)
  --interval MS     milliseconds between readings from each meter (default 1000)
  --no-dashboard    start the server and simulators but not the Swing window
  --list            list the scenarios and failure demonstrations, then exit
  --stop            stop whatever a previous run of this script started
  --help            show this message
EOF
}

# Records a background process so --stop and the exit trap can find it again.
track() {
    echo "$1" >> "${PID_FILE}"
}

stop_all() {
    if [[ ! -f "${PID_FILE}" ]]; then
        say "nothing to stop"
        return 0
    fi
    while read -r pid; do
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            say "stopping pid ${pid}"
            kill "${pid}" 2>/dev/null || true
        fi
    done < "${PID_FILE}"
    rm -f "${PID_FILE}"
    say "stopped. MySQL is left running — 'docker compose down' if you want it gone too."
}

# Waits for a TCP port to start accepting, so the next process is not started against a
# server that is not listening yet.
wait_for_port() {
    local port="$1" what="$2" attempts=60
    while (( attempts-- > 0 )); do
        if (exec 3<>"/dev/tcp/127.0.0.1/${port}") 2>/dev/null; then
            exec 3>&- 3<&-
            return 0
        fi
        sleep 0.5
    done
    die "${what} never started listening on port ${port}; see ${LOG_DIR}"
}

# --------------------------------------------------------------------------- arguments

while [[ $# -gt 0 ]]; do
    case "$1" in
        --scenario)     SCENARIO="${2:?--scenario needs a name}"; shift 2 ;;
        --failure)      FAILURE="${2:?--failure needs a name}"; shift 2 ;;
        --interval)     INTERVAL="${2:?--interval needs a value}"; shift 2 ;;
        --no-dashboard) NO_DASHBOARD=1; shift ;;
        --list)         LIST=1; shift ;;
        --stop)         stop_all; exit 0 ;;
        --help|-h)      usage; exit 0 ;;
        *)              usage >&2; die "unknown option: $1" ;;
    esac
done

cd "${PROJECT_ROOT}"

# --------------------------------------------------------------------------- build

say "building"
mvn -q compile

CLASSPATH_FILE="$(mktemp)"
mvn -q dependency:build-classpath "-Dmdep.outputFile=${CLASSPATH_FILE}"
CP="target/classes:$(cat "${CLASSPATH_FILE}")"
rm -f "${CLASSPATH_FILE}"

run_java() { java -cp "${CP}" "$@"; }

if [[ -n "${LIST:-}" ]]; then
    run_java com.smarthome.energy.simulator.SimulatorLauncher --list-scenarios
    echo
    run_java com.smarthome.energy.demo.FailureDemos --list
    exit 0
fi

# --------------------------------------------------------------------------- database

say "starting MySQL (${DOCKER} compose up -d)"
${DOCKER} compose up -d >/dev/null

say "waiting for MySQL to accept connections"
for _ in $(seq 1 60); do
    if [[ "$(${DOCKER} inspect --format '{{.State.Health.Status}}' energy-mysql 2>/dev/null)" == "healthy" ]]; then
        break
    fi
    sleep 1
done
[[ "$(${DOCKER} inspect --format '{{.State.Health.Status}}' energy-mysql 2>/dev/null)" == "healthy" ]] \
    || die "MySQL did not become healthy; try '${DOCKER} compose logs mysql'"

if [[ ! -f src/main/resources/db.properties ]]; then
    say "creating src/main/resources/db.properties from the example (it is git-ignored)"
    cp src/main/resources/db.properties.example src/main/resources/db.properties
fi

# --------------------------------------------------------------------------- failure demos

if [[ -n "${FAILURE}" ]]; then
    say "running the '${FAILURE}' failure demonstration"
    run_java com.smarthome.energy.demo.FailureDemos --demo "${FAILURE}"
    exit 0
fi

# --------------------------------------------------------------------------- the stack

mkdir -p "${LOG_DIR}"
: > "${PID_FILE}"
trap 'echo; stop_all' INT TERM

say "starting the ingest server (log: ${LOG_DIR}/server.log)"
run_java com.smarthome.energy.server.EnergyMonitorServer > "${LOG_DIR}/server.log" 2>&1 &
track $!
wait_for_port 5060 "the ingest server"
wait_for_port 5061 "the dashboard feed"

if (( NO_DASHBOARD == 0 )); then
    if [[ -z "${DISPLAY:-}" ]]; then
        warn "no DISPLAY set — skipping the Swing dashboard; the rest of the stack still runs"
    else
        say "opening the dashboard"
        run_java com.smarthome.energy.client.DashboardApp > "${LOG_DIR}/dashboard.log" 2>&1 &
        track $!
        # The dashboard subscribes on start-up; a second or two here means the first scripted
        # fault is not delivered to an empty room.
        sleep 3
    fi
fi

SIM_ARGS=()
[[ -n "${INTERVAL}" ]] && SIM_ARGS+=(--interval "${INTERVAL}")
if [[ -n "${SCENARIO}" ]]; then
    SIM_ARGS+=(--scenario "${SCENARIO}")
    say "replaying scenario '${SCENARIO}' — the simulators stop when it ends"
else
    say "streaming with random anomalies — Ctrl-C to stop"
fi

say "starting the meter simulators (log: ${LOG_DIR}/simulator.log)"
run_java com.smarthome.energy.simulator.SimulatorLauncher "${SIM_ARGS[@]}" \
    2>&1 | tee "${LOG_DIR}/simulator.log" &
SIM_PID=$!
track "${SIM_PID}"

# Foregrounding the simulators means a scenario run ends by itself, and a free-running one
# ends when the operator says so.
wait "${SIM_PID}" || true

if [[ -n "${SCENARIO}" ]]; then
    say "scenario finished. The server and dashboard are still up so the event log can be read."
    say "press Ctrl-C, or run './scripts/demo.sh --stop', when you are done."
    # Nothing left to do but hold the trap open for the operator.
    while true; do sleep 3600; done
fi

stop_all
