#!/usr/bin/env python3
"""Assemble existing Doran recordings without changing their playback speed.

All source media must already exist. This tool never records, starts a device,
generates a conversation, or certifies the resulting demonstration as passed.
"""

import argparse
import hashlib
import json
import math
import shutil
import subprocess
from pathlib import Path


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(command):
    subprocess.run(command, check=True)


def probe(path):
    return json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_streams', '-show_format',
        '-of', 'json', str(path)], text=True))


def number(value, field):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f'{field}: finite numeric value required')
    return float(value)


def inspect(config, base):
    for executable in ('ffmpeg', 'ffprobe'):
        if not shutil.which(executable):
            raise ValueError(f'Missing tool: {executable}')
    if not isinstance(config.get('scenes'), list) or not config['scenes']:
        raise ValueError('At least one real recorded scene is required')
    font = Path(config.get('font', '/System/Library/Fonts/AppleSDGothicNeo.ttc'))
    if not font.is_file():
        raise ValueError('Set font to an existing Korean font file')
    version = config.get('version', {})
    for key, length in [('source_commit', 40), ('car_apk_sha256', 64), ('mobile_apk_sha256', 64)]:
        value = version.get(key, '')
        if len(value) != length or any(c not in '0123456789abcdef' for c in value):
            raise ValueError(f'version.{key}: verified lowercase hexadecimal hash required')
    scenes = []
    for index, item in enumerate(config['scenes'], 1):
        source = (base / item['source']).resolve()
        if not source.is_file():
            raise ValueError(f'Scene {index}: source recording has not been provided')
        info = probe(source)
        if not any(s['codec_type'] == 'video' for s in info['streams']):
            raise ValueError(f'Scene {index}: no video stream')
        start = number(item.get('start_seconds', 0), 'start_seconds')
        end = number(item.get('end_seconds', float(info['format']['duration'])), 'end_seconds')
        if start < 0 or end <= start or end > float(info['format']['duration']) + 0.05:
            raise ValueError(f'Scene {index}: segment is outside the recording')
        mode = item.get('capture_mode')
        if mode not in ('human', 'synthetic', 'silent'):
            raise ValueError(f'Scene {index}: state human, synthetic, or silent capture_mode')
        has_audio = any(s['codec_type'] == 'audio' for s in info['streams'])
        if mode != 'silent' and not has_audio:
            raise ValueError(f'Scene {index}: speech scene lacks device audio')
        human = (base / item['human_audio']).resolve() if item.get('human_audio') else None
        if human and (not human.is_file() or not any(
                s['codec_type'] == 'audio' for s in probe(human)['streams'])):
            raise ValueError(f'Scene {index}: human microphone recording is missing')
        if mode == 'human' and not human and not item.get('audio_contains_human', False):
            raise ValueError(f'Scene {index}: provide human audio or confirm it is in source audio')
        offset = number(item.get('human_offset_seconds', 0), 'human_offset_seconds')
        if human and not item.get('offset_measured_for_this_take', False):
            raise ValueError(f'Scene {index}: measure the offset from this same take first')
        mutes = item.get('mic_mute_intervals', [])
        if not isinstance(mutes, list) or (mutes and not human):
            raise ValueError(f'Scene {index}: microphone mute intervals require a separate human track')
        for interval in mutes:
            mute_start = number(interval.get('start_seconds'), 'mute.start_seconds')
            mute_end = number(interval.get('end_seconds'), 'mute.end_seconds')
            if not start <= mute_start < mute_end <= end:
                raise ValueError(f'Scene {index}: mute interval must be inside this source segment')
            if (interval.get('no_human_speech_confirmed') is not True
                    or not isinstance(interval.get('evidence'), str)
                    or not interval['evidence'].strip()):
                raise ValueError(f'Scene {index}: mute needs reviewed TTS-only evidence with no human speech')
        surface = item.get('surface')
        if surface not in ('Pleos', 'Android', 'iOS'):
            raise ValueError(f'Scene {index}: invalid surface')
        scenes.append(dict(item, source=source, human_audio=human, start=start,
                           end=end, offset=offset, duration=end-start, has_audio=has_audio,
                           mic_mute_intervals=mutes))
    core = [s for s in scenes if s.get('core_unedited', False)]
    if len(core) != 1 or core[0]['duration'] < 30:
        raise ValueError('Mark one continuous source segment of at least 30s as core_unedited')
    return font, scenes


