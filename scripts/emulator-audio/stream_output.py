#!/usr/bin/env python3
"""Private, localhost-only emulator output capture. --play explicitly enables host playback.

No microphone/input injection, VM control, audio-device setting, or alternative TTS.
Uses installed grpc/protobuf; this exact message subset is checked against the installed SDK proto.
"""
from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib
import json
import signal
import subprocess
import threading
import time
import wave

import grpc
from google.protobuf import descriptor_pb2, descriptor_pool, message_factory


def write_pcm(sink, block):
    remaining = memoryview(block)
    while remaining:
        written = sink.write(remaining)
        if written is None or written <= 0:
            raise BrokenPipeError('Player pipe did not accept PCM bytes')
        remaining = remaining[written:]


class PcmFifo:
    def __init__(self, maximum_bytes=76800):
        self.data = bytearray()
        self.maximum_bytes = maximum_bytes

    def append(self, block):
        if len(block) % 4 or len(self.data) + len(block) > self.maximum_bytes:
            raise ValueError('PCM alignment or application FIFO byte limit exceeded')
        self.data.extend(block)

    def tick(self, size=3840):
        count = min(size, len(self.data))
        block = bytes(self.data[:count])
        del self.data[:count]
        return block + bytes(size - count), count, size - count



class PlaybackBuffer:
    """400ms application budget: FIFO + source bytes awaiting write completion.

    Already-written OS pipe, ffplay, and SDL buffers are not counted. This is
    not an end-to-end playback-latency bound.
    """
    def __init__(self, maximum_bytes=76800):
        self.fifo = PcmFifo(maximum_bytes)
        self.outstanding = 0
        self.lock = threading.Lock()

    def append(self, block):
        with self.lock:
            if self.outstanding + len(block) > self.fifo.maximum_bytes:
                raise BufferError('Application FIFO plus incomplete-write source budget exceeded 400ms')
            self.fifo.append(block)
            self.outstanding += len(block)

    def tick(self, size=3840):
        with self.lock:
            block, count, padding = self.fifo.tick(size)
            return block, count, padding, self.outstanding

    def acknowledge(self, count):
        with self.lock:
            if not 0 <= count <= self.outstanding:
                raise ValueError('Invalid source-byte acknowledgement')
            self.outstanding -= count


def tick_deadline(previous_due, now, period=20_000_000):
    # Keep the original grid for sub-tick wake-up jitter; rebasing every tiny
    # delay would progressively underfeed real-time PCM. A missed whole tick
    # rebases instead of sending multiple catch-up ticks in a burst.
    return (now if now - previous_due >= period else previous_due) + period


def messages(proto_path):
    text = proto_path.read_text()
    section = text[text.index('message AudioFormat {'):text.index('message MicrophoneState {')]
    required = ['uint64 samplingRate = 1;', 'Channels channels = 2;', 'SampleFormat format = 3;',
                'AUD_FMT_S16 = 1;', 'Stereo = 1;', 'AudioFormat format = 1;',
                'uint64 timestamp = 2;', 'bytes audio = 3;']
    if not all(value in section for value in required):
        raise ValueError('Installed proto does not match the reviewed audio schema')
    file = descriptor_pb2.FileDescriptorProto(name='doran_audio_subset.proto', package='android.emulation.control', syntax='proto3')
    audio = file.message_type.add(name='AudioFormat')
    # Enum fields are wire-compatible varints; only the checked numeric constants are sent.
    for name, number, kind in [('samplingRate', 1, 4), ('channels', 2, 5), ('format', 3, 5), ('mode', 4, 5)]:
        audio.field.add(name=name, number=number, type=kind, label=1)
    packet = file.message_type.add(name='AudioPacket')
    packet.field.add(name='format', number=1, type=11, label=1, type_name='.android.emulation.control.AudioFormat')
    packet.field.add(name='timestamp', number=2, type=4, label=1)
    packet.field.add(name='audio', number=3, type=12, label=1)
    pool = descriptor_pool.DescriptorPool()
    pool.Add(file)
    return (message_factory.GetMessageClass(pool.FindMessageTypeByName('android.emulation.control.AudioFormat')),
            message_factory.GetMessageClass(pool.FindMessageTypeByName('android.emulation.control.AudioPacket')))


