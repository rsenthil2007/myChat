"""Android device binding: mobile number + SSAID, optional Supabase table.

Set SUPABASE_URL and SUPABASE_SERVICE_KEY to store rows in Postgres.
Otherwise registrations are kept in a local JSON file (fine for tests / VPS).
"""

from __future__ import annotations

import hashlib
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


def normalize_mobile(raw: str) -> str:
    digits = re.sub(r"\D", "", raw or "")
    if digits.startswith("91") and len(digits) == 12:
        digits = digits[2:]
    if not MOBILE_RE.match(digits):
        raise DeviceAuthError("Enter a valid mobile number")
    return digits


def normalize_ssaid(raw: str) -> str:
    raw = str(raw or "").strip()
    if not raw:
        raise DeviceAuthError("Invalid device id")
    value = re.sub(r"[^0-9a-fA-F]", "", raw).lower()
    if SSAID_RE.match(value):
        return value
    # Some phones return a non-hex ANDROID_ID; keep a stable 16-char bind key.
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def normalize_display_name(raw: str) -> str:
    name = re.sub(r"\s+", " ", (raw or "").strip())[:24]
    if len(name) < 2:
        raise DeviceAuthError("Enter a user name (at least 2 characters)")
    return name


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
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
        raise DeviceAuthError("Could not reach account store", 503) from exc


def _supabase_get(mobile: str) -> dict | None:
    q = urllib.parse.urlencode(
        {
            "mobile": f"eq.{mobile}",
            "select": "mobile,ssaid,firebase_uid,display_name,created_at,last_seen_at",
        }
    )
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
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".json.tmp")
        tmp.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
        tmp.replace(path)
    except OSError as exc:
        raise DeviceAuthError(f"Could not save device registration ({exc})", 500) from exc


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
            "firebaseUid": str(row.get("firebase_uid") or ""),
            "displayName": str(row.get("display_name") or ""),
            "createdAt": str(row.get("created_at") or ""),
            "lastSeenAt": str(row.get("last_seen_at") or ""),
        }
    row = _file_get(mobile)
    if not row:
        return None
    return {
        "mobile": mobile,
        "ssaid": str(row.get("ssaid") or ""),
        "firebaseUid": str(row.get("firebaseUid") or ""),
        "displayName": str(row.get("displayName") or ""),
        "createdAt": str(row.get("createdAt") or ""),
        "lastSeenAt": str(row.get("lastSeenAt") or ""),
    }


def upsert(
    mobile: str,
    ssaid: str,
    firebase_uid: str = "",
    display_name: str = "",
    created_at: str | None = None,
) -> None:
    now = utc_now()
    created = created_at or now
    if _use_supabase():
        existing = _supabase_get(mobile)
        if existing:
            patch = {"ssaid": ssaid, "last_seen_at": now}
            if firebase_uid:
                patch["firebase_uid"] = firebase_uid
            if display_name and not existing.get("display_name"):
                patch["display_name"] = display_name
            q = urllib.parse.urlencode({"mobile": f"eq.{mobile}"})
            _supabase_request("PATCH", f"{SUPABASE_TABLE}?{q}", patch)
            return
        _supabase_request(
            "POST",
            SUPABASE_TABLE,
            {
                "mobile": mobile,
                "ssaid": ssaid,
                "firebase_uid": firebase_uid,
                "display_name": display_name,
                "created_at": created,
                "last_seen_at": now,
            },
        )
        return
    with _file_lock:
        data = _file_load()
        prev = data.get(mobile) if isinstance(data.get(mobile), dict) else {}
        stored_name = str(prev.get("displayName") or "") or display_name
        stored_uid = str(prev.get("firebaseUid") or "") or firebase_uid
        data[mobile] = {
            "ssaid": ssaid,
            "firebaseUid": stored_uid,
            "displayName": stored_name,
            "createdAt": str(prev.get("createdAt") or created),
            "lastSeenAt": now,
        }
        _file_save(data)


def _claims_from_token(id_token: str) -> dict:
    import firebase_auth as fb

    try:
        claims = fb.verify_id_token(id_token)
    except fb.FirebaseTokenError as exc:
        raise DeviceAuthError(str(exc), exc.status) from exc
    mobile = normalize_mobile(str(claims.get("phone") or ""))
    uid = str(claims.get("uid") or "")
    if not uid:
        raise DeviceAuthError("Invalid Firebase user", 401)
    return {"mobile": mobile, "uid": uid}


def register(id_token: str, ssaid_raw: str, display_name_raw: str) -> dict:
    claims = _claims_from_token(id_token)
    mobile = claims["mobile"]
    uid = claims["uid"]
    ssaid = normalize_ssaid(ssaid_raw)
    display_name = normalize_display_name(display_name_raw)
    existing = lookup(mobile)
    if existing and existing.get("ssaid") and existing["ssaid"] != ssaid:
        raise DeviceAuthError(
            "This mobile number is already registered on another device.",
            409,
        )
    upsert(
        mobile,
        ssaid,
        firebase_uid=uid,
        display_name=display_name,
        created_at=existing.get("createdAt") if existing else None,
    )
    stored = lookup(mobile) or {}
    return {
        "ok": True,
        "mobile": mobile,
        "displayName": stored.get("displayName") or display_name,
    }


def verify(id_token: str, ssaid_raw: str) -> dict:
    claims = _claims_from_token(id_token)
    mobile = claims["mobile"]
    ssaid = normalize_ssaid(ssaid_raw)
    existing = lookup(mobile)
    if not existing or existing.get("ssaid") != ssaid:
        raise DeviceAuthError(
            "This phone does not match the registered device. The app cannot continue.",
            403,
        )
    upsert(
        mobile,
        ssaid,
        firebase_uid=claims["uid"],
        display_name=str(existing.get("displayName") or ""),
        created_at=existing.get("createdAt"),
    )
    return {
        "ok": True,
        "mobile": mobile,
        "displayName": str(existing.get("displayName") or ""),
    }
