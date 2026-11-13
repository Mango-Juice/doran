#!/usr/bin/env bash
set -euo pipefail
# Dart native asset hooks forward PATH but omit DEVELOPER_DIR. Keep the
# selected Xcode scoped to this build, including hooks which invoke xcrun.
DORAN_XCODE="${DEVELOPER_DIR:-$(xcode-select -p)}"
DORAN_FLUTTER="${FLUTTER_BIN:-flutter}"
DORAN_WRAPPERS="$(pwd)/.dart_tool/doran-xcode-bin"
mkdir -p "$DORAN_WRAPPERS"
python3 - "$DORAN_WRAPPERS/xcrun" "$DORAN_XCODE" <<'PY'
import pathlib,shlex,sys
p=pathlib.Path(sys.argv[1])
p.write_text('#!/bin/sh\nexport DEVELOPER_DIR='+shlex.quote(sys.argv[2])+'\nexec /usr/bin/xcrun "$@"\n')
p.chmod(0o700)
PY
export DEVELOPER_DIR="$DORAN_XCODE"
export PATH="$DORAN_WRAPPERS:$PATH"
"$DORAN_FLUTTER" build ios --simulator --debug "$@"
