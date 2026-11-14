#!/usr/bin/env python3
"""Loopback-only, synthetic SSE peer for SocketReplayTest; never calls Supabase or AI.

The first turn is committed to SQLite, then its connection ends without an SSE done event.
The same request after a server restart returns the one persisted turn. This fixture tests
the Android client's transport/replay behavior, not the production server's idempotency.

Run from apps/car, after a separate VM/build slot has been granted:
  python3 scripts/qa_socket_server.py --run-id UUID --port 18991 \
    --database app/build/verification/qa-socket-replay/UUID/server.sqlite3
  adb -s emulator-5554 reverse tcp:18991 tcp:18991

Keep the same run ID/database for restart. Stop the server between the instrumentation
persist_disconnect and offline_after_restart phases, then restart it before replay_after_restart.
This script never changes emulator, account, microphone, audio, or network settings.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socket
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from uuid import UUID, uuid4, uuid5, NAMESPACE_URL


PHRASE = "합성 시험: 네트워크가 끊겨도 이 문장은 한 번만 저장해 주세요."
REPLY = "합성 응답: 네트워크 복구를 확인했어요."
API = "/functions/v1/doran-api/v1"
FIXTURE_AUTH = "doran-socket-fixture-only"
SCHEMA = "doran-socket-replay-v1"


def canonical_uuid(value: str) -> str:
    parsed = str(UUID(value))
    if parsed != value:
        raise ValueError("A canonical lower-case UUID is required")
    return parsed


def emit(event: str, **values: object) -> None:
    print(json.dumps({"event": event, "at_unix_seconds": time.time(), **values}), flush=True)


class Fixture:
    def __init__(self, run_id: str, database: Path):
        self.run_id = run_id
        self.database = database
        self.instance = str(uuid4())
        existed = database.exists()
        database.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            if existed:
                # Never initialize or overwrite an unrelated file supplied by the operator.
                row = connection.execute("SELECT schema_id, run_id FROM fixture_metadata WHERE singleton=1").fetchone()
                if row != (SCHEMA, run_id):
                    raise ValueError("Existing database is not owned by this fixture run")
            else:
                connection.executescript(
                    """
                    CREATE TABLE fixture_metadata (
                      singleton INTEGER PRIMARY KEY CHECK(singleton=1), schema_id TEXT NOT NULL, run_id TEXT NOT NULL
                    );
                    CREATE TABLE turns (
                      request_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, text TEXT NOT NULL,
                      turn_id TEXT NOT NULL, attempts INTEGER NOT NULL, created_at REAL NOT NULL
                    );
                    """
                )
                connection.execute("INSERT INTO fixture_metadata VALUES (1, ?, ?)", (SCHEMA, run_id))
        os.chmod(database, 0o600)

    def connect(self) -> sqlite3.Connection:
        return sqlite3.connect(self.database, timeout=5)

    def accept_turn(self, session_id: str, value: dict[str, object]) -> tuple[bool, dict[str, object]]:
        request_id = canonical_uuid(str(value.get("request_id", "")))
        text = value.get("text")
        if text != PHRASE:
            raise ValueError("Only the fixed synthetic phrase is accepted")
        with self.connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute(
                "SELECT session_id, text, turn_id, attempts FROM turns WHERE request_id=?", (request_id,)
            ).fetchone()
            first = row is None
            if first:
                if connection.execute("SELECT COUNT(*) FROM turns").fetchone()[0] != 0:
                    raise ValueError("This fixture accepts exactly one request identity")
                turn_id = str(uuid5(NAMESPACE_URL, f"doran-socket-replay:{self.run_id}:{request_id}"))
                attempts = 1
                connection.execute(
                    "INSERT INTO turns VALUES (?, ?, ?, ?, ?, ?)",
                    (request_id, session_id, text, turn_id, attempts, time.time()),
                )
            else:
                if row[0] != session_id or row[1] != text:
                    raise ValueError("An idempotency identity cannot change its payload")
                turn_id, attempts = row[2], row[3] + 1
                connection.execute("UPDATE turns SET attempts=? WHERE request_id=?", (attempts, request_id))
        return first, {"turn_id": turn_id, "attempts": attempts, "server_instance": self.instance, "fixture": True}

    def status(self, request_id: str) -> dict[str, object]:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT session_id, text, turn_id, attempts FROM turns WHERE request_id=?", (request_id,)
            ).fetchone()
            total = connection.execute("SELECT COUNT(*) FROM turns").fetchone()[0]
        if row is None:
            raise KeyError("Synthetic turn has not been persisted")
        return {
            "fixture": True,
            "row_count": total,
            "request_id": request_id,
            "session_id": row[0],
            "text_sha256": hashlib.sha256(row[1].encode()).hexdigest(),
            "turn_id": row[2],
            "attempts": row[3],
            "server_instance": self.instance,
        }


def handler_for(fixture: Fixture) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, _format: str, *_args: object) -> None:
            # Do not print headers, credentials, request bodies, or caller-supplied paths.
            return

        def guarded(self) -> bool:
            if self.client_address[0] != "127.0.0.1" or self.headers.get("X-Doran-QA-Run") != fixture.run_id:
                self.respond(403, {"error": {"code": "QA_RUN_REQUIRED", "message": "Synthetic fixture only"}})
                return False
            if self.headers.get("Authorization") != f"Bearer {FIXTURE_AUTH}" or self.headers.get("apikey") != FIXTURE_AUTH:
                self.respond(403, {"error": {"code": "QA_AUTH_REQUIRED", "message": "Synthetic fixture only"}})
                return False
            return True

        def respond(self, status: int, value: dict[str, object]) -> None:
            encoded = json.dumps(value, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(encoded)
            self.wfile.flush()
            self.close_connection = True

        def do_GET(self) -> None:
            if not self.guarded():
                return
            match = re.fullmatch(re.escape(API) + r"/qa/replay-status/([0-9a-f-]{36})", self.path)
            try:
                if match is None:
                    self.respond(404, {"error": {"code": "QA_PATH_NOT_ALLOWED"}})
                    return
                self.respond(200, fixture.status(canonical_uuid(match.group(1))))
            except (KeyError, ValueError):
                self.respond(409, {"error": {"code": "QA_STATE_MISMATCH"}})

        def do_POST(self) -> None:
            if not self.guarded():
                return
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if not 0 < size <= 65_536:
                    raise ValueError("Invalid request size")
                value = json.loads(self.rfile.read(size))
                if not isinstance(value, dict):
                    raise ValueError("Expected object")
                if self.path in ("/auth/v1/signup", "/auth/v1/token?grant_type=refresh_token"):
                    self.respond(200, {"access_token": FIXTURE_AUTH, "refresh_token": FIXTURE_AUTH, "expires_in": 3600})
                    return
                match = re.fullmatch(re.escape(API) + r"/sessions/([0-9a-f-]{36})/turns", self.path)
                if match is None:
                    self.respond(404, {"error": {"code": "QA_PATH_NOT_ALLOWED"}})
                    return
                first, result = fixture.accept_turn(canonical_uuid(match.group(1)), value)
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream; charset=utf-8")
                self.send_header("Cache-Control", "no-cache")
                self.send_header("Connection", "close")
                self.end_headers()

                def event(name: str, body: dict[str, object]) -> None:
                    self.wfile.write(f"event: {name}\ndata: {json.dumps(body, ensure_ascii=False)}\n\n".encode())
                    self.wfile.flush()

                event("start", result)
                if first:
                    event("delta", {"text": "합성 응답 도중 연결 종료", "fixture": True})
                    # A real TCP EOF after the committed start/delta, with no application-level done.
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_WR)
                    emit("turn_committed_connection_closed_before_done", attempts=result["attempts"])
                    return
                event("sentence", {"text": REPLY, "index": 0, "fixture": True})
                event("done", {**result, "assistant_text": REPLY, "session_version": 1})
                self.close_connection = True
                emit("same_turn_replayed", attempts=result["attempts"])
            except (ValueError, json.JSONDecodeError):
                self.respond(409, {"error": {"code": "QA_PAYLOAD_MISMATCH"}})
            except (BrokenPipeError, ConnectionResetError):
                emit("fixture_client_disconnected")

    return Handler


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--port", required=True, type=int)
    parser.add_argument("--database", required=True, type=Path)
    args = parser.parse_args()
    run_id = canonical_uuid(args.run_id)
    if not 1024 <= args.port <= 65535:
        parser.error("Use an unprivileged local port")
    owned_root = Path(__file__).resolve().parent.parent / "app/build/verification/qa-socket-replay"
    database = args.database.resolve()
    if not database.is_relative_to(owned_root.resolve()):
        parser.error("Database must be inside app/build/verification/qa-socket-replay")
    fixture = Fixture(run_id, database)
    server = ThreadingHTTPServer(("127.0.0.1", args.port), handler_for(fixture))
    server.daemon_threads = True
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    emit("fixture_ready", port=args.port, process_id=os.getpid(), server_instance=fixture.instance)
    try:
        server.serve_forever(poll_interval=0.1)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        emit("fixture_stopped")


if __name__ == "__main__":
    main()
