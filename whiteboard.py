"""Shared whiteboard helpers — one transparent layer per author + unique colors."""

from __future__ import annotations

import re
from datetime import datetime, timezone

MAX_LAYERS = 24
MAX_STROKES_PER_LAYER = 400
# RGB Euclidean distance under this counts as "same" color across members
COLOR_NEAR_THRESHOLD = 48

# Fixed room palette — assigned one-per-member on join; also offered as swatches
PALETTE = [
    {"id": "black", "name": "Black", "hex": "#0f172a"},
    {"id": "red", "name": "Red", "hex": "#dc2626"},
    {"id": "blue", "name": "Blue", "hex": "#2563eb"},
    {"id": "green", "name": "Green", "hex": "#16a34a"},
    {"id": "orange", "name": "Orange", "hex": "#ea580c"},
    {"id": "purple", "name": "Purple", "hex": "#7c3aed"},
    {"id": "teal", "name": "Teal", "hex": "#0d9488"},
    {"id": "brown", "name": "Brown", "hex": "#92400e"},
    {"id": "pink", "name": "Pink", "hex": "#db2777"},
    {"id": "navy", "name": "Navy", "hex": "#1e3a8a"},
    {"id": "olive", "name": "Olive", "hex": "#4d7c0f"},
    {"id": "gray", "name": "Gray", "hex": "#64748b"},
]

_HEX_RE = re.compile(r"^#?[0-9a-fA-F]{6}$")


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def empty_whiteboard() -> dict:
    return {
        "w": 0,
        "h": 0,
        "layers": [],
        "palette": list(PALETTE),
        "updatedAt": utc_now(),
    }


def public_whiteboard(board: dict | None) -> dict:
    data = board or empty_whiteboard()
    return {
        "w": int(data.get("w") or 0),
        "h": int(data.get("h") or 0),
        "layers": list(data.get("layers") or []),
        "palette": list(data.get("palette") or PALETTE),
        "updatedAt": data.get("updatedAt") or utc_now(),
    }


def normalize_hex(value: str | None, fallback: str = "#0f172a") -> str:
    raw = str(value or "").strip()
    if not raw:
        return fallback
    if not raw.startswith("#"):
        raw = "#" + raw
    if not _HEX_RE.match(raw):
        return fallback
    return raw.lower()


def _hex_to_rgb(hex_color: str) -> tuple[int, int, int]:
    h = normalize_hex(hex_color)
    return int(h[1:3], 16), int(h[3:5], 16), int(h[5:7], 16)


def colors_near(a: str, b: str, threshold: int = COLOR_NEAR_THRESHOLD) -> bool:
    ar, ag, ab = _hex_to_rgb(a)
    br, bg, bb = _hex_to_rgb(b)
    dist = ((ar - br) ** 2 + (ag - bg) ** 2 + (ab - bb) ** 2) ** 0.5
    return dist <= threshold


def _ensure_board(room: dict) -> dict:
    board = room.get("whiteboard")
    if not isinstance(board, dict):
        board = empty_whiteboard()
    if not isinstance(board.get("layers"), list):
        board["layers"] = []
    if not board.get("palette"):
        board["palette"] = list(PALETTE)
    room["whiteboard"] = board
    return board


def _find_layer(board: dict, author_id: str) -> tuple[int, dict | None]:
    layers = board.get("layers") or []
    for i, layer in enumerate(layers):
        if isinstance(layer, dict) and layer.get("authorId") == author_id:
            return i, layer
    return -1, None


def layer_colors(layer: dict | None) -> set[str]:
    """Colors owned by a layer: assigned + every non-eraser stroke color."""
    if not layer:
        return set()
    owned: set[str] = set()
    if layer.get("assignedColor"):
        owned.add(normalize_hex(layer["assignedColor"]))
    for stroke in layer.get("strokes") or []:
        if not isinstance(stroke, dict):
            continue
        kind = str(stroke.get("t") or stroke.get("type") or "pen")
        if kind == "erase":
            continue
        owned.add(normalize_hex(stroke.get("c") or stroke.get("color")))
    for extra in layer.get("extraColors") or []:
        owned.add(normalize_hex(extra))
    return owned


