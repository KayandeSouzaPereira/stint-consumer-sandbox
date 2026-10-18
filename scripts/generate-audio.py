#!/usr/bin/env python3
"""SDD S.5, 8d: copied from Stint 34d7ac7, examples/stint-example-product-meeting/scripts/generate-audio.py (SDD 3a.6),
unchanged except for this header and --verify. The sandbox depends on no Stint example code, so it keeps its own copy.
It reads corpus format v2 as it reads v1 (the new fields are ignored).

--verify regenerates every meeting into a temporary directory and compares the hashes with the manifest, without
writing anything: the audio is reproducible from the manifest (CA4).

Original text (3a.6, 8c): generates the synthetic meeting audio from the corpus scripts with Kokoro-82M (Apache-2.0) through
kokoro-onnx (MIT), on CPU, and records every file's SHA-256 in corpus/manifest.json.

Runs outside the Maven build (WSL2, Python 3.11+):
    pip install kokoro-onnx==0.4.9 soundfile pyyaml
    # model files (Apache-2.0), next to this script or in KOKORO_HOME:
    #   kokoro-v1.0.onnx and voices-v1.0.bin from the kokoro-onnx release assets
    python scripts/generate-audio.py corpus

Each meeting becomes corpus/audio/<id>.wav: 16 kHz mono, one line per speaker's turn with 0.6 s pauses. The audio
directory is git-ignored; the manifest keeps the hashes, the engine, its version and the voices, so a regenerated file
is checked against them. Synthetic data only.
"""
import hashlib
import json
import os
import sys
from pathlib import Path

import numpy as np
import soundfile as sf
import yaml
from kokoro_onnx import Kokoro

RATE = 16000
PAUSE_SECONDS = 0.6


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resample(samples: np.ndarray, source_rate: int) -> np.ndarray:
    if source_rate == RATE:
        return samples
    positions = np.linspace(0, len(samples) - 1, int(len(samples) * RATE / source_rate))
    return np.interp(positions, np.arange(len(samples)), samples).astype(np.float32)


def main(corpus: Path, verify: bool = False) -> None:
    home = Path(os.environ.get("KOKORO_HOME", Path(__file__).parent))
    kokoro = Kokoro(str(home / "kokoro-v1.0.onnx"), str(home / "voices-v1.0.bin"))
    manifest_path = corpus / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    audio_dir = Path(__import__("tempfile").mkdtemp()) if verify else corpus / "audio"
    audio_dir.mkdir(exist_ok=True)
    mismatches = 0
    for entry in manifest["meetings"]:
        script_path = corpus / entry["script"]
        if sha256(script_path) != entry["scriptSha256"]:
            sys.exit(f"{script_path} does not match the manifest: update the manifest first")
        meeting = yaml.safe_load(script_path.read_text(encoding="utf-8"))
        voices = {s["id"]: s["voice"] for s in meeting["speakers"]}
        parts = []
        for line in meeting["script"]:
            samples, rate = kokoro.create(line["text"], voice=voices[line["speaker"]], speed=1.0, lang="en-us")
            parts.append(resample(np.asarray(samples, dtype=np.float32), rate))
            parts.append(np.zeros(int(RATE * PAUSE_SECONDS), dtype=np.float32))
        audio = np.concatenate(parts)
        out = audio_dir / f"{meeting['id']}.wav"
        sf.write(out, audio, RATE, subtype="PCM_16")
        if verify:
            same = entry.get("audio", {}).get("sha256") == sha256(out)
            mismatches += 0 if same else 1
            print(f"{meeting['id']}: {'same hash' if same else 'DIFFERENT hash'} {sha256(out)}")
            continue
        entry["audio"] = {"file": f"audio/{out.name}", "sha256": sha256(out), "seconds": round(len(audio) / RATE, 2),
                          "rate": RATE, "engine": "kokoro-onnx", "engineVersion": "0.4.9",
                          "model": "kokoro-v1.0", "voices": sorted(set(voices.values()))}
        print(f"{meeting['id']}: {entry['audio']['seconds']} s")
    if verify:
        sys.exit(1 if mismatches else 0)
    manifest_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if a != "--verify"]
    main(Path(args[0] if args else "corpus"), "--verify" in sys.argv)
