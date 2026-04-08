"""Web UI server: threaded HTTP + WebSocket with filesystem watching."""

import hashlib
import base64
import http.server
import json
import os
import signal
import socketserver
import struct
import sys
import threading
import time
import webbrowser
from pathlib import Path

from .board import Board, find_root, HistoryEntry


def get_board_state() -> dict:
    try:
        board = Board()
    except FileNotFoundError:
        return {"error": "No .kanban/ found"}

    lanes = []
    for lane_conf in board.lanes:
        name = lane_conf["name"]
        cards_data = []
        for card in board.cards_in_lane(name):
            history = board.load_history(card.id)
            diff_stat = board.get_diff_stat(card.id)
            cards_data.append({
                **card.to_dict(),
                "history": history,
                "diff_stat": diff_stat if "(no branch)" not in diff_stat else "",
            })
        lanes.append({**lane_conf, "cards": cards_data})

    return {
        "project": board.config.get("project", ""),
        "base_branch": board.base_branch,
        "lanes": lanes,
        "timestamp": time.time(),
    }


def handle_ui_command(cmd: dict) -> dict:
    board = Board()
    action = cmd.get("action")

    if action == "move":
        ok, msg, results = board.move(cmd["card_id"], cmd["lane"], agent="human-ui")
        return {"success": ok, "message": msg,
                "gate_results": [r.to_dict() for r in results]}
    elif action == "reject":
        card = board.reject(cmd["card_id"], reason=cmd.get("reason", ""), agent="human-ui")
        return {"success": True, "card": card.to_dict()}
    elif action == "block":
        board.block(cmd["card_id"], reason=cmd.get("reason", "Blocked via UI"))
        return {"success": True}
    elif action == "unblock":
        board.unblock(cmd["card_id"])
        return {"success": True}
    elif action == "note":
        board.add_note(cmd["card_id"], cmd.get("message", ""), agent="human")
        return {"success": True}
    elif action == "add":
        card = board.create_card(
            title=cmd.get("title", "New task"),
            description=cmd.get("description", ""),
        )
        return {"success": True, "card": card.to_dict()}
    elif action == "diff":
        diff = board.get_diff(cmd["card_id"])
        return {"success": True, "diff": diff}
    elif action == "context":
        ctx = board.get_context(cmd["card_id"])
        return {"success": True, "context": ctx}
    return {"success": False, "message": f"Unknown action: {action}"}


# ── WebSocket (RFC 6455 minimal) ──────────────────────────────

def ws_accept_key(key):
    GUID = "258EAFA5-E914-47DA-95CA-5AB5CF11731F"
    return base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()

def ws_encode(data: str) -> bytes:
    payload = data.encode("utf-8")
    frame = bytearray([0x81])
    L = len(payload)
    if L < 126:
        frame.append(L)
    elif L < 65536:
        frame.append(126)
        frame.extend(struct.pack(">H", L))
    else:
        frame.append(127)
        frame.extend(struct.pack(">Q", L))
    frame.extend(payload)
    return bytes(frame)

def ws_decode(data: bytes):
    if len(data) < 2:
        return None, None, 0
    opcode = data[0] & 0x0F
    masked = bool(data[1] & 0x80)
    L = data[1] & 0x7F
    off = 2
    if L == 126:
        if len(data) < 4: return None, None, 0
        L = struct.unpack(">H", data[2:4])[0]; off = 4
    elif L == 127:
        if len(data) < 10: return None, None, 0
        L = struct.unpack(">Q", data[2:10])[0]; off = 10
    if masked:
        if len(data) < off + 4 + L: return None, None, 0
        mask = data[off:off+4]; off += 4
        payload = bytearray(data[off:off+L])
        for i in range(L): payload[i] ^= mask[i % 4]
        payload = bytes(payload)
    else:
        if len(data) < off + L: return None, None, 0
        payload = data[off:off+L]
    return opcode, payload, off + L


class WSClients:
    def __init__(self):
        self.clients = []
        self.lock = threading.Lock()

    def add(self, c):
        with self.lock: self.clients.append(c)

    def remove(self, c):
        with self.lock: self.clients = [x for x in self.clients if x is not c]

    def broadcast(self, msg: str):
        frame = ws_encode(msg)
        with self.lock:
            dead = []
            for c in self.clients:
                try: c.sendall(frame)
                except: dead.append(c)
            for c in dead:
                self.clients = [x for x in self.clients if x is not c]

ws_clients = WSClients()


def handle_ws(conn, addr):
    ws_clients.add(conn)
    try:
        state = get_board_state()
        conn.sendall(ws_encode(json.dumps({"type": "state", "data": state})))
        buf = b""
        while True:
            try:
                data = conn.recv(4096)
                if not data: break
                buf += data
                while buf:
                    op, payload, consumed = ws_decode(buf)
                    if op is None: break
                    buf = buf[consumed:]
                    if op == 0x8: return
                    if op == 0x9:
                        conn.sendall(bytes(bytearray([0x8A, len(payload)]) + payload))
                        continue
                    if op == 0x1:
                        try:
                            cmd = json.loads(payload.decode())
                            result = handle_ui_command(cmd)
                            conn.sendall(ws_encode(json.dumps({"type": "result", "data": result})))
                            state = get_board_state()
                            ws_clients.broadcast(json.dumps({"type": "state", "data": state}))
                        except Exception as e:
                            conn.sendall(ws_encode(json.dumps({"type": "error", "data": str(e)})))
            except (ConnectionResetError, BrokenPipeError): break
    finally:
        ws_clients.remove(conn)
        try: conn.close()
        except: pass


def get_html():
    p = Path(__file__).parent / "ui.html"
    return p.read_text() if p.exists() else "<h1>ui.html not found</h1>"


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass

    def do_GET(self):
        if self.headers.get("Upgrade", "").lower() == "websocket":
            key = self.headers.get("Sec-WebSocket-Key", "")
            resp = (
                "HTTP/1.1 101 Switching Protocols\r\n"
                "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                f"Sec-WebSocket-Accept: {ws_accept_key(key)}\r\n\r\n"
            )
            self.wfile.write(resp.encode())
            self.wfile.flush()
            handle_ws(self.request, self.client_address)
            return
        if self.path in ("/", "/index.html"):
            html = get_html().encode()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", len(html))
            self.end_headers()
            self.wfile.write(html)
            return
        if self.path == "/api/state":
            data = json.dumps(get_board_state()).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(data)
            return
        self.send_response(404)
        self.end_headers()


class Server(socketserver.ThreadingMixIn, http.server.HTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def file_watcher(root: Path, interval=1.0):
    last = {}
    def scan():
        m = {}
        try:
            for f in root.rglob("*"):
                if f.is_file():
                    try: m[str(f)] = f.stat().st_mtime
                    except: pass
        except: pass
        return m
    last = scan()
    while True:
        time.sleep(interval)
        cur = scan()
        if cur != last:
            last = cur
            try:
                ws_clients.broadcast(json.dumps({"type": "state", "data": get_board_state()}))
            except: pass


def run_server(host="127.0.0.1", port=8741):
    try:
        kanban_root = find_root()
    except FileNotFoundError:
        print("Error: No .kanban/ found. Run `kb init` first.", file=sys.stderr)
        sys.exit(1)

    srv = Server((host, port), Handler)
    print(f"kb web UI → http://{host}:{port}")
    print("Ctrl+C to stop.\n")

    threading.Thread(target=file_watcher, args=(kanban_root,), daemon=True).start()
    webbrowser.open(f"http://{host}:{port}")

    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")
        srv.shutdown()
