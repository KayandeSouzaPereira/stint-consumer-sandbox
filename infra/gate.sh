#!/usr/bin/env bash
# SDD S.4, 8c: the Phase 2 gate evidence in one command. It only sequences Maven runs; every assertion is in a test.
#
#   bash infra/gate.sh                      regression (RF1) once, then chaos, load and observability (RF2, RF3, RF5)
#   bash infra/gate.sh --regression-only --repeat 3     CA2: the regression three times in a row
#   bash infra/gate.sh --orders 100         the load scenario with N orders (default 50)
#   bash infra/gate.sh --only ChaosIT       one scenario class (or a JUnit -Dtest pattern)
#   bash infra/gate.sh --native             RF4 only: both domains built as native images (in a container) and the
#                                           happy path run on them as containers (Docker needs ~8 GB of memory)
#
# Needs: docker compose up -d (floci, MailHog, Jaeger), Stint installed at the README's SHA, the AWS CLI, JDK 25,
# Maven (MVN=... if `mvn` isn't the right one) and no sandbox app already running (ports 8080-8083).
# Every run starts from infra/reset.sh: DESTRUCTIVE for floci and MailHog only (it refuses any other endpoint).
set -euo pipefail

export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
export AWS_REGION=us-east-1
export AWS_DEFAULT_REGION=us-east-1

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MVN="${MVN:-mvn}"
REPEAT=1
ORDERS=50
REGRESSION_ONLY=false
NATIVE=false
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repeat) REPEAT="$2"; shift 2 ;;
    --orders) ORDERS="$2"; shift 2 ;;
    --regression-only) REGRESSION_ONLY=true; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    --native) NATIVE=true; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

OUT="${GATE_OUT:-$ROOT/gate-out}/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
SUMMARY="$OUT/summary.txt"
cd "$ROOT"

log() { echo "[gate $(date +%H:%M:%S)] $*" | tee -a "$SUMMARY"; }
mvn_() { # name, maven args...
  local name="$1"; shift
  log "mvn $* (log $OUT/$name.log)"
  if "$MVN" "$@" > "$OUT/$name.log" 2>&1; then
    grep -E "Tests run:.*in io\.|BUILD" "$OUT/$name.log" | sed 's/^\[INFO\] /    /' | tee -a "$SUMMARY"
  else
    grep -E "Tests run:|FAIL|BUILD|ERROR\]" "$OUT/$name.log" | head -40 | tee -a "$SUMMARY"
    log "FAILED: $name"; exit 1
  fi
}

# The counterpart of FlociOrdersIT (S.3 8g) runs as its own process, started and stopped here.
BILLING_PID=""
start_billing() {
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar sandbox-billing/target/quarkus-app/quarkus-run.jar > "$OUT/$1-billing.log" 2>&1 &
  BILLING_PID=$!
  # curl's own retry (1 s apart, up to 90) until the health check answers UP
  curl -fsS --retry 90 --retry-delay 1 --retry-connrefused --retry-all-errors     http://localhost:8082/q/health/ready > /dev/null 2>&1 || { log "Billing not ready"; exit 1; }
}
stop_billing() {
  [ -z "$BILLING_PID" ] && return 0
  if [ -r "/proc/$BILLING_PID/winpid" ]; then taskkill //F //PID "$(cat "/proc/$BILLING_PID/winpid")" >/dev/null 2>&1 || true
  else kill "$BILLING_PID" 2>/dev/null || true; fi
  wait "$BILLING_PID" 2>/dev/null || true
  BILLING_PID=""
}
trap stop_billing EXIT

log "Stint pinned: $(grep -o 'Stint `[0-9a-f]*`' README.md | head -1); sandbox $(git rev-parse --short HEAD)"
if [ "$NATIVE" = true ]; then
  # remote-container-build: sources and binary go in and out by docker cp, so no bind mount of C: (8f)
  mvn_ native-build package -Dnative -Dquarkus.native.remote-container-build=true -DskipTests \
    -pl sandbox-orders,sandbox-billing -am
  for m in sandbox-orders sandbox-billing; do # the image App.nativeImage runs: the runner on the micro image
    ctx="$ROOT/$m/target/native-image-ctx"
    rm -rf "$ctx" && mkdir -p "$ctx" && cp "$m"/target/*-runner "$ctx/application"
    cat > "$ctx/Dockerfile" <<'DOCKERFILE'
FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0
WORKDIR /work
COPY --chmod=755 application /work/application
ENTRYPOINT ["./application"]
DOCKERFILE
    log "docker build stint-s4/$m:native"
    docker build -q -t "stint-s4/$m:native" "$ctx" >> "$OUT/native-build.log" 2>&1 || { log "FAILED: image $m"; exit 1; }
  done
  bash infra/reset.sh > "$OUT/reset-native.log" 2>&1
  mvn_ native verify -Pscenarios -pl sandbox-scenarios -Dtest=NativeIT -Dscenarios.apps=native
  cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
  log "PASSED. Evidence and logs: $OUT"
  exit 0
fi
mvn_ build clean install

if [ -z "$ONLY" ]; then
  for run in $(seq 1 "$REPEAT"); do
    log "=== regression run $run of $REPEAT ==="
    bash infra/reset.sh > "$OUT/reset-$run.log" 2>&1
    mvn_ "billing-floci-$run" verify -Pfloci -pl sandbox-billing
    bash infra/reset.sh > "$OUT/reset-$run-b.log" 2>&1
    start_billing "orders-floci-$run"
    mvn_ "orders-floci-$run" verify -Pfloci -pl sandbox-orders
    stop_billing
    mvn_ "regression-$run" verify -Pscenarios -pl sandbox-scenarios -Dtest=RegressionIT -Dscenarios.run="$run"
  done
fi

if [ -n "$ONLY" ]; then
  mvn_ "scenarios" verify -Pscenarios -pl sandbox-scenarios -Dtest="$ONLY" -Dscenarios.orders="$ORDERS"
elif [ "$REGRESSION_ONLY" = false ]; then
  mvn_ "scenarios" verify -Pscenarios -pl sandbox-scenarios -Dtest='ChaosIT,LoadIT,ObservabilityIT' \
    -Dscenarios.orders="$ORDERS"
fi

cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
log "PASSED. Evidence and logs: $OUT"
