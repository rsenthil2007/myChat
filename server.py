#!/usr/bin/env python3
"""myChat local server — static files + shared JSON room API with SSE push.

HTTP  :8080  (fine for desktop localhost text/draw)
HTTPS :8443  (required for microphone on phones over LAN)
"""

from __future__ import annotations

import errno
import json
import os
import queue
import re
import socket
import ssl
import subprocess
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote

import device_accounts as devices
import pictionary_game as picto
import whiteboard as wb

ROOT = Path(__file__).resolve().parent
ROOMS_DIR = ROOT / "data" / "rooms"
CERT_DIR = ROOT / "data" / "certs"
CERT_FILE = CERT_DIR / "cert.pem"
KEY_FILE = CERT_DIR / "key.pem"
PORT = int(os.environ.get("MYCHAT_HTTP_PORT", "8080"))
TLS_PORT = int(os.environ.get("MYCHAT_HTTPS_PORT", "8443"))
# Comma-separated origins, or "*" (default). Example: https://app.example.com
CORS_ORIGINS = [o.strip() for o in os.environ.get("CORS_ORIGINS", "*").split(",") if o.strip()]
PERSIST_ROOMS = os.environ.get("PERSIST_ROOMS", "1").strip() not in ("0", "false", "False")

ROOM_RE = re.compile(r"^[a-z0-9-]{1,24}$")
MAX_BODY = 8_000_000  # short audio clips + secure envelopes
MAX_SECURE_DATA = 6_000_000
SECURE_VERSIONS = {3, 4, 5}
B64_RE = re.compile(r"^[A-Za-z0-9+/]+=*$")
lock = threading.Lock()

# room_id -> list[queue.Queue]
_subscribers: dict[str, list[queue.Queue]] = {}
_sub_lock = threading.Lock()
_shutting_down = False


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def normalize_room(room_id: str) -> str:
    value = (room_id or "lobby").strip().lower().replace(" ", "-")[:24]
    value = re.sub(r"[^a-z0-9-]", "", value)
    return value or "lobby"


def room_path(room_id: str) -> Path:
    """Resolved path under ROOMS_DIR only (rejects traversal)."""
    rid = normalize_room(room_id)
    if not ROOM_RE.match(rid):
        raise ValueError("Invalid room id")
    base = ROOMS_DIR.resolve()
    path = (base / f"{rid}.json").resolve()
    if path != base and base not in path.parents:
        raise ValueError("Invalid room path")
    return path


def empty_room(room_id: str) -> dict:
    rid = normalize_room(room_id)
    return {
        "version": 1,
        "roomId": rid,
        "updatedAt": utc_now(),
        "messages": [],
        "pictionary": picto.empty_pictionary(),
        "whiteboard": wb.empty_whiteboard(),
    }


def load_room(room_id: str) -> dict:
    rid = normalize_room(room_id)
    try:
        path = room_path(rid)
    except ValueError:
        return empty_room(rid)
    if not path.exists():
        return empty_room(rid)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict) or not isinstance(data.get("messages"), list):
            return empty_room(rid)
        data["roomId"] = rid
        if not isinstance(data.get("pictionary"), dict):
            data["pictionary"] = picto.empty_pictionary()
        if not isinstance(data.get("whiteboard"), dict):
            data["whiteboard"] = wb.empty_whiteboard()
        return data
    except (OSError, json.JSONDecodeError):
        return empty_room(rid)


def save_room(room: dict) -> dict:
    ROOMS_DIR.mkdir(parents=True, exist_ok=True)
    rid = normalize_room(room.get("roomId") or "lobby")
    if not ROOM_RE.match(rid):
        raise ValueError("Invalid room id")
    data = {
        "version": 1,
        "roomId": rid,
        "updatedAt": utc_now(),
        "messages": room.get("messages") or [],
        "pictionary": room.get("pictionary") or picto.empty_pictionary(),
        "whiteboard": room.get("whiteboard") or wb.empty_whiteboard(),
    }
    if room.get("_picWord"):
        data["_picWord"] = room["_picWord"]
    path = room_path(rid)
    tmp = path.with_suffix(".json.tmp")
    try:
        tmp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
        tmp.replace(path)
    except OSError as exc:
        raise OSError(f"Could not save room {rid}: {exc}") from exc
    return data


