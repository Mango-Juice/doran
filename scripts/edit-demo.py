#!/usr/bin/env python3
"""Edit existing app recordings into a silent, captioned demonstration.

Never records, opens a microphone, changes playback speed, or creates app evidence.
The output directory is private: only the reviewed MP4, SRT and thumbnail are published.
"""
import argparse
import hashlib
import json
import math
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'apps/mobile/assets/fonts/SUIT-Variable.ttf'


def digest(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def run(command):
    subprocess.run(command, check=True)


def probe(path):
    return json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(path)
    ], text=True))


def number(value, field):
    if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
        raise ValueError(f'{field}: finite numeric value required')
    return float(value)


def caption_text(value):
    if not isinstance(value, str) or not value.strip():
        raise ValueError('Caption text must not be empty')
    lines = value.split('\n')
    if len(lines) > 2 or any(len(line) > 44 for line in lines):
        raise ValueError('Captions must use at most two lines of 44 characters')
    if any(ord(c) < 32 and c != '\n' for c in value):
        raise ValueError('Caption contains control characters')
    return value


def inspect(config, base):
    for executable in ('ffmpeg', 'ffprobe'):
        if not shutil.which(executable):
            raise ValueError(f'Missing tool: {executable}')
    if config.get('silent_output') is not True:
        raise ValueError('silent_output must be true')
    font = Path(config.get('font', FONT)).resolve()
    if not font.is_file():
        raise ValueError('Korean font is missing')
    if not isinstance(config.get('scenes'), list) or not config['scenes']:
        raise ValueError('At least one existing recorded scene is required')
    version = config.get('version', {})
    for key, length in [('source_commit', 40), ('car_apk_sha256', 64), ('mobile_apk_sha256', 64)]:
        value = version.get(key, '')
        if not isinstance(value, str) or len(value) != length or any(c not in '0123456789abcdef' for c in value):
            raise ValueError(f'version.{key}: verified hexadecimal hash required')
    scenes = []
    for index, item in enumerate(config['scenes'], 1):
        source = (base / item['source']).resolve()
        if not source.is_file():
            raise ValueError(f'Scene {index}: recording is missing')
        info = probe(source)
        if not any(s['codec_type'] == 'video' for s in info['streams']):
            raise ValueError(f'Scene {index}: video stream is missing')
        start = number(item.get('start_seconds', 0), 'start_seconds')
        end = number(item['end_seconds'], 'end_seconds')
        if start < 0 or end <= start or end > float(info['format']['duration']) + .05:
            raise ValueError(f'Scene {index}: invalid source interval')
        if item.get('surface') not in ('Pleos', 'Android', 'iOS'):
            raise ValueError(f'Scene {index}: invalid surface')
        if not isinstance(item.get('synthetic_input'), bool):
            raise ValueError(f'Scene {index}: explicitly declare synthetic_input')
        if item.get('playback_speed', 1) != 1:
            raise ValueError('Playback speed cannot be changed')
        previous_end = 0
        captions = []
        for caption in item.get('captions', []):
            cs = number(caption['start_seconds'], 'caption.start_seconds')
            ce = number(caption['end_seconds'], 'caption.end_seconds')
            if not 0 <= cs < ce <= end - start or cs < previous_end:
                raise ValueError(f'Scene {index}: overlapping or out-of-range captions')
            captions.append(dict(start_seconds=cs, end_seconds=ce, text=caption_text(caption['text'])))
            previous_end = ce
        scenes.append(dict(item, source=source, start=start, end=end, duration=end-start, captions=captions))
    core = [s for s in scenes if s.get('core_unedited')]
    if len(core) != 1 or core[0]['duration'] < 30:
        raise ValueError('Exactly one unedited source segment of at least 30 seconds is required')
    total = sum(s['duration'] for s in scenes) + 8
    if not 150 <= total <= 210:
        raise ValueError(f'Total duration {total:g}s must be 150..210 seconds')
    return font, scenes


def encode_args():
    return ['-an', '-c:v', 'libx264', '-threads', '2', '-preset', 'veryfast', '-crf', '19',
            '-pix_fmt', 'yuv420p', '-r', '30', '-movflags', '+faststart']


def draw_text(font, file, size, x, y, color='F5F7F8', extra=''):
    return (f'drawtext=fontfile={font}:textfile={file}:expansion=none:fontsize={size}:'
            f'fontcolor=0x{color}:x={x}:y={y}:line_spacing=8{extra}')


def title_card(output, font, title, subtitle):
    title_file, subtitle_file = output.with_suffix('.title.txt'), output.with_suffix('.subtitle.txt')
    title_file.write_text(title)
    subtitle_file.write_text(subtitle)
    filters = ','.join([
        'drawbox=x=120:y=355:w=80:h=6:color=0x9CC7DE:t=fill',
        draw_text(font, title_file, 76, 120, 410),
        draw_text(font, subtitle_file, 32, 120, 530, 'B7C3CA')])
    run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n', '-filter_threads', '1',
         '-f', 'lavfi', '-i', 'color=c=0x20292F:s=1920x1080:r=30:d=4',
         '-vf', filters, '-t', '4', *encode_args(), str(output)])


