# myChat

Closed-group room chat with **text**, **sketches**, **voice notes**, and a **shared whiteboard** (unique colors + sketch toolkit). HTML/CSS/JS + Python room server. Easy to wrap with **WebInto.app**.

## Architecture (production)

- **One cheap VPS** (e.g. **Hetzner ~$3/mo**): serves the UI **and** `server.py`
- Devices join the **same room code** → everyone sees the same chat and all board layers
- **Android** registers with **Firebase SMS** plus a **user name**; the name is shown on every message
- Room data stored in `data/rooms/{room}.json` on the VPS
- Device accounts: `data/devices.json` now, optional **Supabase** table `mychat_device_accounts` later
- `localStorage` is a cache / offline fallback only

```text
Android (WebInto.app)  ─┐
Browsers               ─┼─ HTTPS ─►  Hetzner VPS (nginx/Caddy + server.py)
Laptop browser         ─┘
```

## Deploy on Hetzner (recommended)

1. Create a Cloud server (Ubuntu). Open firewall ports **80** and **443**.
2. Copy this project to the server (git clone or scp), then:

```bash
sudo apt update
sudo apt install -y python3 python3-pip python3-venv nginx certbot python3-certbot-nginx
cd /opt/mychat   # or your path
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

3. Use samples in `deploy/`:
   - `deploy/mychat.service` — systemd unit
   - `deploy/nginx-mychat.conf` — reverse proxy + SSE-friendly timeouts
4. `sudo certbot --nginx -d your-domain` for HTTPS.
5. Point **WebInto.app** at `https://your-domain` (same origin — leave `API_BASE` empty in `js/config.js`).
6. Optional: domain at HostingRaja with DNS **A record** → Hetzner IP.

Env vars (optional):

| Variable | Meaning |
| --- | --- |
| `CORS_ORIGINS` | Comma-separated allowed origins, or `*` (default) |
| `MYCHAT_HTTP_PORT` | Default `8080` |
| `MYCHAT_HTTPS_PORT` | Default `8443` (built-in TLS; prefer nginx TLS in production) |
| `FIREBASE_CREDENTIALS` | Path to Firebase Admin service-account JSON (VPS only; unused while SMS is off) |
| `MYCHAT_ADMIN_MOBILES` | Optional comma-separated admin numbers. If unset, existing `devices.json` rows without `status` are admins |
| `MYCHAT_ADMIN_WEB` | `1` (default) enables `/admin`. Set `0` to disable the web admin page |
| `MYCHAT_ADMIN_WEB_OTP` | Test OTP for `/admin` (default `246810`). Change or disable with `MYCHAT_ADMIN_WEB=0` |
| `FIREBASE_SERVICE_ACCOUNT_JSON` | Same credentials as inline JSON instead of a file |
| `SUPABASE_URL` / `SUPABASE_SERVICE_KEY` | Optional; store device accounts in `mychat_device_accounts` |

**Do not** use DigitalOcean Functions or shared PHP hosting as the sync backend.

### Split hosting (optional)

If the static UI is elsewhere, set in `js/config.js`:

```js
API_BASE: "https://your-hetzner-domain",
SYNC_MODE: "shared",
```

## Run locally (dev)

```bash
pip install -r requirements.txt
python server.py
```

- Desktop: **http://localhost:8080**
- Phone (LAN mic): **https://YOUR-PC-IP:8443** (accept cert warning once)

Join the **same room** on all devices. Messages sync through `data/rooms/{room}.json`.

## Config (`js/config.js`)

| Field | Default | Notes |
| --- | --- | --- |
| `API_BASE` | `""` | Empty = same origin (Hetzner one-box) |
| `SYNC_MODE` | `"shared"` | `"local"` forces localStorage-only (no multi-device sync) |

If the badge shows **Local**, other members will **not** see your messages — check that `server.py` is running and `API_BASE` is correct.

## Security & compression

Outbound payloads are **deflate-compressed when it saves space**, then sealed with
**SHA-256 keystream + HMAC**. Room code is the shared key.

## Voice notes

Hold the mic (desktop) or **tap to start / tap to send** (phone). Max 60s.  
Production / WebInto.app: use **real HTTPS** (Let’s Encrypt), not a self-signed LAN cert.

## Shared whiteboard

In a room, open the **Board** tab:

1. Everyone draws on the same board; each person gets their own **transparent layer**.
2. Opening the board assigns a unique **standard color**.
3. Sketch toolkit: **Pen, Eraser, Line, Arrow, Rect, Circle, Oval, Text**.
4. Colors already used by others are blocked.
5. **Undo** / **Clear mine** only affect *your* strokes; **Clear all** resets the board.

## Pictionary (optional)

Hidden by default. Set `FEATURES.pictionary = true` in `js/app.js` to show the tab.

## Tests

```bash
python tests/test_suite.py
```

## Project layout

```
myChat/
  server.py
  firebase_auth.py
  device_accounts.py
  whiteboard.py
  index.html
  js/config.js
  js/app.js
  js/storage.js
  deploy/mychat.service
  deploy/nginx-mychat.conf
  android/
  admin.html
  supabase/device_accounts.sql
  data/rooms/
```

## Native Android (Kotlin)

v0.4.8 chat client lives in [`android/`](android/README.md). New phones **request access**; an admin admits them (app **Admin** screen or `https://chat.microbear.in/admin`). Firebase SMS code is still in the repo but unused until billing is on. Debug APK is built by GitHub Actions.

## Wrap as a mobile app (WebInto.app)

| Feature | Android permission |
| --- | --- |
| Chat / Board / SSE | `INTERNET`, `ACCESS_NETWORK_STATE` |
| Voice notes | `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS` |

1. Point WebInto.app at your **Hetzner HTTPS** URL.
2. Enable **Microphone / Audio** in the builder.
3. Prefer Let’s Encrypt TLS.

## Optional next steps

- SMS on the web client; optional user-name rename
- Auth / room passwords
- Migrate LAN crypto to Web Crypto AES-GCM when HTTPS-only is acceptable
 
