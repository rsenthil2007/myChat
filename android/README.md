# Native myChat (Kotlin) — v0.2

Text Android client for the same `server.py` rooms as the web app.

**In this version:** join a room, send/receive **text sealed with the room code** (same `SecurePipe` as the browser).  
**Not yet:** sketches, voice, whiteboard (those still show as `[sketch]` / `[voice note]`).

The older WebInto wrap notes stay in `Wrap/`.

## Open in Android Studio

1. Install Android Studio (Koala / Ladybug or newer) with JDK 17 and an Android SDK.
2. **File → Open** this `android` folder (not the myChat repo root).
3. Let Gradle sync. First sync downloads the Android Gradle Plugin.
4. Start the web server from the myChat root: `python server.py`
5. Run on an emulator. Default server URL is `http://10.0.2.2:8080` (emulator → your PC).
6. On a physical phone, set **Server URL** to `http://YOUR-PC-LAN-IP:8080` (same Wi-Fi), or your HTTPS VPS later.

Use the **same room code** as the browser. Incoming web messages decrypt on the phone; texts you send from the phone decrypt in the browser.

## GitHub debug APK

Workflow: `.github/workflows/android-debug.yml`  
On push to `android/**` (or manual **Actions → Android debug APK → Run workflow**), GitHub builds `app-debug.apk` as artifact **mychat-debug-apk**.

## Next slices (later)

1. Live SSE instead of polling  
2. Sketches / voice