def render_scene(scene, index, output, font):
    label = output.with_suffix('.label.txt')
    label.write_text('실제 앱 시연  |  예시 기록' +
                     ('  |  가상 주행 상태' if scene.get('virtual_driving') else ''))
    filters = ['fps=30:start_time=0', f"tpad=stop_mode=clone:stop_duration={scene['duration']}",
               f"trim=start={scene['start']}:end={scene['end']}", 'setpts=PTS-STARTPTS',
               'scale=w=1920:h=900:force_original_aspect_ratio=decrease:force_divisible_by=2',
               'pad=1920:1080:(ow-iw)/2:0:color=0x20292F', 'setsar=1',
               draw_text(font, label, 23, 56, 922, '9CC7DE')]
    for ci, caption in enumerate(scene['captions']):
        path = output.with_suffix(f'.caption-{ci}.txt')
        path.write_text(caption['text'])
        filters.append(draw_text(font, path, 34, 56, 969,
                                 extra=f":enable='gte(t,{caption['start_seconds']})*lt(t,{caption['end_seconds']})'"))
    run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n', '-filter_threads', '1',
         '-i', str(scene['source']), '-vf', ','.join(filters), '-map', '0:v:0',
         '-t', str(scene['duration']), *encode_args(), str(output)])


def srt_time(seconds):
    ms = round(seconds * 1000)
    hours, ms = divmod(ms, 3600000)
    minutes, ms = divmod(ms, 60000)
    sec, ms = divmod(ms, 1000)
    return f'{hours:02}:{minutes:02}:{sec:02},{ms:03}'


def write_srt(path, scenes):
    timeline, entries = 4.0, []
    for scene in scenes:
        for caption in scene['captions']:
            entries.append(f"{len(entries)+1}\n{srt_time(timeline+caption['start_seconds'])} --> "
                           f"{srt_time(timeline+caption['end_seconds'])}\n{caption['text']}\n")
        timeline += scene['duration']
    path.write_text('\n'.join(entries), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    config_path = args.config.resolve()
    config = json.loads(config_path.read_text())
    try:
        font, scenes = inspect(config, config_path.parent)
    except (KeyError, ValueError) as error:
        parser.error(str(error))
    if args.validate_only:
        print(json.dumps({'validation': 'passed', 'scenes': len(scenes),
                          'duration_seconds': sum(s['duration'] for s in scenes)+8,
                          'silent_output': True, 'rendered': False}))
        return
    if not args.output_dir:
        parser.error('--output-dir is required')
    output = args.output_dir.resolve()
    if any(c in str(output) + str(font) for c in "':;,[]\\"):
        parser.error('Output directory and font cannot contain FFmpeg filter delimiters')
    output.mkdir(parents=True, exist_ok=False)
    # The original variable font defaults to weight 100. Pin a readable weight
    # in a private rendering copy; preserve the repository's original font.
    from fontTools.ttLib import TTFont
    from fontTools.varLib.instancer import instantiateVariableFont
    face = TTFont(font)
    if 'fvar' in face and any(axis.axisTag == 'wght' for axis in face['fvar'].axes):
        face = instantiateVariableFont(face, {'wght': 500}, inplace=False)
        font = output/'SUIT-render-medium.ttf'
        face.save(font)
    title_card(output/'00-intro.mp4', font, '도란', '차량에서 나눈 이야기를 하루의 기록으로')
    parts, evidence = [output/'00-intro.mp4'], []
    for index, scene in enumerate(scenes, 1):
        part = output/f'{index:02}-scene.mp4'
        render_scene(scene, index, part, font)
        rendered_duration = float(probe(part)['format']['duration'])
        if abs(rendered_duration - scene['duration']) > 0.04:
            raise RuntimeError(f'Scene {index}: source interval duration was not preserved')
        parts.append(part)
        evidence.append({'source': str(scene['source']), 'sha256': digest(scene['source']),
                         'start_seconds': scene['start'], 'end_seconds': scene['end'],
                         'synthetic_input': scene['synthetic_input'], 'surface': scene['surface'],
                         'core_unedited': bool(scene.get('core_unedited')), 'captions': scene['captions']})
    title_card(output/'99-outro.mp4', font, '말로 남기고, 내 글로 다듬기', '도란  |  남생이')
    parts.append(output/'99-outro.mp4')
    listing = output/'concat.txt'
    listing.write_text(''.join(f"file '{p.name}'\n" for p in parts))
    final = output/'doran-demo.mp4'
    run(['ffmpeg', '-hide_banner', '-loglevel', 'warning', '-n', '-f', 'concat',
         '-safe', '1', '-i', str(listing), '-map', '0:v:0', '-an', '-c:v', 'copy',
         '-movflags', '+faststart', str(final)])
    actual = probe(final)
    streams = actual['streams']
    if len(streams) != 1 or streams[0]['codec_type'] != 'video' or streams[0]['width'] != 1920 or streams[0]['height'] != 1080:
        raise RuntimeError('Output must be a single 1920x1080 video stream without audio')
    expected_duration = sum(s['duration'] for s in scenes) + 8
    if abs(float(actual['format']['duration']) - expected_duration) > 0.1:
        raise RuntimeError('Caption and video timelines do not match')
    if not 150 <= float(actual['format']['duration']) <= 210.1:
        raise RuntimeError('Rendered duration outside submission target')
    run(['ffmpeg', '-v', 'error', '-i', str(final), '-f', 'null', '-'])
    write_srt(output/'doran-demo.srt', scenes)
    manifest = {'status': 'technical_checks_passed_pending_visual_review', 'version': config['version'],
                'silent_output': True, 'playback_speed': 1, 'scenes': evidence,
                'duration_seconds': float(actual['format']['duration']), 'video_sha256': digest(final),
                'srt_sha256': digest(output/'doran-demo.srt')}
    (output/'edit-manifest.private.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
    for index, seconds in enumerate([2, 4+scenes[0]['duration']/2, float(actual['format']['duration'])-2]):
        run(['ffmpeg', '-v', 'error', '-n', '-ss', str(seconds), '-i', str(final),
             '-frames:v', '1', '-update', '1', str(output/f'preview-{index+1}.png')])
    print(f'Rendered for visual review: {final}')


if __name__ == '__main__':
    main()
