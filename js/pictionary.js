/**
 * Pictionary tab — same room, server keeps the secret word.
 */
const Pictionary = (() => {
  let session = null;
  let pad = null;
  let state = null;
  let secretWord = null;
  let syncTimer = 0;
  let lastDrawingAt = "";

  const els = {};

  function bindElements() {
    els.panel = document.getElementById("panel-pic");
    els.status = document.getElementById("pic-status-text");
    els.wordLine = document.getElementById("pic-word-line");
    els.scores = document.getElementById("pic-scores");
    els.guesses = document.getElementById("pic-guesses");
    els.canvas = document.getElementById("pic-canvas");
    els.color = document.getElementById("pic-color");
    els.size = document.getElementById("pic-size");
    els.clearBtn = document.getElementById("pic-clear-btn");
    els.skipBtn = document.getElementById("pic-skip-btn");
    els.startBtn = document.getElementById("pic-start-btn");
    els.guessInput = document.getElementById("pic-guess-input");
    els.guessBtn = document.getElementById("pic-guess-btn");
  }

  function api(action, body) {
    const base =
      window.MyChatConfig && typeof window.MyChatConfig.apiUrl === "function"
        ? window.MyChatConfig.apiUrl(
            `/api/rooms/${encodeURIComponent(session.roomId)}/pictionary/${action}`
          )
        : `/api/rooms/${encodeURIComponent(session.roomId)}/pictionary/${action}`;
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

  function isDrawer() {
    return !!(state && session && state.drawerId === session.authorId && state.phase === "drawing");
  }

  function renderScores() {
    const scores = (state && state.scores) || {};
    const entries = Object.entries(scores).sort((a, b) => b[1] - a[1]);
    if (!entries.length) {
      els.scores.innerHTML = "<em>No scores yet</em>";
      return;
    }
    els.scores.innerHTML = entries
      .map(([name, score]) => `<div class="pic-score-row"><span>${escapeHtml(name)}</span><strong>${score}</strong></div>`)
      .join("");
  }

  function renderGuesses() {
    const list = (state && state.lastGuesses) || [];
    if (!list.length) {
      els.guesses.innerHTML = "<em>Guesses appear here</em>";
      return;
    }
    els.guesses.innerHTML = list
      .slice()
      .reverse()
      .map((g) => {
        const cls = g.correct ? "pic-guess correct" : "pic-guess";
        return `<div class="${cls}"><strong>${escapeHtml(g.authorName)}</strong>: ${escapeHtml(g.text)}</div>`;
      })
      .join("");
  }

  function escapeHtml(s) {
    return String(s || "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function updateChrome() {
    if (!state) {
      els.status.textContent = "Waiting to start…";
      els.wordLine.textContent = "";
      return;
    }

    const drawing = state.phase === "drawing";
    const reveal = state.phase === "reveal";
    const mine = isDrawer();

    if (state.phase === "idle") {
      els.status.textContent = "Tap “I'll draw” to start a round.";
      els.wordLine.textContent = "";
      secretWord = null;
    } else if (drawing && mine) {
      els.status.textContent = `Round ${state.round} — you are drawing`;
      els.wordLine.textContent = secretWord ? `Word: ${secretWord}` : `Hint: ${state.hint || ""}`;
    } else if (drawing) {
      els.status.textContent = `Round ${state.round} — ${state.drawerName || "Someone"} is drawing`;
      els.wordLine.textContent = state.hint || `${state.wordLen || "?"} letters`;
      secretWord = null;
    } else if (reveal) {
      const win = state.winnerName
        ? `${state.winnerName} guessed it!`
        : "Round skipped.";
      els.status.textContent = `${win} Word was “${state.revealWord || "?"}”.`;
      els.wordLine.textContent = "Tap “I'll draw” for the next round.";
      secretWord = null;
    }

    pad.setEnabled(mine);
    els.clearBtn.disabled = !mine;
    els.skipBtn.disabled = !mine;
    els.guessInput.disabled = mine || !drawing;
    els.guessBtn.disabled = mine || !drawing;
    els.startBtn.textContent = state.phase === "idle" ? "I'll draw" : "I'll draw next";

    // Viewer canvas sync
    if (!mine) {
      const stamp = (state.updatedAt || "") + ":" + ((state.drawing && state.drawing.strokes && state.drawing.strokes.length) || 0);
      if (stamp !== lastDrawingAt) {
        lastDrawingAt = stamp;
        pad.loadDrawing(state.drawing);
      }
    }

    renderScores();
    renderGuesses();
  }

  function applyState(pic, opts) {
    state = pic || null;
    if (opts && opts.word) secretWord = opts.word;
    updateChrome();
  }

  function queueStrokeSync(drawing) {
    if (!isDrawer()) return;
    window.clearTimeout(syncTimer);
    syncTimer = window.setTimeout(async () => {
      try {
        const data = await api("stroke", {
          authorId: session.authorId,
          authorName: session.authorName,
          drawing: drawing || pad.getDrawingPayload()
        });
        applyState(data.pictionary);
      } catch (err) {
        console.warn(err);
      }
    }, 280);
  }

  async function startRound() {
    if (!session) return;
    try {
      const data = await api("start", {
        authorId: session.authorId,
        authorName: session.authorName
      });
      pad.clear();
      lastDrawingAt = "";
      applyState(data.pictionary, { word: data.word });
    } catch (err) {
      alert(err.message || "Could not start round");
    }
  }

  async function skipRound() {
    if (!session || !isDrawer()) return;
    try {
      const data = await api("skip", {
        authorId: session.authorId,
        authorName: session.authorName
      });
      applyState(data.pictionary);
    } catch (err) {
      alert(err.message || "Could not skip");
    }
  }

  async function submitGuess() {
    if (!session || isDrawer()) return;
    const guess = els.guessInput.value.trim();
    if (!guess) return;
    els.guessInput.value = "";
    try {
      const data = await api("guess", {
        authorId: session.authorId,
        authorName: session.authorName,
        guess
      });
      applyState(data.pictionary);
      if (data.correct) {
        // celebration is in status line
      }
    } catch (err) {
      alert(err.message || "Guess failed");
    }
  }

  function onRoomData(data) {
    if (!data || !data.pictionary) return;
    // Keep secret word if we are still the drawer mid-round
    const keep =
      secretWord &&
      data.pictionary.phase === "drawing" &&
      session &&
      data.pictionary.drawerId === session.authorId;
    applyState(data.pictionary, keep ? { word: secretWord } : null);
  }

  function setSession(next) {
    session = next;
    if (!next) {
      state = null;
      secretWord = null;
      if (pad) pad.clear();
    }
  }

  function showPanel(visible) {
    if (!els.panel) return;
    els.panel.hidden = !visible;
    if (visible && pad) {
      requestAnimationFrame(() => pad.resize());
      updateChrome();
    }
  }

  function init() {
    bindElements();
    pad = CanvasDraw.create(els.canvas, {
      onStrokeEnd: (drawing) => queueStrokeSync(drawing)
    });
    pad.setEnabled(false);

    els.color.addEventListener("input", () => pad.setColor(els.color.value));
    els.size.addEventListener("input", () => pad.setSize(els.size.value));
    pad.setColor(els.color.value);
    pad.setSize(els.size.value);

    els.clearBtn.addEventListener("click", () => {
      if (!isDrawer()) return;
      pad.clear();
      queueStrokeSync(pad.getDrawingPayload());
    });
    els.skipBtn.addEventListener("click", skipRound);
    els.startBtn.addEventListener("click", startRound);
    els.guessBtn.addEventListener("click", submitGuess);
    els.guessInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") {
        e.preventDefault();
        submitGuess();
      }
    });

    updateChrome();
  }

  return { init, setSession, onRoomData, showPanel };
})();

// Top-level `const` does not attach to window; app.js feature-detects on window.
window.Pictionary = Pictionary;
