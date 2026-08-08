/**
 * Optional WebInto.app / Capacitor native permission bridge.
 * No-ops in normal browsers; asks AUDIO before getUserMedia inside WebView shells.
 */
const NativeBridge = (() => {
  function hasNative() {
    return !!(window.Native && typeof window.Native.call === "function");
  }

  function callNative(method, payload) {
    return new Promise((resolve) => {
      if (!hasNative()) {
        resolve({ ok: false, skipped: true });
        return;
      }
      let settled = false;
      const finish = (value) => {
        if (settled) return;
        settled = true;
        resolve(value);
      };
      try {
        window.Native.call(method, payload || {}, (raw) => {
          let data = raw;
          if (typeof raw === "string") {
            try {
              data = JSON.parse(raw);
            } catch {
              data = { raw };
            }
          }
          finish({ ok: true, data });
        });
        // Some shells never callback — don't block recording forever
        setTimeout(() => finish({ ok: false, timeout: true }), 2500);
      } catch (err) {
        finish({ ok: false, error: err });
      }
    });
  }

  function statusOf(result) {
    const data = result && result.data;
    if (!data) return "";
    const params = data.params || data;
    return String(params.permissionStatus || params.status || data.permissionStatus || "").toUpperCase();
  }

  /**
   * Ensure microphone permission when running inside WebInto.app.
   * Returns true if it is OK to call getUserMedia.
   */
  async function ensureMicrophone() {
    if (!hasNative()) return true;

    const check = await callNative("permissions", {
      action: "CHECK",
      payload: { permission: "AUDIO" }
    });
    const status = statusOf(check);
    if (
      status === "GRANTED" ||
      status === "AUTHORIZED" ||
      status === "AUTHORIZED_ALWAYS" ||
      check.skipped
    ) {
      return true;
    }

    if (status === "DENIED_ALWAYS") {
      alert(
        "Microphone is blocked for this app.\n\nOpen Android Settings → Apps → myChat → Permissions → Microphone, then try again."
      );
      await callNative("permissions", {
        action: "OPEN_APP_SETTINGS",
        payload: {}
      });
      return false;
    }

    const ask = await callNative("permissions", {
      action: "ASK",
      payload: { permission: "AUDIO" }
    });
    const after = statusOf(ask);
    if (
      after === "GRANTED" ||
      after === "AUTHORIZED" ||
      after === "AUTHORIZED_ALWAYS" ||
      ask.timeout ||
      ask.skipped
    ) {
      return true;
    }

    alert("Microphone permission is required for voice notes.");
    return false;
  }

  return { hasNative, ensureMicrophone };
})();

window.NativeBridge = NativeBridge;
