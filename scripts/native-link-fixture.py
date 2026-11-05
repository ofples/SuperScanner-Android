"""Synthetic Native Linking endpoint for a dedicated Android emulator test.

All listeners bind only to 127.0.0.1. The Android Emulator reaches these
fixtures through its documented host alias, 10.0.2.2. No discovery broadcast,
physical Supernote, account, clipboard, or note content is involved.
"""

from __future__ import annotations

import json
import socket
import socketserver
import threading

PAIR_PORT = 60002
CLIPBOARD_PORT = 12346
UDP_PORT = 12345
CONTROL_PORT = 60003
SEND_FIXTURE = "native synthetic clipboard ✓"
RECEIVE_FIXTURE = "synthetic incoming clipboard 🧪"


class State:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.mode = "approve"
        self.pair_requests = 0
        self.pair_cancels = 0
        self.pair_statuses: list[int] = []
        self.same_name = True
        self.pair_name: str | None = None
        self.udp_frames: list[str] = []
        self.last_udp_peer: tuple[str, int] | None = None
        self.notify_count = 0
        self.hide_count = 0
        self.fetch_count = 0
        self.send_bytes = 0
        self.send_matches = False
        self.clipboard_connections = 0
        self.clipboard_header_bytes = 0
        self.clipboard_send_stage = "idle"

    def reset(self, mode: str) -> None:
        if mode not in {"approve", "reject", "hold"}:
            raise ValueError("invalid fixture mode")
        with self.lock:
            self.mode = mode
            self.pair_requests = 0
            self.pair_cancels = 0
            self.pair_statuses.clear()
            self.same_name = True
            self.pair_name = None
            self.udp_frames.clear()
            self.last_udp_peer = None
            self.notify_count = 0
            self.hide_count = 0
            self.fetch_count = 0
            self.send_bytes = 0
            self.send_matches = False
            self.clipboard_connections = 0
            self.clipboard_header_bytes = 0
            self.clipboard_send_stage = "idle"

    def snapshot(self) -> dict[str, object]:
        with self.lock:
            return {
                "pairRequests": self.pair_requests,
                "pairCancels": self.pair_cancels,
                "pairStatuses": list(self.pair_statuses),
                "sameTemporaryName": self.same_name,
                "udpFrames": list(self.udp_frames),
                "notifyCount": self.notify_count,
                "hideCount": self.hide_count,
                "fetchCount": self.fetch_count,
                "sendBytes": self.send_bytes,
                "sendMatchesFixture": self.send_matches,
                "clipboardConnections": self.clipboard_connections,
                "clipboardHeaderBytes": self.clipboard_header_bytes,
                "clipboardSendStage": self.clipboard_send_stage,
            }


STATE = State()
UDP = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
UDP.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
UDP.bind(("127.0.0.1", UDP_PORT))


