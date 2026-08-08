"""Pictionary game helpers for myChat rooms."""

from __future__ import annotations

import random
import re
from datetime import datetime, timezone

WORDS = [
    "apple", "airplane", "anchor", "arrow", "backpack", "banana", "baseball", "basket",
    "beach", "bicycle", "bird", "book", "bottle", "bridge", "broom", "butterfly",
    "cake", "camel", "camera", "candle", "cannon", "canoe", "carrot", "castle",
    "cat", "chair", "cheese", "cherry", "clock", "cloud", "coffee", "compass",
    "computer", "cookie", "crab", "crown", "diamond", "dinosaur", "doctor", "dog",
    "dolphin", "donut", "dragon", "drum", "duck", "eagle", "earth", "elephant",
    "envelope", "eye", "fire", "fish", "flag", "flower", "football", "frog",
    "ghost", "giraffe", "glasses", "guitar", "hammer", "hat", "heart", "helicopter",
    "house", "iceberg", "igloo", "island", "jacket", "kangaroo", "key", "kite",
    "ladder", "lamp", "leaf", "lemon", "lighthouse", "lion", "magnet", "map",
    "mermaid", "moon", "mountain", "mouse", "mushroom", "music", "ninja", "octopus",
    "onion", "owl", "paint", "panda", "parrot", "pencil", "penguin", "piano",
    "pizza", "planet", "rainbow", "robot", "rocket", "sandwich", "shark", "sheep",
    "shoe", "skeleton", "snail", "snake", "snowman", "soccer", "spider", "spoon",
    "star", "sun", "sushi", "sword", "tiger", "toast", "train", "tree",
    "trumpet", "turtle", "umbrella", "unicorn", "violin", "volcano", "watermelon",
    "whale", "window", "wizard", "zebra",
]


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def empty_pictionary() -> dict:
    return {
        "phase": "idle",
        "round": 0,
        "drawerId": None,
        "drawerName": None,
        "hint": "",
        "wordLen": 0,
        "drawing": None,
        "scores": {},
        "lastGuesses": [],
        "revealWord": None,
        "winnerName": None,
        "updatedAt": utc_now(),
    }


def make_hint(word: str) -> str:
    return " ".join("_" if ch.isalpha() else ch for ch in word)


def normalize_guess(text: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (text or "").strip().lower())


def public_pictionary(pic: dict | None) -> dict:
    data = dict(pic or empty_pictionary())
    data.pop("word", None)
    return data


def start_round(room: dict, author_id: str, author_name: str) -> tuple[dict, str]:
    pic = dict(room.get("pictionary") or empty_pictionary())
    word = random.choice(WORDS)
    pic.update(
        {
            "phase": "drawing",
            "round": int(pic.get("round") or 0) + 1,
            "drawerId": author_id,
            "drawerName": author_name,
            "hint": make_hint(word),
            "wordLen": len(word),
            "drawing": None,
            "lastGuesses": [],
            "revealWord": None,
            "winnerName": None,
            "updatedAt": utc_now(),
            "scores": dict(pic.get("scores") or {}),
        }
    )
    if author_name not in pic["scores"]:
        pic["scores"][author_name] = int(pic["scores"].get(author_name) or 0)
    room["pictionary"] = pic
    room["_picWord"] = word
    return room, word


def apply_drawing(room: dict, author_id: str, drawing: dict) -> dict:
    pic = dict(room.get("pictionary") or empty_pictionary())
    if pic.get("phase") != "drawing" or pic.get("drawerId") != author_id:
        raise PermissionError("Only the drawer can update the sketch")
    if not isinstance(drawing, dict):
        raise ValueError("Invalid drawing")
    # Keep payload bounded
    strokes = drawing.get("strokes") or []
    if not isinstance(strokes, list) or len(strokes) > 200:
        raise ValueError("Drawing too large")
    pic["drawing"] = {
        "w": int(drawing.get("w") or 300),
        "h": int(drawing.get("h") or 300),
        "strokes": strokes,
    }
    pic["updatedAt"] = utc_now()
    room["pictionary"] = pic
    return room


def apply_guess(room: dict, author_id: str, author_name: str, guess: str) -> tuple[dict, bool]:
    pic = dict(room.get("pictionary") or empty_pictionary())
    if pic.get("phase") != "drawing":
        raise PermissionError("No active round")
    if pic.get("drawerId") == author_id:
        raise PermissionError("Drawer cannot guess")

    word = str(room.get("_picWord") or "")
    cleaned = normalize_guess(guess)
    if not cleaned or len(cleaned) > 40:
        raise ValueError("Invalid guess")

    correct = cleaned == normalize_guess(word)
    guesses = list(pic.get("lastGuesses") or [])
    guesses.append(
        {
            "authorId": author_id,
            "authorName": author_name,
            "text": str(guess).strip()[:40],
            "correct": correct,
            "at": utc_now(),
        }
    )
    pic["lastGuesses"] = guesses[-12:]

    scores = dict(pic.get("scores") or {})
    if author_name not in scores:
        scores[author_name] = 0
    if pic.get("drawerName") and pic["drawerName"] not in scores:
        scores[pic["drawerName"]] = int(scores.get(pic["drawerName"]) or 0)

    if correct:
        scores[author_name] = int(scores.get(author_name) or 0) + 1
        drawer = pic.get("drawerName")
        if drawer:
            scores[drawer] = int(scores.get(drawer) or 0) + 1
        pic["scores"] = scores
        pic["phase"] = "reveal"
        pic["revealWord"] = word
        pic["winnerName"] = author_name
        pic["updatedAt"] = utc_now()
        room["pictionary"] = pic
        room.pop("_picWord", None)
        return room, True

    pic["scores"] = scores
    pic["updatedAt"] = utc_now()
    room["pictionary"] = pic
    return room, False


def skip_round(room: dict, author_id: str) -> dict:
    pic = dict(room.get("pictionary") or empty_pictionary())
    if pic.get("phase") != "drawing" or pic.get("drawerId") != author_id:
        raise PermissionError("Only the drawer can skip")
    word = str(room.get("_picWord") or "")
    pic["phase"] = "reveal"
    pic["revealWord"] = word or "?"
    pic["winnerName"] = None
    pic["updatedAt"] = utc_now()
    room["pictionary"] = pic
    room.pop("_picWord", None)
    return room
