#!/usr/bin/env python3
"""Inspect an existing PCM16 WAV without recording, playback, filtering, or modification.

Exit 0 means inspection completed, never that speech or audio quality passed.
With --require-nonzero, empty/all-zero PCM exits 1. Invalid/unsupported input exits 2.
"""

from __future__ import annotations

import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import stat
import sys
import wave


MAX_FILE_BYTES = 512 * 1024 * 1024
ACTIVITY_WINDOW_MS = 20
ACTIVITY_THRESHOLD_DBFS = -60


class InvalidCapture(ValueError):
    pass


def inspect_wav(path: Path) -> dict:
    before = path.stat()
    if not stat.S_ISREG(before.st_mode):
        raise InvalidCapture("Input must be a regular file")
    if before.st_size > MAX_FILE_BYTES:
        raise InvalidCapture("Input exceeds the 512 MiB inspection limit")

    total_samples = total_frames = nonzero_samples = squared_sum = peak = 0
    first_nonzero_frame = last_nonzero_frame = None
    active_frame_ranges: list[list[int]] = []
    with path.open("rb") as source, wave.open(source, "rb") as audio:
        channels, rate = audio.getnchannels(), audio.getframerate()
        declared_frames = audio.getnframes()
        if audio.getcomptype() != "NONE" or audio.getsampwidth() != 2:
            raise InvalidCapture("Only uncompressed signed 16-bit little-endian PCM WAV is supported")
        if not 1 <= channels <= 32 or not 1 <= rate <= 384_000:
            raise InvalidCapture("Unsupported channel count or sample rate")
        window_frames = max(1, rate * ACTIVITY_WINDOW_MS // 1000)
        frame_bytes = channels * 2
        while data := audio.readframes(window_frames):
            if len(data) % frame_bytes:
                raise InvalidCapture("PCM data ends inside a frame")
            block = array.array("h", data)
            if sys.byteorder != "little":
                block.byteswap()
            frames = len(block) // channels
            block_squared_sum = 0
            for index, sample in enumerate(block):
                block_squared_sum += sample * sample
                peak = max(peak, abs(sample))
                if sample:
                    nonzero_samples += 1
                    frame = total_frames + index // channels
                    if first_nonzero_frame is None:
                        first_nonzero_frame = frame
                    last_nonzero_frame = frame
            # This is a declared signal-energy threshold, not voice/activity recognition.
            if block_squared_sum / len(block) > (32768 * 10 ** (ACTIVITY_THRESHOLD_DBFS / 20)) ** 2:
                end = total_frames + frames
                if active_frame_ranges and active_frame_ranges[-1][1] == total_frames:
                    active_frame_ranges[-1][1] = end
                else:
                    active_frame_ranges.append([total_frames, end])
            total_samples += len(block)
            squared_sum += block_squared_sum
            total_frames += frames
        if total_frames != declared_frames:
            raise InvalidCapture("PCM data is truncated relative to its WAV header")

    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    after = path.stat()
    if (before.st_size, before.st_mtime_ns, before.st_ino) != (after.st_size, after.st_mtime_ns, after.st_ino):
        raise InvalidCapture("Input changed during inspection; inspect only a finished capture")
    rms = math.sqrt(squared_sum / total_samples) if total_samples else 0.0
    return {
        "schema_version": 1,
        "file": path.name,
        "sha256": digest.hexdigest(),
        "size_bytes": after.st_size,
        "status": "EMPTY_PCM" if not total_samples else "NONZERO_PCM" if nonzero_samples else "ALL_ZERO_PCM",
        "sample_format": "signed PCM16 little-endian",
        "sample_rate_hz": rate,
        "channels": channels,
        "frames": total_frames,
        "duration_seconds": total_frames / rate,
        "nonzero_samples": nonzero_samples,
        "first_nonzero_frame": first_nonzero_frame,
        "last_nonzero_frame": last_nonzero_frame,
        "first_nonzero_seconds": first_nonzero_frame / rate if first_nonzero_frame is not None else None,
        "last_nonzero_seconds": last_nonzero_frame / rate if last_nonzero_frame is not None else None,
        "rms_int16": rms,
        "rms_dbfs": 20 * math.log10(rms / 32768) if rms else None,
        "peak_int16": peak,
        "activity_window_ms": ACTIVITY_WINDOW_MS,
        "activity_threshold_dbfs": ACTIVITY_THRESHOLD_DBFS,
        "activity_intervals_seconds": [[start / rate, end / rate] for start, end in active_frame_ranges],
        "speech_presence_verified": False,
        "physical_audibility_verified": False,
        "audio_quality_verified": False,
        "limitations": [
            "Nonzero PCM may be noise, a click, or other audio; it does not identify speech or TTS.",
            "All-zero PCM does not identify whether the app, routing, or capture path caused the silence.",
            "Frame positions are relative to this file, not measured input-to-speech latency.",
        ],
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav", type=Path)
    parser.add_argument("--require-nonzero", action="store_true", help="Exit 1 for empty/all-zero PCM; never verifies speech or quality")
    args = parser.parse_args(argv)
    try:
        result = inspect_wav(args.wav)
    except (InvalidCapture, OSError, EOFError, wave.Error, OverflowError, RuntimeError) as error:
        result = {"schema_version": 1, "file": args.wav.name, "status": "INVALID_OR_UNSUPPORTED_CAPTURE", "error_class": type(error).__name__}
        if isinstance(error, InvalidCapture):
            result["reason"] = str(error)
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 1 if args.require_nonzero and result["status"] != "NONZERO_PCM" else 0


if __name__ == "__main__":
    raise SystemExit(main())
