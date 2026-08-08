/**
 * Touch/mouse freehand pads — stroke coordinate lists + basic sketch tools.
 * CanvasDraw = default chat pad; CanvasDraw.create(el, { tools: true }) for Board.
 *
 * Stroke payload:
 *   { t:"pen"|"erase"|"line"|"arrow"|"rect"|"circle"|"oval"|"text", c, s, p:[...], tx? }
 */
const CanvasDraw = (() => {
  const TOOLS = ["pen", "erase", "line", "arrow", "rect", "circle", "oval", "text"];
  const SHAPE_TOOLS = ["line", "arrow", "rect", "circle", "oval"];

  function paintOneStroke(ctx, stroke) {
    const type = stroke.type || stroke.t || "pen";
    const pts = stroke.points || stroke.p || [];
    const col = stroke.color || stroke.c || "#0f172a";
    const sz = stroke.size || stroke.s || 4;

    if (type === "text") {
      const text = String(stroke.text || stroke.tx || "").slice(0, 80);
      if (!text || pts.length < 2) return;
      ctx.save();
      ctx.fillStyle = col;
      ctx.font = `600 ${Math.max(12, sz * 3)}px "DM Sans", sans-serif`;
      ctx.textBaseline = "top";
      ctx.fillText(text, pts[0], pts[1]);
      ctx.restore();
      return;
    }

    if (pts.length < 2) return;

    ctx.save();
    if (type === "erase") {
      ctx.globalCompositeOperation = "destination-out";
      ctx.strokeStyle = "rgba(0,0,0,1)";
      ctx.fillStyle = "rgba(0,0,0,1)";
    } else {
      ctx.globalCompositeOperation = "source-over";
      ctx.strokeStyle = col;
      ctx.fillStyle = col;
    }
    ctx.lineCap = "round";
    ctx.lineJoin = "round";
    ctx.lineWidth = type === "erase" ? Math.max(sz * 2, 8) : sz;

    if (SHAPE_TOOLS.includes(type)) {
      if (pts.length < 4) {
        ctx.restore();
        return;
      }
      const x1 = pts[0];
      const y1 = pts[1];
      const x2 = pts[2];
      const y2 = pts[3];
      if (type === "rect") {
        ctx.strokeRect(
          Math.min(x1, x2),
          Math.min(y1, y2),
          Math.abs(x2 - x1) || 1,
          Math.abs(y2 - y1) || 1
        );
      } else if (type === "oval" || type === "circle") {
        let left = Math.min(x1, x2);
        let top = Math.min(y1, y2);
        let w = Math.abs(x2 - x1) || 1;
        let h = Math.abs(y2 - y1) || 1;
        if (type === "circle") {
          const side = Math.max(w, h);
          // Grow from the drag origin corner so it feels like Paint
          if (x2 < x1) left = x1 - side;
          if (y2 < y1) top = y1 - side;
          w = side;
          h = side;
        }
        const cx = left + w / 2;
        const cy = top + h / 2;
        ctx.beginPath();
        ctx.ellipse(cx, cy, w / 2, h / 2, 0, 0, Math.PI * 2);
        ctx.stroke();
      } else {
        ctx.beginPath();
        ctx.moveTo(x1, y1);
        ctx.lineTo(x2, y2);
        ctx.stroke();
        if (type === "arrow") {
          const angle = Math.atan2(y2 - y1, x2 - x1);
          const head = Math.max(10, sz * 3);
          ctx.beginPath();
          ctx.moveTo(x2, y2);
          ctx.lineTo(
            x2 - head * Math.cos(angle - Math.PI / 6),
            y2 - head * Math.sin(angle - Math.PI / 6)
          );
          ctx.lineTo(
            x2 - head * Math.cos(angle + Math.PI / 6),
            y2 - head * Math.sin(angle + Math.PI / 6)
          );
          ctx.closePath();
          ctx.fill();
        }
      }
      ctx.restore();
      return;
    }

    // pen / erase freehand
    if (pts.length === 2) {
      ctx.beginPath();
      ctx.arc(pts[0], pts[1], ctx.lineWidth / 2, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      return;
    }
    ctx.beginPath();
    ctx.moveTo(pts[0], pts[1]);
    for (let i = 2; i < pts.length; i += 2) ctx.lineTo(pts[i], pts[i + 1]);
    ctx.stroke();
    ctx.restore();
  }

  function paintStrokesOn(ctx, strokes) {
    (strokes || []).forEach((stroke) => paintOneStroke(ctx, stroke));
  }

  function toWireStroke(s) {
    const out = {
      t: s.type || "pen",
      c: s.color,
      s: s.size,
      p: s.points
    };
    if (s.text) out.tx = String(s.text).slice(0, 80);
    return out;
  }

  function fromWireStroke(stroke) {
    return {
      type: stroke.t || stroke.type || "pen",
      color: stroke.c || stroke.color || "#0f172a",
      size: stroke.s || stroke.size || 4,
      points: stroke.p || stroke.points || [],
      text: stroke.tx || stroke.text || ""
    };
  }

  function createStrokePad(canvasEl, options) {
    const opts = options || {};
    let canvas = canvasEl;
    let ctx = canvas.getContext("2d", { willReadFrequently: false });
    let drawing = false;
    let lastX = 0;
    let lastY = 0;
    let color = "#0f172a";
    let size = 4;
    let tool = "pen";
    let strokes = [];
    let current = null;
    let enabled = true;
    let onStrokeEnd = opts.onStrokeEnd || null;
    const transparent = !!opts.transparent;
    const toolsEnabled = !!opts.tools;

    function cssSize() {
      const wrap = canvas.parentElement;
      const rect = wrap.getBoundingClientRect();
      return {
        w: Math.max(1, Math.floor(rect.width)),
        h: Math.max(1, Math.floor(rect.height))
      };
    }

    function fillBackground(w, h) {
      if (transparent) {
        ctx.clearRect(0, 0, w, h);
      } else {
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, w, h);
      }
    }

    function resize() {
      if (!canvas) return;
      const { w: cssW, h: cssH } = cssSize();
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const snapshot = strokes.slice();

      canvas.width = Math.floor(cssW * dpr);
      canvas.height = Math.floor(cssH * dpr);
      canvas.style.width = cssW + "px";
      canvas.style.height = cssH + "px";
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      redraw(snapshot, cssW, cssH);
      strokes = snapshot;
    }

    function redraw(list, w, h) {
      fillBackground(w, h);
      list.forEach((stroke) => paintOneStroke(ctx, stroke));
    }

    function q(n) {
      return Math.round(n * 10) / 10;
    }

    function pointerPos(e) {
      const rect = canvas.getBoundingClientRect();
      const point = e.touches ? e.touches[0] : e;
      return {
        x: q(point.clientX - rect.left),
        y: q(point.clientY - rect.top)
      };
    }

    function finishStroke() {
      drawing = false;
      current = null;
      if (typeof onStrokeEnd === "function") onStrokeEnd(getDrawingPayload());
    }

    function start(e) {
      if (!enabled) return;
      e.preventDefault();
      const p = pointerPos(e);

      if (toolsEnabled && tool === "text") {
        const text = window.prompt("Text label (max 80 chars):", "");
        if (text == null) return;
        const cleaned = String(text).trim().slice(0, 80);
        if (!cleaned) return;
        strokes.push({
          type: "text",
          color,
          size,
          points: [p.x, p.y],
          text: cleaned
        });
        const { w, h } = cssSize();
        redraw(strokes, w, h);
        finishStroke();
        return;
      }

      drawing = true;
      lastX = p.x;
      lastY = p.y;
      const type = toolsEnabled ? tool : "pen";
      if (SHAPE_TOOLS.includes(type)) {
        current = { type, color, size, points: [p.x, p.y, p.x, p.y] };
      } else {
        current = { type, color, size, points: [p.x, p.y] };
      }
      strokes.push(current);
      const { w, h } = cssSize();
      redraw(strokes, w, h);
    }

    function move(e) {
      if (!enabled || !drawing || !current) return;
      e.preventDefault();
      const p = pointerPos(e);
      if (SHAPE_TOOLS.includes(current.type)) {
        current.points[2] = p.x;
        current.points[3] = p.y;
        const { w, h } = cssSize();
        redraw(strokes, w, h);
        return;
      }
      const dx = p.x - lastX;
      const dy = p.y - lastY;
      if (dx * dx + dy * dy < 2.25) return;
      current.points.push(p.x, p.y);
      // Incremental paint for pen/erase
      paintOneStroke(ctx, {
        type: current.type,
        color: current.color,
        size: current.size,
        points: [lastX, lastY, p.x, p.y]
      });
      lastX = p.x;
      lastY = p.y;
    }

    function end(e) {
      if (!drawing) return;
      if (e) e.preventDefault();
      if (current && SHAPE_TOOLS.includes(current.type)) {
        const pts = current.points;
        if (pts.length >= 4) {
          const dx = pts[2] - pts[0];
          const dy = pts[3] - pts[1];
          if (dx * dx + dy * dy < 4) {
            // Too small — drop
            strokes.pop();
            const { w, h } = cssSize();
            redraw(strokes, w, h);
          }
        }
      }
      finishStroke();
    }

    canvas.addEventListener("mousedown", start);
    canvas.addEventListener("mousemove", move);
    window.addEventListener("mouseup", end);
    canvas.addEventListener("touchstart", start, { passive: false });
    canvas.addEventListener("touchmove", move, { passive: false });
    canvas.addEventListener("touchend", end, { passive: false });
    canvas.addEventListener("touchcancel", end, { passive: false });
    window.addEventListener("resize", resize);
    resize();

    function setColor(value) {
      color = value;
    }

    function setSize(value) {
      size = Number(value) || 4;
    }

    function setTool(value) {
      const next = String(value || "pen");
      tool = TOOLS.includes(next) ? next : "pen";
      canvas.dataset.tool = tool;
    }

    function getTool() {
      return tool;
    }

    function setEnabled(value) {
      enabled = !!value;
      canvas.style.pointerEvents = enabled ? "auto" : "none";
    }

    function setOnStrokeEnd(fn) {
      onStrokeEnd = fn;
    }

    function clear() {
      strokes = [];
      current = null;
      const { w, h } = cssSize();
      fillBackground(w, h);
    }

    function isBlank() {
      return strokes.length === 0;
    }

    function strokeCount() {
      return strokes.length;
    }

    function undoLast() {
      if (!strokes.length) return false;
      strokes.pop();
      current = null;
      drawing = false;
      const { w, h } = cssSize();
      redraw(strokes, w, h);
      return true;
    }

    function getDrawingPayload() {
      const { w, h } = cssSize();
      return {
        w,
        h,
        strokes: strokes.map(toWireStroke)
      };
    }

    function loadDrawing(payload) {
      const { w, h } = cssSize();
      clear();
      if (!payload) return;
      const list = (payload.strokes || []).map(fromWireStroke);
      strokes = list;
      redraw(list, w, h);
    }

    function open() {
      clear();
      requestAnimationFrame(resize);
    }

    return {
      setColor,
      setSize,
      setTool,
      getTool,
      setEnabled,
      setOnStrokeEnd,
      clear,
      isBlank,
      strokeCount,
      undoLast,
      getDrawingPayload,
      loadDrawing,
      open,
      resize
    };
  }

  function drawingToCanvas(payload) {
    const w = Math.max(1, payload.w || 300);
    const h = Math.max(1, payload.h || 300);
    const el = document.createElement("canvas");
    el.width = w;
    el.height = h;
    el.className = "drawing";
    const c = el.getContext("2d");
    c.fillStyle = "#ffffff";
    c.fillRect(0, 0, w, h);
    paintStrokesOn(c, payload.strokes || []);
    return el;
  }

  let pad = null;

  function init(canvasEl) {
    pad = createStrokePad(canvasEl);
  }

  return {
    TOOLS,
    create: createStrokePad,
    init,
    setColor: (v) => pad && pad.setColor(v),
    setSize: (v) => pad && pad.setSize(v),
    clear: () => pad && pad.clear(),
    isBlank: () => (pad ? pad.isBlank() : true),
    getDrawingPayload: () => (pad ? pad.getDrawingPayload() : null),
    drawingToCanvas,
    paintStrokesOn,
    open: () => pad && pad.open(),
    resize: () => pad && pad.resize()
  };
})();
