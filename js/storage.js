/**
 * Shared room storage via API (cross-device) with localStorage cache/fallback.
 * Uses MyChatConfig.API_BASE when UI and API are on different hosts.
 */
const Storage = (() => {
  const PREFIX = "mychat.room.";
  const SESSION_KEY = "mychat.session";

  let useApi = null; // null = unknown, true/false after probe

  function cfg() {
    return (
      window.MyChatConfig || {
        API_BASE: "",
        SYNC_MODE: "shared",
        apiUrl: (path) => path,
        preferLocal: () => false
      }
    );
  }

  function api(path) {
    return cfg().apiUrl(path);
  }

  function roomsUrl(roomId, suffix) {
    const id = encodeURIComponent(roomId);
    return api("/api/rooms/" + id + (suffix || ""));
  }

  function roomKey(roomId) {
    return PREFIX + normalizeRoom(roomId);
  }

  function normalizeRoom(roomId) {
    return (
      String(roomId || "lobby")
        .trim()
        .toLowerCase()
        .replace(/\s+/g, "-")
        .replace(/[^a-z0-9-]/g, "")
        .slice(0, 24) || "lobby"
    );
  }

  function emptyRoom(roomId) {
    return {
      version: 1,
      roomId: normalizeRoom(roomId),
      updatedAt: new Date().toISOString(),
      messages: []
    };
  }

  function loadLocal(roomId) {
    const key = roomKey(roomId);
    try {
      const raw = localStorage.getItem(key);
      if (!raw) return emptyRoom(roomId);
      const data = JSON.parse(raw);
      if (!data || !Array.isArray(data.messages)) return emptyRoom(roomId);
      data.roomId = normalizeRoom(roomId);
      return data;
    } catch {
      return emptyRoom(roomId);
    }
  }

  function saveLocal(room) {
    const normalized = {
      version: 1,
      roomId: normalizeRoom(room.roomId),
      updatedAt: new Date().toISOString(),
      messages: Array.isArray(room.messages) ? room.messages : [],
      pictionary: room.pictionary,
      whiteboard: room.whiteboard
    };
    localStorage.setItem(roomKey(normalized.roomId), JSON.stringify(normalized));
    return normalized;
  }

  async function probeApi() {
    if (cfg().preferLocal && cfg().preferLocal()) {
      useApi = false;
      return false;
    }
    if (useApi === true) return true;
    try {
      const res = await fetch(api("/api/health"), { cache: "no-store" });
      useApi = res.ok;
    } catch {
      useApi = false;
    }
    return useApi;
  }

  function resetApiProbe() {
    useApi = null;
  }

  async function loadRoom(roomId) {
    const id = normalizeRoom(roomId);
    if (await probeApi()) {
      try {
        const res = await fetch(roomsUrl(id), { cache: "no-store" });
        if (res.ok) {
          const data = await res.json();
          if (data && Array.isArray(data.messages)) {
            saveLocal(data);
            return data;
          }
        }
      } catch {
        /* fall through */
      }
    }
    return loadLocal(id);
  }

  async function appendMessage(roomId, message) {
    const id = normalizeRoom(roomId);

    if (!(await probeApi())) {
      throw new Error("Not connected to the chat server");
    }

    const res = await fetch(roomsUrl(id, "/messages"), {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(message)
    });
    if (!res.ok) {
      useApi = null;
      const text = await res.text().catch(() => "");
      let hint = "Could not send (HTTP " + res.status + ")";
      try {
        const parsed = JSON.parse(text);
        if (parsed && parsed.error) hint = parsed.error;
      } catch {
        /* keep hint */
      }
      throw new Error(hint);
    }
    const data = await res.json();
    saveLocal(data);
    return data;
  }

  async function clearRoom(roomId) {
    const id = normalizeRoom(roomId);

    if (await probeApi()) {
      const res = await fetch(roomsUrl(id, "/messages"), {
        method: "DELETE"
      });
      if (!res.ok) {
        throw new Error("Server clear failed (" + res.status + ")");
      }
      const data = await res.json();
      saveLocal(data);
      return data;
    }

    return saveLocal(emptyRoom(id));
  }

  function saveSession(session) {
    localStorage.setItem(SESSION_KEY, JSON.stringify(session));
  }

  function loadSession() {
    try {
      const raw = localStorage.getItem(SESSION_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch {
      return null;
    }
  }

  function clearSession() {
    localStorage.removeItem(SESSION_KEY);
  }

  async function exportRoomJson(roomId) {
    const room = await loadRoom(roomId);
    return JSON.stringify(room, null, 2);
  }

  /**
   * Subscribe to room updates:
   * - Shared mode: Server-Sent Events
   * - Fallback: localStorage events (same-browser tabs)
   */
  function onRoomChange(roomId, callback) {
    const id = normalizeRoom(roomId);
    const key = roomKey(id);
    let source = null;
    let stopped = false;

    const storageHandler = (e) => {
      if (stopped) return;
      if (e.key === key && e.newValue) {
        try {
          const data = JSON.parse(e.newValue);
          if (normalizeRoom(data.roomId || id) !== id) return;
          callback(data);
        } catch {
          /* ignore */
        }
      }
    };
    window.addEventListener("storage", storageHandler);

    probeApi().then((ok) => {
      if (stopped || !ok || typeof EventSource === "undefined") return;
      source = new EventSource(roomsUrl(id, "/stream"));
      source.addEventListener("room", (ev) => {
        if (stopped) return;
        try {
          const data = JSON.parse(ev.data);
          if (normalizeRoom(data.roomId || "") !== id) return;
          saveLocal(data);
          callback(data);
        } catch {
          /* ignore bad payloads */
        }
      });
      source.onerror = () => {
        /* browser auto-reconnects; ignore */
      };
    });

    return () => {
      stopped = true;
      if (source) {
        source.close();
        source = null;
      }
      window.removeEventListener("storage", storageHandler);
    };
  }

  function isSharedMode() {
    return useApi === true;
  }

  return {
    normalizeRoom,
    loadRoom,
    appendMessage,
    clearRoom,
    saveSession,
    loadSession,
    clearSession,
    exportRoomJson,
    onRoomChange,
    emptyRoom,
    probeApi,
    resetApiProbe,
    isSharedMode,
    api
  };
})();
