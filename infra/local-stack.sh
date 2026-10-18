#!/usr/bin/env bash
# SDD S.5, RF6/CA7: starts or stops the local model stack of the manual run, in WSL2, on loopback only (127.0.0.1):
#   Whisper (faster-whisper distil-small.en, CPU)        :8001   scripts/whisper-server.py
#   LitJev  (Qwen/Qwen3.5-2B, fp16, the GPU)             :8000
#   llama.cpp (Qwen3-4B-Instruct-2507 Q4_K_M, CPU)       :8080
# Load order is the GPU plan of S.5 8e: Whisper (CPU) -> LitJev (GPU) -> generator (CPU). Synthetic data only, no key.
# Paths (E: holds the caches; C: has little room): override STACK, LITJEV, SANDBOX, LOGS for another layout.
#   bash infra/local-stack.sh up | down | status
# Run it from Git Bash on Windows (it calls WSL); from inside WSL, run the `wsl_` body directly.
set -euo pipefail
export MSYS_NO_PATHCONV=1

STACK="${STACK:-/mnt/e/stint-3a6}"
LITJEV="${LITJEV:-/mnt/e/stint-local-decision/litjev}"
SANDBOX="${SANDBOX:-/mnt/c/dev/stint-consumer-sandbox}"
LOGS="${LOGS:-/mnt/e/stint-s5/logs}"
REVISION=15852e8c16360a2fea060d615a32b45270f8a8fc

wsl_() { wsl -d Ubuntu -- bash -lc "$1"; }

up() {
  wsl_ "mkdir -p $LOGS
    nohup $STACK/whisper/.venv/bin/python $SANDBOX/scripts/whisper-server.py --model Systran/faster-distil-whisper-small.en \
      --device cpu --threads 6 --port 8001 > $LOGS/whisper.log 2>&1 &
    cd $LITJEV && HF_HOME=/mnt/e/stint-local-decision/hf UV_CACHE_DIR=/mnt/e/stint-local-decision/uv \
      nohup uv run --locked --python 3.13 litjev --model Qwen/Qwen3.5-2B --revision $REVISION --dtype float16 --port 8000 \
      > $LOGS/litjev.log 2>&1 &
    cd $STACK/llama/bin-b11658/llama-b11658 && LD_LIBRARY_PATH=.:$LITJEV/.venv/lib/python3.13/site-packages/torch/lib \
      nohup ./llama-server -m $STACK/llama/models/Qwen3-4B-Instruct-2507-Q4_K_M.gguf --host 127.0.0.1 --port 8080 -c 8192 \
      --threads 6 --alias qwen3-4b-instruct-2507-q4_k_m > $LOGS/llama.log 2>&1 &
    sleep 1; echo started"
}

down() {
  wsl_ "pkill -f whisper-server.py; pkill -f 'litjev --model'; pkill -f llama-server; sleep 1; echo stopped" || true
}

status() {
  for p in 8001:/ 8000:/v1/models 8080:/health; do
    code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:${p%%:*}${p#*:}" || true)
    echo "127.0.0.1:${p%%:*}  ${code:-no answer}"
  done
}

case "${1:-status}" in
  up) up ;;
  down) down ;;
  status) status ;;
  *) echo "usage: bash infra/local-stack.sh up|down|status" >&2; exit 2 ;;
esac
