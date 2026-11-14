#!/usr/bin/env python3
"""Package already verified artifacts; never build or manufacture demo evidence."""
import argparse
import hashlib
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path


BUILD_INPUTS = {
    'car': ['apps/car/app/src/main', 'apps/car/app/build.gradle.kts',
            'apps/car/app/gradle.lockfile', 'apps/car/app/proguard-rules.pro',
            'apps/car/build.gradle.kts', 'apps/car/settings.gradle.kts',
            'apps/car/gradle.properties', 'apps/car/gradle', 'apps/car/gradlew',
            'apps/car/gradlew.bat'],
    'mobile': ['apps/mobile/lib', 'apps/mobile/assets', 'apps/mobile/android',
               'apps/mobile/ios', 'apps/mobile/pubspec.yaml', 'apps/mobile/pubspec.lock'],
}


def resolve_commit(root, revision):
    if not re.fullmatch(r'[0-9a-fA-F]{7,40}', revision):
        raise ValueError('빌드 커밋은 7~40자리 Git SHA로 지정하세요.')
    return subprocess.check_output(
        ['git', 'rev-parse', '--verify', revision + '^{commit}'],
        cwd=root, text=True).strip()


def input_tree(root, commit, application):
    listing = subprocess.check_output(
        ['git', 'ls-tree', '-r', '-z', commit, '--', *BUILD_INPUTS[application]], cwd=root)
    if not listing:
        raise ValueError(f'{application}: 빌드 입력이 없습니다.')
    return listing, hashlib.sha256(listing).hexdigest()


def source_evidence(root, revision, current, application):
    build_commit = resolve_commit(root, revision)
    before, digest = input_tree(root, build_commit, application)
    after, current_digest = input_tree(root, current, application)
    if before != after:
        raise ValueError(f'{application}: APK 빌드 커밋과 현재 release 입력이 다릅니다. 다시 빌드하세요.')
    return {'build_source_commit': build_commit, 'build_input_tree_sha256': digest,
            'packaging_input_tree_sha256': current_digest,
            'build_input_paths': BUILD_INPUTS[application],
            'build_inputs_match_packaging_commit': True,
            'source_comparison_scope': 'Tracked build inputs only; excludes local configuration, signing secrets and external SDKs. Does not independently prove which inputs produced APK bytes.'}


def inspect_video(info):
    duration = float(info['format']['duration'])
    streams = info.get('streams', [])
    video = [s for s in streams if s.get('codec_type') == 'video']
    audio = [s for s in streams if s.get('codec_type') == 'audio']
    if not math.isfinite(duration) or len(video) != 1:
        raise ValueError('유효한 단일 영상 트랙이 필요합니다.')
    v = video[0]
    if (v.get('width'), v.get('height'), v.get('codec_name')) != (1920, 1080, 'h264'):
        raise ValueError('1920×1080 H.264 영상이 필요합니다.')
    if not 150 <= duration <= 210 or audio:
        raise ValueError('시연 영상은 150~210초이며 오디오 트랙이 없어야 합니다.')
    return {'duration_seconds': duration, 'width': 1920, 'height': 1080,
            'video_codec': 'h264', 'audio_tracks': len(audio)}


def probe_video(path):
    info = json.loads(subprocess.check_output(
        ['ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(path)], text=True))
    return inspect_video(info)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--car-apk', type=Path, required=True)
    parser.add_argument('--mobile-apk', type=Path, required=True)
    parser.add_argument('--video', type=Path, required=True)
    parser.add_argument('--car-source-commit', required=True)
    parser.add_argument('--mobile-source-commit', required=True)
    naming = parser.add_mutually_exclusive_group(required=True)
    naming.add_argument('--team')
    naming.add_argument('--provisional-team', action='store_true')
    parser.add_argument('--version', default='1.0.0')
    args = parser.parse_args()
    if args.team is not None and not re.fullmatch(r'[\w가-힣-]{1,40}', args.team):
        parser.error('팀명은 문자·숫자·밑줄·하이픈으로 입력하세요.')
    if not re.fullmatch(r'\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?', args.version):
        parser.error('버전은 1.0.0과 같은 형식으로 입력하세요.')
    root = Path(__file__).resolve().parents[1]
    if subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip():
        parser.error('검증한 소스와 문서를 먼저 커밋하세요. 작업 폴더가 변경되어 있습니다.')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    try:
        car_source = source_evidence(root, args.car_source_commit, commit, 'car')
        mobile_source = source_evidence(root, args.mobile_source_commit, commit, 'mobile')
    except (ValueError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    car_name = f'{args.team}_도란_release.apk' if args.team else 'doran-car-release.apk'
    mobile_name = f'{args.team}_도란모바일_release.apk' if args.team else 'doran-mobile-release.apk'
    sources = [(args.car_apk, f'apk/{car_name}'),
               (args.mobile_apk, f'apk/{mobile_name}'),
               (args.video, 'demo/doran-demo.mp4')]
    outputs = [relative for _, relative in sources] + ['release-manifest.json']
    for relative in outputs:
        if (root/relative).exists():
            parser.error(f'기존 산출물을 덮어쓰지 않습니다: {relative}')
    for source, _ in sources:
        if not source.is_file() or source.stat().st_size == 0:
            parser.error(f'산출물이 없습니다: {source}')
    for source in [args.car_apk, args.mobile_apk]:
        subprocess.run(['python3', str(root/'scripts/verify-publication.py'), '--apk', str(source)], cwd=root, check=True)
    try:
        demo = probe_video(args.video)
    except (ValueError, KeyError, subprocess.CalledProcessError) as error:
        parser.error(str(error))
    manifest = {'schema_version': 2, 'release_version': args.version,
                'packaging_source_commit': commit,
                'submission_commit_location': 'Final GitHub Release tag and submission email; not self-referenced inside this manifest.',
                'team_name': args.team, 'naming_status': 'final' if args.team else 'provisional',
                'packaged_at': datetime.now(timezone.utc).isoformat(),
                'artifacts': []}
    with tempfile.TemporaryDirectory(prefix='.release-stage-', dir=root) as temporary:
        stage = Path(temporary)
        for source, relative in sources:
            target = stage/relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            with target.open('rb') as stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
            artifact = {'path': relative, 'bytes': target.stat().st_size,
                        'sha256': digest, 'distribution': 'git'}
            if relative == f'apk/{car_name}':
                artifact.update(car_source)
            elif relative == f'apk/{mobile_name}':
                artifact.update(mobile_source)
            else:
                artifact.update(demo)
            if relative.startswith('demo/'):
                artifact.update(distribution='release_asset', asset_name=target.name)
            manifest['artifacts'].append(artifact)
        (stage/'release-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n')
        created = []
        try:
            for relative in outputs:
                target = root/relative
                target.parent.mkdir(parents=True, exist_ok=True)
                # Atomic no-clobber publication, including competing writes after preflight.
                os.link(stage/relative, target)
                created.append(target)
        except BaseException:
            for target in reversed(created):
                target.unlink()
            raise
    print('패키징 완료. APK와 manifest를 커밋하고 영상은 GitHub Release asset으로 업로드하세요.')


if __name__ == '__main__':
    main()