def others_colors(board: dict, author_id: str) -> set[str]:
    owned: set[str] = set()
    for layer in board.get("layers") or []:
        if not isinstance(layer, dict):
            continue
        if layer.get("authorId") == author_id:
            continue
        owned |= layer_colors(layer)
    return owned


def color_taken_by_others(board: dict, author_id: str, color: str) -> bool:
    candidate = normalize_hex(color)
    for other in others_colors(board, author_id):
        if colors_near(candidate, other):
            return True
    return False


def _pick_assigned_color(board: dict, author_id: str) -> str:
    taken = others_colors(board, author_id)
    palette = board.get("palette") or PALETTE
    for swatch in palette:
        hex_color = normalize_hex(swatch.get("hex"))
        if not any(colors_near(hex_color, t) for t in taken):
            return hex_color
    # Palette exhausted — generate distinct-ish fallbacks
    for i in range(24):
        fallback = normalize_hex(f"#{(i * 97) % 256:02x}{(i * 57) % 256:02x}{(i * 37) % 256:02x}")
        if not any(colors_near(fallback, t) for t in taken):
            return fallback
    return "#0f172a"


def join_board(room: dict, author_id: str, author_name: str) -> dict:
    """Ensure the member has a layer with a unique assigned color."""
    if not author_id:
        raise ValueError("authorId required")
    board = _ensure_board(room)
    idx, layer = _find_layer(board, author_id)
    if layer is None:
        if len(board["layers"]) >= MAX_LAYERS:
            raise ValueError(f"Too many layers (max {MAX_LAYERS})")
        assigned = _pick_assigned_color(board, author_id)
        layer = {
            "authorId": author_id,
            "authorName": (author_name or "Guest")[:24],
            "assignedColor": assigned,
            "extraColors": [],
            "strokes": [],
            "updatedAt": utc_now(),
        }
        board["layers"].append(layer)
    else:
        layer["authorName"] = (author_name or layer.get("authorName") or "Guest")[:24]
        if not layer.get("assignedColor"):
            layer["assignedColor"] = _pick_assigned_color(board, author_id)
        if not isinstance(layer.get("extraColors"), list):
            layer["extraColors"] = []
        board["layers"][idx] = layer
    board["updatedAt"] = utc_now()
    room["whiteboard"] = board
    return room


def claim_color(room: dict, author_id: str, author_name: str, color: str) -> dict:
    """Reserve an extra drawing color for this member (must not match others)."""
    if not author_id:
        raise ValueError("authorId required")
    room = join_board(room, author_id, author_name)
    board = _ensure_board(room)
    idx, layer = _find_layer(board, author_id)
    assert layer is not None
    hex_color = normalize_hex(color)
    if color_taken_by_others(board, author_id, hex_color):
        raise ValueError("That color is already used by someone else in this room")
    mine = layer_colors(layer)
    if not any(colors_near(hex_color, m) for m in mine):
        extras = list(layer.get("extraColors") or [])
        extras.append(hex_color)
        # keep extras lean
        layer["extraColors"] = extras[-12:]
    layer["updatedAt"] = utc_now()
    board["layers"][idx] = layer
    board["updatedAt"] = utc_now()
    room["whiteboard"] = board
    return room


ALLOWED_STROKE_TYPES = {"pen", "erase", "line", "arrow", "rect", "circle", "oval", "text"}


