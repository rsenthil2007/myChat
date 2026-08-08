/**
 * Deploy / WebIntoApp settings.
 *
 * Hetzner one-box (UI + server.py on same HTTPS host): leave API_BASE empty.
 * Split hosting (static UI elsewhere): set API_BASE to your VPS origin, e.g.
 *   API_BASE: "https://chat.example.com"
 *
 * SYNC_MODE:
 *   "shared" — use server when reachable (closed-group default)
 *   "local"  — force localStorage only (no multi-device sync)
 */
window.MyChatConfig = {
  API_BASE: "",
  SYNC_MODE: "shared",

  apiUrl: function (path) {
    const base = String(this.API_BASE || "").replace(/\/$/, "");
    const p = path.charAt(0) === "/" ? path : "/" + path;
    return base + p;
  },

  preferLocal: function () {
    return String(this.SYNC_MODE || "shared").toLowerCase() === "local";
  }
};
