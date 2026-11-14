#!/usr/bin/env python3
"""Send synthetic PCM files to one emulator; never open a physical microphone.

A JSON queue entry contains request_id and audio_file (relative to --input-root).
Create entries by atomic rename into --queue. Leave the RPC open between utterances:
the sender supplies silence until the next entry. There is no host audio playback.
SENT means the bytes were yielded to gRPC, not that speech recognition succeeded.
"""
import argparse
from collections import deque
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import threading
import time
import wave

import grpc

from stream_output import credentials, messages, session_seconds, tick_deadline

RATE = 16000
CHUNK_BYTES = 640
PERIOD_NS = 20_000_000
REQUEST_ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,95}\Z")


def load_audio(root, relative):
    if not isinstance(relative, str) or not relative or Path(relative).is_absolute():
        raise ValueError('audio_file must be a relative PCM or WAV path')
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError('audio_file must exist inside input-root')
    if path.stat().st_size > RATE * 2 * 30 + 65536:
        raise ValueError('Each utterance is limited to 30 seconds')
    if path.suffix.lower() == '.wav':
        with wave.open(str(path), 'rb') as source:
            if (source.getframerate(), source.getnchannels(), source.getsampwidth(), source.getcomptype()) != (RATE, 1, 2, 'NONE'):
                raise ValueError('WAV must be uncompressed S16LE mono 16000Hz')
            audio = source.readframes(source.getnframes())
    elif path.suffix.lower() == '.pcm':
        audio = path.read_bytes()
    else:
        raise ValueError('Only .pcm and .wav are accepted')
    if not audio or len(audio) % 2 or len(audio) > RATE * 2 * 30:
        raise ValueError('PCM must contain complete 16-bit samples, at most 30 seconds')
    return audio


class FileQueue:
    def __init__(self, root, queue_path, emit):
        self.root = root
        self.queue_path = queue_path
        self.emit = emit
        self.seen_files = set()
        self.request_ids = set()
        self.pending = deque()
        self.active = None
        self.offset = 0
        self.cooldown_frames = 0

    def poll(self):
        for trigger in sorted(self.queue_path.glob('*.json')):
            if trigger.name in self.seen_files:
                continue
            self.seen_files.add(trigger.name)
            try:
                if trigger.is_symlink() or trigger.stat().st_size > 4096:
                    raise ValueError('Trigger must be a regular JSON file of at most 4KiB')
                item = json.loads(trigger.read_text())
                request_id = item.get('request_id') if isinstance(item, dict) else None
                if not isinstance(request_id, str) or not REQUEST_ID.fullmatch(request_id):
                    raise ValueError('Invalid request_id')
                if request_id in self.request_ids:
                    self.emit('DUPLICATE_IGNORED', request_id=request_id, trigger=trigger.name)
                    continue
                # Reserve IDs before validating files: retry a corrected trigger with a new ID.
                self.request_ids.add(request_id)
                audio = load_audio(self.root, item.get('audio_file'))
                if len(self.pending) >= 5:
                    raise ValueError('At most five utterances can be queued')
                self.pending.append(dict(request_id=request_id, audio_file=item['audio_file'],
                                         pcm=audio, sha256=hashlib.sha256(audio).hexdigest()))
                self.emit('QUEUED', request_id=request_id, audio_file=item['audio_file'],
                          pcm_sha256=hashlib.sha256(audio).hexdigest(), source_bytes=len(audio))
            except (OSError, ValueError, TypeError, wave.Error) as error:
                self.emit('REJECTED', trigger=trigger.name, error_class=type(error).__name__)

    def frame(self):
        if self.cooldown_frames:
            self.cooldown_frames -= 1
            return bytes(CHUNK_BYTES), None, 0, 0
        if self.active is None and self.pending:
            self.active = self.pending.popleft()
            self.offset = 0
            self.emit('STARTED', request_id=self.active['request_id'],
                      pcm_sha256=self.active['sha256'], source_bytes=len(self.active['pcm']))
        if self.active is None:
            return bytes(CHUNK_BYTES), None, 0, 0
        request_id = self.active['request_id']
        offset = self.offset
        block = self.active['pcm'][offset:offset + CHUNK_BYTES]
        self.offset += len(block)
        if self.offset == len(self.active['pcm']):
            self.emit('SOURCE_FRAMED', request_id=request_id, source_bytes=self.offset)
            self.active = None
            self.cooldown_frames = 50  # One second of silence after each utterance.
        return block + bytes(CHUNK_BYTES - len(block)), request_id, offset, len(block)


