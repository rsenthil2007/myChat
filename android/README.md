# Native myChat (Kotlin) — v0.4.3

Android client for the same `server.py` rooms as the web app.

**In this version:** sealed **text**, **sketches**, **voice notes**, **clear chat**, and a shared **Board** tab.  
The board uses a fixed **1280×1600** (4:5) logical canvas on every device (phone, laptop, APK), so strokes line up and the phone board can use most of the remaining height.

**Not yet:** Pictionary (hidden on the web too), live SSE (the app still polls every 2s).

The older WebInto wrap notes stay in `Wrap/`.

## Open in Android Studio

1. Install Android Studio (Koala / Ladybug or newer) with JDK 17 and an Android SDK.
2. **File → Open** this `android` folder (not the myChat repo root).
3. Let Gradle sync. First sync downloads the Android Gradle Plugin.
4. Default **Server URL** is `https://chat.microbear.in`. For a local `python server.py`, use `http://10.0.2.2:8080` on the emulator, or `http://YOUR-PC-LAN-IP:8080` on a phone.

Use the **same room code** as the browser. Chat and board share that room.

Redeploy `server.py` / `whiteboard.py` on the VPS so rooms pick up **1280×1600**. Existing 16:9 doodles stay in the top band (no remapping); extra space is below. Use **Clear all** only if an old room still looks stretched.

## GitHub debug APK

Workflow: `.github/workflows/android-debug.yml`  
On push to `android/**` (or manual **Actions → Android debug APK → Run workflow**), GitHub builds `app-debug.apk` as artifact **mychat-debug-apk**.

## Next slices (later)

1. Live SSE instead of polling  
2. Board shapes / text toolkit (web already has them; Android draws them if someone else used them)