def _normalize_strokes(raw, board: dict, author_id: str) -> list:
    if not isinstance(raw, list):
        raise ValueError("strokes must be a list")
    if len(raw) > MAX_STROKES_PER_LAYER:
        raise ValueError(f"Too many strokes (max {MAX_STROKES_PER_LAYER})")
    out = []
    for item in raw:
        if not isinstance(item, dict):
            continue
        kind = str(item.get("t") or item.get("type") or "pen").lower()
        if kind not in ALLOWED_STROKE_TYPES:
            kind = "pen"
        pts = item.get("p") or item.get("points") or []
        if not isinstance(pts, list):
            continue
        if kind == "text":
            if len(pts) < 2:
                continue
            text = str(item.get("tx") or item.get("text") or "").strip()[:80]
            if not text:
                continue
            color = normalize_hex(item.get("c") or item.get("color"))
            if color_taken_by_others(board, author_id, color):
                raise ValueError("Stroke color conflicts with another member — pick a free color")
            out.append(
                {
                    "t": "text",
                    "c": color,
                    "s": max(1, min(48, float(item.get("s") or item.get("size") or 4))),
                    "p": [float(pts[0]), float(pts[1])],
                    "tx": text,
                }
            )
            continue

        min_pts = 4 if kind in ("line", "arrow", "rect", "circle", "oval") else 2
        if len(pts) < min_pts:
            continue
        if len(pts) > 4000:
            raise ValueError("Stroke too long")
        color = normalize_hex(item.get("c") or item.get("color"))
        if kind != "erase" and color_taken_by_others(board, author_id, color):
            raise ValueError("Stroke color conflicts with another member — pick a free color")
        entry = {
            "t": kind,
            "c": color,
            "s": max(1, min(48, float(item.get("s") or item.get("size") or 4))),
            "p": [float(x) for x in pts],
        }
        out.append(entry)
    return out


def apply_strokes(
    room: dict,
    author_id: str,
    author_name: str,
    strokes,
    width: int | None = None,
    height: int | None = None,
) -> dict:
    """Replace the author's layer strokes (other layers untouched)."""
    if not author_id:
        raise ValueError("authorId required")
    room = join_board(room, author_id, author_name)
    board = _ensure_board(room)
    clean = _normalize_strokes(strokes, board, author_id)

    idx, layer = _find_layer(board, author_id)
    assert layer is not None
    layer["authorName"] = (author_name or layer.get("authorName") or "Guest")[:24]
    layer["strokes"] = clean
    layer["updatedAt"] = utc_now()
    board["layers"][idx] = layer

    if width and width > 0:
        board["w"] = int(width)
    if height and height > 0:
        board["h"] = int(height)
    board["updatedAt"] = utc_now()
    room["whiteboard"] = board
    return room


def undo_stroke(room: dict, author_id: str) -> dict:
    """Remove only the last stroke on the caller's own layer."""
    if not author_id:
        raise ValueError("authorId required")
    board = _ensure_board(room)
    idx, layer = _find_layer(board, author_id)
    if layer is None:
        raise ValueError("No layer for this author")
    strokes = list(layer.get("strokes") or [])
    if not strokes:
        raise ValueError("Nothing to undo on your layer")
    strokes.pop()
    layer["strokes"] = strokes
    layer["updatedAt"] = utc_now()
    board["layers"][idx] = layer
    board["updatedAt"] = utc_now()
    room["whiteboard"] = board
    return room


def clear_mine(room: dict, author_id: str) -> dict:
    """Clear only the caller's layer strokes (keeps assigned color)."""
    if not author_id:
        raise ValueError("authorId required")
    board = _ensure_board(room)
    idx, layer = _find_layer(board, author_id)
    if layer is None:
        return room
    layer["strokes"] = []
    layer["extraColors"] = []
    layer["updatedAt"] = utc_now()
    board["layers"][idx] = layer
    board["updatedAt"] = utc_now()
    room["whiteboard"] = board
    return room


def clear_all(room: dict) -> dict:
    board = empty_whiteboard()
    room["whiteboard"] = board
    return room


def palette_name(hex_color: str) -> str | None:
    target = normalize_hex(hex_color)
    for swatch in PALETTE:
        if normalize_hex(swatch["hex"]) == target:
            return swatch["name"]
    return None