def credentials(args):
    values = {}
    if args.discovery:
        for line in args.discovery.read_text().splitlines():
            if '=' in line:
                key, value = line.split('=', 1)
                values[key.strip()] = value.strip()
    port = args.port or int(values.get('grpc.port', '0'))
    if not 1 <= port <= 65535:
        raise ValueError('A valid localhost gRPC port is required')
    token = args.token_file.read_text().strip() if args.token_file else values.get('grpc.token', '')
    if not token or any(character.isspace() for character in token):
        raise ValueError('Token must be supplied through a private token file or discovery ini')
    return port, (('authorization', 'Bearer ' + token),)


def session_seconds(value):
    seconds = int(value)
    if not 1 <= seconds <= 1800:
        raise argparse.ArgumentTypeError('--seconds must be between 1 and 1800')
    return seconds


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--discovery', type=Path)
    parser.add_argument('--port', type=int)
    parser.add_argument('--token-file', type=Path)
    parser.add_argument('--proto', type=Path, default=Path.home() / 'Library/Android/sdk/emulator/lib/emulator_controller.proto')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--seconds', type=session_seconds, default=30, help='Session limit: 1..1800 seconds; default 30')
    parser.add_argument('--play', action='store_true')
    parser.add_argument('--playback-gain', type=int, choices=(1, 4, 16), default=1,
                        help='Explicit playback compensation: 4 for the TTS probe, 16 for known-tone probe; archived PCM unchanged')
    parser.add_argument('--self-test', action='store_true')
    args = parser.parse_args()
    AudioFormat, AudioPacket = messages(args.proto)
    wanted = AudioFormat(samplingRate=48000, channels=1, format=1)
    if args.self_test:
        packet = AudioPacket(format=wanted, timestamp=1234567, audio=b'\x01\x00\xff\x7f' * 4)
        parsed = AudioPacket.FromString(packet.SerializeToString())
        assert parsed.timestamp == 1234567 and parsed.audio == packet.audio and parsed.format.samplingRate == 48000
        class PartialSink:
            def __init__(self):
                self.data = bytearray()
            def write(self, block):
                count = min(3, len(block))
                self.data.extend(block[:count])
                return count
        sink = PartialSink()
        write_pcm(sink, packet.audio)
        assert sink.data == packet.audio
        fifo = PcmFifo()
        original = bytes(range(32))
        fifo.append(original[:12])
        fifo.append(original[12:])
        first, count1, pad1 = fifo.tick(16)
        second, count2, pad2 = fifo.tick(24)
        idle, count3, pad3 = fifo.tick(16)
        assert first + second[:count2] == original and count1 == 16 and count2 == 16
        assert pad1 == 0 and pad2 == 8 and second[16:] == bytes(8)
        assert idle == bytes(16) and count3 == 0 and pad3 == 16
        try:
            PcmFifo(4).append(bytes(8))
            raise AssertionError('Overflow was not rejected')
        except ValueError:
            pass
        budget = PlaybackBuffer(32)
        budget.append(original[:12])
        budget.append(original[12:])
        before_late, used, _, outstanding = budget.tick(16)
        assert outstanding == 32
        try:
            budget.append(bytes(4))
            raise AssertionError('In-flight source bytes must count toward the budget')
        except BufferError:
            pass
        budget.acknowledge(used)
        due = 1_000_000_000
        after_delay = due + 150_000_000
        assert tick_deadline(due, after_delay) == after_delay + 20_000_000
        after_late, used, pad, _ = budget.tick(24)
        budget.acknowledge(used)
        assert before_late + after_late[:used] == original and pad == 8
        assert budget.outstanding == 0
        assert budget.tick(16)[:3] == (bytes(16), 0, 16)
        assert tick_deadline(due, due - 1) == due + 20_000_000
        jitter_due = due
        for _ in range(3000):
            jitter_due = tick_deadline(jitter_due, jitter_due + 1_000_000)
        assert jitter_due == due + 60_000_000_000  # 60s, no 1ms/tick drift
        assert tick_deadline(due, due + 20_000_000) == due + 40_000_000
        print('SCHEMA_PARTIAL_WRITE_PACING_CLOCK_REBASE_AND_BYTE_BUDGET_SELF_TEST_OK (no network, capture, or playback)')
        return
    if args.output is None:
        parser.error('Use a new --output directory')
    if args.playback_gain != 1 and not args.play:
        parser.error('--playback-gain requires explicit --play')
    port, metadata = credentials(args)
    args.output.mkdir(parents=True, exist_ok=False, mode=0o700)
    stop = threading.Event()
    playback_buffer = PlaybackBuffer()
    report = dict(status='STARTING', endpoint='127.0.0.1:' + str(port), auth='Bearer token (value never logged)',
                  format='S16LE stereo 48000Hz', playback_requested=args.play, microphone_injected=False,
                  playback_gain=args.playback_gain,
                  playback_filter=f'volume={args.playback_gain},alimiter=limit=0.3:level=false' if args.playback_gain != 1 else None,
                  playback_gain_scope='Explicit host playback compensation; archived PCM unchanged' if args.playback_gain != 1 else 'No gain processing',
                  archived_pcm_gain_or_filter_applied=False,
                  playback_clock='48000Hz stereo, fixed 20ms ticks; 80ms initial jitter margin',
                  application_buffer_limit_ms=400,
                  application_buffer_scope='Helper FIFO plus source bytes awaiting write completion; excludes OS pipe, ffplay, and SDL buffers',
                  actual_end_to_end_latency_not_measured=True,
                  session_limit_seconds=args.seconds,
                  playback_lateness_notice_ms=100,
                  playback_clock_rebases=0, playback_clock_max_lateness_ms=0,
                  playback_buffer_overflow=False, player_pipe_failed=False,
                  playback_padding_bytes=0, playback_underrun_ticks=0, playback_ticks=0,
                  started_at=datetime.now(timezone.utc).isoformat(), proto_sha256=hashlib.sha256(args.proto.read_bytes()).hexdigest(),
                  packets=0, bytes=0, source_gap_notifications=0, playback_backpressure=False,
                  physical_audibility_verified=False)
    channel = grpc.insecure_channel('127.0.0.1:' + str(port), options=[('grpc.max_receive_message_length', 1024 * 1024)])
    call = None
    player = None
    player_log = None
    worker = None

    def halt(*_):
        stop.set()
        if call is not None:
            call.cancel()

    def player_writer():
        fifo = PcmFifo()
        due = time.monotonic_ns() + 80_000_000
        try:
            with (args.output / 'playback-ticks.jsonl').open('x') as ticks:
                while not stop.is_set():
                    now = time.monotonic_ns()
                    if now < due:
                        stop.wait(min((due - now) / 1e9, 0.005))
                        continue
                    lateness_ns = max(0, now - due)
                    rebased = lateness_ns >= 20_000_000
                    report['playback_clock_max_lateness_ms'] = max(
                        report['playback_clock_max_lateness_ms'], lateness_ns / 1e6)
                    if rebased:
                        report['playback_clock_rebases'] += 1
                    if lateness_ns > 100_000_000:
                        print('PLAYBACK_CLOCK_REBASE >100ms; source FIFO preserved, no catch-up burst', flush=True)
                    block, source_bytes, padding, buffered = playback_buffer.tick()
                    start = time.monotonic_ns()
                    write_pcm(player.stdin, block)
                    finish = time.monotonic_ns()
                    playback_buffer.acknowledge(source_bytes)
                    next_due = tick_deadline(due, now)
                    ticks.write(json.dumps(dict(tick=report['playback_ticks'], planned_monotonic_ns=due,
                                                write_started_monotonic_ns=start, write_finished_monotonic_ns=finish,
                                                next_due_monotonic_ns=next_due, clock_rebased=rebased,
                                                rebase_during_source_idle=rebased and source_bytes == 0,
                                                source_bytes=source_bytes, silence_padding_bytes=padding,
                                                buffered_source_bytes_including_inflight=buffered,
                                                lateness_ms=lateness_ns / 1e6)) + '\n')
                    report['playback_ticks'] += 1
                    report['playback_padding_bytes'] += padding
                    report['playback_underrun_ticks'] += int(padding > 0)
                    due = next_due
        except (BrokenPipeError, OSError, ValueError, RuntimeError) as error:
            if not stop.is_set():
                report['player_pipe_failed'] = isinstance(error, OSError)
                report['playback_abort_class'] = type(error).__name__
                print('PLAYBACK_ABORT: player pipe or PCM invariant failed; original PCM preserved', flush=True)
                halt()

    signal.signal(signal.SIGINT, halt)
    signal.signal(signal.SIGTERM, halt)
    try:
        grpc.channel_ready_future(channel).result(timeout=5)
        # Read-only authenticated handshake; do not print the returned VM information.
        channel.unary_unary('/android.emulation.control.EmulatorController/getStatus')(b'', metadata=metadata, timeout=5)
        if args.play:
            player_log = (args.output / 'ffplay.log').open('xb')
            player_command = ['/opt/homebrew/bin/ffplay', '-hide_banner', '-loglevel', 'warning', '-nodisp', '-autoexit',
                              '-f', 's16le', '-sample_rate', '48000', '-ch_layout', 'stereo',
                              '-probesize', '32', '-analyzeduration', '0']
            if args.playback_gain != 1:
                player_command += ['-af', f'volume={args.playback_gain},alimiter=limit=0.3:level=false']
            player_command += ['-i', 'pipe:0']
            report['player_command_without_credentials'] = player_command
            player = subprocess.Popen(player_command,
                                      stdin=subprocess.PIPE, stdout=player_log, stderr=player_log, bufsize=0)
            report['ffplay_pid'] = player.pid
            worker = threading.Thread(target=player_writer, daemon=True)
            worker.start()
        rpc = channel.unary_stream('/android.emulation.control.EmulatorController/streamAudio',
                                   request_serializer=lambda value: value.SerializeToString(), response_deserializer=AudioPacket.FromString)
        call = rpc(wanted, metadata=metadata, timeout=args.seconds)
        print('READY authenticated localhost output stream; waiting for actual guest PCM', flush=True)
        previous_arrival = None
        with (args.output / 'output.pcm').open('xb') as raw, (args.output / 'packets.jsonl').open('x') as packet_log:
            for packet in call:
                if stop.is_set():
                    break
                audio = packet.audio
                if not audio:
                    continue
                if packet.format.samplingRate != 48000 or packet.format.channels != 1 or packet.format.format != 1 or len(audio) % 4:
                    raise ValueError('Unexpected audio format or incomplete stereo PCM frame')
                arrival = time.monotonic_ns()
                gap = (arrival - previous_arrival) / 1e6 if previous_arrival is not None else None
                if gap is not None and gap > 100:
                    report['source_gap_notifications'] += 1
                    print('SOURCE_GAP >100ms (possible source idle or underrun; not a quality verdict)', flush=True)
                entry = dict(index=report['packets'], source_timestamp_unix_us=packet.timestamp,
                             arrival_unix_ns=time.time_ns(), arrival_monotonic_ns=arrival,
                             byte_offset=report['bytes'], bytes=len(audio), arrival_gap_ms=gap)
                raw.write(audio)
                packet_log.write(json.dumps(entry) + '\n')
                report['packets'] += 1
                report['bytes'] += len(audio)
                previous_arrival = arrival
                if report['packets'] == 1:
                    print('FIRST_PCM_PACKET captured' + (' and forwarded to ffplay' if player else ''), flush=True)
                if player:
                    try:
                        playback_buffer.append(audio)
                    except BufferError:
                        report['playback_backpressure'] = True
                        report['playback_buffer_overflow'] = True
                        raise
        report['status'] = 'STOPPED' if stop.is_set() else 'STREAM_ENDED'
    except grpc.RpcError as error:
        code = error.code().name
        report['grpc_status'] = code  # Never log details/debug strings that could include credentials.
        report['status'] = 'DURATION_LIMIT' if code == 'DEADLINE_EXCEEDED' and call is not None else 'STOPPED' if stop.is_set() else 'RPC_FAILED'
    except Exception as error:
        report['status'] = 'FAILED'
        report['error_class'] = type(error).__name__
    finally:
        halt()
        channel.close()
        if player:
            # Discard queued late output on stop; never leave a player running after the helper.
            player.terminate()
            try:
                player.wait(timeout=3)
            except subprocess.TimeoutExpired:
                player.kill()
                player.wait()
            report['ffplay_exit_code'] = player.returncode
        if worker:
            worker.join(timeout=1)
        if player_log:
            player_log.close()
        raw_path = args.output / 'output.pcm'
        if raw_path.exists():
            with wave.open(str(args.output / 'output.packet-sequence.wav'), 'wb') as wav, raw_path.open('rb') as raw:
                wav.setnchannels(2)
                wav.setsampwidth(2)
                wav.setframerate(48000)
                for block in iter(lambda: raw.read(65536), b''):
                    wav.writeframesraw(block)
            report['raw_pcm_sha256'] = hashlib.sha256(raw_path.read_bytes()).hexdigest()
        report['finished_at'] = datetime.now(timezone.utc).isoformat()
        report['timeline_note'] = 'Raw/WAV preserve packet order; idle gaps are described by packet timestamps, not synthesized into the file.'
        (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps({key: report[key] for key in ['status', 'packets', 'bytes', 'source_gap_notifications', 'playback_backpressure']}), flush=True)


if __name__ == '__main__':
    main()
