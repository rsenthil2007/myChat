"""Verify Firebase ID tokens (Phone Auth). Test tokens allowed only when enabled."""

from __future__ import annotations

import json
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent
_firebase_ready: bool | None = None


class FirebaseTokenError(Exception):
    def __init__(self, message: str, status: int = 401):
        super().__init__(message)
        self.status = status


def _init_firebase() -> bool:
    global _firebase_ready
    if _firebase_ready is not None:
        return _firebase_ready
    cred_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    cred_path = os.environ.get("FIREBASE_CREDENTIALS", "").strip()
    default_path = ROOT / "data" / "firebase-service-account.json"
    try:
        import firebase_admin
        from firebase_admin import credentials
    except ImportError:
        _firebase_ready = False
        return False
    if firebase_admin._apps:
        _firebase_ready = True
        return True
    try:
        if cred_json:
            info = json.loads(cred_json)
            cred = credentials.Certificate(info)
        elif cred_path and Path(cred_path).exists():
            cred = credentials.Certificate(cred_path)
        elif default_path.exists():
            cred = credentials.Certificate(str(default_path))
        else:
            _firebase_ready = False
            return False
        firebase_admin.initialize_app(cred)
        _firebase_ready = True
        return True
    except Exception:
        _firebase_ready = False
        return False


def verify_id_token(id_token: str) -> dict:
    """Return {uid, phone} from a Firebase ID token. phone is E.164 or digits."""
    token = (id_token or "").strip()
    if not token:
        raise FirebaseTokenError("Sign in with SMS first", 401)

    if os.environ.get("MYCHAT_ALLOW_TEST_TOKENS", "").strip() in ("1", "true", "True") and token.startswith("test:"):
        parts = token.split(":")
        if len(parts) < 3:
            raise FirebaseTokenError("Invalid test token", 401)
        phone = parts[1].strip()
        uid = parts[2].strip() or "test-uid"
        if not phone or not uid:
            raise FirebaseTokenError("Invalid test token", 401)
        return {"uid": uid, "phone": phone}

    if not _init_firebase():
        raise FirebaseTokenError("Firebase is not configured on the server", 503)
    try:
        from firebase_admin import auth as fb_auth

        decoded = fb_auth.verify_id_token(token)
    except Exception as exc:  # noqa: BLE001
        raise FirebaseTokenError("Invalid or expired SMS session. Request a new code.", 401) from exc
    uid = str(decoded.get("uid") or "")
    phone = str(decoded.get("phone_number") or "")
    if not uid:
        raise FirebaseTokenError("Invalid Firebase user", 401)
    if not phone:
        raise FirebaseTokenError("Firebase account has no phone number", 401)
    return {"uid": uid, "phone": phone}
