# Native myChat (Kotlin) — v0.4.6

Android client for the same `server.py` rooms as the web app.

**In this version:** sealed **text**, **sketches**, **voice notes**, **clear chat**, a shared **Board** tab, **Firebase SMS** registration, and a **user name** chosen at register time (shown on every message).  
The board uses a fixed **1280×1600** (4:5) logical canvas. The APK always talks to `https://chat.microbear.in`.

**Not yet:** Pictionary (hidden on the web too), live SSE (the app still polls every 2s), rename user name, web SMS.

The older WebInto wrap notes stay in `Wrap/`.

## Registration (SMS + user name)

1. First launch asks for a **user name** (at least 2 characters) and a **mobile number**.
2. Firebase Phone Auth sends a real SMS code. After the code is accepted, the app sends the Firebase ID token, this phone’s SSAID (`Settings.Secure.ANDROID_ID`), and the user name to `POST /api/device/register`.
3. Later launches reuse the Firebase session and call `POST /api/device/verify` with the ID token + SSAID. A mismatch shows an error and the app closes.
4. The join screen **locks** the user name. It is stored on the server and used as `authorName` on chat, sketches, voice notes, and board layers. There is no rename UI yet.

Existing 0.4.5 installs used an in-app OTP (not SMS). Those users must register again with SMS. Old `devices.json` rows without `firebaseUid` / `displayName` are updated on the first successful SMS register **if the SSAID still matches**.

The web client still joins with a typed name and room code (no SMS yet).

## Firebase Console (required before SMS works)

1. Create a Firebase project and add an Android app with package **`in.microbear.mychat`**.
2. Enable **Authentication → Sign-in method → Phone**.
3. Add the **debug SHA-1** (and SHA-256) from the CI keystore below. Without this, SMS will fail on GitHub-built APKs.
4. Download `google-services.json` and replace `android/app/google-services.json` (the copy in git is a compile placeholder only).
5. For GitHub Actions, paste the same file into the repo secret **`GOOGLE_SERVICES_JSON`**.

Firebase may require a paid (Blaze) plan for Phone Auth in production.

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
