"""Android device binding: mobile number + SSAID, optional Supabase table.

Set SUPABASE_URL and SUPABASE_SERVICE_KEY to store rows in Postgres.
Otherwise registrations are kept in a local JSON file (fine for tests / VPS).
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEVICES_PATH = ROOT / "data" / "devices.json"
# Prefixed so this table can live in a shared Supabase project with other apps.
SUPABASE_TABLE = "mychat_device_accounts"
_file_lock = threading.Lock()

MOBILE_RE = re.compile(r"^\d{10,15}$")
SSAID_RE = re.compile(r"^[0-9a-f]{8,32}$")


class DeviceAuthError(Exception):
    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.status = status


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def otp_secret() -> str:
    return os.environ.get("MYCHAT_DEVICE_SECRET") or "mychat-device-otp-v1"


def normalize_mobile(raw: str) -> str:
    digits = re.sub(r"\D", "", raw or "")
    if digits.startswith("91") and len(digits) == 12:
        digits = digits[2:]
    if not MOBILE_RE.match(digits):
        raise DeviceAuthError("Enter a valid mobile number")
    return digits


def normalize_ssaid(raw: str) -> str:
    value = re.sub(r"[^0-9a-fA-F]", "", raw or "").lower()
    if not SSAID_RE.match(value):
        raise DeviceAuthError("Invalid device id")
    return value


def make_otp(mobile: str, ssaid: str) -> str:
    """6-digit code derived from mobile + SSAID + current UTC hour."""
    slot = datetime.now(timezone.utc).strftime("%Y%m%d%H")
    digest = hmac.new(
        otp_secret().encode("utf-8"),
        f"{mobile}|{ssaid}|{slot}".encode("utf-8"),
        hashlib.sha256,
    ).digest()
    return f"{int.from_bytes(digest[:4], 'big') % 1_000_000:06d}"


def _use_supabase() -> bool:
    return bool(os.environ.get("SUPABASE_URL", "").strip() and os.environ.get("SUPABASE_SERVICE_KEY", "").strip())


def _supabase_request(method: str, path_query: str, body: dict | None = None) -> list | dict | None:
    base = os.environ["SUPABASE_URL"].rstrip("/")
    key = os.environ["SUPABASE_SERVICE_KEY"].strip()
    url = f"{base}/rest/v1/{path_query}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        method=method,
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "Prefer": "return=representation",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=12) as res:
            raw = res.read().decode("utf-8")
            if not raw:
                return None
            return json.loads(raw)
    except urllib.error.HTTPError as exc:
        exc.read()
        raise DeviceAuthError(f"Could not reach account store ({exc.code})", 503) from exc


def _supabase_get(mobile: str) -> dict | None:
    q = urllib.parse.urlencode({"mobile": f"eq.{mobile}", "select": "mobile,ssaid,created_at,last_seen_at"})
    rows = _supabase_request("GET", f"{SUPABASE_TABLE}?{q}")
    if not isinstance(rows, list) or not rows:
        return None
    row = rows[0]
    if not isinstance(row, dict):
        return None
    return row


def _file_load() -> dict:
    path = DEVICES_PATH
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _file_save(data: dict) -> None:
    path = DEVICES_PATH
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def _file_get(mobile: str) -> dict | None:
    with _file_lock:
        row = _file_load().get(mobile)
    return row if isinstance(row, dict) else None


def lookup(mobile: str) -> dict | None:
    if _use_supabase():
        row = _supabase_get(mobile)
        if not row:
            return None
        return {
            "mobile": str(row.get("mobile") or mobile),
            "ssaid": str(row.get("ssaid") or ""),
            "createdAt": str(row.get("created_at") or ""),
            "lastSeenAt": str(row.get("last_seen_at") or ""),
        }
    row = _file_get(mobile)
    if not row:
        return None
    return {
        "mobile": mobile,
        "ssaid": str(row.get("ssaid") or ""),
        "createdAt": str(row.get("createdAt") or ""),
        "lastSeenAt": str(row.get("lastSeenAt") or ""),
    }


def upsert(mobile: str, ssaid: str, created_at: str | None = None) -> None:
    now = utc_now()
    created = created_at or now
    if _use_supabase():
        existing = _supabase_get(mobile)
        if existing:
            q = urllib.parse.urlencode({"mobile": f"eq.{mobile}"})
            _supabase_request("PATCH", f"{SUPABASE_TABLE}?{q}", {"ssaid": ssaid, "last_seen_at": now})
            return
        _supabase_request(
            "POST",
            SUPABASE_TABLE,
            {"mobile": mobile, "ssaid": ssaid, "created_at": created, "last_seen_at": now},
        )
        return
    with _file_lock:
        data = _file_load()
        prev = data.get(mobile) if isinstance(data.get(mobile), dict) else {}
        data[mobile] = {
            "ssaid": ssaid,
            "createdAt": str(prev.get("createdAt") or created),
            "lastSeenAt": now,
        }
        _file_save(data)


def register(mobile_raw: str, ssaid_raw: str) -> dict:
    mobile = normalize_mobile(mobile_raw)
    ssaid = normalize_ssaid(ssaid_raw)
    existing = lookup(mobile)
    if existing and existing.get("ssaid") and existing["ssaid"] != ssaid:
        raise DeviceAuthError(
            "This mobile number is already registered on another device.",
            409,
        )
    upsert(mobile, ssaid, existing.get("createdAt") if existing else None)
    return {"ok": True, "mobile": mobile, "otp": make_otp(mobile, ssaid)}


def verify(mobile_raw: str, ssaid_raw: str) -> dict:
    mobile = normalize_mobile(mobile_raw)
    ssaid = normalize_ssaid(ssaid_raw)
    existing = lookup(mobile)
    if not existing or existing.get("ssaid") != ssaid:
        raise DeviceAuthError(
            "This phone does not match the registered device. The app cannot continue.",
            403,
        )
    upsert(mobile, ssaid, existing.get("createdAt"))
    return {"ok": True, "mobile": mobile}
