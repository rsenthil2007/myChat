# Native myChat (Kotlin) — v0.1

Text-only Android client for the same `server.py` rooms as the web app.

**In this version:** join a room, send/receive text, poll every 2 seconds.  
**Not yet:** sketches, voice, whiteboard, sealed messages (those show as `[encrypted — open in web myChat]`).

The older WebInto wrap notes stay in `Wrap/`.

## Open in Android Studio

1. Install Android Studio (Koala / Ladybug or newer) with JDK 17 and an Android SDK.
2. **File → Open** this `android` folder (not the myChat repo root).
3. Let Gradle sync. First sync downloads the Android Gradle Plugin.
4. Start the web server from the myChat root: `python server.py`
5. Run on an emulator. Default server URL is `http://10.0.2.2:8080` (emulator → your PC).
6. On a physical phone, set **Server URL** to `http://YOUR-PC-LAN-IP:8080` (same Wi-Fi), or your HTTPS VPS later.

## GitHub debug APK

Workflow: `.github/workflows/android-debug.yml`  
On push to `android/**` (or manual **Run workflow**), GitHub builds `app-debug.apk` as an artifact.

## Next slices (later)

1. Live SSE instead of polling  
2. Decrypt sealed text with the room code  
3. Sketches / voice
