(() => {
  // Flip pictionary to true when you want the game tab visible again.
  const FEATURES = { pictionary: false };

  const els = {
    setup: document.getElementById("setup-screen"),
    chat: document.getElementById("chat-screen"),
    draw: document.getElementById("draw-screen"),
    nameInput: document.getElementById("name-input"),
    roomInput: document.getElementById("room-input"),
    joinBtn: document.getElementById("join-btn"),
    leaveBtn: document.getElementById("leave-btn"),
    clearBtn: document.getElementById("clear-btn"),
    exportBtn: document.getElementById("export-btn"),
    roomTitle: document.getElementById("room-title"),
    list: document.getElementById("message-list"),
    textInput: document.getElementById("text-input"),
    sendBtn: document.getElementById("send-btn"),
    drawOpenBtn: document.getElementById("draw-open-btn"),
    drawCancelBtn: document.getElementById("draw-cancel-btn"),
    drawSendBtn: document.getElementById("draw-send-btn"),
    drawClearBtn: document.getElementById("draw-clear-btn"),
    penColor: document.getElementById("pen-color"),
    penSize: document.getElementById("pen-size"),
    canvas: document.getElementById("draw-canvas"),
    audioBtn: document.getElementById("audio-btn"),
    recBanner: document.getElementById("rec-banner"),
    hint: document.querySelector(".hint"),
    syncBadge: document.getElementById("sync-badge")
  };

  let session = null;
  let unsubStorage = null;
  let sending = false;
  let renderToken = 0;
  const bodyCache = new Map();

  let mediaRecorder = null;
  let mediaStream = null;
  let audioChunks = [];
  let recording = false;
  let recordingCancelled = false;
  let recordingStartedAt = 0;
  let maxRecordTimer = 0;
  let useTapToTalk = false;
  const MAX_AUDIO_MS = 60000;

  function uid() {
    return "m_" + Date.now().toString(36) + "_" + Math.random().toString(36).slice(2, 8);
  }

  function show(screen) {
    els.setup.hidden = screen !== "setup";
    els.chat.hidden = screen !== "chat";
    els.draw.hidden = screen !== "draw";
  }

  function formatTime(iso) {
    try {
      return new Date(iso).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
    } catch {
      return "";
    }
  }

  function updateSyncHint() {
    const shared = Storage.isSharedMode();
    const forcedLocal =
      window.MyChatConfig &&
      typeof window.MyChatConfig.preferLocal === "function" &&
      window.MyChatConfig.preferLocal();
    if (els.hint) {
      if (forcedLocal) {
        els.hint.textContent =
          "Local-only mode (config): this device will not sync with other room members.";
      } else if (shared) {
        els.hint.textContent =
          "Shared room: everyone with this room code sees the same chat and board. Payloads are sealed with the room code.";
      } else {
        els.hint.textContent =
          "Local only — others in the room will not see your messages. Start server.py on the VPS (or check API_BASE).";
      }
    }
    if (els.syncBadge) {
      els.syncBadge.textContent = shared ? "Synced" : "Local";
      els.syncBadge.dataset.mode = shared ? "shared" : "local";
      els.syncBadge.title = shared
        ? "Connected to shared room server"
        : "Not syncing across devices";
      els.syncBadge.hidden = false;
    }
  }

  async function decodeBody(msg) {
    if (!msg) return null;
    if (bodyCache.has(msg.id)) return bodyCache.get(msg.id);

    let body = null;
    if (msg.secure && session) {
      try {
        body = await SecurePipe.open(
          { v: msg.v, zip: msg.zip, iv: msg.iv, mac: msg.mac, data: msg.data },
          session.roomId
        );
        bodyCache.set(msg.id, body);
      } catch {
        // Do not cache failures — allows recovery after refresh/code updates
        return { error: "Could not decrypt — clear the room and resend." };
      }
    } else if (msg.type === "text") {
      body = { text: msg.text || "" };
      bodyCache.set(msg.id, body);
    } else if (msg.type === "drawing" && msg.imageData) {
      body = { imageData: msg.imageData };
      bodyCache.set(msg.id, body);
    }

    return body;
  }

  function blobToBase64(blob) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => {
        const result = String(reader.result || "");
        const comma = result.indexOf(",");
        resolve(comma >= 0 ? result.slice(comma + 1) : result);
      };
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
  }

  async function buildSecureMessage(type, plainBody) {
    const envelope = await SecurePipe.seal(plainBody, session.roomId);
    return {
      id: uid(),
      type,
      authorId: session.authorId,
      authorName: session.authorName,
      createdAt: new Date().toISOString(),
      secure: true,
      v: envelope.v,
      zip: envelope.zip,
      iv: envelope.iv,
      mac: envelope.mac,
      data: envelope.data
    };
  }

  async function renderMessages(messages) {
    const token = ++renderToken;

    els.list.innerHTML = "";
    if (!messages.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.innerHTML =
        "<strong>No messages yet</strong>Send text, a sketch, or hold the mic for a voice note.";
      els.list.appendChild(empty);
      return;
    }

    const frag = document.createDocumentFragment();

    for (const msg of messages) {
      if (token !== renderToken) return;

      const mine = session && msg.authorId === session.authorId;
      const row = document.createElement("article");
      row.className = "msg " + (mine ? "mine" : "theirs");
      row.dataset.id = msg.id;

      const meta = document.createElement("div");
      meta.className = "msg-meta";
      meta.textContent = `${mine ? "You" : msg.authorName} · ${formatTime(msg.createdAt)}`;

      const bubble = document.createElement("div");
      bubble.className = "bubble";

      const body = await decodeBody(msg);
      if (token !== renderToken) return;

      if (!body || body.error) {
        bubble.textContent = (body && body.error) || "Unsupported message";
      } else if (msg.type === "drawing") {
        bubble.classList.add("drawing-only");
        if (body.strokes || body.w) {
          bubble.appendChild(CanvasDraw.drawingToCanvas(body));
        } else if (body.imageData) {
          const img = document.createElement("img");
          img.className = "drawing";
          img.alt = "Drawing from " + msg.authorName;
          const src = String(body.imageData || "");
          if (src.startsWith("data:image/") && src.includes(";base64,")) {
            img.src = src;
            bubble.appendChild(img);
          } else {
            bubble.textContent = "(invalid drawing)";
          }
        } else {
          bubble.textContent = "(empty drawing)";
        }
      } else if (msg.type === "audio") {
        bubble.classList.add("audio-bubble");
        const audio = document.createElement("audio");
        audio.controls = true;
        audio.preload = "metadata";
        const mime = String(body.mime || "audio/webm").replace(/[^\w./+-]/g, "");
        const b64 = String(body.audio || "");
        if (b64 && /^[A-Za-z0-9+/]+=*$/.test(b64.slice(0, 64))) {
          audio.src = `data:${mime};base64,${b64}`;
          bubble.appendChild(audio);
        } else {
          bubble.textContent = "(invalid audio)";
        }
      } else {
        bubble.textContent = body.text || "";
      }

      row.appendChild(meta);
      row.appendChild(bubble);
      frag.appendChild(row);
    }

    if (token !== renderToken) return;
    els.list.appendChild(frag);
    scrollToLatest();
  }

  function scrollToLatest() {
    const el = els.list;
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight;
      // Second pass after images/audio layout
      requestAnimationFrame(() => {
        el.scrollTop = el.scrollHeight;
      });
    });
  }

  function applyRoomUpdate(data) {
    if (!session || !data) return;
    if (Storage.normalizeRoom(data.roomId || "") !== session.roomId) return;
    bodyCache.clear();
    renderMessages(data.messages || []);
    if (FEATURES.pictionary && window.Pictionary) Pictionary.onRoomData(data);
    if (window.Whiteboard) Whiteboard.onRoomData(data);
  }

  async function refresh() {
    if (!session) return;
    const room = await Storage.loadRoom(session.roomId);
    if (Storage.normalizeRoom(room.roomId || session.roomId) !== session.roomId) {
      return;
    }
    bodyCache.clear();
    await renderMessages(room.messages || []);
    if (FEATURES.pictionary && window.Pictionary) Pictionary.onRoomData(room);
    if (window.Whiteboard) Whiteboard.onRoomData(room);
    updateSyncHint();
  }

  async function enterRoom(name, roomId) {
    const authorName = name.trim().slice(0, 24) || "Guest";
    const room = Storage.normalizeRoom(roomId);
    await Storage.probeApi();
    updateSyncHint();

    if (unsubStorage) {
      unsubStorage();
      unsubStorage = null;
    }

    bodyCache.clear();
    SecurePipe.clearKeyCache();
    els.list.innerHTML = "";

    session = {
      authorId:
        "u_" +
        authorName.toLowerCase().replace(/\s+/g, "-") +
        "_" +
        Math.random().toString(36).slice(2, 6),
      authorName,
      roomId: room
    };
    Storage.saveSession({
      authorId: session.authorId,
      authorName: session.authorName,
      roomId: session.roomId
    });

    els.roomTitle.textContent = room;
    unsubStorage = Storage.onRoomChange(room, applyRoomUpdate);
    if (FEATURES.pictionary && window.Pictionary) Pictionary.setSession(session);
    if (window.Whiteboard) Whiteboard.setSession(session);

    show("chat");
    setRoomTab("chat");
    await refresh();
    els.textInput.focus();
  }

  function leaveRoom() {
    stopRecording(false);
    if (unsubStorage) {
      unsubStorage();
      unsubStorage = null;
    }
    session = null;
    bodyCache.clear();
    SecurePipe.clearKeyCache();
    Storage.clearSession();
    if (FEATURES.pictionary && window.Pictionary) Pictionary.setSession(null);
    if (window.Whiteboard) Whiteboard.setSession(null);
    els.list.innerHTML = "";
    els.nameInput.value = "";
    els.roomInput.value = "lobby";
    show("setup");
    els.nameInput.focus();
  }

  function setRoomTab(tab) {
    const chatTab = document.getElementById("tab-chat");
    const boardTab = document.getElementById("tab-board");
    const picTab = document.getElementById("tab-pic");
    const chatPanel = document.getElementById("panel-chat");
    const boardPanel = document.getElementById("panel-board");
    const picPanel = document.getElementById("panel-pic");

    // Games stay off until FEATURES.pictionary is enabled
    const next = tab === "pic" && !FEATURES.pictionary ? "chat" : tab;
    const isChat = next === "chat";
    const isBoard = next === "board";
    const isPic = FEATURES.pictionary && next === "pic";

    if (chatTab) {
      chatTab.classList.toggle("active", isChat);
      chatTab.setAttribute("aria-selected", isChat ? "true" : "false");
    }
    if (boardTab) {
      boardTab.classList.toggle("active", isBoard);
      boardTab.setAttribute("aria-selected", isBoard ? "true" : "false");
    }
    if (picTab) {
      picTab.hidden = !FEATURES.pictionary;
      picTab.classList.toggle("active", isPic);
      picTab.setAttribute("aria-selected", isPic ? "true" : "false");
    }

    if (chatPanel) chatPanel.hidden = !isChat;
    if (window.Whiteboard) Whiteboard.showPanel(isBoard);
    else if (boardPanel) boardPanel.hidden = !isBoard;
    if (FEATURES.pictionary && window.Pictionary) Pictionary.showPanel(isPic);
    else if (picPanel) picPanel.hidden = true;
  }

  async function clearChat() {
    if (!session) return;
    const ok = window.confirm(
      `Clear all messages in room "${session.roomId}" for everyone?\n\nThis cannot be undone.`
    );
    if (!ok) return;
    try {
      bodyCache.clear();
      const room = await Storage.clearRoom(session.roomId);
      await renderMessages(room.messages || []);
      if (FEATURES.pictionary && window.Pictionary) Pictionary.onRoomData(room);
      if (window.Whiteboard) Whiteboard.onRoomData(room);
    } catch (err) {
      alert(
        "Could not clear the shared room. Check that the server is running, then try again.\n\n" +
          (err && err.message ? err.message : "")
      );
    }
  }

  async function sendText() {
    if (!session || sending) return;
    const text = els.textInput.value.trim();
    if (!text) return;

    sending = true;
    els.sendBtn.disabled = true;
    try {
      const msg = await buildSecureMessage("text", { text });
      bodyCache.set(msg.id, { text });
      await Storage.appendMessage(session.roomId, msg);
      els.textInput.value = "";
      autosize();
      await refresh();
    } finally {
      sending = false;
      els.sendBtn.disabled = false;
      els.textInput.focus();
    }
  }

  async function sendDrawing() {
    if (!session || sending) return;
    if (CanvasDraw.isBlank()) {
      alert("Draw something first.");
      return;
    }

    sending = true;
    els.drawSendBtn.disabled = true;
    try {
      const drawing = CanvasDraw.getDrawingPayload();
      const msg = await buildSecureMessage("drawing", drawing);
      bodyCache.set(msg.id, drawing);
      await Storage.appendMessage(session.roomId, msg);
      show("chat");
      await refresh();
    } finally {
      sending = false;
      els.drawSendBtn.disabled = false;
    }
  }

  async function sendAudioBlob(blob) {
    if (!session) return;
    if (!blob || blob.size < 64) {
      alert("Recording was empty — tap the mic to start, tap again to send.");
      return;
    }
    sending = true;
    try {
      const audio = await blobToBase64(blob);
      const mime = blob.type || "audio/webm";
      const plain = { mime, audio };
      const msg = await buildSecureMessage("audio", plain);
      bodyCache.set(msg.id, plain);
      await Storage.appendMessage(session.roomId, msg);
      await refresh();
    } catch (err) {
      console.error(err);
      alert("Could not send voice note. Try again.");
    } finally {
      sending = false;
    }
  }

  function pickAudioMime() {
    const candidates = [
      "audio/webm;codecs=opus",
      "audio/webm",
      "audio/mp4",
      "audio/aac",
      "audio/ogg;codecs=opus"
    ];
    for (const type of candidates) {
      if (MediaRecorder.isTypeSupported(type)) return type;
    }
    return "";
  }

  async function startRecording() {
    if (!session || recording || sending) return;

    // Phones block getUserMedia on plain http://192.168.x.x (not a secure context).
    if (!window.isSecureContext) {
      const host = location.hostname || "YOUR-PC-IP";
      alert(
        "Microphone needs HTTPS on mobile.\n\n" +
          "Open: https://" +
          host +
          ":8443\n\n" +
          "Accept the certificate warning, then join the same room."
      );
      return;
    }

    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      alert("Audio recording is not supported in this browser.");
      return;
    }

    if (typeof MediaRecorder === "undefined") {
      alert("MediaRecorder is not available in this browser.");
      return;
    }

    if (window.NativeBridge) {
      const ok = await NativeBridge.ensureMicrophone();
      if (!ok) return;
    }

    try {
      mediaStream = await navigator.mediaDevices.getUserMedia({
        audio: {
          echoCancellation: true,
          noiseSuppression: true
        }
      });
    } catch (err) {
      const name = err && err.name ? err.name : "";
      if (name === "NotAllowedError" || name === "PermissionDeniedError") {
        alert(
          "Microphone permission was denied. Enable it in the app or browser site settings."
        );
      } else {
        alert("Could not access the microphone (" + (name || "error") + ").");
      }
      return;
    }

    audioChunks = [];
    const mime = pickAudioMime();
    const recorderMime = mime || "";

    try {
      mediaRecorder = recorderMime
        ? new MediaRecorder(mediaStream, { mimeType: recorderMime })
        : new MediaRecorder(mediaStream);
    } catch {
      mediaRecorder = new MediaRecorder(mediaStream);
    }

    const recordedType = mediaRecorder.mimeType || recorderMime || "audio/webm";

    mediaRecorder.ondataavailable = (e) => {
      if (e.data && e.data.size) audioChunks.push(e.data);
    };

    mediaRecorder.onerror = () => {
      cleanupStream();
      alert("Recording failed. Try again.");
    };

    mediaRecorder.onstop = async () => {
      const chunks = audioChunks.slice();
      const cancelled = recordingCancelled;
      cleanupStream();
      if (cancelled) return;
      const blob = new Blob(chunks, { type: recordedType });
      await sendAudioBlob(blob);
    };

    recordingCancelled = false;
    recording = true;
    recordingStartedAt = Date.now();
    els.audioBtn.classList.add("recording");
    els.recBanner.hidden = false;
    els.recBanner.textContent = useTapToTalk
      ? "Recording… tap mic again to send"
      : "Recording… release to send";

    // Timeslice so Android actually emits audio chunks before stop
    try {
      mediaRecorder.start(250);
    } catch {
      mediaRecorder.start();
    }

    window.clearTimeout(maxRecordTimer);
    maxRecordTimer = window.setTimeout(() => {
      if (recording) stopRecording(true);
    }, MAX_AUDIO_MS);
  }

  function cleanupStream() {
    window.clearTimeout(maxRecordTimer);
    maxRecordTimer = 0;
    if (mediaStream) {
      mediaStream.getTracks().forEach((t) => t.stop());
      mediaStream = null;
    }
    mediaRecorder = null;
    recording = false;
    els.audioBtn.classList.remove("recording");
    els.recBanner.hidden = true;
    els.recBanner.textContent = "Recording… release to send";
  }

  function stopRecording(send) {
    if (!recording || !mediaRecorder) {
      cleanupStream();
      return;
    }

    // Ignore accidental tiny taps
    if (send && Date.now() - recordingStartedAt < 280) {
      recordingCancelled = true;
      try {
        if (mediaRecorder.state !== "inactive") mediaRecorder.stop();
        else cleanupStream();
      } catch {
        cleanupStream();
      }
      return;
    }

    recordingCancelled = !send;
    try {
      if (mediaRecorder.state === "recording") {
        try {
          mediaRecorder.requestData();
        } catch {
          /* some browsers lack requestData */
        }
        mediaRecorder.stop();
      } else if (mediaRecorder.state !== "inactive") {
        mediaRecorder.stop();
      } else {
        cleanupStream();
      }
    } catch {
      cleanupStream();
    }
  }

  function autosize() {
    const el = els.textInput;
    el.style.height = "auto";
    el.style.height = Math.min(el.scrollHeight, 120) + "px";
  }

  function bindAudio() {
    // Phones: tap to start / tap to stop (hold-to-talk loses pointer events too easily).
    // Desktop: hold to talk.
    useTapToTalk =
      window.matchMedia("(pointer: coarse)").matches ||
      navigator.maxTouchPoints > 0;

    if (useTapToTalk) {
      els.audioBtn.title = "Tap to record / tap to send";
      els.recBanner.textContent = "Recording… tap mic again to send";
      els.audioBtn.addEventListener("click", (e) => {
        e.preventDefault();
        if (recording) stopRecording(true);
        else startRecording();
      });
      return;
    }

    els.audioBtn.title = "Hold to record";
    const onDown = async (e) => {
      e.preventDefault();
      try {
        els.audioBtn.setPointerCapture(e.pointerId);
      } catch {
        /* ignore */
      }
      await startRecording();
    };
    const onUp = (e) => {
      e.preventDefault();
      stopRecording(true);
    };

    els.audioBtn.addEventListener("pointerdown", onDown);
    els.audioBtn.addEventListener("pointerup", onUp);
    els.audioBtn.addEventListener("pointercancel", () => stopRecording(true));
  }

  function bind() {
    els.joinBtn.addEventListener("click", () => {
      const name = els.nameInput.value.trim();
      if (!name) {
        els.nameInput.focus();
        return;
      }
      enterRoom(name, els.roomInput.value || "lobby");
    });

    els.nameInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") els.joinBtn.click();
    });
    els.roomInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter") els.joinBtn.click();
    });

    els.leaveBtn.addEventListener("click", leaveRoom);
    els.clearBtn.addEventListener("click", clearChat);
    els.sendBtn.addEventListener("click", sendText);

    els.textInput.addEventListener("input", autosize);
    els.textInput.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) {
        e.preventDefault();
        sendText();
      }
    });

    els.drawOpenBtn.addEventListener("click", () => {
      show("draw");
      CanvasDraw.open();
    });
    els.drawCancelBtn.addEventListener("click", () => show("chat"));
    els.drawSendBtn.addEventListener("click", sendDrawing);
    els.drawClearBtn.addEventListener("click", () => CanvasDraw.clear());
    els.penColor.addEventListener("input", () => CanvasDraw.setColor(els.penColor.value));
    els.penSize.addEventListener("input", () => CanvasDraw.setSize(els.penSize.value));

    const tabChat = document.getElementById("tab-chat");
    const tabBoard = document.getElementById("tab-board");
    const tabPic = document.getElementById("tab-pic");
    if (tabChat) tabChat.addEventListener("click", () => setRoomTab("chat"));
    if (tabBoard) tabBoard.addEventListener("click", () => setRoomTab("board"));
    if (FEATURES.pictionary && tabPic) tabPic.addEventListener("click", () => setRoomTab("pic"));

    bindAudio();
  }

  async function boot() {
    if (els.exportBtn) {
      els.exportBtn.hidden = true;
      els.exportBtn.setAttribute("aria-hidden", "true");
      els.exportBtn.tabIndex = -1;
      els.exportBtn.onclick = null;
    }

    CanvasDraw.init(els.canvas);
    CanvasDraw.setColor(els.penColor.value);
    CanvasDraw.setSize(els.penSize.value);
    if (FEATURES.pictionary && window.Pictionary) Pictionary.init();
    if (window.Whiteboard) Whiteboard.init();
    bind();

    await Storage.probeApi();
    updateSyncHint();

    const saved = Storage.loadSession();
    els.roomInput.value = (saved && saved.roomId) || "lobby";
    if (saved && saved.authorName && saved.roomId) {
      els.nameInput.value = saved.authorName;
      session = saved;
      els.roomTitle.textContent = saved.roomId;
      unsubStorage = Storage.onRoomChange(saved.roomId, applyRoomUpdate);
      if (FEATURES.pictionary && window.Pictionary) Pictionary.setSession(session);
      if (window.Whiteboard) Whiteboard.setSession(session);
      show("chat");
      setRoomTab("chat");
      await refresh();
    } else {
      show("setup");
      els.nameInput.focus();
    }
  }

  boot();
})();
