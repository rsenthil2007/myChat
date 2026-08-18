#!/usr/bin/env python3
"""Comprehensive myChat test suite: rooms, clear sync, isolation, crypto, API."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.client import HTTPConnection
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import server as chat_server  # noqa: E402
import device_accounts as devices  # noqa: E402
import pictionary_game as picto  # noqa: E402
import whiteboard as wb  # noqa: E402


class RoomHelpersTest(unittest.TestCase):
    def test_normalize_room(self):
        self.assertEqual(chat_server.normalize_room("R1"), "r1")
        self.assertEqual(chat_server.normalize_room(" Study Math "), "study-math")
        self.assertEqual(chat_server.normalize_room("a b!@#c"), "a-bc")
        self.assertEqual(chat_server.normalize_room(""), "lobby")
        self.assertEqual(chat_server.normalize_room("x" * 50), "x" * 24)

    def test_normalize_keeps_distinct_rooms(self):
        a = chat_server.normalize_room("r1")
        b = chat_server.normalize_room("r2")
        c = chat_server.normalize_room("room1")
        self.assertNotEqual(a, b)
        self.assertNotEqual(a, c)
        self.assertNotEqual(b, c)

    def test_room_path_stays_inside_rooms_dir(self):
        old = chat_server.ROOMS_DIR
        tmp = ROOT / "data" / "rooms_path_test"
        tmp.mkdir(parents=True, exist_ok=True)
        chat_server.ROOMS_DIR = tmp
        try:
            path = chat_server.room_path("../etc/passwd")
            self.assertEqual(path.parent.resolve(), tmp.resolve())
            self.assertTrue(str(path).endswith(".json"))
            # traversal characters are stripped by normalize
            self.assertNotIn("..", path.name)
        finally:
            chat_server.ROOMS_DIR = old

    def test_save_room_rejects_empty_invalid_after_normalize_still_lobby(self):
        old = chat_server.ROOMS_DIR
        tmp = ROOT / "data" / "rooms_save_test"
        tmp.mkdir(parents=True, exist_ok=True)
        for p in tmp.glob("*.json"):
            p.unlink()
        chat_server.ROOMS_DIR = tmp
        try:
            saved = chat_server.save_room({"roomId": "../../evil", "messages": []})
            self.assertEqual(saved["roomId"], "evil")
            self.assertTrue((tmp / "evil.json").exists())
            self.assertFalse((tmp.parent / "evil.json").exists())
        finally:
            for p in tmp.glob("*.json*"):
                p.unlink()
            chat_server.ROOMS_DIR = old

    def test_valid_b64_field(self):
        self.assertTrue(chat_server.valid_b64_field("AAAA", 16))
        self.assertFalse(chat_server.valid_b64_field("", 16))
        self.assertFalse(chat_server.valid_b64_field("@@@", 16))
        self.assertFalse(chat_server.valid_b64_field("AAA", 16))  # not padded
        self.assertFalse(chat_server.valid_b64_field("A" * 20, 8))


class DeviceAccountsTest(unittest.TestCase):
    def setUp(self):
        self._old = devices.DEVICES_PATH
        self.path = ROOT / "data" / "devices_unit_test.json"
        devices.DEVICES_PATH = self.path
        if self.path.exists():
            self.path.unlink()
        self._old_url = os.environ.pop("SUPABASE_URL", None)
        self._old_key = os.environ.pop("SUPABASE_SERVICE_KEY", None)
        self._old_test = os.environ.get("MYCHAT_ALLOW_TEST_TOKENS")
        os.environ["MYCHAT_ALLOW_TEST_TOKENS"] = "1"

    def tearDown(self):
        if self.path.exists():
            self.path.unlink()
        tmp = self.path.with_suffix(".json.tmp")
        if tmp.exists():
            tmp.unlink()
        devices.DEVICES_PATH = self._old
        if self._old_url is not None:
            os.environ["SUPABASE_URL"] = self._old_url
        if self._old_key is not None:
            os.environ["SUPABASE_SERVICE_KEY"] = self._old_key
        if self._old_test is None:
            os.environ.pop("MYCHAT_ALLOW_TEST_TOKENS", None)
        else:
            os.environ["MYCHAT_ALLOW_TEST_TOKENS"] = self._old_test

    def test_normalize_strips_country_code(self):
        self.assertEqual(devices.normalize_mobile("+91 98765 43210"), "9876543210")

    def test_display_name_is_required(self):
        with self.assertRaises(devices.DeviceAuthError):
            devices.normalize_display_name(" ")
        self.assertEqual(devices.normalize_display_name("  Ada  "), "Ada")

    def test_register_binds_ssaid_and_keeps_username(self):
        token = "test:9876543210:uid-alice"
        result = devices.register(token, "aabbccddeeff0011", "Alice")
        self.assertTrue(result["ok"])
        self.assertEqual(result["mobile"], "9876543210")
        self.assertEqual(result["displayName"], "Alice")
        again = devices.register(token, "aabbccddeeff0011", "Alicia")
        self.assertEqual(again["displayName"], "Alice")
        verified = devices.verify(token, "aabbccddeeff0011")
        self.assertEqual(verified["displayName"], "Alice")

    def test_other_device_is_rejected(self):
        token = "test:9876543210:uid-alice"
        devices.register(token, "aabbccddeeff0011", "Alice")
        with self.assertRaises(devices.DeviceAuthError) as ctx:
            devices.register(token, "1122334455667788", "Alice")
        self.assertEqual(ctx.exception.status, 409)
        with self.assertRaises(devices.DeviceAuthError) as ctx:
            devices.verify(token, "1122334455667788")
        self.assertEqual(ctx.exception.status, 403)

    def test_non_hex_ssaid_is_hashed(self):
        token = "test:9123456780:uid-bob"
        result = devices.register(token, "not-a-hex-id", "Bob")
        self.assertTrue(result["ok"])
        devices.verify(token, "not-a-hex-id")


class RoomFileIsolationTest(unittest.TestCase):
    def setUp(self):
        self._old = chat_server.ROOMS_DIR
        self.tmpdir = ROOT / "data" / "rooms_test"
        self.tmpdir.mkdir(parents=True, exist_ok=True)
        chat_server.ROOMS_DIR = self.tmpdir
        for p in self.tmpdir.glob("*.json"):
            p.unlink()

    def tearDown(self):
        for p in self.tmpdir.glob("*.json"):
            p.unlink()
        chat_server.ROOMS_DIR = self._old

    def test_rooms_do_not_share_messages(self):
        r1 = chat_server.empty_room("r1")
        r1["messages"].append({"id": "m1", "type": "text", "text": "only-r1"})
        chat_server.save_room(r1)

        r2 = chat_server.empty_room("r2")
        r2["messages"].append({"id": "m2", "type": "text", "text": "only-r2"})
        chat_server.save_room(r2)

        loaded1 = chat_server.load_room("r1")
        loaded2 = chat_server.load_room("r2")
        self.assertEqual([m["text"] for m in loaded1["messages"]], ["only-r1"])
        self.assertEqual([m["text"] for m in loaded2["messages"]], ["only-r2"])

    def test_clear_empties_only_target_room(self):
        for rid, text in (("r1", "a"), ("r2", "b")):
            room = chat_server.empty_room(rid)
            room["messages"].append({"id": "m", "type": "text", "text": text})
            chat_server.save_room(room)

        cleared = chat_server.empty_room("r1")
        chat_server.save_room(cleared)

        self.assertEqual(chat_server.load_room("r1")["messages"], [])
        self.assertEqual(chat_server.load_room("r2")["messages"][0]["text"], "b")


class LiveServerTest(unittest.TestCase):
    """HTTP + SSE integration against an ephemeral server instance."""

    @classmethod
    def setUpClass(cls):
        cls._old_rooms = chat_server.ROOMS_DIR
        cls.tmpdir = ROOT / "data" / "rooms_live_test"
        cls.tmpdir.mkdir(parents=True, exist_ok=True)
        for p in cls.tmpdir.glob("*.json"):
            p.unlink()
        chat_server.ROOMS_DIR = cls.tmpdir
        cls._old_devices = devices.DEVICES_PATH
        cls.devfile = ROOT / "data" / "devices_live_test.json"
        devices.DEVICES_PATH = cls.devfile
        if cls.devfile.exists():
            cls.devfile.unlink()
        os.environ["MYCHAT_ALLOW_TEST_TOKENS"] = "1"

        cls.httpd = chat_server.ThreadingHTTPServer(("127.0.0.1", 0), chat_server.Handler)
        cls.port = cls.httpd.server_address[1]
        cls.base = f"http://127.0.0.1:{cls.port}"
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        # wait until healthy
        for _ in range(50):
            try:
                with urllib.request.urlopen(cls.base + "/api/health", timeout=0.5) as res:
                    if res.status == 200:
                        break
            except Exception:
                time.sleep(0.05)

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        for p in cls.tmpdir.glob("*.json"):
            p.unlink()
        chat_server.ROOMS_DIR = cls._old_rooms
        if cls.devfile.exists():
            cls.devfile.unlink()
        devices.DEVICES_PATH = cls._old_devices

    def setUp(self):
        for p in self.tmpdir.glob("*.json"):
            p.unlink()
        if self.devfile.exists():
            self.devfile.unlink()

    def _json(self, method: str, path: str, body: dict | None = None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            self.base + path,
            data=data,
            method=method,
            headers={"Content-Type": "application/json"} if body is not None else {},
        )
        with urllib.request.urlopen(req, timeout=5) as res:
            raw = res.read().decode("utf-8")
            return res.status, json.loads(raw) if raw else {}

    def _secure_text(self, text: str, room: str, author: str = "T"):
        """Build a minimal secure envelope via Node SecurePipe."""
        script = ROOT / "scripts" / "seal_one.js"
        out = subprocess.check_output(
            ["node", str(script), room, text],
            cwd=str(ROOT),
            text=True,
        )
        env = json.loads(out)
        return {
            "id": f"m_{int(time.time() * 1000)}_{author}",
            "type": "text",
            "authorId": f"u_{author}",
            "authorName": author,
            "createdAt": chat_server.utc_now(),
            "secure": True,
            **env,
        }

    def test_health(self):
        status, data = self._json("GET", "/api/health")
        self.assertEqual(status, 200)
        self.assertTrue(data.get("ok"))

    def test_device_register_and_verify(self):
        token = "test:9876543210:uid-alice"
        status, data = self._json(
            "POST",
            "/api/device/register",
            {"idToken": token, "ssaid": "aabbccddeeff0011", "displayName": "Alice"},
        )
        self.assertEqual(status, 200)
        self.assertTrue(data.get("ok"))
        self.assertEqual(data.get("displayName"), "Alice")
        status, data = self._json(
            "POST",
            "/api/device/verify",
            {"idToken": token, "ssaid": "aabbccddeeff0011"},
        )
        self.assertEqual(status, 200)
        self.assertEqual(data.get("displayName"), "Alice")
        req = urllib.request.Request(
            self.base + "/api/device/verify",
            data=json.dumps({"idToken": token, "ssaid": "1122334455667788"}).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 403)

    def test_post_get_message(self):
        msg = self._secure_text("hello-api", "suite-a", "A")
        status, room = self._json("POST", "/api/rooms/suite-a/messages", msg)
        self.assertEqual(status, 201)
        self.assertEqual(len(room["messages"]), 1)
        self.assertTrue(room["messages"][0]["mac"])

        status, got = self._json("GET", "/api/rooms/suite-a")
        self.assertEqual(status, 200)
        self.assertEqual(len(got["messages"]), 1)

    def test_room_isolation_via_api(self):
        self._json("POST", "/api/rooms/iso-a/messages", self._secure_text("in-a", "iso-a", "A"))
        self._json("POST", "/api/rooms/iso-b/messages", self._secure_text("in-b", "iso-b", "B"))

        _, a = self._json("GET", "/api/rooms/iso-a")
        _, b = self._json("GET", "/api/rooms/iso-b")
        self.assertEqual(len(a["messages"]), 1)
        self.assertEqual(len(b["messages"]), 1)
        self.assertNotEqual(a["messages"][0]["data"], b["messages"][0]["data"])

    def test_clear_room_empties_server_state(self):
        self._json("POST", "/api/rooms/clr/messages", self._secure_text("bye", "clr", "A"))
        status, cleared = self._json("DELETE", "/api/rooms/clr/messages")
        self.assertEqual(status, 200)
        self.assertEqual(cleared["messages"], [])
        self.assertEqual(cleared["roomId"], "clr")

        _, got = self._json("GET", "/api/rooms/clr")
        self.assertEqual(got["messages"], [])

    def test_clear_does_not_touch_other_room(self):
        self._json("POST", "/api/rooms/keep/messages", self._secure_text("keep-me", "keep", "K"))
        self._json("POST", "/api/rooms/wipe/messages", self._secure_text("wipe-me", "wipe", "W"))
        self._json("DELETE", "/api/rooms/wipe/messages")

        _, keep = self._json("GET", "/api/rooms/keep")
        _, wipe = self._json("GET", "/api/rooms/wipe")
        self.assertEqual(len(keep["messages"]), 1)
        self.assertEqual(wipe["messages"], [])

    def test_clear_is_pushed_over_sse(self):
        room_id = "sse-clear"
        self._json("POST", f"/api/rooms/{room_id}/messages", self._secure_text("x", room_id, "S"))

        received: list[dict] = []
        done = threading.Event()

        def listen():
            conn = HTTPConnection("127.0.0.1", self.port, timeout=10)
            conn.putrequest("GET", f"/api/rooms/{room_id}/stream")
            conn.putheader("Accept", "text/event-stream")
            conn.endheaders()
            resp = conn.getresponse()
            buf = b""
            while not done.is_set():
                chunk = resp.read(1)
                if not chunk:
                    break
                buf += chunk
                while b"\n\n" in buf:
                    frame, buf = buf.split(b"\n\n", 1)
                    text = frame.decode("utf-8", errors="replace")
                    if "event: room" in text:
                        for line in text.splitlines():
                            if line.startswith("data: "):
                                received.append(json.loads(line[6:]))
                                if received and received[-1].get("messages") == []:
                                    done.set()
                                    return

        t = threading.Thread(target=listen, daemon=True)
        t.start()
        # allow SSE subscribe + initial snapshot
        time.sleep(0.3)
        self._json("DELETE", f"/api/rooms/{room_id}/messages")
        self.assertTrue(done.wait(5), "SSE did not receive cleared room")
        self.assertTrue(any(r.get("messages") == [] for r in received))

    def test_pictionary_round_over_http(self):
        room_id = "pic-http"
        status, started = self._json(
            "POST",
            f"/api/rooms/{room_id}/pictionary/start",
            {"authorId": "u_alice", "authorName": "Alice"},
        )
        self.assertEqual(status, 200)
        word = started["word"]
        self.assertTrue(word)
        self.assertEqual(started["pictionary"]["phase"], "drawing")
        self.assertNotIn("word", started["pictionary"])

        # Public GET must not leak the word
        status, public = self._json("GET", f"/api/rooms/{room_id}/pictionary")
        self.assertEqual(status, 200)
        self.assertNotIn("word", public)
        self.assertEqual(public["drawerName"], "Alice")

        # Drawer strokes sync
        status, stroked = self._json(
            "POST",
            f"/api/rooms/{room_id}/pictionary/stroke",
            {
                "authorId": "u_alice",
                "authorName": "Alice",
                "drawing": {"w": 300, "h": 400, "strokes": [{"c": "#000", "s": 4, "p": [1, 2, 3, 4]}]},
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(stroked["pictionary"]["drawing"]["strokes"]), 1)

        # Wrong guess keeps round alive
        status, wrong = self._json(
            "POST",
            f"/api/rooms/{room_id}/pictionary/guess",
            {"authorId": "u_bob", "authorName": "Bob", "guess": "definitelywrong"},
        )
        self.assertEqual(status, 200)
        self.assertFalse(wrong["correct"])
        self.assertEqual(wrong["pictionary"]["phase"], "drawing")

        # Correct guess reveals and scores
        status, right = self._json(
            "POST",
            f"/api/rooms/{room_id}/pictionary/guess",
            {"authorId": "u_bob", "authorName": "Bob", "guess": word},
        )
        self.assertEqual(status, 200)
        self.assertTrue(right["correct"])
        self.assertEqual(right["pictionary"]["phase"], "reveal")
        self.assertEqual(right["pictionary"]["winnerName"], "Bob")

    def test_pictionary_non_drawer_cannot_stroke(self):
        room_id = "pic-guard"
        self._json(
            "POST",
            f"/api/rooms/{room_id}/pictionary/start",
            {"authorId": "u_alice", "authorName": "Alice"},
        )
        req = urllib.request.Request(
            self.base + f"/api/rooms/{room_id}/pictionary/stroke",
            data=json.dumps(
                {"authorId": "u_bob", "authorName": "Bob", "drawing": {"w": 10, "h": 10, "strokes": []}}
            ).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 403)

    def test_room_snapshot_includes_pictionary(self):
        room_id = "pic-snap"
        self._json(
            "POST",
            f"/api/rooms/{room_id}/pictionary/start",
            {"authorId": "u_alice", "authorName": "Alice"},
        )
        status, room = self._json("GET", f"/api/rooms/{room_id}")
        self.assertEqual(status, 200)
        self.assertIn("pictionary", room)
        self.assertEqual(room["pictionary"]["phase"], "drawing")
        self.assertNotIn("_picWord", room)

    def test_reject_secure_message_without_mac(self):
        bad = {
            "id": "m_bad",
            "type": "text",
            "authorId": "u_x",
            "authorName": "x",
            "createdAt": chat_server.utc_now(),
            "secure": True,
            "v": 3,
            "zip": 0,
            "iv": "AAAA",
            "data": "BBBB",
        }
        req = urllib.request.Request(
            self.base + "/api/rooms/badmac/messages",
            data=json.dumps(bad).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 400)

    def test_reject_unsupported_secure_version(self):
        bad = {
            "id": "m_v1",
            "type": "text",
            "authorId": "u_x",
            "authorName": "x",
            "createdAt": chat_server.utc_now(),
            "secure": True,
            "v": 1,
            "zip": 0,
            "iv": "AAAA",
            "mac": "CCCC",
            "data": "BBBB",
        }
        req = urllib.request.Request(
            self.base + "/api/rooms/badver/messages",
            data=json.dumps(bad).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 400)

    def test_reject_invalid_drawing_without_base64(self):
        bad = {
            "id": "m_draw",
            "type": "drawing",
            "authorId": "u_x",
            "authorName": "x",
            "createdAt": chat_server.utc_now(),
            "imageData": "data:image/png,not-base64",
        }
        req = urllib.request.Request(
            self.base + "/api/rooms/baddraw/messages",
            data=json.dumps(bad).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 400)

    def test_sse_initial_snapshot_and_unsubscribe_wakeup(self):
        room_id = "sse-wake"
        self._json(
            "POST",
            f"/api/rooms/{room_id}/messages",
            {
                "id": "m1",
                "type": "text",
                "authorId": "u1",
                "authorName": "A",
                "createdAt": chat_server.utc_now(),
                "text": "hello-sse",
            },
        )
        conn = HTTPConnection("127.0.0.1", self.port, timeout=5)
        conn.request("GET", f"/api/rooms/{room_id}/stream")
        resp = conn.getresponse()
        self.assertEqual(resp.status, 200)
        # Read first event (initial snapshot)
        buf = b""
        deadline = time.time() + 3
        while time.time() < deadline and b"\n\n" not in buf:
            chunk = resp.read(1)
            if not chunk:
                break
            buf += chunk
        text = buf.decode("utf-8", errors="replace")
        self.assertIn("event: room", text)
        self.assertIn("hello-sse", text)
        conn.close()
        # Closing should unsubscribe; wake_all should not hang
        chat_server.wake_all_subscribers()
        chat_server._shutting_down = False

    def test_whiteboard_layers_and_author_undo(self):
        room_id = "wb-live"
        status, joined = self._json(
            "POST",
            f"/api/rooms/{room_id}/whiteboard/join",
            {"authorId": "u_alice", "authorName": "Alice"},
        )
        self.assertEqual(status, 200)
        alice_color = joined["whiteboard"]["layers"][0]["assignedColor"]
        self.assertTrue(alice_color)

        status, a = self._json(
            "POST",
            f"/api/rooms/{room_id}/whiteboard/stroke",
            {
                "authorId": "u_alice",
                "authorName": "Alice",
                "drawing": {
                    "w": 400,
                    "h": 300,
                    "strokes": [
                        {"c": alice_color, "s": 4, "p": [1, 2, 3, 4]},
                        {"c": alice_color, "s": 4, "p": [5, 6, 7, 8]},
                    ],
                },
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(a["whiteboard"]["layers"]), 1)

        status, bob_join = self._json(
            "POST",
            f"/api/rooms/{room_id}/whiteboard/join",
            {"authorId": "u_bob", "authorName": "Bob"},
        )
        self.assertEqual(status, 200)
        bob_color = [l for l in bob_join["whiteboard"]["layers"] if l["authorId"] == "u_bob"][0][
            "assignedColor"
        ]
        self.assertNotEqual(alice_color, bob_color)

        status, b = self._json(
            "POST",
            f"/api/rooms/{room_id}/whiteboard/stroke",
            {
                "authorId": "u_bob",
                "authorName": "Bob",
                "drawing": {
                    "w": 400,
                    "h": 300,
                    "strokes": [{"c": bob_color, "s": 5, "p": [9, 10, 11, 12]}],
                },
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(b["whiteboard"]["layers"]), 2)

        # Bob cannot claim Alice's color
        req = urllib.request.Request(
            self.base + f"/api/rooms/{room_id}/whiteboard/claim-color",
            data=json.dumps(
                {"authorId": "u_bob", "authorName": "Bob", "color": alice_color}
            ).encode("utf-8"),
            method="POST",
            headers={"Content-Type": "application/json"},
        )
        with self.assertRaises(urllib.error.HTTPError) as ctx:
            urllib.request.urlopen(req, timeout=5)
        self.assertEqual(ctx.exception.code, 400)

        status, undone = self._json(
            "POST",
            f"/api/rooms/{room_id}/whiteboard/undo",
            {"authorId": "u_alice", "authorName": "Alice"},
        )
        self.assertEqual(status, 200)
        by_id = {layer["authorId"]: layer for layer in undone["whiteboard"]["layers"]}
        self.assertEqual(len(by_id["u_alice"]["strokes"]), 1)
        self.assertEqual(len(by_id["u_bob"]["strokes"]), 1)

        status, room = self._json("GET", f"/api/rooms/{room_id}")
        self.assertEqual(status, 200)
        self.assertIn("whiteboard", room)
        self.assertEqual(len(room["whiteboard"]["layers"]), 2)
        self.assertTrue(room["whiteboard"]["palette"])


class PictionaryTest(unittest.TestCase):
    def setUp(self):
        self.room = {
            "roomId": "pic",
            "messages": [],
            "pictionary": picto.empty_pictionary(),
        }

    def test_start_hides_word_in_public(self):
        room, word = picto.start_round(self.room, "u1", "Alice")
        pub = picto.public_pictionary(room["pictionary"])
        self.assertEqual(room["_picWord"], word)
        self.assertNotIn("word", pub)
        self.assertEqual(pub["phase"], "drawing")
        self.assertEqual(pub["drawerName"], "Alice")
        self.assertTrue(pub["hint"])

    def test_guess_correct_scores_both(self):
        room, word = picto.start_round(self.room, "u1", "Alice")
        room, ok = picto.apply_guess(room, "u2", "Bob", word)
        self.assertTrue(ok)
        self.assertEqual(room["pictionary"]["phase"], "reveal")
        self.assertEqual(room["pictionary"]["winnerName"], "Bob")
        self.assertGreaterEqual(room["pictionary"]["scores"]["Bob"], 1)
        self.assertGreaterEqual(room["pictionary"]["scores"]["Alice"], 1)
        self.assertNotIn("_picWord", room)

    def test_drawer_cannot_guess(self):
        room, word = picto.start_round(self.room, "u1", "Alice")
        with self.assertRaises(PermissionError):
            picto.apply_guess(room, "u1", "Alice", word)

    def test_wrong_guess_keeps_drawing(self):
        room, _word = picto.start_round(self.room, "u1", "Alice")
        room, ok = picto.apply_guess(room, "u2", "Bob", "notthewordxyz")
        self.assertFalse(ok)
        self.assertEqual(room["pictionary"]["phase"], "drawing")
        self.assertIn("_picWord", room)


class WhiteboardTest(unittest.TestCase):
    def setUp(self):
        self.room = {
            "roomId": "board",
            "messages": [],
            "whiteboard": wb.empty_whiteboard(),
        }

    def test_join_assigns_unique_palette_colors(self):
        room = wb.join_board(self.room, "u1", "Alice")
        room = wb.join_board(room, "u2", "Bob")
        room = wb.join_board(room, "u3", "Cara")
        colors = [layer["assignedColor"] for layer in room["whiteboard"]["layers"]]
        self.assertEqual(len(colors), 3)
        self.assertEqual(len(set(colors)), 3)
        self.assertEqual(colors[0], wb.PALETTE[0]["hex"])
        self.assertEqual(colors[1], wb.PALETTE[1]["hex"])
        self.assertEqual(colors[2], wb.PALETTE[2]["hex"])

    def test_board_keeps_canonical_size(self):
        room = {"whiteboard": wb.empty_whiteboard(), "messages": []}
        room = wb.join_board(room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        self.assertEqual(room["whiteboard"]["w"], wb.CANONICAL_W)
        self.assertEqual(room["whiteboard"]["h"], wb.CANONICAL_H)
        room = wb.apply_strokes(
            room,
            "u1",
            "Alice",
            [{"c": alice, "s": 4, "p": [10, 20, 30, 40]}],
            333,
            222,
        )
        # Client screen pixels must not rewrite the shared logical canvas.
        self.assertEqual(room["whiteboard"]["w"], wb.CANONICAL_W)
        self.assertEqual(room["whiteboard"]["h"], wb.CANONICAL_H)

    def test_board_upgrades_old_16x9_size(self):
        room = {
            "whiteboard": {"w": 1280, "h": 720, "layers": []},
            "messages": [],
        }
        room = wb.join_board(room, "u1", "Alice")
        self.assertEqual(room["whiteboard"]["w"], wb.CANONICAL_W)
        self.assertEqual(room["whiteboard"]["h"], wb.CANONICAL_H)
        public = wb.public_whiteboard({"w": 1280, "h": 720, "layers": []})
        self.assertEqual(public["w"], wb.CANONICAL_W)
        self.assertEqual(public["h"], wb.CANONICAL_H)

    def test_layers_are_per_author(self):
        room = wb.join_board(self.room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        room = wb.apply_strokes(
            room,
            "u1",
            "Alice",
            [{"c": alice, "s": 4, "p": [1, 2, 3, 4]}],
            300,
            200,
        )
        room = wb.join_board(room, "u2", "Bob")
        bob = [l for l in room["whiteboard"]["layers"] if l["authorId"] == "u2"][0]["assignedColor"]
        room = wb.apply_strokes(
            room,
            "u2",
            "Bob",
            [{"c": bob, "s": 5, "p": [5, 6, 7, 8]}, {"c": bob, "s": 5, "p": [9, 10]}],
        )
        layers = room["whiteboard"]["layers"]
        self.assertEqual(len(layers), 2)
        self.assertEqual(layers[0]["authorName"], "Alice")
        self.assertEqual(len(layers[0]["strokes"]), 1)
        self.assertEqual(len(layers[1]["strokes"]), 2)

    def test_cannot_use_another_members_color(self):
        room = wb.join_board(self.room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        room = wb.apply_strokes(
            room, "u1", "Alice", [{"c": alice, "s": 4, "p": [1, 2, 3, 4]}]
        )
        room = wb.join_board(room, "u2", "Bob")
        with self.assertRaises(ValueError):
            wb.apply_strokes(
                room, "u2", "Bob", [{"c": alice, "s": 5, "p": [5, 6, 7, 8]}]
            )

    def test_claim_color_blocks_near_match(self):
        room = wb.join_board(self.room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        room = wb.join_board(room, "u2", "Bob")
        with self.assertRaises(ValueError):
            wb.claim_color(room, "u2", "Bob", alice)
        # Bob can claim a free palette color
        free = wb.PALETTE[3]["hex"]
        room = wb.claim_color(room, "u2", "Bob", free)
        bob = [l for l in room["whiteboard"]["layers"] if l["authorId"] == "u2"][0]
        self.assertIn(wb.normalize_hex(free), [wb.normalize_hex(c) for c in bob.get("extraColors") or []])

    def test_undo_only_affects_own_layer(self):
        room = wb.join_board(self.room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        room = wb.apply_strokes(
            room,
            "u1",
            "Alice",
            [{"c": alice, "s": 4, "p": [1, 2, 3, 4]}, {"c": alice, "s": 4, "p": [5, 6]}],
        )
        room = wb.join_board(room, "u2", "Bob")
        bob = [l for l in room["whiteboard"]["layers"] if l["authorId"] == "u2"][0]["assignedColor"]
        room = wb.apply_strokes(
            room, "u2", "Bob", [{"c": bob, "s": 5, "p": [7, 8, 9, 10]}]
        )
        room = wb.undo_stroke(room, "u1")
        by_id = {layer["authorId"]: layer for layer in room["whiteboard"]["layers"]}
        self.assertEqual(len(by_id["u1"]["strokes"]), 1)
        self.assertEqual(len(by_id["u2"]["strokes"]), 1)

    def test_undo_empty_raises(self):
        with self.assertRaises(ValueError):
            wb.undo_stroke(self.room, "u1")

    def test_clear_mine_leaves_others(self):
        room = wb.join_board(self.room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        room = wb.apply_strokes(
            room, "u1", "Alice", [{"c": alice, "s": 4, "p": [1, 2, 3, 4]}]
        )
        room = wb.join_board(room, "u2", "Bob")
        bob = [l for l in room["whiteboard"]["layers"] if l["authorId"] == "u2"][0]["assignedColor"]
        room = wb.apply_strokes(
            room, "u2", "Bob", [{"c": bob, "s": 5, "p": [5, 6, 7, 8]}]
        )
        room = wb.clear_mine(room, "u1")
        by_id = {layer["authorId"]: layer for layer in room["whiteboard"]["layers"]}
        self.assertEqual(by_id["u1"]["strokes"], [])
        self.assertEqual(by_id["u1"]["assignedColor"], alice)
        self.assertEqual(len(by_id["u2"]["strokes"]), 1)

    def test_shape_and_text_strokes(self):
        room = wb.join_board(self.room, "u1", "Alice")
        alice = room["whiteboard"]["layers"][0]["assignedColor"]
        room = wb.apply_strokes(
            room,
            "u1",
            "Alice",
            [
                {"t": "line", "c": alice, "s": 3, "p": [10, 10, 40, 40]},
                {"t": "arrow", "c": alice, "s": 3, "p": [10, 50, 80, 50]},
                {"t": "rect", "c": alice, "s": 2, "p": [5, 5, 30, 20]},
                {"t": "circle", "c": alice, "s": 2, "p": [40, 40, 70, 55]},
                {"t": "oval", "c": alice, "s": 2, "p": [80, 40, 120, 70]},
                {"t": "text", "c": alice, "s": 4, "p": [12, 12], "tx": "Valve"},
                {"t": "erase", "c": alice, "s": 8, "p": [1, 1, 2, 2, 3, 3]},
            ],
        )
        strokes = room["whiteboard"]["layers"][0]["strokes"]
        self.assertEqual(
            [s["t"] for s in strokes],
            ["line", "arrow", "rect", "circle", "oval", "text", "erase"],
        )
        self.assertEqual(strokes[5]["tx"], "Valve")
        # Eraser does not expand owned colors beyond assigned
        owned = wb.layer_colors(room["whiteboard"]["layers"][0])
        self.assertIn(wb.normalize_hex(alice), owned)


class CryptoPipeTest(unittest.TestCase):
    def test_node_crypto_suite(self):
        script = ROOT / "scripts" / "test-crypto.js"
        proc = subprocess.run(
            ["node", str(script)],
            cwd=str(ROOT),
            capture_output=True,
            text=True,
            timeout=60,
        )
        self.assertEqual(proc.returncode, 0, proc.stdout + proc.stderr)
        self.assertIn("OK wire", proc.stdout)


def main():
    loader = unittest.defaultTestLoader
    suite = unittest.TestSuite()
    suite.addTests(loader.loadTestsFromTestCase(RoomHelpersTest))
    suite.addTests(loader.loadTestsFromTestCase(RoomFileIsolationTest))
    suite.addTests(loader.loadTestsFromTestCase(PictionaryTest))
    suite.addTests(loader.loadTestsFromTestCase(WhiteboardTest))
    suite.addTests(loader.loadTestsFromTestCase(CryptoPipeTest))
    suite.addTests(loader.loadTestsFromTestCase(LiveServerTest))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main())