def check_schema(proto):
    source = proto.read_text()
    required = ('Mono = 0;', 'MODE_REAL_TIME = 1;', 'DeliveryMode mode = 4;',
                'rpc injectAudio(stream AudioPacket) returns (google.protobuf.Empty) {}')
    if not all(item in source for item in required):
        raise ValueError('Installed input-audio schema differs from the reviewed contract')
    return messages(proto)


def self_test(proto):
    # Pure framing/schema tests; no files, network, playback, or microphone capture.
    events = []
    queue = FileQueue(Path('.'), Path('.'), lambda kind, **values: events.append((kind, values)))
    original = bytes(range(256)) * 6 + b'\x00\x00'
    queue.pending.append(dict(request_id='self-test-01', pcm=original,
                              audio_file='fixture.pcm', sha256=hashlib.sha256(original).hexdigest()))
    recovered = bytearray()
    for _ in range(3):
        block, request_id, offset, size = queue.frame()
        assert len(block) == CHUNK_BYTES and request_id == 'self-test-01'
        assert offset == len(recovered)
        recovered.extend(block[:size])
    assert recovered == original
    assert queue.frame() == (bytes(CHUNK_BYTES), None, 0, 0)
    assert [kind for kind, _ in events] == ['STARTED', 'SOURCE_FRAMED']
    assert REQUEST_ID.fullmatch('take-01.utterance_2') and not REQUEST_ID.fullmatch('../bad')
    assert tick_deadline(0, 1_000_000) == PERIOD_NS
    assert tick_deadline(0, 60_000_000) == 80_000_000
    AudioFormat, AudioPacket = check_schema(proto)
    packet = AudioPacket(format=AudioFormat(samplingRate=RATE, channels=0, format=1, mode=1),
                         timestamp=1234567, audio=bytes(CHUNK_BYTES))
    restored = AudioPacket.FromString(packet.SerializeToString())
    assert restored.audio == bytes(CHUNK_BYTES) and restored.format.mode == 1
    print('FILE_INPUT_FRAMING_SILENCE_PACING_SCHEMA_SELF_TEST_OK (no microphone, network, or playback)')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--discovery', type=Path)
    parser.add_argument('--port', type=int)
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--proto', type=Path, default=Path.home() / 'Library/Android/sdk/emulator/lib/emulator_controller.proto')
    parser.add_argument('--input-root', type=Path)
    parser.add_argument('--queue', type=Path)
    parser.add_argument('--output', type=Path)
    parser.add_argument('--seconds', type=session_seconds, default=1800)
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    if args.self_test:
        self_test(args.proto)
        return
    if not args.input_root or not args.queue or not args.output:
        parser.error('--input-root, --queue, and a new --output are required')
    if not args.input_root.is_dir() or not args.queue.is_dir():
        parser.error('input-root and queue must already exist')
    AudioFormat, AudioPacket = check_schema(args.proto)
    port, metadata = credentials(args)
    os.umask(0o077)
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    stop = threading.Event()
    generator_done = threading.Event()
    report = dict(status='STARTING', physical_microphone_opened=False, host_playback=False,
                  source='pre-generated synthetic audio files', delivery_mode='MODE_REAL_TIME',
                  maximum_seconds=args.seconds, format='S16LE mono 16000Hz', frame_ms=20,
                  packets_yielded=0, source_bytes_yielded=0, silence_bytes_yielded=0,
                  clock_rebases=0, maximum_lateness_ms=0, sdk_recognition_verified=False,
                  started_at=datetime.now(timezone.utc).isoformat(),
                  timestamp_semantics='Host Unix microseconds at generator yield; not guest consumption time',
                  completion_semantics='Yielded bytes are not proof of SDK recognition',
                  proto_sha256=hashlib.sha256(args.proto.read_bytes()).hexdigest())
    log_lock = threading.Lock()
    events = (args.output / 'events.jsonl').open('x', buffering=1)
    frames = (args.output / 'frames.jsonl').open('x', buffering=1)

    def emit(kind, **values):
        item = dict(event=kind, unix_us=time.time_ns() // 1000,
                    monotonic_ns=time.monotonic_ns(), **values)
        with log_lock:
            events.write(json.dumps(item) + '\n')
        if kind in ('STARTED', 'SOURCE_FRAMED', 'REJECTED', 'DUPLICATE_IGNORED'):
            print(json.dumps(item), flush=True)

    queue = FileQueue(args.input_root, args.queue, emit)
    wanted = AudioFormat(samplingRate=RATE, channels=0, format=1, mode=1)
    started = time.monotonic()

    def halt(*_):
        stop.set()

    def requests():
        due = time.monotonic_ns()
        next_poll = 0
        while not stop.is_set() and time.monotonic() - started < args.seconds:
            now = time.monotonic_ns()
            if now < due:
                stop.wait(min((due - now) / 1e9, 0.005))
                continue
            if now >= next_poll:
                queue.poll()
                next_poll = now + 100_000_000
            block, request_id, source_offset, source_bytes = queue.frame()
            send_ns = time.monotonic_ns()
            unix_us = time.time_ns() // 1000
            late = max(0, send_ns - due)
            report['clock_rebases'] += int(late >= PERIOD_NS)
            report['maximum_lateness_ms'] = max(report['maximum_lateness_ms'], late / 1e6)
            frames.write(json.dumps(dict(index=report['packets_yielded'], request_id=request_id,
                                        source_offset=source_offset, source_bytes=source_bytes,
                                        padding_bytes=CHUNK_BYTES-source_bytes, sent_unix_us=unix_us,
                                        sent_monotonic_ns=send_ns, lateness_ms=late / 1e6)) + '\n')
            report['packets_yielded'] += 1
            report['source_bytes_yielded'] += source_bytes
            report['silence_bytes_yielded'] += CHUNK_BYTES - source_bytes
            yield AudioPacket(format=wanted, timestamp=unix_us, audio=block)
            due = tick_deadline(due, send_ns)
        emit('GENERATOR_STOPPED', active_request=queue.active['request_id'] if queue.active else None,
             queued_requests=len(queue.pending))

    def request_stream():
        try:
            yield from requests()
        finally:
            generator_done.set()

    signal.signal(signal.SIGINT, halt)
    signal.signal(signal.SIGTERM, halt)
    channel = grpc.insecure_channel('127.0.0.1:' + str(port))
    call = None
    try:
        grpc.channel_ready_future(channel).result(timeout=5)
        channel.unary_unary('/android.emulation.control.EmulatorController/getStatus')(b'', metadata=metadata, timeout=5)
        call = channel.stream_unary('/android.emulation.control.EmulatorController/injectAudio',
                                    request_serializer=lambda packet: packet.SerializeToString()).future(
                                        request_stream(), metadata=metadata, timeout=args.seconds + 15)
        report['status'] = 'SENDING'
        print('READY file-only input stream; idle silence; no physical microphone or playback', flush=True)
        while not call.done() and not stop.wait(0.1):
            if (args.output / 'stop').exists() or time.monotonic() - started >= args.seconds:
                stop.set()
        call.result(timeout=5)
        report['status'] = 'COMPLETED'
    except Exception as error:
        report['status'] = 'STOPPED_WITH_RPC_ERROR' if stop.is_set() else 'FAILED'
        report['error_class'] = type(error).__name__
        if isinstance(error, grpc.RpcError):
            report['grpc_code'] = error.code().name
    finally:
        stop.set()
        if call is not None and not call.done():
            call.cancel()
        channel.close()
        report['generator_stopped'] = generator_done.wait(timeout=3) if call is not None else True
        events.close()
        frames.close()
        report['finished_at'] = datetime.now(timezone.utc).isoformat()
        report['active_request_incomplete'] = queue.active['request_id'] if queue.active else None
        report['queued_requests_remaining'] = len(queue.pending)
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)
    if report['status'] != 'COMPLETED':
        raise SystemExit(1)


if __name__ == '__main__':
    main()
