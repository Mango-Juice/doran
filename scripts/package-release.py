#!/usr/bin/env python3
"""Package already verified artifacts; never build or manufacture demo evidence."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import tempfile
from datetime import datetime, timezone
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--car-apk', type=Path, required=True)
    parser.add_argument('--mobile-apk', type=Path, required=True)
    parser.add_argument('--video', type=Path, required=True)
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
    probe = subprocess.check_output(['ffprobe', '-v', 'error', '-show_entries', 'format=duration', '-of', 'json', str(args.video)], text=True)
    duration = float(json.loads(probe)['format']['duration'])
    if duration < 60:
        parser.error('제출 영상은 실제 흐름을 담은 60초 이상의 영상이어야 합니다.')
    manifest = {'release_version': args.version, 'source_commit': commit,
                'team_name': args.team, 'naming_status': 'final' if args.team else 'provisional',
                'packaged_at': datetime.now(timezone.utc).isoformat(),
                'video_duration_seconds': duration, 'artifacts': []}
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
