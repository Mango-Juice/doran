#!/usr/bin/env python3
"""Check Git publication candidates and release APKs without printing secrets.

This is a targeted release gate, not a claim that all private data can be detected.
Only tracked and non-ignored untracked files are inspected by default.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


SECRET_RULES = {
    "openai_secret": rb"\bsk-(?:proj-|svcacct-)?[A-Za-z0-9_-]{32,}\b",
    "supabase_secret": rb"\bsb_secret_[A-Za-z0-9_-]{20,}\b",
    "github_secret": rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})\b",
    "private_key": rb"-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----\r?\n[A-Za-z0-9+/]",
    "google_oauth_secret": rb"\bGOCSPX-[A-Za-z0-9_-]{20,}\b",
    "aws_access_key": rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b",
    "private_local_path": rb"(?:/Users/|/home/)[A-Za-z0-9._-]+/|[A-Za-z]:\\Users\\[A-Za-z0-9._-]+\\",
}
JWT = re.compile(rb"\b(eyJ[A-Za-z0-9_-]{8,})\.(eyJ[A-Za-z0-9_-]{8,})\.([A-Za-z0-9_-]{20,})\b")
EMAIL = re.compile(rb"\b[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]{1,64}@[A-Za-z0-9.-]{1,253}\.[A-Za-z]{2,63}\b")
PRIVATE_EXPORT = re.compile(r"(?:KakaoTalk_Chat|참가정보|참가신청|consent|server-events|logcat|source-manifest)", re.I)
SAFE_EMAIL_DOMAINS = {"example.com", "example.org", "example.net", "localhost.test"}
# Reviewed native SDK/JNA binaries contain their vendor's build paths, not this
# project's local paths. Pin exact bytes; a dependency update requires re-review.
# This exception applies only to local-path detection, never secret detection.
REVIEWED_VENDOR_PATH_BINARIES = {
    "f41614cd7d30f4ec4e482fb6686722b559861c5b759a30fb46fb25fd73f2ef41",  # arm64 uacore
    "e262e528436960b51259a62d2a44deb914d00804b25a0a295c089b739ffe8445",  # armv7 JNA
    "532e42c7c0f9366893629dc0da24bdf6e4f5ad13d96606205c2545150532cac3",  # armv7 uacore
    "05968c54b7bdc25c3e9a272389d70295692cee749a993633025397f9d01b2c56",  # arm JNA
    "1ff39ffce0dfc1ccc2f10a08777ece9096ae73276853a46ef307d48a78173de5",  # x86 JNA
    "081d3f045743b818d4d3f1868b8655f34760828ead6e2b712659d5bceadafe04",  # x64 JNA
    "887e4327dcb08e6402e539b799eaf375baadb8b47587ccae984e17fc0615d2a0",  # x64 uacore
}


def candidates(root: Path) -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root, check=True, capture_output=True,
    )
    return sorted({Path(os.fsdecode(p)) for p in result.stdout.split(b"\0") if p})


def inspect_bytes(data: bytes, label: str, allowed_emails: set[str], *, reviewed_vendor_paths: bool = False) -> list[dict]:
    findings = []
    for rule, pattern in SECRET_RULES.items():
        if rule == "private_local_path" and reviewed_vendor_paths:
            continue
        match = re.search(pattern, data)
        if match:
            findings.append({"file": label, "rule": rule, "line": data[:match.start()].count(b"\n") + 1})
    for match in JWT.finditer(data):
        try:
            payload = json.loads(base64.urlsafe_b64decode(match.group(2) + b"=" * (-len(match.group(2)) % 4)))
        except (ValueError, UnicodeDecodeError):
            continue
        # Publishable legacy anon JWTs contain no user session. All others require review.
        if payload.get("role") != "anon" or payload.get("sub") or payload.get("session_id"):
            findings.append({"file": label, "rule": "non_public_jwt", "line": data[:match.start()].count(b"\n") + 1})
            break
    if b"\0" not in data[:4096]:
        for match in EMAIL.finditer(data):
            address = match.group().decode("ascii").lower()
            if re.fullmatch(r".+@[123]x\.(?:png|jpe?g|webp)", address):
                continue  # Apple's image scale suffix is a filename, not a mailbox.
            if address not in allowed_emails and address.rsplit("@", 1)[1] not in SAFE_EMAIL_DOMAINS:
                findings.append({"file": label, "rule": "email_requires_review", "line": data[:match.start()].count(b"\n") + 1})
                break
    return findings


def manifest_tool() -> str | None:
    located = shutil.which("apkanalyzer")
    if located:
        return located
    for sdk in (os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"), str(Path.home() / "Library/Android/sdk")):
        if sdk:
            p = Path(sdk) / "cmdline-tools/latest/bin/apkanalyzer"
            if p.is_file():
                return str(p)
    return None


def inspect_apk(path: Path, allowed_emails: set[str]) -> tuple[dict, list[dict]]:
    findings = []
    evidence = {"file": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(), "size_bytes": path.stat().st_size}
    reviewed_binaries = []
    try:
        with zipfile.ZipFile(path) as archive:
            for member in archive.infolist():
                if member.file_size > 100 * 1024 * 1024:
                    findings.append({"file": path.name, "rule": "oversize_apk_member_requires_review", "member": member.filename})
                    continue
                lower = member.filename.lower()
                if lower.startswith(("assets/fixtures/", "assets/test/", "assets/private/")) or lower.endswith((".jks", ".keystore", ".p12", ".env")):
                    findings.append({"file": path.name, "rule": "private_or_test_apk_asset", "member": member.filename})
                if lower.endswith((".dex", ".json", ".xml", ".txt", ".env", ".properties", ".dart", ".so")):
                    data = archive.read(member)
                    digest = hashlib.sha256(data).hexdigest() if lower.endswith(".so") else ""
                    reviewed = digest in REVIEWED_VENDOR_PATH_BINARIES
                    if reviewed:
                        reviewed_binaries.append({"member": member.filename, "sha256": digest})
                    findings.extend(inspect_bytes(data, f"{path.name}!/{member.filename}", allowed_emails, reviewed_vendor_paths=reviewed))
    except zipfile.BadZipFile:
        findings.append({"file": path.name, "rule": "invalid_apk_zip"})
        return evidence, findings
    if reviewed_binaries:
        evidence["reviewed_vendor_build_paths"] = reviewed_binaries
    tool = manifest_tool()
    if not tool:
        findings.append({"file": path.name, "rule": "manifest_unverified_apkanalyzer_missing"})
        return evidence, findings
    result = subprocess.run([tool, "manifest", "print", str(path)], capture_output=True, text=True)
    if result.returncode:
        findings.append({"file": path.name, "rule": "manifest_read_failed"})
        return evidence, findings
    evidence["manifest_checked"] = True
    for name in ("debuggable", "testOnly", "usesCleartextTraffic"):
        if re.search(rf'android:{name}\s*=\s*"true"', result.stdout):
            findings.append({"file": path.name, "rule": f"release_{name}_true"})
    return evidence, findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--apk", type=Path, action="append", default=[])
    parser.add_argument("--allow-email", action="append", default=[], help="Exact reviewed public address; never wildcard")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    root = args.root.resolve()
    findings = []
    count = 0
    allowed = {address.lower() for address in args.allow_email}
    apks = {p.resolve() for p in args.apk}
    for relative in candidates(root):
        path = root / relative
        label = relative.as_posix()
        if path.is_symlink():
            findings.append({"file": label, "rule": "symlink_requires_review"})
            continue
        if not path.is_file():
            continue
        count += 1
        name = path.name.lower()
        if name in {"local.properties", "key.properties"} or name.endswith((".jks", ".keystore", ".p12", ".pem")) or (name.startswith(".env") and name != ".env.example"):
            findings.append({"file": label, "rule": "private_config_in_publication"})
        if PRIVATE_EXPORT.search(path.name) or path.suffix.lower() in {".docx", ".csv", ".jsonl"}:
            findings.append({"file": label, "rule": "private_export_requires_review"})
        if path.suffix.lower() == ".apk":
            apks.add(path.resolve())
            continue
        if path.stat().st_size <= 8 * 1024 * 1024:
            findings.extend(inspect_bytes(path.read_bytes(), label, allowed))
        elif path.suffix.lower() not in {".mp4", ".png", ".jpg", ".webp", ".ttf", ".otf", ".jar"}:
            findings.append({"file": label, "rule": "large_file_not_scanned"})
    apk_evidence = []
    for path in sorted(apks):
        if not path.is_file():
            findings.append({"file": path.name, "rule": "apk_missing"})
            continue
        evidence, issues = inspect_apk(path, allowed)
        apk_evidence.append(evidence)
        findings.extend(issues)
    report = {"passed": not findings, "candidate_files": count, "apks": apk_evidence, "findings": findings, "limitations": ["이미지·영상의 개인정보는 별도 눈으로 검토해야 합니다", "무시된 파일과 기존 Git 커밋 이력은 검사하지 않습니다", "서명 유효성·실행·서버 접근 권한은 별도 검증 대상입니다"]}
    if args.json:
        print(json.dumps(report, ensure_ascii=False, indent=2))
    else:
        print(f"Publication scan: {'PASS' if report['passed'] else 'FAIL'} — {count} files, {len(apk_evidence)} APKs")
        for finding in findings:
            print(f"{finding['file']}:{finding.get('line', '-')} {finding['rule']}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