def encode_args():
    return ['-c:v', 'libx264', '-threads', '2', '-preset', 'veryfast', '-crf', '20',
            '-pix_fmt', 'yuv420p', '-r', '30', '-c:a', 'aac', '-b:a', '192k',
            '-ar', '48000', '-ac', '2', '-movflags', '+faststart']


def title_card(output, font, title, subtitle, duration=4):
    title_file = output.with_suffix('.title.txt')
    subtitle_file = output.with_suffix('.subtitle.txt')
    title_file.write_text(title)
    subtitle_file.write_text(subtitle)
    filters = (
        f'drawtext=fontfile={font}:textfile={title_file}:fontsize=78:'
        'fontcolor=0xf4f0e7:x=(w-text_w)/2:y=405,'
        f'drawtext=fontfile={font}:textfile={subtitle_file}:fontsize=30:'
        'fontcolor=0xc7c5bc:x=(w-text_w)/2:y=535')
    run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n', '-filter_threads', '1',
         '-f', 'lavfi', '-i', f'color=c=0x171a17:s=1920x1080:r=30:d={duration}',
         '-f', 'lavfi', '-i', 'anullsrc=r=48000:cl=stereo',
         '-vf', filters, '-t', str(duration), *encode_args(), str(output)])


def render_scene(scene, index, output, font):
    duration = scene['duration']
    source = scene['source']
    start, end = scene['start'], scene['end']
    voice = {'human': '사람의 실제 발화', 'synthetic': '합성 음성 입력',
             'silent': '화면 조작'}[scene['capture_mode']]
    badge = f"{scene['surface']} 에뮬레이터  ·  가상 사례  ·  {voice}"
    if scene.get('core_unedited', False):
        badge += '  ·  연속 녹화 / 배속 없음'
    if scene.get('mic_mute_intervals'):
        badge += '  ·  중복 수음 제거'
    label = output.with_suffix('.label.txt')
    label.write_text(badge)
    command = ['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n',
               '-filter_complex_threads', '1', '-i', str(source)]
    if scene['human_audio']:
        command += ['-i', str(scene['human_audio'])]
    video = (f'[0:v]trim=start={start}:end={end},setpts=PTS-STARTPTS,'
             'scale=w=1920:h=1008:force_original_aspect_ratio=decrease:force_divisible_by=2,'
             'pad=1920:1080:(ow-iw)/2:0:color=0x111411,fps=30,setsar=1,'
             f'drawtext=fontfile={font}:textfile={label}:fontsize=25:'
             'fontcolor=0xf4f0e7:x=(w-text_w)/2:y=1030[v]')
    if scene['has_audio']:
        device = (f'[0:a]atrim=start={start}:end={end},asetpts=PTS-STARTPTS,'
                  f'aresample=48000,apad=whole_dur={duration},atrim=duration={duration}[device]')
    else:
        device = f'anullsrc=r=48000:cl=stereo,atrim=duration={duration}[device]'
    filters = [video, device]
    if scene['human_audio']:
        offset = scene['offset']
        alignment = (f'adelay=delays={offset*1000:.3f}:all=1' if offset >= 0 else
                     f'atrim=start={-offset},asetpts=PTS-STARTPTS')
        mute_filters = ''.join(
            f",volume=0:enable='between(t,{item['start_seconds']-start},{item['end_seconds']-start})'"
            for item in scene.get('mic_mute_intervals', []))
        filters.append('[1:a]aresample=48000,asetpts=PTS-STARTPTS,' + alignment +
                       f',atrim=start={start}:end={end},asetpts=PTS-STARTPTS{mute_filters},'
                       f'apad=whole_dur={duration},atrim=duration={duration}[human]')
        filters.append('[device][human]amix=inputs=2:duration=longest:'
                       'dropout_transition=0:normalize=1[a]')
    else:
        filters.append('[device]anull[a]')
    command += ['-filter_complex', ';'.join(filters), '-map', '[v]', '-map', '[a]',
                '-t', str(duration), *encode_args(), str(output)]
    print(f'Encoding scene {index}: {duration:.2f}s, {scene["capture_mode"]}', flush=True)
    run(command)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True, type=Path)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = json.loads(config_path.read_text())
    try:
        font, scenes = inspect(config, config_path.parent)
    except (KeyError, ValueError) as error:
        parser.error(str(error))
    duration = sum(s['duration'] for s in scenes) + 8
    if args.validate_only:
        print(json.dumps({'input_validation': 'passed', 'scenes': len(scenes),
                          'planned_duration_seconds': duration,
                          'actual_rendering': 'not_started'}, ensure_ascii=False))
        return
    if not args.output_dir:
        parser.error('--output-dir is required for rendering')
    output = args.output_dir.resolve()
    if any(c in str(output) + str(font) for c in "':;,[]\\"):
        parser.error('Use a font and output directory without FFmpeg filter delimiters')
    output.mkdir(parents=True, exist_ok=False)
    title_card(output/'00-intro.mp4', font, '도란', '차량에서 나눈 이야기를 하루의 기록으로 · 가상 사례 시연')
    parts = [output/'00-intro.mp4']
    evidence = []
    for index, scene in enumerate(scenes, 1):
        part = output/f'{index:02d}-scene.mp4'
        render_scene(scene, index, part, font)
        parts.append(part)
        evidence.append({'source_name': scene['source'].name,
                         'source_sha256': digest(scene['source']),
                         'start_seconds': scene['start'], 'end_seconds': scene['end'],
                         'capture_mode': scene['capture_mode'], 'surface': scene['surface'],
                         'core_unedited': bool(scene.get('core_unedited', False)),
                         'human_audio_sha256': digest(scene['human_audio']) if scene['human_audio'] else None,
                         'human_offset_seconds': scene['offset'] if scene['human_audio'] else None,
                         'mic_mute_intervals_source_timeline': scene['mic_mute_intervals'],
                         'audio_mix': 'equal weights, normalize=1; each track gain 0.5' if scene['human_audio'] else 'source audio only',
                         'noise_reduction_or_syllable_retiming': False})
    version = config['version']
    title_card(output/'99-outro.mp4', font, '소스 · APK · 검증 기록',
               f"github.com/Mango-Juice/doran  ·  기준 커밋 {version['source_commit'][:12]}")
    parts.append(output/'99-outro.mp4')
    listing = output/'concat.txt'
    listing.write_text(''.join(f"file '{p.name}'\n" for p in parts))
    final = output/'doran-demo.mp4'
    run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n', '-f', 'concat',
         '-safe', '1', '-i', str(listing), '-c', 'copy', '-movflags', '+faststart', str(final)])
    actual = probe(final)
    manifest = {'status': 'rendered_pending_visual_audio_privacy_review',
                'version': version, 'scenes': evidence, 'playback_speed': 1,
                'duration_seconds': float(actual['format']['duration']),
                'video_sha256': digest(final),
                'limitations': ['Source captions and capture declarations require manual review.',
                                'Rendering does not verify app functionality, voice quality, or privacy.']}
    (output/'edit-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    for index, seconds in enumerate([2, 4 + scenes[0]['duration']/2,
                                     max(0, manifest['duration_seconds']-2)]):
        run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n', '-ss', str(seconds),
             '-i', str(final), '-frames:v', '1', '-update', '1', str(output/f'preview-{index+1}.png')])
    print(f'Rendered for manual review: {final}')


if __name__ == '__main__':
    main()