def public_snapshot(room: dict) -> dict:
    """Room payload safe for all clients (secret word stripped)."""
    rid = normalize_room(room.get("roomId") or "lobby")
    return {
        "version": room.get("version", 1),
        "roomId": rid,
        "updatedAt": room.get("updatedAt") or utc_now(),
        "messages": room.get("messages") or [],
        "pictionary": picto.public_pictionary(room.get("pictionary")),
        "whiteboard": wb.public_whiteboard(room.get("whiteboard")),
    }


def subscribe(room_id: str) -> queue.Queue:
    q: queue.Queue = queue.Queue()
    with _sub_lock:
        _subscribers.setdefault(room_id, []).append(q)
    return q


def unsubscribe(room_id: str, q: queue.Queue) -> None:
    with _sub_lock:
        subs = _subscribers.get(room_id)
        if not subs:
            return
        try:
            subs.remove(q)
        except ValueError:
            pass
        if not subs:
            _subscribers.pop(room_id, None)
    # Wake any blocked SSE reader
    try:
        q.put_nowait(None)
    except Exception:  # noqa: BLE001
        pass


def wake_all_subscribers() -> None:
    global _shutting_down
    _shutting_down = True
    with _sub_lock:
        items = [(rid, list(qs)) for rid, qs in _subscribers.items()]
        _subscribers.clear()
    for _rid, qs in items:
        for q in qs:
            try:
                q.put_nowait(None)
            except Exception:  # noqa: BLE001
                pass


def broadcast(room_id: str, room: dict) -> None:
    """Push public room snapshot to listeners."""
    payload = json.dumps(public_snapshot(room), ensure_ascii=False, separators=(",", ":"))
    with _sub_lock:
        subs = list(_subscribers.get(room_id, []))
    for q in subs:
        q.put(payload)


def valid_b64_field(value: str, max_len: int) -> bool:
    if not value or len(value) > max_len:
        return False
    if len(value) % 4 != 0:
        return False
    return bool(B64_RE.match(value))


def local_ips() -> list[str]:
    ips = ["127.0.0.1"]
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            ip = info[4][0]
            if ip and ip not in ips:
                ips.append(ip)
    except OSError:
        pass
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.connect(("8.8.8.8", 80))
        ip = sock.getsockname()[0]
        sock.close()
        if ip and ip not in ips:
            ips.append(ip)
    except OSError:
        pass
    return ips


def ensure_tls_certs() -> tuple[Path, Path]:
    """Create a self-signed cert covering localhost + LAN IPs (for mobile mic)."""
    CERT_DIR.mkdir(parents=True, exist_ok=True)
    if CERT_FILE.exists() and KEY_FILE.exists():
        return CERT_FILE, KEY_FILE

    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import rsa
    from cryptography.x509.oid import NameOID

    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    hostnames = ["localhost", "myChat.local"]
    ips = local_ips()
    san: list[x509.GeneralName] = [x509.DNSName(h) for h in hostnames]
    for ip in ips:
        try:
            san.append(x509.IPAddress(__import__("ipaddress").ip_address(ip)))
        except ValueError:
            pass

    subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "myChat Local")])
    cert = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(datetime.now(timezone.utc) - timedelta(minutes=1))
        .not_valid_after(datetime.now(timezone.utc) + timedelta(days=825))
        .add_extension(x509.SubjectAlternativeName(san), critical=False)
        .sign(key, hashes.SHA256())
    )

    KEY_FILE.write_bytes(
        key.private_bytes(
            encoding=serialization.Encoding.PEM,
            format=serialization.PrivateFormat.TraditionalOpenSSL,
            encryption_algorithm=serialization.NoEncryption(),
        )
    )
    CERT_FILE.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
    print(f"Created TLS certificate at {CERT_FILE} (IPs: {', '.join(ips)})")
    return CERT_FILE, KEY_FILE


