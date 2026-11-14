#!/usr/bin/env python3
"""Private real-microphone bridge. No capture without explicit --record-microphone.

Preserves original PCM/WAV and host read-arrival times, not exact microphone PTS.
Does not change VM settings, synthesize speech, inject text, or play audio.
Each authorized recording defaults to 300 seconds and cannot exceed 1800 seconds.
Blocking input is the default; realtime input may overwrite unconsumed older input.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import queue
import select
import signal
import subprocess
import threading
import time
import wave

from stream_output import credentials, messages
import grpc

CHUNK_BYTES = 640  # 20ms * 16000 samples/s * 2 bytes/sample.


class PcmFrames:
    def __init__(self):
        self.tail = bytearray()

    def feed(self, block):
        self.tail.extend(block)
        chunks = []
        while len(self.tail) >= CHUNK_BYTES:
            chunks.append(bytes(self.tail[:CHUNK_BYTES]))
            del self.tail[:CHUNK_BYTES]
        return chunks

    def finish(self):
        tail = bytes(self.tail)
        self.tail.clear()
        return tail


def schema(proto):
    text = proto.read_text()
    required = ('Mono = 0;', 'MODE_UNSPECIFIED = 0;', 'MODE_REAL_TIME = 1;', 'DeliveryMode mode = 4;',
                'rpc injectAudio(stream AudioPacket) returns (google.protobuf.Empty) {}')
    if not all(item in text for item in required):
        raise ValueError('Installed input-audio proto differs from the reviewed contract')
    return messages(proto)


def self_test(proto):
    AudioFormat, AudioPacket = schema(proto)
    original = bytes(range(256)) * 8 + b'\x00'
    frames = PcmFrames()
    chunks = []
    position = 0
    for size in (1, 3, 217, 640, 19, 2, 933, 234):
        chunks += frames.feed(original[position:position + size])
        position += size
    chunks += frames.feed(original[position:])
    tail = frames.finish()
    assert all(len(chunk) == CHUNK_BYTES for chunk in chunks)
    assert b''.join(chunks) + tail == original
    assert len(tail) % 2 == 1 and frames.finish() == b''
    silent = PcmFrames()
    assert silent.feed(bytes(CHUNK_BYTES * 2)) == [bytes(CHUNK_BYTES), bytes(CHUNK_BYTES)]
    assert silent.feed(b'') == [] and silent.finish() == b''
    assert PcmFrames().finish() == b''
    for mode in (0, 1):
        packet = AudioPacket(format=AudioFormat(samplingRate=16000, channels=0, format=1, mode=mode),
                             timestamp=1234567, audio=bytes(CHUNK_BYTES))
        restored = AudioPacket.FromString(packet.SerializeToString())
        assert restored.format.samplingRate == 16000 and restored.format.channels == 0
        assert restored.format.format == 1 and restored.format.mode == mode
        assert restored.audio == bytes(CHUNK_BYTES) and restored.timestamp == 1234567
    print('MIC_SCHEMA_PARTIAL_READ_SILENCE_AND_EOF_SELF_TEST_OK (no network or microphone)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--discovery', type=Path)
    parser.add_argument('--port', type=int)
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--proto', type=Path, default=Path.home() / 'Library/Android/sdk/emulator/lib/emulator_controller.proto')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--seconds', type=int, default=300, help='Explicit capture limit: 1–1800 seconds; default 300')
    parser.add_argument('--input-mode', choices=('blocking', 'realtime'), default='blocking',
                        help='blocking=mode0; realtime=mode1 may overwrite unconsumed old input. Both retain original PCM')
    parser.add_argument('--microphone', default='MacBook Pro 마이크')
    parser.add_argument('--ffmpeg', default='/opt/homebrew/bin/ffmpeg')
    parser.add_argument('--record-microphone', action='store_true', help='Explicitly start and privately retain real microphone audio')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test(args.proto)
        return
    if not args.record_microphone:
        parser.error('Capture is disabled. --record-microphone requires an explicit user start instruction')
    if args.output is None or not 1 <= args.seconds <= 1800:
        parser.error('Use a new --output directory and --seconds between 1 and 1800')
    AudioFormat, AudioPacket = schema(args.proto)
    delivery_mode = 0 if args.input_mode == 'blocking' else 1
    wanted = AudioFormat(samplingRate=16000, channels=0, format=1, mode=delivery_mode)
    port, metadata = credentials(args)
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    stop = threading.Event()
    reader_done = threading.Event()
    pending = queue.Queue(maxsize=100)  # At most two seconds of unsent framed audio.
    child = None
    worker = None
    call = None
    capture_log = None
    started = None
    reason = {'value': None}
    report = dict(status='STARTING', started_at=datetime.now(timezone.utc).isoformat(),
                  source='real host microphone via ffmpeg avfoundation', original_recording_retained=True,
                  private_recordings=['microphone-original.pcm', 'microphone-original.wav'],
                  format='S16LE mono 16000Hz', frame_bytes=CHUNK_BYTES, frame_ms=20,
                  input_mode=args.input_mode, delivery_mode=delivery_mode, maximum_seconds=args.seconds,
                  input_mode_semantics=('Wait for guest consumption; may accumulate old input and reach backpressure bound'
                                        if args.input_mode == 'blocking' else
                                        'Experimental SDK realtime mode: unconsumed old input may be overwritten to keep new input; original raw file retained'),
                  input_delivery_clock='Native ffmpeg live PCM arrival; 640-byte framing; no synthetic samples or extra replay pacing',
                  realtime_requires_short_integration_pilot=args.input_mode == 'realtime',
                  inject_audio_integration_verified=False, packets_yielded_to_grpc=0,
                  received_bytes=0, complete_frames=0, trailing_bytes_not_injected=0,
                  audio_padding_or_gain_applied=False, text_injection=False, playback=False,
                  timestamp_semantics='Host pipe-read completion/arrival Unix microseconds; not exact microphone capture PTS',
                  arrival_log_semantics='Multiple 20ms frames from one pipe read share its arrival time',
                  auth='localhost Bearer; value kept in memory and never logged',
                  sdk_stt_recognition_verified=False,
                  proto_sha256=hashlib.sha256(args.proto.read_bytes()).hexdigest())
    channel = grpc.insecure_channel('127.0.0.1:' + str(port))

    def halt(value):
        if reason['value'] is None:
            reason['value'] = value
        stop.set()

    def signal_stop(signum, _frame):
        halt('SIGNAL_' + str(signum))

    def reader():
        framing = PcmFrames()
        digest = hashlib.sha256()
        try:
            with (args.output / 'microphone-original.pcm').open('xb') as pcm, \
                    wave.open(str(args.output / 'microphone-original.wav'), 'wb') as wav, \
                    (args.output / 'chunk-arrivals.private.jsonl').open('x') as arrivals:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(16000)
                while True:
                    ready, _, _ = select.select([child.stdout], [], [], 0.1)
                    if not ready:
                        if child.poll() is not None:
                            break
                        continue
                    block = os.read(child.stdout.fileno(), 8192)
                    arrived_ns = time.time_ns()
                    monotonic_ns = time.monotonic_ns()
                    if not block:
                        if not stop.is_set():
                            halt('MAXIMUM_DURATION' if report['received_bytes'] >= args.seconds * 32000 else 'MICROPHONE_EOF')
                        break
                    pcm.write(block)
                    digest.update(block)
                    report['received_bytes'] += len(block)
                    for chunk in framing.feed(block):
                        index = report['complete_frames']
                        wav.writeframesraw(chunk)
                        arrivals.write(json.dumps(dict(index=index, sample_offset=index * 320,
                                                       arrival_unix_us=arrived_ns // 1000,
                                                       arrival_monotonic_ns=monotonic_ns, bytes=len(chunk))) + '\n')
                        report['complete_frames'] += 1
                        if not stop.is_set():
                            try:
                                pending.put((chunk, arrived_ns // 1000), timeout=0.5)
                            except queue.Full:
                                halt('INJECTION_BACKPRESSURE')
                tail = framing.finish()
                # Keep the exact source PCM. WAV stores only complete 16-bit samples; no invented silence.
                wav.writeframesraw(tail[:len(tail) // 2 * 2])
                report['trailing_bytes_not_injected'] = len(tail)
                report['wav_omitted_incomplete_sample_bytes'] = len(tail) % 2
                report['pcm_sha256'] = digest.hexdigest()
        except Exception as error:
            report['reader_error_class'] = type(error).__name__
            halt('MICROPHONE_READ_FAILED')
        finally:
            reader_done.set()

    def requests():
        while not reader_done.is_set() or not pending.empty():
            try:
                chunk, arrival_us = pending.get(timeout=0.1)
            except queue.Empty:
                continue
            report['packets_yielded_to_grpc'] += 1
            yield AudioPacket(format=wanted, timestamp=arrival_us, audio=chunk)

    old_handlers = {sig: signal.signal(sig, signal_stop) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        grpc.channel_ready_future(channel).result(timeout=5)
        channel.unary_unary('/android.emulation.control.EmulatorController/getStatus')(b'', metadata=metadata, timeout=5)
        report['authenticated_get_status'] = True
        if stop.is_set():
            raise RuntimeError('Capture cancelled before microphone start')
        capture_log = (args.output / 'ffmpeg.private.log').open('xb')
        command = [args.ffmpeg, '-nostdin', '-hide_banner', '-loglevel', 'warning', '-f', 'avfoundation',
                   '-i', ':' + args.microphone, '-ac', '1', '-ar', '16000', '-t', str(args.seconds),
                   '-f', 's16le', 'pipe:1']
        if stop.is_set():
            raise RuntimeError('Capture cancelled before microphone start')
        started = time.monotonic()
        child = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                 stderr=capture_log, bufsize=0)
        report['status'] = 'CAPTURING_PRIVATE_ORIGINAL'
        print('MICROPHONE_CAPTURE_STARTED: original PCM/WAV retained privately; maximum '
              + str(args.seconds) + ' seconds; input mode ' + args.input_mode, flush=True)
        worker = threading.Thread(target=reader, name='private-microphone-reader', daemon=True)
        worker.start()
        call = channel.stream_unary('/android.emulation.control.EmulatorController/injectAudio',
                                    request_serializer=lambda packet: packet.SerializeToString()).future(
                                        requests(), metadata=metadata, timeout=args.seconds + 15)
        while not call.done() and not stop.wait(0.05):
            elapsed = time.monotonic() - started
            if elapsed >= args.seconds:
                halt('MAXIMUM_DURATION')
            elif elapsed > 10 and report['received_bytes'] == 0:
                halt('MICROPHONE_STARTUP_TIMEOUT')
        if call.done() and not stop.is_set():
            halt('RPC_ENDED')
    except Exception as error:
        report['error_class'] = type(error).__name__
        if isinstance(error, grpc.RpcError):
            report['grpc_code'] = error.code().name
        halt('START_OR_RPC_FAILED')
    finally:
        stop.set()
        if child is not None and child.poll() is None:
            try:
                child.send_signal(signal.SIGINT)
            except ProcessLookupError:
                pass
            try:
                child.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    child.terminate()
                except ProcessLookupError:
                    pass
                try:
                    child.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    try:
                        child.kill()
                    except ProcessLookupError:
                        pass
                    child.wait(timeout=2)
        if worker is not None:
            worker.join(timeout=3)
            report['reader_stopped'] = not worker.is_alive()
        if call is not None:
            try:
                call.result(timeout=3)
                report['inject_stream_completed_ok'] = True
            except Exception as error:
                report['inject_stream_completed_ok'] = False
                report['rpc_completion_class'] = type(error).__name__
                if isinstance(error, grpc.RpcError):
                    report['grpc_code'] = error.code().name
                call.cancel()
        channel.close()
        if child is not None:
            report['ffmpeg_exit_code'] = child.returncode
            child.stdout.close()
        if capture_log is not None:
            capture_log.close()
        for sig, handler in old_handlers.items():
            signal.signal(sig, handler)
        report['stop_reason'] = reason['value']
        report['bytes_not_yielded_to_grpc'] = report['received_bytes'] - report['packets_yielded_to_grpc'] * CHUNK_BYTES
        report['finished_at'] = datetime.now(timezone.utc).isoformat()
        report['elapsed_seconds'] = None if started is None else time.monotonic() - started
        expected_stop = reason['value'] in ('MAXIMUM_DURATION', 'SIGNAL_2', 'SIGNAL_15')
        report['status'] = ('STOPPED' if expected_stop and report['received_bytes'] > 0 and
                            report.get('inject_stream_completed_ok') and report.get('reader_stopped') else 'FAILED')
        (args.output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({key: report[key] for key in ('status', 'stop_reason', 'received_bytes', 'packets_yielded_to_grpc')}), flush=True)
    if report['status'] == 'FAILED':
        raise SystemExit(1)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print credential-bearing gRPC details, metadata, arguments, or discovery contents.
        print('MICROPHONE_BRIDGE_FAILED: ' + type(error).__name__, flush=True)
        raise SystemExit(1)
