"""Android device binding: mobile number + SSAID, optional Supabase table.

Set SUPABASE_URL and SUPABASE_SERVICE_KEY to store rows in Postgres.
Otherwise registrations are kept in a local JSON file (fine for tests / VPS).

New accounts are pending until an admin admits them. Firebase SMS register()
still exists for later; the app currently uses request_access() instead.

Admin numbers: MYCHAT_ADMIN_MOBILES (comma-separated) and/or isAdmin on a row.
Existing devices.json rows that have no status field are treated as admitted
admins (the first phone already on the VPS).
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re
import threading
import time
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
DEFAULT_WEB_OTP = "246810"


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


def configured_admin_mobiles() -> set[str]:
    raw = os.environ.get("MYCHAT_ADMIN_MOBILES", "").strip()
    out: set[str] = set()
    for part in raw.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            out.add(normalize_mobile(part))
        except DeviceAuthError:
            continue
    return out


def web_admin_enabled() -> bool:
    return os.environ.get("MYCHAT_ADMIN_WEB", "1").strip() not in ("0", "false", "False")


def web_admin_otp() -> str:
    return os.environ.get("MYCHAT_ADMIN_WEB_OTP", DEFAULT_WEB_OTP).strip() or DEFAULT_WEB_OTP


def _admin_secret() -> bytes:
    raw = (
        os.environ.get("MYCHAT_ADMIN_SECRET", "").strip()
        or os.environ.get("MYCHAT_DEVICE_SECRET", "").strip()
        or "mychat-admin-dev"
    )
    return raw.encode("utf-8")


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
            "select": "mobile,ssaid,firebase_uid,display_name,status,is_admin,created_at,last_seen_at",
        }
    )
    rows = _supabase_request("GET", f"{SUPABASE_TABLE}?{q}")
    if not isinstance(rows, list) or not rows:
        return None
    row = rows[0]
    if not isinstance(row, dict):
        return None
    return row


def _supabase_list() -> list[dict]:
    q = urllib.parse.urlencode(
        {
            "select": "mobile,ssaid,firebase_uid,display_name,status,is_admin,created_at,last_seen_at",
            "order": "created_at.asc",
        }
    )
    rows = _supabase_request("GET", f"{SUPABASE_TABLE}?{q}")
    if not isinstance(rows, list):
        return []
    return [row for row in rows if isinstance(row, dict)]


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


def _public_row(mobile: str, row: dict, *, from_supabase: bool = False) -> dict:
    if from_supabase:
        has_status = "status" in row and row.get("status") not in (None, "")
        status = str(row.get("status") or "").strip().lower() or "admitted"
        named = str(row.get("display_name") or "")
        uid = str(row.get("firebase_uid") or "")
        created = str(row.get("created_at") or "")
        seen = str(row.get("last_seen_at") or "")
        ssaid = str(row.get("ssaid") or "")
        flagged = row.get("is_admin")
    else:
        has_status = "status" in row
        status = str(row.get("status") or "").strip().lower() or "admitted"
        named = str(row.get("displayName") or "")
        uid = str(row.get("firebaseUid") or "")
        created = str(row.get("createdAt") or "")
        seen = str(row.get("lastSeenAt") or "")
        ssaid = str(row.get("ssaid") or "")
        flagged = row.get("isAdmin")
    if status not in ("pending", "admitted", "rejected"):
        status = "admitted"
    admins = configured_admin_mobiles()
    is_admin = mobile in admins
    if flagged is True or str(flagged).lower() in ("1", "true"):
        is_admin = True
    # Legacy VPS rows (no status field) are the original phone(s) — treat as admin
    # unless MYCHAT_ADMIN_MOBILES is set and this number is not on that list.
    if not has_status:
        status = "admitted"
        if not admins:
            is_admin = True
    return {
        "mobile": mobile,
        "ssaid": ssaid,
        "firebaseUid": uid,
        "displayName": named,
        "status": status,
        "isAdmin": is_admin,
        "createdAt": created,
        "lastSeenAt": seen,
    }


def lookup(mobile: str) -> dict | None:
    if _use_supabase():
        row = _supabase_get(mobile)
        if not row:
            return None
        return _public_row(str(row.get("mobile") or mobile), row, from_supabase=True)
    row = _file_get(mobile)
    if not row:
        return None
    return _public_row(mobile, row)


def list_accounts() -> list[dict]:
    if _use_supabase():
        return [_public_row(str(row.get("mobile") or ""), row, from_supabase=True) for row in _supabase_list() if row.get("mobile")]
    with _file_lock:
        data = _file_load()
    out = []
    for mobile, row in data.items():
        if isinstance(row, dict):
            out.append(_public_row(str(mobile), row))
    out.sort(key=lambda r: r.get("createdAt") or "")
    return out


def upsert(
    mobile: str,
    ssaid: str,
    firebase_uid: str = "",
    display_name: str = "",
    created_at: str | None = None,
    status: str | None = None,
    is_admin: bool | None = None,
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
            if status:
                patch["status"] = status
            if is_admin is not None:
                patch["is_admin"] = is_admin
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
                "status": status or "pending",
                "is_admin": bool(is_admin),
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
        stored_status = status or str(prev.get("status") or "pending")
        stored_admin = prev.get("isAdmin")
        if is_admin is not None:
            stored_admin = is_admin
        elif stored_admin is None:
            stored_admin = False
        data[mobile] = {
            "ssaid": ssaid,
            "firebaseUid": stored_uid,
            "displayName": stored_name,
            "status": stored_status,
            "isAdmin": bool(stored_admin),
            "createdAt": str(prev.get("createdAt") or created),
            "lastSeenAt": now,
        }
        _file_save(data)


def is_admin(mobile: str) -> bool:
    try:
        normalized = normalize_mobile(mobile)
    except DeviceAuthError:
        return False
    row = lookup(normalized)
    return bool(row and row.get("isAdmin") and row.get("status") == "admitted")


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


def _bind_guard(existing: dict | None, ssaid: str) -> None:
    if existing and existing.get("ssaid") and existing["ssaid"] != ssaid:
        raise DeviceAuthError(
            "This mobile number is already registered on another device.",
            409,
        )


def _account_payload(stored: dict, display_name: str = "") -> dict:
    status = str(stored.get("status") or "pending")
    return {
        "ok": True,
        "mobile": stored.get("mobile"),
        "displayName": stored.get("displayName") or display_name,
        "status": status,
        "isAdmin": bool(stored.get("isAdmin")),
    }


def request_access(mobile_raw: str, ssaid_raw: str, display_name_raw: str) -> dict:
    mobile = normalize_mobile(mobile_raw)
    ssaid = normalize_ssaid(ssaid_raw)
    display_name = normalize_display_name(display_name_raw)
    existing = lookup(mobile)
    _bind_guard(existing, ssaid)
    auto_admin = mobile in configured_admin_mobiles() or bool(existing and existing.get("isAdmin"))
    if existing and existing.get("status") == "admitted":
        upsert(
            mobile,
            ssaid,
            display_name=display_name,
            created_at=existing.get("createdAt"),
            status="admitted",
            is_admin=True if auto_admin else None,
        )
        stored = lookup(mobile) or existing
        return _account_payload(stored, display_name)
    status = "admitted" if auto_admin else "pending"
    upsert(
        mobile,
        ssaid,
        display_name=display_name,
        created_at=existing.get("createdAt") if existing else None,
        status=status,
        is_admin=auto_admin,
    )
    stored = lookup(mobile) or {}
    return _account_payload(stored, display_name)


def check(mobile_raw: str, ssaid_raw: str) -> dict:
    mobile = normalize_mobile(mobile_raw)
    ssaid = normalize_ssaid(ssaid_raw)
    existing = lookup(mobile)
    if not existing:
        raise DeviceAuthError("This phone is not registered yet. Request access first.", 404)
    if existing.get("ssaid") != ssaid:
        raise DeviceAuthError(
            "This phone does not match the registered device. The app cannot continue.",
            403,
        )
    if existing.get("status") == "rejected":
        raise DeviceAuthError("This registration was declined. Contact the admin.", 403)
    upsert(
        mobile,
        ssaid,
        display_name=str(existing.get("displayName") or ""),
        created_at=existing.get("createdAt"),
        status=str(existing.get("status") or "pending"),
        is_admin=bool(existing.get("isAdmin")),
    )
    stored = lookup(mobile) or existing
    return _account_payload(stored)


def register(id_token: str, ssaid_raw: str, display_name_raw: str) -> dict:
    claims = _claims_from_token(id_token)
    mobile = claims["mobile"]
    uid = claims["uid"]
    ssaid = normalize_ssaid(ssaid_raw)
    display_name = normalize_display_name(display_name_raw)
    existing = lookup(mobile)
    _bind_guard(existing, ssaid)
    auto_admin = mobile in configured_admin_mobiles() or bool(existing and existing.get("isAdmin"))
    upsert(
        mobile,
        ssaid,
        firebase_uid=uid,
        display_name=display_name,
        created_at=existing.get("createdAt") if existing else None,
        status="admitted",
        is_admin=auto_admin,
    )
    stored = lookup(mobile) or {}
    return _account_payload(stored, display_name)


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
    if existing.get("status") == "rejected":
        raise DeviceAuthError("This registration was declined. Contact the admin.", 403)
    if existing.get("status") == "pending":
        raise DeviceAuthError("Waiting for admin approval.", 403)
    upsert(
        mobile,
        ssaid,
        firebase_uid=claims["uid"],
        display_name=str(existing.get("displayName") or ""),
        created_at=existing.get("createdAt"),
        status="admitted",
        is_admin=bool(existing.get("isAdmin")),
    )
    stored = lookup(mobile) or existing
    return _account_payload(stored)


def list_requests(include_all: bool = False) -> list[dict]:
    rows = list_accounts()
    if include_all:
        visible = rows
    else:
        visible = [row for row in rows if row.get("status") == "pending"]
    return [
        {
            "mobile": row["mobile"],
            "displayName": row.get("displayName") or "",
            "status": row.get("status"),
            "isAdmin": bool(row.get("isAdmin")),
            "createdAt": row.get("createdAt") or "",
            "ssaidTail": str(row.get("ssaid") or "")[-4:],
        }
        for row in visible
    ]


def admit(mobile_raw: str) -> dict:
    mobile = normalize_mobile(mobile_raw)
    existing = lookup(mobile)
    if not existing:
        raise DeviceAuthError("No request for that number", 404)
    upsert(
        mobile,
        str(existing.get("ssaid") or ""),
        display_name=str(existing.get("displayName") or ""),
        created_at=existing.get("createdAt"),
        status="admitted",
        is_admin=bool(existing.get("isAdmin")),
    )
    stored = lookup(mobile) or existing
    return _account_payload(stored)


def reject(mobile_raw: str) -> dict:
    mobile = normalize_mobile(mobile_raw)
    existing = lookup(mobile)
    if not existing:
        raise DeviceAuthError("No request for that number", 404)
    if existing.get("isAdmin"):
        raise DeviceAuthError("Cannot reject an admin account", 400)
    upsert(
        mobile,
        str(existing.get("ssaid") or ""),
        display_name=str(existing.get("displayName") or ""),
        created_at=existing.get("createdAt"),
        status="rejected",
        is_admin=False,
    )
    stored = lookup(mobile) or existing
    return _account_payload(stored)


def issue_admin_token(mobile: str) -> str:
    exp = int(time.time()) + 12 * 3600
    payload = f"{mobile}:{exp}"
    sig = hmac.new(_admin_secret(), payload.encode("utf-8"), hashlib.sha256).hexdigest()[:32]
    return f"{payload}:{sig}"


def parse_admin_token(token: str) -> str:
    raw = (token or "").strip()
    if raw.lower().startswith("bearer "):
        raw = raw[7:].strip()
    parts = raw.split(":")
    if len(parts) != 3:
        raise DeviceAuthError("Admin sign-in required", 401)
    mobile, exp_s, sig = parts
    try:
        exp = int(exp_s)
    except ValueError as exc:
        raise DeviceAuthError("Admin sign-in required", 401) from exc
    payload = f"{mobile}:{exp_s}"
    expected = hmac.new(_admin_secret(), payload.encode("utf-8"), hashlib.sha256).hexdigest()[:32]
    if not hmac.compare_digest(sig, expected):
        raise DeviceAuthError("Admin sign-in required", 401)
    if exp < int(time.time()):
        raise DeviceAuthError("Admin session expired. Sign in again.", 401)
    if not is_admin(mobile):
        raise DeviceAuthError("Not an admin number", 403)
    return mobile


def admin_session_from_device(mobile_raw: str, ssaid_raw: str) -> dict:
    result = check(mobile_raw, ssaid_raw)
    if not result.get("isAdmin") or result.get("status") != "admitted":
        raise DeviceAuthError("Not an admin number", 403)
    mobile = str(result["mobile"])
    return {"ok": True, "token": issue_admin_token(mobile), "mobile": mobile}


def admin_web_login(mobile_raw: str, otp_raw: str) -> dict:
    if not web_admin_enabled():
        raise DeviceAuthError("Web admin is disabled", 403)
    mobile = normalize_mobile(mobile_raw)
    otp = re.sub(r"\D", "", otp_raw or "")
    expected = re.sub(r"\D", "", web_admin_otp())
    if not otp or not hmac.compare_digest(otp, expected):
        raise DeviceAuthError("Wrong number or OTP", 401)
    if not is_admin(mobile):
        raise DeviceAuthError("Not an admin number", 403)
    return {"ok": True, "token": issue_admin_token(mobile), "mobile": mobile}