class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(ROOT), **kwargs)

    def log_message(self, fmt: str, *args) -> None:
        # Avoid noisy logs for long-lived SSE connections after connect
        if " /api/rooms/" in (fmt % args) and "/stream" in (fmt % args):
            return
        print("[%s] %s" % (self.log_date_time_string(), fmt % args))

    def end_headers(self) -> None:
        path = unquote(self.path.split("?", 1)[0])
        if path.endswith((".js", ".css", ".html", ".webmanifest")):
            self.send_header("Cache-Control", "no-store, max-age=0")
        super().end_headers()

    def _cors(self) -> None:
        origin = self.headers.get("Origin") or ""
        if "*" in CORS_ORIGINS:
            allow = "*"
        elif origin and origin in CORS_ORIGINS:
            allow = origin
        elif CORS_ORIGINS:
            allow = CORS_ORIGINS[0]
        else:
            allow = "*"
        self.send_header("Access-Control-Allow-Origin", allow)
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
        if allow != "*":
            self.send_header("Vary", "Origin")
            self.send_header("Access-Control-Allow-Credentials", "true")

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._cors()
        self.end_headers()

    def do_GET(self) -> None:
        path = unquote(self.path.split("?", 1)[0])

        if path == "/api/health":
            self.write_json(
                200,
                {
                    "ok": True,
                    "version": 8,
                    "sync": "shared",
                    "persistRooms": PERSIST_ROOMS,
                    "secureHint": "Serve this app over HTTPS (nginx/Caddy) for microphone on phones and WebIntoApp",
                    "features": ["chat", "board", "audio", "pictionary", "deviceAuth", "firebaseSms", "adminAdmit"],
                    "adminWeb": devices.web_admin_enabled(),
                },
            )
            return

        if path in ("/admin", "/admin/"):
            self.path = "/admin.html"
            super().do_GET()
            return

        if path == "/api/admin/requests":
            self.handle_admin_requests()
            return

        if path.startswith("/api/rooms/"):
            parts = path.strip("/").split("/")
            # api/rooms/{id}
            # api/rooms/{id}/stream
            # api/rooms/{id}/pictionary | whiteboard
            if len(parts) == 4 and parts[3] == "stream":
                self.handle_stream(normalize_room(parts[2]))
                return
            if len(parts) == 4 and parts[3] == "pictionary":
                self.handle_get_pictionary(normalize_room(parts[2]))
                return
            if len(parts) == 4 and parts[3] == "whiteboard":
                self.handle_get_whiteboard(normalize_room(parts[2]))
                return
            if len(parts) == 3:
                self.handle_get_room(normalize_room(parts[2]))
                return
            self.send_error(404, "Not found")
            return

        super().do_GET()

    def do_POST(self) -> None:
        path = unquote(self.path.split("?", 1)[0])
        parts = path.strip("/").split("/")
        if path == "/api/device/register":
            self.handle_device_register()
            return
        if path == "/api/device/verify":
            self.handle_device_verify()
            return
        if path == "/api/device/request":
            self.handle_device_request()
            return
        if path == "/api/device/check":
            self.handle_device_check()
            return
        if path == "/api/admin/login":
            self.handle_admin_login()
            return
        if path == "/api/admin/session":
            self.handle_admin_session()
            return
        if path == "/api/admin/admit":
            self.handle_admin_admit()
            return
        if path == "/api/admin/reject":
            self.handle_admin_reject()
            return
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "rooms" and parts[3] == "messages":
            self.handle_post_message(normalize_room(parts[2]))
            return
        # api/rooms/{id}/pictionary/{action}
        if (
            len(parts) == 5
            and parts[0] == "api"
            and parts[1] == "rooms"
            and parts[3] == "pictionary"
            and parts[4] in ("start", "stroke", "guess", "skip")
        ):
            self.handle_pictionary_action(normalize_room(parts[2]), parts[4])
            return
        # api/rooms/{id}/whiteboard/{action}
        if (
            len(parts) == 5
            and parts[0] == "api"
            and parts[1] == "rooms"
            and parts[3] == "whiteboard"
            and parts[4] in ("join", "claim-color", "stroke", "undo", "clear-mine", "clear-all")
        ):
            self.handle_whiteboard_action(normalize_room(parts[2]), parts[4])
            return
        self.send_error(404, "Not found")

    def do_DELETE(self) -> None:
        path = unquote(self.path.split("?", 1)[0])
        parts = path.strip("/").split("/")
        if len(parts) == 4 and parts[0] == "api" and parts[1] == "rooms" and parts[3] == "messages":
            self.handle_clear_room(normalize_room(parts[2]))
            return
        self.send_error(404, "Not found")

    def handle_get_room(self, room_id: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return
        with lock:
            room = load_room(room_id)
        self.write_json(200, public_snapshot(room))

    def handle_get_pictionary(self, room_id: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return
        with lock:
            room = load_room(room_id)
        self.write_json(200, picto.public_pictionary(room.get("pictionary")))

    def handle_get_whiteboard(self, room_id: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return
        with lock:
            room = load_room(room_id)
        self.write_json(200, wb.public_whiteboard(room.get("whiteboard")))

    def handle_clear_room(self, room_id: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return
        with lock:
            room = empty_room(room_id)
            room = save_room(room)
        broadcast(room_id, room)
        self.write_json(200, public_snapshot(room))

    def handle_stream(self, room_id: str) -> None:
        """Server-Sent Events: quiet until a client sends a message."""
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return

        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache, no-transform")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self._cors()
        self.end_headers()

        q = subscribe(room_id)
        try:
            with lock:
                room = load_room(room_id)
            self._sse_write(
                "room",
                json.dumps(public_snapshot(room), ensure_ascii=False, separators=(",", ":")),
            )

            while not _shutting_down:
                try:
                    payload = q.get(timeout=25)
                except queue.Empty:
                    # Keepalive comment so proxies don't drop idle SSE
                    try:
                        self.wfile.write(b": keepalive\n\n")
                        self.wfile.flush()
                    except (BrokenPipeError, ConnectionResetError, OSError):
                        break
                    continue
                if payload is None:
                    break
                self._sse_write("room", payload)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
            pass
        finally:
            unsubscribe(room_id, q)

    def _sse_write(self, event: str, data: str) -> None:
        # SSE data lines must not contain raw newlines inside a field
        safe = data.replace("\r", "").replace("\n", "")
        chunk = f"event: {event}\ndata: {safe}\n\n".encode("utf-8")
        self.wfile.write(chunk)
        self.wfile.flush()

    def handle_post_message(self, room_id: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return

        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_BODY:
            self.write_json(400, {"error": "Invalid body size"})
            return

        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.write_json(400, {"error": "Invalid JSON"})
            return

        if not isinstance(payload, dict):
            self.write_json(400, {"error": "Message must be an object"})
            return

        msg_type = payload.get("type")
        if msg_type not in ("text", "drawing", "audio"):
            self.write_json(400, {"error": "type must be text, drawing, or audio"})
            return

        message = {
            "id": str(payload.get("id") or f"m_{int(datetime.now().timestamp() * 1000)}"),
            "type": msg_type,
            "authorId": str(payload.get("authorId") or "unknown")[:64],
            "authorName": str(payload.get("authorName") or "Guest")[:24],
            "createdAt": str(payload.get("createdAt") or utc_now()),
        }

        # Preferred path: compressed + encrypted envelope (server stores ciphertext only)
        if payload.get("secure"):
            iv = str(payload.get("iv") or "")
            data = str(payload.get("data") or "")
            mac = str(payload.get("mac") or "")
            try:
                ver = int(payload.get("v") or 0)
            except (TypeError, ValueError):
                ver = 0
            if ver not in SECURE_VERSIONS:
                self.write_json(400, {"error": "Unsupported secure envelope version"})
                return
            if not valid_b64_field(iv, 256) or not valid_b64_field(mac, 256):
                self.write_json(400, {"error": "Invalid secure envelope (iv/mac)"})
                return
            if not valid_b64_field(data, MAX_SECURE_DATA):
                self.write_json(400, {"error": "Invalid secure envelope (data)"})
                return
            message["secure"] = True
            message["v"] = ver
            message["zip"] = 1 if payload.get("zip") else 0
            message["iv"] = iv
            message["mac"] = mac
            message["data"] = data
        elif msg_type == "text":
            text = str(payload.get("text") or "").strip()
            if not text or len(text) > 2000:
                self.write_json(400, {"error": "Invalid text"})
                return
            message["text"] = text
        elif msg_type == "drawing":
            image = str(payload.get("imageData") or "")
            if not image.startswith("data:image/") or len(image) > MAX_BODY:
                self.write_json(400, {"error": "Invalid drawing"})
                return
            if ";base64," not in image[:64]:
                self.write_json(400, {"error": "Invalid drawing encoding"})
                return
            message["imageData"] = image
        else:
            self.write_json(400, {"error": "Audio requires secure envelope"})
            return

        with lock:
            room = load_room(room_id)
            room["messages"].append(message)
            if len(room["messages"]) > 500:
                room["messages"] = room["messages"][-500:]
            try:
                room = save_room(room)
            except OSError as exc:
                print(f"save_room failed: {exc!r}")
                self.write_json(500, {"error": str(exc)})
                return

        try:
            broadcast(room_id, room)
            self.write_json(201, public_snapshot(room))
        except Exception as exc:  # noqa: BLE001
            print(f"post message reply failed: {exc!r}")
            self.write_json(500, {"error": "Could not send message"})

    def _read_json_body(self) -> dict | None:
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_BODY:
            self.write_json(400, {"error": "Invalid body size"})
            return None
        try:
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            self.write_json(400, {"error": "Invalid JSON"})
            return None
        if not isinstance(payload, dict):
            self.write_json(400, {"error": "Body must be an object"})
            return None
        return payload

    def handle_pictionary_action(self, room_id: str, action: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return
        payload = self._read_json_body()
        if payload is None:
            return

        author_id = str(payload.get("authorId") or "")[:64]
        author_name = str(payload.get("authorName") or "Guest")[:24]
        if not author_id:
            self.write_json(400, {"error": "authorId required"})
            return

        try:
            with lock:
                room = load_room(room_id)
                word = None
                correct = None
                if action == "start":
                    room, word = picto.start_round(room, author_id, author_name)
                elif action == "stroke":
                    room = picto.apply_drawing(room, author_id, payload.get("drawing") or {})
                elif action == "guess":
                    room, correct = picto.apply_guess(
                        room, author_id, author_name, str(payload.get("guess") or "")
                    )
                elif action == "skip":
                    room = picto.skip_round(room, author_id)
                else:
                    self.write_json(404, {"error": "Unknown action"})
                    return
                room = save_room(room)
        except PermissionError as exc:
            self.write_json(403, {"error": str(exc)})
            return
        except ValueError as exc:
            self.write_json(400, {"error": str(exc)})
            return

        broadcast(room_id, room)
        body = {
            "room": public_snapshot(room),
            "pictionary": picto.public_pictionary(room.get("pictionary")),
        }
        if word is not None:
            body["word"] = word
        if correct is not None:
            body["correct"] = correct
        self.write_json(200, body)

    def handle_whiteboard_action(self, room_id: str, action: str) -> None:
        if not ROOM_RE.match(room_id):
            self.write_json(400, {"error": "Invalid room id"})
            return
        payload = self._read_json_body()
        if payload is None:
            return

        author_id = str(payload.get("authorId") or "")[:64]
        author_name = str(payload.get("authorName") or "Guest")[:24]
        if not author_id:
            self.write_json(400, {"error": "authorId required"})
            return

        try:
            with lock:
                room = load_room(room_id)
                if action == "join":
                    room = wb.join_board(room, author_id, author_name)
                elif action == "claim-color":
                    room = wb.claim_color(
                        room, author_id, author_name, str(payload.get("color") or "")
                    )
                elif action == "stroke":
                    drawing = payload.get("drawing") or {}
                    strokes = drawing.get("strokes") if isinstance(drawing, dict) else None
                    if strokes is None:
                        strokes = payload.get("strokes") or []
                    w = drawing.get("w") if isinstance(drawing, dict) else payload.get("w")
                    h = drawing.get("h") if isinstance(drawing, dict) else payload.get("h")
                    room = wb.apply_strokes(
                        room,
                        author_id,
                        author_name,
                        strokes,
                        int(w) if w else None,
                        int(h) if h else None,
                    )
                elif action == "undo":
                    room = wb.undo_stroke(room, author_id)
                elif action == "clear-mine":
                    room = wb.clear_mine(room, author_id)
                elif action == "clear-all":
                    room = wb.clear_all(room)
                else:
                    self.write_json(404, {"error": "Unknown action"})
                    return
                room = save_room(room)
        except PermissionError as exc:
            self.write_json(403, {"error": str(exc)})
            return
        except ValueError as exc:
            self.write_json(400, {"error": str(exc)})
            return

        broadcast(room_id, room)
        self.write_json(
            200,
            {
                "room": public_snapshot(room),
                "whiteboard": wb.public_whiteboard(room.get("whiteboard")),
            },
        )

    def handle_device_register(self) -> None:
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.register(
                str(payload.get("idToken") or payload.get("id_token") or ""),
                str(payload.get("ssaid") or ""),
                str(payload.get("displayName") or payload.get("userName") or ""),
            )
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001
            print(f"device register failed: {exc!r}")
            self.write_json(500, {"ok": False, "error": "Could not register this device. Try again."})
            return
        self.write_json(200, result)

    def handle_device_request(self) -> None:
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.request_access(
                str(payload.get("mobile") or ""),
                str(payload.get("ssaid") or ""),
                str(payload.get("displayName") or payload.get("userName") or ""),
            )
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001
            print(f"device request failed: {exc!r}")
            self.write_json(500, {"ok": False, "error": "Could not send this request. Try again."})
            return
        self.write_json(200, result)

    def handle_device_check(self) -> None:
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.check(
                str(payload.get("mobile") or ""),
                str(payload.get("ssaid") or ""),
            )
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001
            print(f"device check failed: {exc!r}")
            self.write_json(500, {"ok": False, "error": "Could not check this device. Try again."})
            return
        self.write_json(200, result)

    def _require_admin(self) -> str | None:
        auth = self.headers.get("Authorization") or ""
        try:
            return devices.parse_admin_token(auth)
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return None

    def handle_admin_login(self) -> None:
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.admin_web_login(
                str(payload.get("mobile") or ""),
                str(payload.get("otp") or payload.get("code") or ""),
            )
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        self.write_json(200, result)

    def handle_admin_session(self) -> None:
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.admin_session_from_device(
                str(payload.get("mobile") or ""),
                str(payload.get("ssaid") or ""),
            )
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        self.write_json(200, result)

    def handle_admin_requests(self) -> None:
        if self._require_admin() is None:
            return
        include_all = "all=1" in (self.path.split("?", 1)[1] if "?" in self.path else "")
        self.write_json(200, {"ok": True, "requests": devices.list_requests(include_all=include_all)})

    def handle_admin_admit(self) -> None:
        if self._require_admin() is None:
            return
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.admit(str(payload.get("mobile") or ""))
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        self.write_json(200, result)

    def handle_admin_reject(self) -> None:
        if self._require_admin() is None:
            return
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.reject(str(payload.get("mobile") or ""))
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        self.write_json(200, result)

    def handle_device_verify(self) -> None:
        payload = self._read_json_body()
        if payload is None:
            return
        try:
            result = devices.verify(
                str(payload.get("idToken") or payload.get("id_token") or ""),
                str(payload.get("ssaid") or ""),
            )
        except devices.DeviceAuthError as exc:
            self.write_json(exc.status, {"ok": False, "error": str(exc)})
            return
        except Exception as exc:  # noqa: BLE001
            print(f"device verify failed: {exc!r}")
            self.write_json(500, {"ok": False, "error": "Could not verify this device. Try again."})
            return
        self.write_json(200, result)

    def write_json(self, status: int, data: dict) -> None:
        body = json.dumps(data, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self._cors()
        self.end_headers()
        self.wfile.write(body)


class ReuseThreadingHTTPServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True


def _serve(server: ThreadingHTTPServer, label: str) -> None:
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        print(f"{label} stopped.")


def _print_port_owners(port: int) -> None:
    try:
        out = subprocess.check_output(["ss", "-tlnp"], text=True, timeout=5)
    except Exception as exc:  # noqa: BLE001
        print(f"(could not list listeners: {exc})")
        return
    matched = [line for line in out.splitlines() if f":{port}" in line]
    if matched:
        print("Listeners:")
        for line in matched:
            print(line)
    else:
        print(f"(ss found no :{port} listener)")


def bind_http(port: int) -> ReuseThreadingHTTPServer:
    last: OSError | None = None
    for attempt in range(1, 16):
        try:
            return ReuseThreadingHTTPServer(("0.0.0.0", port), Handler)
        except OSError as exc:
            last = exc
            if getattr(exc, "errno", None) not in (errno.EADDRINUSE, 98):
                raise
            print(f"Port {port} already in use (attempt {attempt}/15). Retrying…")
            if attempt in (1, 15):
                _print_port_owners(port)
            time.sleep(1)
    assert last is not None
    raise last


def main() -> None:
    ROOMS_DIR.mkdir(parents=True, exist_ok=True)
    ips = local_ips()
    lan = next((ip for ip in ips if not ip.startswith("127.")), ips[0])

    http_server = bind_http(PORT)
    threading.Thread(target=_serve, args=(http_server, "HTTP"), daemon=True).start()

    https_server = None
    try:
        cert, key = ensure_tls_certs()
        https_server = bind_http(TLS_PORT)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(certfile=str(cert), keyfile=str(key))
        https_server.socket = ctx.wrap_socket(https_server.socket, server_side=True)
        threading.Thread(target=_serve, args=(https_server, "HTTPS"), daemon=True).start()
    except Exception as exc:  # noqa: BLE001
        print(f"HTTPS not available ({exc}). Phone mic needs HTTPS — pip install cryptography")
        https_server = None

    print(f"myChat HTTP  http://localhost:{PORT}")
    print(f"myChat HTTP  http://{lan}:{PORT}")
    if https_server:
        print(f"myChat HTTPS https://localhost:{TLS_PORT}")
        print(f"myChat HTTPS https://{lan}:{TLS_PORT}  ← use this on phones for microphone")
        print("First visit: accept the certificate warning (Advanced → Proceed).")
    print("Stop with Ctrl+C")

    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        print("\nStopped.")
        wake_all_subscribers()
        http_server.shutdown()
        if https_server:
            https_server.shutdown()


if __name__ == "__main__":
    main()
