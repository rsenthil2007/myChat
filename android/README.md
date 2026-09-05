# Native myChat (Kotlin) — v0.4.9

Android client for the same `server.py` rooms as the web app.

**In this version:** sealed **text**, **sketches**, **voice notes**, **clear chat**, a shared **Board** tab, a **user name** chosen at request time, and **admin admit** (no paid SMS). Firebase Phone Auth code is still in the repo for later.  
The board uses a fixed **1280×1600** (4:5) logical canvas. The APK always talks to `https://chat.microbear.in`.

**Not yet:** Pictionary (hidden on the web too), live SSE (the app still polls every 2s), rename user name, paid SMS.

The older WebInto wrap notes stay in `Wrap/`.

## Registration (request + admin)

1. First launch asks for a **user name** (at least 2 characters) and a **mobile number**.
2. The app sends mobile, SSAID, and name to `POST /api/device/request`. New users stay **pending**.
3. An admin admits or rejects from the **Admin** screen on the admin phone, or from `https://chat.microbear.in/admin`.
4. After admit, later launches call `POST /api/device/check`. Same mobile + SSAID → in. Mismatch → blocked.
5. The join screen **locks** the user name. It is used as `authorName` on chat, sketches, voice notes, and board layers.

Numbers already in `/opt/mychat/data/devices.json` (rows with no `status` field) are treated as **admitted admins**. That is how the first phone on InterServer becomes admin without SMS.

Firebase SMS (`/api/device/register`) is still implemented on the server but the app does not call it while billing is off.

The web chat client still joins with a typed name and room code (no device gate).

## Admin

On the **admin phone**: Join screen → **Review access requests**, or Chat → **Admin**.

Temporary **web admin** (disable after testing):

1. Open `https://chat.microbear.in/admin`
2. Enter the admin mobile number and test OTP **`246810`**
3. Admit or reject pending users

Disable the web page on the VPS:

```
Environment=MYCHAT_ADMIN_WEB=0
```

Optional: pin the admin number with `MYCHAT_ADMIN_MOBILES=98XXXXXXXX` and change the OTP with `MYCHAT_ADMIN_WEB_OTP`.

## Firebase Console (SMS later)

SMS remains optional. Package **`in.microbear.mychat`**. Debug SHA-1 / SHA-256 below. Real SMS needs Blaze billing.

### Debug keystore fingerprints (GitHub APK)

Committed at `android/ci-debug.keystore` (password `android`, alias `androiddebugkey`) so every CI APK has the same signing cert.

- **SHA-1:** `13:F0:29:34:07:10:32:58:1D:A0:24:B4:40:10:EC:E6:19:5E:AB:96`
- **SHA-256:** `5B:ED:01:8E:61:80:E2:9F:C7:DF:38:B0:E2:25:EE:52:C9:BE:AB:80:EE:AA:7C:3F:29:A1:BC:E0:15:9C:27:78`


## VPS (Firebase Admin)

The APK never holds the service-account private key. On InterServer:

1. `cd /opt/mychat && git pull`
2. `.venv/bin/pip install -r requirements.txt` (needs `firebase-admin`)
3. Put the Firebase Admin JSON at `/opt/mychat/data/firebase-service-account.json` (this path is gitignored).
4. In `mychat.service`, set `FIREBASE_CREDENTIALS=/opt/mychat/data/firebase-service-account.json` (see `deploy/mychat.service`).
5. Stop leftover listeners on 8080, then `systemctl start mychat`. Do not run `python server.py` by hand while systemd owns the port.

Optional env: `FIREBASE_SERVICE_ACCOUNT_JSON` (inline JSON) instead of a file. Tests may set `MYCHAT_ALLOW_TEST_TOKENS=1` for `test:<mobile>:<uid>` tokens — never enable that on the VPS.

### Supabase (later)

Use the shared **generic** Supabase project. Tables are prefixed `mychat_`.

1. Run `supabase/device_accounts.sql` (adds `firebase_uid` and `display_name` if you already created the table).
2. On the VPS:

```
Environment=SUPABASE_URL=https://YOUR_PROJECT.supabase.co
Environment=SUPABASE_SERVICE_KEY=YOUR_SERVICE_ROLE_KEY
```

Use the **service role** key on the server only. Never put it in the APK. If those env vars are missing, accounts stay in `data/devices.json`.

## Open in Android Studio

1. Install Android Studio (Koala / Ladybug or newer) with JDK 17 and an Android SDK.
2. **File → Open** this `android` folder (not the myChat repo root).
3. Replace `app/google-services.json` with the real Firebase file.
4. Let Gradle sync.

Use the **same room code** as the browser. Chat and board share that room.

## GitHub debug APK

Workflow: `.github/workflows/android-debug.yml`  
On push to `android/**` (or manual **Actions → Android debug APK → Run workflow**), GitHub builds `app-debug.apk` as artifact **mychat-debug-apk**.

## Next slices (later)

1. Live SSE instead of polling  
2. Board shapes / text toolkit (web already has them; Android draws them if someone else used them)  
3. Optional rename of user name  
4. SMS on the web client  
5. Switch JSON device file to Supabase as the live store
