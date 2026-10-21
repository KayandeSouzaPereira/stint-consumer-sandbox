#!/usr/bin/env bash
# SDD S.4, 8c: the Phase 2 gate evidence in one command. It only sequences Maven runs; every assertion is in a test.
#
#   bash infra/gate.sh                      regression (RF1) once, then chaos, load and observability (RF2, RF3, RF5)
#   bash infra/gate.sh --regression-only --repeat 3     CA2: the regression three times in a row
#   bash infra/gate.sh --regression-only --skeleton 3   SDD S.11 CA1: the 3a regression (portal roles, PII sweep) once and
#                                           the pipeline skeleton three times in a row (default 3; 0 skips it)
#   bash infra/gate.sh --orders 100         the load scenario with N orders (default 50)
#   bash infra/gate.sh --native3a           SDD S.11 RF6 (8e): the 3a domains as native images, one at a time (privacy, business,
#                                           records, channels); a build that fails is a finding, not a stop; p1's cycle
#                                           then runs with the ones that built as images and the rest on the JVM
#   bash infra/gate.sh --projects 6         SDD S.11: the 3a chaos and load (PipelineChaosIT, PipelineLoadIT) with M
#                                           simultaneous projects (default 6); also run by the default invocation
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
SKELETON=3
ORDERS=50
PROJECTS=6
REGRESSION_ONLY=false
NATIVE=false
NATIVE3A=false
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --repeat) REPEAT="$2"; shift 2 ;;
    --skeleton) SKELETON="$2"; shift 2 ;;
    --orders) ORDERS="$2"; shift 2 ;;
    --projects) PROJECTS="$2"; shift 2 ;;
    --regression-only) REGRESSION_ONLY=true; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    --native) NATIVE=true; shift ;;
    --native3a) NATIVE3A=true; shift ;;
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
if [ "$NATIVE3A" = true ]; then
  built=""
  for m in sandbox-privacy sandbox-business sandbox-records sandbox-channels; do
    log "native build of $m (log $OUT/native-build-$m.log)"
    ctx="$ROOT/$m/target/native-image-ctx"
    if "$MVN" package -Dnative -Dquarkus.native.remote-container-build=true -DskipTests -pl "$m" -am         > "$OUT/native-build-$m.log" 2>&1       && rm -rf "$ctx" && mkdir -p "$ctx" && cp "$m"/target/*-runner "$ctx/application"       && printf 'FROM quay.io/quarkus/ubi9-quarkus-micro-image:2.0
WORKDIR /work
COPY --chmod=755 application /work/application
ENTRYPOINT ["./application"]
' > "$ctx/Dockerfile"       && docker build -q -t "stint-s4/$m:native" "$ctx" >> "$OUT/native-build-$m.log" 2>&1; then
      built="$built,$m"
      log "native OK: $m"
    else
      log "native FAILED: $m (a finding; see $OUT/native-build-$m.log)"
    fi
  done
  if [ -n "$built" ]; then
    bash infra/reset.sh > "$OUT/reset-native3a.log" 2>&1
    mvn_ native3a verify -Pscenarios -pl sandbox-scenarios -Dtest=PipelineNativeIT -Dscenarios.native.modules="${built#,}"
    cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
  fi
  log "DONE. Native images that built: ${built#,}. Evidence and logs: $OUT"
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
  # SDD S.11, RF1: the 3a regression (S.5 to S.10). The portal roles and the sweeper's positive control once, the
  # pipeline skeleton as many times as asked; each run on a fresh floci. The evidence is copied after each stage, before
  # anything else can clean the target directory (S.10 finding S10-4).
  if [ "$SKELETON" -gt 0 ]; then
    bash infra/reset.sh > "$OUT/reset-3a.log" 2>&1
    mvn_ regression-3a verify -Pscenarios -pl sandbox-scenarios -Dtest='PortalRolesIT,PiiSweepIT'
    cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
    for run in $(seq 1 "$SKELETON"); do
      log "=== pipeline skeleton run $run of $SKELETON ==="
      bash infra/reset.sh > "$OUT/reset-skeleton-$run.log" 2>&1
      mvn_ "skeleton-$run" verify -Pscenarios -pl sandbox-scenarios -Dtest=PipelineSkeletonIT
      cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
    done
  fi
fi

if [ -n "$ONLY" ]; then
  mvn_ "scenarios" verify -Pscenarios -pl sandbox-scenarios -Dtest="$ONLY" -Dscenarios.orders="$ORDERS"
elif [ "$REGRESSION_ONLY" = false ]; then
  mvn_ "scenarios" verify -Pscenarios -pl sandbox-scenarios -Dtest='ChaosIT,LoadIT,ObservabilityIT' \
    -Dscenarios.orders="$ORDERS"
  cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
  # SDD S.11, RF2 to RF5: the 3a pipeline under kills, then under M simultaneous projects (each on a fresh floci)
  for scenario in PipelineChaosIT PipelineLoadIT; do
    bash infra/reset.sh > "$OUT/reset-$scenario.log" 2>&1
    mvn_ "$scenario" verify -Pscenarios -pl sandbox-scenarios -Dtest="$scenario" -Dscenarios.projects="$PROJECTS"
    cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
  done
fi

cp sandbox-scenarios/target/scenarios/*.txt "$OUT/" 2>/dev/null || true
log "PASSED. Evidence and logs: $OUT"