def exact_read(stream, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        block = stream.read(size - len(chunks))
        if not block:
            break
        chunks.extend(block)
    return bytes(chunks)


class ThreadedServer(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True
    block_on_close = False


class PairHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(4.0)
        header = bytearray()
        while len(header) < 2048 and not header.endswith(b"\r\n\r\n"):
            chunk = self.rfile.read(1)
            if not chunk:
                return
            header.extend(chunk)
        if not header.endswith(b"\r\n\r\n"):
            return
        lines = bytes(header).decode("ascii", "replace").split("\r\n")
        headers = {}
        for line in lines[1:]:
            if not line:
                break
            if ":" in line:
                key, value = line.split(":", 1)
                headers[key.lower()] = value.strip()
        try:
            status = int(headers.get("status", ""))
        except ValueError:
            return
        name = headers.get("devicename", "")
        with STATE.lock:
            if status == 1:
                STATE.pair_requests += 1
                STATE.pair_statuses.append(1)
                STATE.pair_name = name
                mode = STATE.mode
            elif status == 2:
                STATE.pair_cancels += 1
                STATE.pair_statuses.append(2)
                STATE.same_name = STATE.same_name and bool(STATE.pair_name) and name == STATE.pair_name
                mode = "cancel"
            else:
                return
        if status == 2:
            return
        if mode == "hold":
            try:
                self.rfile.read(1)
            except OSError:
                pass
            return
        body_code = 200 if mode == "approve" else 201
        body = f'{{code:{body_code},msg:"synthetic"}}\n'.encode("ascii")
        self.wfile.write(b"HTTP/1.0 200 OK\n\n" + body)
        self.wfile.flush()


class ClipboardHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        self.request.settimeout(4.0)
        with STATE.lock:
            STATE.clipboard_connections += 1
            STATE.clipboard_send_stage = "connected"
        header = bytearray()
        while len(header) < 96:
            value = self.rfile.read(1)
            if not value:
                with STATE.lock:
                    STATE.clipboard_header_bytes = len(header)
                    STATE.clipboard_send_stage = "eof-before-header"
                return
            header.extend(value)
            with STATE.lock:
                STATE.clipboard_header_bytes = len(header)
                STATE.clipboard_send_stage = "reading-header"
            if header == b"CLIP_TCP_FETCH\n":
                break
            if header.count(b"|") == 2:
                break
        if bytes(header) == b"CLIP_TCP_FETCH\n":
            with STATE.lock:
                STATE.fetch_count += 1
                STATE.clipboard_send_stage = "fetch"
            data = RECEIVE_FIXTURE.encode("utf-8")
            self.wfile.write(f"CLIP_TCP_CONTENT|{len(data)}|".encode("ascii") + data + b"\n")
            self.wfile.flush()
            return
        framed_header = bytes(header)
        if not framed_header.startswith(b"TCP_STRING|") or not framed_header.endswith(b"|"):
            with STATE.lock:
                STATE.clipboard_send_stage = "malformed-header"
            return
        try:
            size = int(framed_header[len(b"TCP_STRING|"):-1])
        except ValueError:
            with STATE.lock:
                STATE.clipboard_send_stage = "invalid-length"
            return
        if not 1 <= size <= 65536:
            with STATE.lock:
                STATE.clipboard_send_stage = "out-of-range-length"
            return
        payload = exact_read(self.rfile, size)
        with STATE.lock:
            STATE.send_bytes = len(payload)
            STATE.send_matches = payload == SEND_FIXTURE.encode("utf-8")
            STATE.clipboard_send_stage = "complete" if len(payload) == size else "short-payload"


class ControlHandler(socketserver.StreamRequestHandler):
    def handle(self) -> None:
        line = self.rfile.readline(128).decode("ascii", "replace").strip()
        if line.startswith("RESET "):
            try:
                STATE.reset(line.split(" ", 1)[1])
                result: object = {"ok": True}
            except ValueError:
                result = {"ok": False}
        elif line == "STATE":
            result = STATE.snapshot()
        elif line == "NOTIFY":
            with STATE.lock:
                peer = STATE.last_udp_peer
                if peer:
                    payload = f"CLIP_TCP_NOTIFY|{len(RECEIVE_FIXTURE.encode('utf-8'))}".encode("ascii")
                    UDP.sendto(payload, peer)
                    STATE.notify_count += 1
            result = {"ok": peer is not None}
        elif line == "HIDE":
            with STATE.lock:
                peer = STATE.last_udp_peer
                if peer:
                    UDP.sendto(b"keyboard_hide", peer)
                    STATE.hide_count += 1
            result = {"ok": peer is not None}
        else:
            result = {"ok": False}
        self.wfile.write((json.dumps(result, separators=(",", ":")) + "\n").encode("ascii"))
        self.wfile.flush()


def udp_loop() -> None:
    while True:
        data, peer = UDP.recvfrom(1024)
        try:
            message = data.decode("ascii")
        except UnicodeDecodeError:
            continue
        with STATE.lock:
            STATE.last_udp_peer = peer
            STATE.udp_frames.append(message)
        if message == "OPEN|49152|0":
            UDP.sendto(b"keyboard_visible", peer)


def main() -> None:
    servers = [
        ThreadedServer(("127.0.0.1", PAIR_PORT), PairHandler),
        ThreadedServer(("127.0.0.1", CLIPBOARD_PORT), ClipboardHandler),
        ThreadedServer(("127.0.0.1", CONTROL_PORT), ControlHandler),
    ]
    threading.Thread(target=udp_loop, daemon=True, name="fixture-udp").start()
    for server in servers:
        threading.Thread(target=server.serve_forever, daemon=True).start()
    print("fixture-ready loopback-only ports=60002,12346,12345/udp,60003", flush=True)
    threading.Event().wait()


if __name__ == "__main__":
    main()
