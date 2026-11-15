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


def local_source_evidence(root, current, application):
    listing = subprocess.check_output(
        ['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard',
         '--', *BUILD_INPUTS[application]], cwd=root)
    paths = sorted(set(listing.decode().strip('\0').split('\0')) - {''})
    if not paths:
        raise ValueError(f'{application}: 빌드 입력이 없습니다.')
    entries = []
    for relative in paths:
        path = root / relative
        if path.is_symlink():
            payload = os.readlink(path).encode()
            kind = 'symlink'
        elif path.is_file():
            payload = path.read_bytes()
            kind = 'executable' if path.stat().st_mode & 0o111 else 'file'
        else:
            payload = b''
            kind = 'deleted'
        entries.append([relative, kind, hashlib.sha256(payload).hexdigest()])
    digest = hashlib.sha256(json.dumps(entries, ensure_ascii=False,
                                     separators=(',', ':')).encode()).hexdigest()
    return {'build_source_commit': None, 'source_base_commit': current,
            'source_state': 'local_working_tree_snapshot',
            'working_tree_input_sha256': digest,
            'build_input_paths': BUILD_INPUTS[application],
            'build_inputs_match_packaging_commit': False,
            'source_comparison_scope': 'Packaging-time snapshot of tracked and non-ignored untracked build inputs, including deletions. Excludes local configuration, signing secrets and external SDKs. Not a build receipt and does not prove which inputs produced APK bytes.'}


def inspect_video(info):
    duration = float(info['format']['duration'])
    streams = info.get('streams', [])
    video = [s for s in streams if s.get('codec_type') == 'video']
    audio = [s for s in streams if s.get('codec_type') == 'audio']
    if not math.isfinite(duration) or duration <= 0 or len(video) != 1:
        raise ValueError('유효한 단일 영상 트랙이 필요합니다.')
    v = video[0]
    if (v.get('width'), v.get('height'), v.get('codec_name')) != (1920, 1080, 'h264'):
        raise ValueError('1920×1080 H.264 영상이 필요합니다.')
    if len(audio) > 1 or any(a.get('codec_name') != 'aac' for a in audio):
        raise ValueError('오디오는 AAC 트랙 최대 한 개만 허용합니다.')
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
    parser.add_argument('--car-source-commit')
    parser.add_argument('--mobile-source-commit')
    parser.add_argument('--allow-uncommitted-local', action='store_true',
                        help='Create a local-only package with working-tree snapshots, not build commit claims')
    parser.add_argument('--output-dir', type=Path, help='Required outside the repository for local-only packages')
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
    destination = root
    if args.allow_uncommitted_local:
        if args.car_source_commit or args.mobile_source_commit:
            parser.error('로컬 준비 모드에는 빌드 커밋을 지정하지 마세요.')
        if args.output_dir is None:
            parser.error('로컬 준비 모드에는 저장소 밖 --output-dir이 필요합니다.')
        destination = args.output_dir.expanduser().resolve()
        if destination == root or root in destination.parents:
            parser.error('로컬 준비 산출물은 저장소 밖에 보관하세요.')
    elif args.output_dir is not None or not args.car_source_commit or not args.mobile_source_commit:
        parser.error('일반 패키징에는 두 빌드 커밋이 필요하며 --output-dir은 로컬 준비 모드 전용입니다.')
    if not args.allow_uncommitted_local and subprocess.check_output(['git', 'status', '--porcelain'], cwd=root, text=True).strip():
        parser.error('검증한 소스와 문서를 먼저 커밋하세요. 작업 폴더가 변경되어 있습니다.')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    try:
        if args.allow_uncommitted_local:
            car_source = local_source_evidence(root, commit, 'car')
            mobile_source = local_source_evidence(root, commit, 'mobile')
        else:
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
        if (destination/relative).exists():
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
    manifest = {'schema_version': 3 if args.allow_uncommitted_local else 2, 'release_version': args.version,
                'packaging_source_commit': None if args.allow_uncommitted_local else commit,
                'submission_commit_location': 'Final GitHub Release tag and submission email; not self-referenced inside this manifest.',
                'team_name': args.team, 'naming_status': 'final' if args.team else 'provisional',
                'packaged_at': datetime.now(timezone.utc).isoformat(),
                'artifacts': []}
    if args.allow_uncommitted_local:
        manifest.update(local_only=True, source_base_commit=commit,
                        submission_commit_location='Pending; this local package has no submission commit.')
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.release-stage-', dir=destination) as temporary:
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
                target = destination/relative
                target.parent.mkdir(parents=True, exist_ok=True)
                # Atomic no-clobber publication, including competing writes after preflight.
                os.link(stage/relative, target)
                created.append(target)
        except BaseException:
            for target in reversed(created):
                target.unlink()
            raise
    if args.allow_uncommitted_local:
        print('로컬 준비 완료. 빌드 소스 증명과 제출 커밋 확정 전에는 공개하지 마세요.')
    else:
        print('패키징 완료. APK와 manifest를 커밋하고 영상은 GitHub Release asset으로 업로드하세요.')


if __name__ == '__main__':
    main()
