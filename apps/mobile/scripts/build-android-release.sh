#!/usr/bin/env bash
set -euo pipefail

# Dart keeps the generated plugin registrant's source URI even with obfuscation.
# Build from a neutral source directory so personal home paths do not enter APKs.
DORAN_SOURCE="$(cd "$(dirname "$0")/.." && pwd)"
DORAN_FLUTTER="${FLUTTER_BIN:-flutter}"
: "${DORAN_DEBUG_SYMBOLS:?Set DORAN_DEBUG_SYMBOLS to an absolute private directory outside the repository}"
DORAN_STAGING="$(mktemp -d /tmp/doran-mobile-release.XXXXXX)"
trap 'rm -rf "$DORAN_STAGING"' EXIT

python3 - "$DORAN_SOURCE" "$DORAN_STAGING" "$DORAN_DEBUG_SYMBOLS" <<'PY'
import pathlib
import shutil
import subprocess
import sys

source, staging, symbols = map(pathlib.Path, sys.argv[1:])
root = pathlib.Path(subprocess.check_output(
    ['git', 'rev-parse', '--show-toplevel'], cwd=source, text=True).strip())
if not symbols.is_absolute() or symbols.resolve().is_relative_to(root.resolve()):
    raise SystemExit('DORAN_DEBUG_SYMBOLS must be an absolute directory outside the repository')
symbols.mkdir(parents=True, exist_ok=True, mode=0o700)
files = subprocess.check_output([
    'git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z',
    '--', str(source.relative_to(root)),
], cwd=root).split(b'\0')
for raw in files:
    if not raw:
        continue
    item = root / raw.decode()
    if item.is_file():
        target = staging / item.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(item, target)
# Reuse only generated build output to avoid duplicating large native artifacts.
(source / 'build').mkdir(exist_ok=True)
(staging / 'build').symlink_to(source / 'build', target_is_directory=True)
PY

cd "$DORAN_STAGING"
"$DORAN_FLUTTER" pub get --enforce-lockfile
"$DORAN_FLUTTER" build apk --release --obfuscate \
  --split-debug-info="$DORAN_DEBUG_SYMBOLS" "$@"
