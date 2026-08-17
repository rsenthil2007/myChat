# Native myChat (Kotlin) — v0.4.5

Android client for the same `server.py` rooms as the web app.

**In this version:** sealed **text**, **sketches**, **voice notes**, **clear chat**, a shared **Board** tab, and a first-launch **mobile + SSAID** device check.  
The board uses a fixed **1280×1600** (4:5) logical canvas. The APK always talks to `https://chat.microbear.in` (the server URL field is gone).

**Not yet:** Pictionary (hidden on the web too), live SSE (the app still polls every 2s), SMS/WhatsApp OTP.

The older WebInto wrap notes stay in `Wrap/`.

## Device binding

First launch asks for a mobile number. The app reads this phone’s SSAID (`Settings.Secure.ANDROID_ID`), sends both to the server, and shows a **popup OTP** generated on the server (not SMS). Later launches send the stored number + current SSAID; a mismatch shows an error and the app closes.

This **binds one number to one phone**. It does **not** prove the person owns that number (anyone can type digits). The web client and raw `/api/rooms` calls are unchanged.

Factory reset or a new phone gets a new SSAID. Delete that row in Supabase (or `data/devices.json`) before they can register the same number again.

### Supabase (preferred)

Use the shared **generic** Supabase project (same database as other small apps). Tables are prefixed `mychat_` so they do not clash with GroupTrack or later apps.

1. Run `supabase/device_accounts.sql` in that project’s SQL editor (creates `mychat_device_accounts`).
2. On the VPS, set in `mychat.service`:

```
Environment=SUPABASE_URL=https://YOUR_PROJECT.supabase.co
Environment=SUPABASE_SERVICE_KEY=YOUR_SERVICE_ROLE_KEY
Environment=MYCHAT_DEVICE_SECRET=a-long-random-string
```

Use the **service role** key on the server only. Never put it in the APK. Leave RLS enabled on this table and do not add public policies.

If those env vars are missing, the server stores accounts in `data/devices.json` instead.

## Open in Android Studio

1. Install Android Studio (Koala / Ladybug or newer) with JDK 17 and an Android SDK.
2. **File → Open** this `android` folder (not the myChat repo root).
3. Let Gradle sync. First sync downloads the Android Gradle Plugin.

Use the **same room code** as the browser. Chat and board share that room.

Redeploy `server.py`, `device_accounts.py`, and `whiteboard.py` on the VPS. Existing 16:9 doodles stay in the top band of the 1280×1600 board.

## GitHub debug APK

Workflow: `.github/workflows/android-debug.yml`  
On push to `android/**` (or manual **Actions → Android debug APK → Run workflow**), GitHub builds `app-debug.apk` as artifact **mychat-debug-apk**.

## Next slices (later)

1. Live SSE instead of polling  
2. Board shapes / text toolkit (web already has them; Android draws them if someone else used them)
