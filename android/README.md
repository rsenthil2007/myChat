# Native myChat (Kotlin) — v0.3

Android client for the same `server.py` rooms as the web app.

**In this version:** sealed **text**, **sketches**, and **voice notes**, plus **clear chat** for everyone.  
**Not yet:** shared whiteboard tab (and Pictionary, which is hidden on the web too).

The older WebInto wrap notes stay in `Wrap/`.

## Open in Android Studio

1. Install Android Studio (Koala / Ladybug or newer) with JDK 17 and an Android SDK.
2. **File → Open** this `android` folder (not the myChat repo root).
3. Let Gradle sync. First sync downloads the Android Gradle Plugin.
4. Start the web server from the myChat root: `python server.py`
5. Run on an emulator. Default server URL is `http://10.0.2.2:8080` (emulator → your PC).
6. On a physical phone, set **Server URL** to `http://YOUR-PC-LAN-IP:8080` (same Wi-Fi), or your HTTPS VPS later.

Use the **same room code** as the browser. Sketches and voice notes go through the same room-code seal as the web client.

## GitHub debug APK

Workflow: `.github/workflows/android-debug.yml`  
On push to `android/**` (or manual **Actions → Android debug APK → Run workflow**), GitHub builds `app-debug.apk` as artifact **mychat-debug-apk**.

## Next slices (later)

1. Shared Board tab  
2. Live SSE instead of polling  
