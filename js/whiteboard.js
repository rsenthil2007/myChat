/**
 * Shared whiteboard — transparent layer per author; unique colors per member.
 */
const Whiteboard = (() => {
  let session = null;
  let minePad = null;
  let state = null;
  let syncTimer = 0;
  let lastStamp = "";
  let drawingBusy = false;
  let activeColor = "#0f172a";
  let joined = false;

  const DEFAULT_PALETTE = [
    { id: "black", name: "Black", hex: "#0f172a" },
    { id: "red", name: "Red", hex: "#dc2626" },
    { id: "blue", name: "Blue", hex: "#2563eb" },
    { id: "green", name: "Green", hex: "#16a34a" },
    { id: "orange", name: "Orange", hex: "#ea580c" },
    { id: "purple", name: "Purple", hex: "#7c3aed" },
    { id: "teal", name: "Teal", hex: "#0d9488" },
    { id: "brown", name: "Brown", hex: "#92400e" },
    { id: "pink", name: "Pink", hex: "#db2777" },
    { id: "navy", name: "Navy", hex: "#1e3a8a" },
    { id: "olive", name: "Olive", hex: "#4d7c0f" },
    { id: "gray", name: "Gray", hex: "#64748b" }
  ];

  const CANONICAL_W = 1280;
  const CANONICAL_H = 1600;
  const NEAR = 48;
  const els = {};

  function bindElements() {
    els.panel = document.getElementById("panel-board");
    els.status = document.getElementById("board-status-text");
    els.layers = document.getElementById("board-layers");
    els.tools = document.getElementById("board-tools");
    els.swatches = document.getElementById("board-swatches");
    els.bg = document.getElementById("board-bg");
    els.mine = document.getElementById("board-mine");
    els.color = document.getElementById("board-color");
    els.size = document.getElementById("board-size");
    els.undoBtn = document.getElementById("board-undo-btn");
    els.clearMineBtn = document.getElementById("board-clear-mine-btn");
    els.clearAllBtn = document.getElementById("board-clear-all-btn");
  }

  function api(action, body) {
    const base =
      window.MyChatConfig && typeof window.MyChatConfig.apiUrl === "function"
        ? window.MyChatConfig.apiUrl(
            `/api/rooms/${encodeURIComponent(session.roomId)}/whiteboard/${action}`
          )
        : `/api/rooms/${encodeURIComponent(session.roomId)}/whiteboard/${action}`;
    return fetch(base, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body)
    }).then(async (res) => {
      const data = await res.json().catch(() => ({}));
      if (!res.ok) throw new Error(data.error || "Request failed");
      return data;
    });
  }

  function escapeHtml(s) {
    return String(s || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function normalizeHex(value) {
    let raw = String(value || "").trim();
    if (!raw) return "#0f172a";
    if (raw[0] !== "#") raw = "#" + raw;
    if (!/^#[0-9a-fA-F]{6}$/.test(raw)) return "#0f172a";
    return raw.toLowerCase();
  }

  function hexToRgb(hex) {
    const h = normalizeHex(hex);
    return [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
  }

  function colorsNear(a, b) {
    const [ar, ag, ab] = hexToRgb(a);
    const [br, bg, bb] = hexToRgb(b);
    const dist = Math.sqrt((ar - br) ** 2 + (ag - bg) ** 2 + (ab - bb) ** 2);
    return dist <= NEAR;
  }

  function layerColors(layer) {
    const owned = new Set();
    if (!layer) return owned;
    if (layer.assignedColor) owned.add(normalizeHex(layer.assignedColor));
    (layer.strokes || []).forEach((s) => {
      const kind = s.t || s.type || "pen";
      if (kind === "erase") return;
      owned.add(normalizeHex(s.c || s.color));
    });
    (layer.extraColors || []).forEach((c) => owned.add(normalizeHex(c)));
    return owned;
  }

  function othersColors() {
    const owned = new Set();
    ((state && state.layers) || []).forEach((layer) => {
      if (!layer || (session && layer.authorId === session.authorId)) return;
      layerColors(layer).forEach((c) => owned.add(c));
    });
    return owned;
  }

  function isTakenByOthers(color) {
    const candidate = normalizeHex(color);
    for (const other of othersColors()) {
      if (colorsNear(candidate, other)) return true;
    }
    return false;
  }

  function myLayer(board) {
    if (!session || !board || !board.layers) return null;
    return board.layers.find((l) => l.authorId === session.authorId) || null;
  }

  function palette() {
    return (state && state.palette && state.palette.length ? state.palette : DEFAULT_PALETTE) || DEFAULT_PALETTE;
  }

  function paletteName(hex) {
    const target = normalizeHex(hex);
    const hit = palette().find((s) => normalizeHex(s.hex) === target);
    return hit ? hit.name : null;
  }

  function setActiveColor(color, opts) {
    const next = normalizeHex(color);
    if (isTakenByOthers(next)) {
      if (opts && opts.silent) return false;
      alert("That color is already used by someone else in this room. Pick a free color.");
      if (els.color) els.color.value = activeColor;
      renderSwatches();
      return false;
    }
    activeColor = next;
    if (els.color) els.color.value = next;
    if (minePad) minePad.setColor(next);
    renderSwatches();
    return true;
  }

  async function claimActiveColor(color) {
    if (!session) return false;
    const next = normalizeHex(color);
    if (!setActiveColor(next)) return false;
    const mine = myLayer(state);
    const alreadyMine = mine && [...layerColors(mine)].some((c) => colorsNear(c, next));
    if (alreadyMine) return true;
    try {
      const data = await api("claim-color", {
        authorId: session.authorId,
        authorName: session.authorName,
        color: next
      });
      applyState(data.whiteboard);
      return true;
    } catch (err) {
      alert(err.message || "Could not claim color");
      const fallback = (myLayer(state) && myLayer(state).assignedColor) || activeColor;
      setActiveColor(fallback, { silent: true });
      return false;
    }
  }

  function boardLogicalSize() {
    const w = (state && state.w > 0 ? state.w : CANONICAL_W) || CANONICAL_W;
    const h = (state && state.h > 0 ? state.h : CANONICAL_H) || CANONICAL_H;
    return { w, h };
  }

  function resizeBg() {
    if (!els.bg || !els.bg.parentElement) return;
    const wrap = els.bg.parentElement;
    const rect = wrap.getBoundingClientRect();
    const availW = Math.max(1, Math.floor(rect.width));
    const availH = Math.max(1, Math.floor(rect.height));
    const { w: lw, h: lh } = boardLogicalSize();
    const scale = Math.min(availW / lw, availH / lh);
    const dw = Math.max(1, Math.floor(lw * scale));
    const dh = Math.max(1, Math.floor(lh * scale));
    const dpr = Math.min(window.devicePixelRatio || 1, 2);

    [els.bg, els.mine].forEach((el) => {
      if (!el) return;
      el.style.width = dw + "px";
      el.style.height = dh + "px";
    });

    els.bg.width = Math.floor(lw * dpr);
    els.bg.height = Math.floor(lh * dpr);
    const ctx = els.bg.getContext("2d");
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    paintOthers(ctx, lw, lh);
  }

  function paintOthers(ctx, w, h) {
    ctx.fillStyle = "#ffffff";
    ctx.fillRect(0, 0, w, h);
    const layers = (state && state.layers) || [];
    layers.forEach((layer) => {
      if (!layer || (session && layer.authorId === session.authorId)) return;
      CanvasDraw.paintStrokesOn(ctx, layer.strokes || []);
    });
  }

  function renderSwatches() {
    if (!els.swatches) return;
    const mine = myLayer(state);
    const mineOwned = layerColors(mine);
    els.swatches.innerHTML = palette()
      .map((swatch) => {
        const hex = normalizeHex(swatch.hex);
        const taken = isTakenByOthers(hex);
        const mineColor = [...mineOwned].some((c) => colorsNear(c, hex));
        const selected = colorsNear(activeColor, hex);
        const disabled = taken && !mineColor;
        const title = disabled
          ? `${swatch.name} — used by someone else`
          : mineColor && normalizeHex(mine && mine.assignedColor) === hex
            ? `${swatch.name} — your assigned color`
            : swatch.name;
        return `<button type="button" class="board-swatch${selected ? " selected" : ""}${
          disabled ? " taken" : ""
        }${mineColor ? " mine-color" : ""}" data-color="${hex}" title="${escapeHtml(
          title
        )}" ${disabled ? "disabled" : ""} style="--swatch:${hex}" aria-label="${escapeHtml(
          title
        )}"></button>`;
      })
      .join("");
  }

  function renderLayersList() {
    if (!els.layers) return;
    const layers = (state && state.layers) || [];
    if (!layers.length) {
      els.layers.innerHTML = "<em>Open the board to claim your color</em>";
      return;
    }
    els.layers.innerHTML = layers
      .map((layer) => {
        const mine = session && layer.authorId === session.authorId;
        const n = (layer.strokes || []).length;
        const tag = mine ? " (you)" : "";
        const color = normalizeHex(layer.assignedColor || "#64748b");
        const label = paletteName(color) || color;
        return `<div class="board-layer-row${mine ? " mine" : ""}">
          <span class="board-layer-name"><i class="board-layer-dot" style="background:${color}"></i>${escapeHtml(
            layer.authorName || "Guest"
          )}${tag}</span>
          <strong>${escapeHtml(label)} · ${n}</strong>
        </div>`;
      })
      .join("");
  }

  function updateChrome() {
    const mine = myLayer(state);
    const count = minePad ? minePad.strokeCount() : mine && mine.strokes ? mine.strokes.length : 0;
    const assigned = mine && mine.assignedColor ? normalizeHex(mine.assignedColor) : null;
    const label = assigned ? paletteName(assigned) || assigned : null;
    if (els.status) {
      els.status.textContent = label
        ? `Your color: ${label} · Tools: pen, eraser, line, arrow, rect, circle, oval, text`
        : "Shared board — each member gets a unique color";
    }
    if (els.undoBtn) els.undoBtn.disabled = !count;
    if (els.clearMineBtn) els.clearMineBtn.disabled = !count;
    renderSwatches();
    renderLayersList();
  }

  function applyState(board) {
    state = board || emptyLocal();
    const stamp =
      (state.updatedAt || "") +
      ":" +
      ((state.layers || [])
        .map(
          (l) =>
            (l.authorId || "") +
            ":" +
            ((l.strokes && l.strokes.length) || 0) +
            ":" +
            (l.assignedColor || "") +
            ":" +
            (l.updatedAt || "")
        )
        .join("|"));

    if (stamp !== lastStamp) {
      lastStamp = stamp;
      const { w: lw, h: lh } = boardLogicalSize();
      if (minePad && minePad.setLogicalSize) minePad.setLogicalSize(lw, lh);
      resizeBg();
      if (minePad && !drawingBusy) {
        const mine = myLayer(state);
        minePad.loadDrawing({
          w: lw,
          h: lh,
          strokes: mine ? mine.strokes || [] : []
        });
      }
    }

    const mine = myLayer(state);
    if (mine && mine.assignedColor) {
      if (!joined || isTakenByOthers(activeColor)) {
        setActiveColor(mine.assignedColor, { silent: true });
      }
    }
    updateChrome();
  }

  function emptyLocal() {
    return { w: 0, h: 0, layers: [], palette: DEFAULT_PALETTE, updatedAt: "" };
  }

  async function ensureJoined() {
    if (!session || joined) return;
    try {
      const data = await api("join", {
        authorId: session.authorId,
        authorName: session.authorName
      });
      joined = true;
      applyState(data.whiteboard);
      const mine = myLayer(state);
      if (mine && mine.assignedColor) setActiveColor(mine.assignedColor, { silent: true });
    } catch (err) {
      console.warn(err);
    }
  }

  function queueStrokeSync(drawing) {
    if (!session) return;
    window.clearTimeout(syncTimer);
    syncTimer = window.setTimeout(async () => {
      try {
        const data = await api("stroke", {
          authorId: session.authorId,
          authorName: session.authorName,
          drawing: drawing || minePad.getDrawingPayload()
        });
        drawingBusy = false;
        joined = true;
        applyState(data.whiteboard);
      } catch (err) {
        console.warn(err);
        drawingBusy = false;
        if (err && err.message) alert(err.message);
      }
    }, 220);
  }

  async function undoMine() {
    if (!session || !minePad) return;
    if (!minePad.undoLast()) return;
    updateChrome();
    try {
      const data = await api("undo", {
        authorId: session.authorId,
        authorName: session.authorName
      });
      applyState(data.whiteboard);
    } catch (err) {
      try {
        const data = await api("stroke", {
          authorId: session.authorId,
          authorName: session.authorName,
          drawing: minePad.getDrawingPayload()
        });
        applyState(data.whiteboard);
      } catch (e2) {
        alert(err.message || "Undo failed");
      }
    }
  }

  async function clearMine() {
    if (!session || !minePad) return;
    minePad.clear();
    updateChrome();
    try {
      const data = await api("clear-mine", {
        authorId: session.authorId,
        authorName: session.authorName
      });
      applyState(data.whiteboard);
      const mine = myLayer(state);
      if (mine && mine.assignedColor) setActiveColor(mine.assignedColor, { silent: true });
    } catch (err) {
      alert(err.message || "Could not clear your layer");
    }
  }

  async function clearAll() {
    if (!session) return;
    const ok = window.confirm("Clear the entire whiteboard for everyone?\n\nThis removes all layers and color claims.");
    if (!ok) return;
    try {
      const data = await api("clear-all", {
        authorId: session.authorId,
        authorName: session.authorName
      });
      if (minePad) minePad.clear();
      joined = false;
      applyState(data.whiteboard);
      await ensureJoined();
    } catch (err) {
      alert(err.message || "Could not clear board");
    }
  }

  function onRoomData(data) {
    if (!data || !data.whiteboard) return;
    applyState(data.whiteboard);
  }

  function setSession(next) {
    session = next;
    joined = false;
    if (!next) {
      state = null;
      lastStamp = "";
      if (minePad) minePad.clear();
      resizeBg();
      updateChrome();
    }
  }

  function showPanel(visible) {
    if (!els.panel) return;
    els.panel.hidden = !visible;
    if (visible) {
      ensureJoined();
      requestAnimationFrame(() => {
        resizeBg();
        if (minePad) minePad.resize();
        updateChrome();
      });
    }
  }

  function setTool(tool) {
    if (!minePad || !minePad.setTool) return;
    minePad.setTool(tool);
    if (!els.tools) return;
    els.tools.querySelectorAll(".board-tool").forEach((btn) => {
      btn.classList.toggle("active", btn.getAttribute("data-tool") === tool);
    });
  }

  function init() {
    bindElements();
    if (!els.mine || !els.bg) return;

    minePad = CanvasDraw.create(els.mine, {
      transparent: true,
      tools: true,
      logicalW: CANONICAL_W,
      logicalH: CANONICAL_H,
      onStrokeEnd: (drawing) => {
        drawingBusy = true;
        queueStrokeSync(drawing);
        updateChrome();
      }
    });
    minePad.setEnabled(true);
    setTool("pen");

    if (els.tools) {
      els.tools.addEventListener("click", (e) => {
        const btn = e.target.closest(".board-tool");
        if (!btn) return;
        setTool(btn.getAttribute("data-tool") || "pen");
      });
    }

    if (els.swatches) {
      els.swatches.addEventListener("click", (e) => {
        const btn = e.target.closest(".board-swatch");
        if (!btn || btn.disabled) return;
        claimActiveColor(btn.getAttribute("data-color"));
      });
    }

    els.color.addEventListener("input", () => {
      // Live preview while dragging; claim on change
      if (!isTakenByOthers(els.color.value)) {
        activeColor = normalizeHex(els.color.value);
        minePad.setColor(activeColor);
        renderSwatches();
      }
    });
    els.color.addEventListener("change", () => {
      claimActiveColor(els.color.value);
    });
    els.size.addEventListener("input", () => minePad.setSize(els.size.value));
    minePad.setSize(els.size.value);

    els.undoBtn.addEventListener("click", undoMine);
    els.clearMineBtn.addEventListener("click", clearMine);
    els.clearAllBtn.addEventListener("click", clearAll);
    window.addEventListener("resize", () => {
      if (els.panel && !els.panel.hidden) resizeBg();
    });

    resizeBg();
    updateChrome();
  }

  return { init, setSession, onRoomData, showPanel };
})();

window.Whiteboard = Whiteboard;
