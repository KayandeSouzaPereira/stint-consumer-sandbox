#!/usr/bin/env python3
"""SDD S.6, 8e: transcribes the corpus audio with the local Whisper server (scripts/whisper-server.py, loopback only) and
keeps what it wrote as fixtures in corpus/transcripts/<id>.json, so the PII filter is tested on how speech-to-text
actually writes the planted data, without a model or Docker in the build.

Each audio is transcribed TWICE and the two texts are compared: the fixture records whether the engine repeated itself
(`repeatable`). The fixture also keeps the audio's hash (from the manifest), the model and the server's engine, so a stale
fixture is detectable. Synthetic data only.

    python scripts/transcribe-corpus.py corpus [--server http://127.0.0.1:8001] [--model Systran/faster-distil-whisper-small.en]
"""
import hashlib
import json
import sys
import urllib.request
import uuid
from pathlib import Path


def transcribe(server: str, model: str, wav: bytes) -> dict:
    boundary = uuid.uuid4().hex
    parts = []
    for name, value in (("model", model), ("language", "en"), ("response_format", "verbose_json")):
        parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="a.wav"\r\n'
                 f'Content-Type: audio/wav\r\n\r\n'.encode() + wav + b"\r\n")
    parts.append(f"--{boundary}--\r\n".encode())
    request = urllib.request.Request(server + "/v1/audio/transcriptions", data=b"".join(parts),
                                     headers={"Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(request, timeout=1200) as response:
        return json.loads(response.read())


def main(corpus: Path, server: str, model: str) -> None:
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    out_dir = corpus / "transcripts"
    out_dir.mkdir(exist_ok=True)
    unstable = []
    for entry in manifest["meetings"]:
        wav = (corpus / entry["audio"]["file"]).read_bytes()
        digest = hashlib.sha256(wav).hexdigest()
        if digest != entry["audio"]["sha256"]:
            sys.exit(f"{entry['id']}: the audio does not match the manifest (run generate-audio.py --verify)")
        first = transcribe(server, model, wav)
        second = transcribe(server, model, wav)
        repeatable = first["text"] == second["text"]
        if not repeatable:
            unstable.append(entry["id"])
        fixture = {"meetingId": entry["id"], "audioSha256": digest, "model": model, "language": first.get("language"),
                   "durationSeconds": first.get("duration"), "repeatable": repeatable, "text": first["text"],
                   "segments": [{"start": s["start"], "end": s["end"], "text": s["text"]} for s in first["segments"]]}
        (out_dir / f"{entry['id']}.json").write_text(json.dumps(fixture, indent=2, ensure_ascii=False) + "\n",
                                                     encoding="utf-8")
        print(f"{entry['id']}: {len(first['segments'])} segments, repeatable={repeatable}")
    print("not repeatable:", unstable or "none")


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument("corpus", nargs="?", default="corpus")
    parser.add_argument("--server", default="http://127.0.0.1:8001")
    parser.add_argument("--model", default="Systran/faster-distil-whisper-small.en")
    opts = parser.parse_args()
    main(Path(opts.corpus), opts.server, opts.model)
