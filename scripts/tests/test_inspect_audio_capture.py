"""Synthetic PCM fixtures only. No devices, microphone, playback, or speech generation."""

import array
from contextlib import redirect_stdout
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import wave


spec = importlib.util.spec_from_file_location("inspect_audio_capture", Path(__file__).parents[1] / "inspect-audio-capture.py")
capture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(capture)


class AudioCaptureInspectionTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)

    def wav(self, samples, *, channels=1, rate=1000, width=2):
        path = self.root / "fixture.wav"
        values = array.array("h", samples)
        if sys.byteorder != "little":
            values.byteswap()
        with wave.open(str(path), "wb") as output:
            output.setnchannels(channels)
            output.setsampwidth(width)
            output.setframerate(rate)
            output.writeframes(values.tobytes() if width == 2 else bytes(samples))
        return path

    def cli(self, *args):
        output = io.StringIO()
        with redirect_stdout(output):
            code = capture.main([str(value) for value in args])
        return code, json.loads(output.getvalue())

    def test_zero_pcm_is_not_a_successful_signal_gate(self):
        path = self.wav([0] * 200)
        code, report = self.cli(path, "--require-nonzero")
        self.assertEqual(1, code)
        self.assertEqual("ALL_ZERO_PCM", report["status"])
        self.assertEqual(0, report["nonzero_samples"])
        self.assertEqual([], report["activity_intervals_seconds"])
        self.assertIsNone(report["rms_dbfs"])
        self.assertIsNone(report["first_nonzero_frame"])

    def test_stereo_positions_are_frames_not_interleaved_samples(self):
        path = self.wav([0, 0, 0, 16384, -16384, 0, 0, 0], channels=2)
        report = capture.inspect_wav(path)
        self.assertEqual(4, report["frames"])
        self.assertEqual(1, report["first_nonzero_frame"])
        self.assertEqual(2, report["last_nonzero_frame"])
        self.assertEqual(2, report["nonzero_samples"])
        self.assertEqual(8192, report["rms_int16"])
        self.assertAlmostEqual(20 * math.log10(0.25), report["rms_dbfs"])
        self.assertFalse(report["speech_presence_verified"])
        self.assertFalse(report["audio_quality_verified"])

    def test_activity_ranges_merge_adjacent_windows_but_keep_silence(self):
        path = self.wav([0] * 20 + [100] * 40 + [0] * 20 + [100] * 5)
        report = capture.inspect_wav(path)
        self.assertEqual([[0.02, 0.06], [0.08, 0.085]], report["activity_intervals_seconds"])
        self.assertEqual(0.085, report["duration_seconds"])

    def test_nonzero_below_energy_threshold_is_not_called_speech(self):
        path = self.wav([1] * 100)
        code, report = self.cli(path, "--require-nonzero")
        self.assertEqual(0, code)
        self.assertEqual("NONZERO_PCM", report["status"])
        self.assertEqual([], report["activity_intervals_seconds"])
        self.assertFalse(report["physical_audibility_verified"])

    def test_empty_pcm_does_not_pass_gate(self):
        code, report = self.cli(self.wav([]), "--require-nonzero")
        self.assertEqual(1, code)
        self.assertEqual("EMPTY_PCM", report["status"])

    def test_truncated_data_is_rejected_instead_of_reporting_short_silence(self):
        path = self.wav([0] * 200)
        path.write_bytes(path.read_bytes()[:-2])
        code, report = self.cli(path)
        self.assertEqual(2, code)
        self.assertIn("truncated", report["reason"])

    def test_partial_frame_is_rejected(self):
        path = self.wav([0] * 200, channels=2)
        path.write_bytes(path.read_bytes()[:-1])
        self.assertEqual(2, self.cli(path)[0])

    def test_chunk_outside_riff_bounds_is_rejected_without_traceback(self):
        path = self.root / "bad-chunk.wav"
        path.write_bytes(b"RIFF" + struct.pack("<I", 12) + b"WAVEJUNK" + struct.pack("<I", 8))
        code, report = self.cli(path)
        self.assertEqual(2, code)
        self.assertEqual("INVALID_OR_UNSUPPORTED_CAPTURE", report["status"])

    def test_unsupported_sample_width_is_rejected(self):
        self.assertEqual(2, self.cli(self.wav([128] * 20, width=1))[0])

    def test_non_wav_missing_directory_and_oversize_inputs_are_rejected(self):
        path = self.root / "not-a-wave.wav"
        path.write_bytes(b"not a wave file")
        for candidate in [path, self.root / "missing.wav", self.root]:
            with self.subTest(candidate=candidate.name):
                self.assertEqual(2, self.cli(candidate)[0])
        valid = self.wav([0] * 100)
        with patch.object(capture, "MAX_FILE_BYTES", 10):
            self.assertEqual(2, self.cli(valid)[0])

    def test_input_bytes_are_preserved_and_hash_is_bound_to_report(self):
        path = self.wav([0, 100, -100, 0] * 20)
        before = path.read_bytes()
        report = capture.inspect_wav(path)
        self.assertEqual(before, path.read_bytes())
        self.assertEqual(hashlib.sha256(before).hexdigest(), report["sha256"])
        self.assertEqual(path.name, report["file"])


if __name__ == "__main__":
    unittest.main()
