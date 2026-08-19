const els = {
  login: document.getElementById("login-panel"),
  desk: document.getElementById("desk"),
  mobile: document.getElementById("mobile"),
  otp: document.getElementById("otp"),
  loginBtn: document.getElementById("login-btn"),
  loginError: document.getElementById("login-error"),
  deskError: document.getElementById("desk-error"),
  list: document.getElementById("list"),
  who: document.getElementById("who"),
  refresh: document.getElementById("refresh-btn"),
  logout: document.getElementById("logout-btn"),
};

const TOKEN_KEY = "mychat_admin_token";

function showError(el, msg) {
  if (!msg) {
    el.classList.add("hidden");
    el.textContent = "";
    return;
  }
  el.classList.remove("hidden");
  el.textContent = msg;
}

async function api(path, body, method = "POST") {
  const token = sessionStorage.getItem(TOKEN_KEY) || "";
  const opts = {
    method,
    headers: { Authorization: token ? `Bearer ${token}` : "" },
  };
  if (body) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  const res = await fetch(path, opts);
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.code || res.status}`);
  return data;
}

function renderRows(rows) {
  if (!rows.length) {
    els.list.innerHTML = "<p class='muted'>No pending requests.</p>";
    return;
  }
  els.list.innerHTML = "";
  for (const row of rows) {
    const card = document.createElement("div");
    card.className = "card";
    const name = row.displayName || "(no name)";
    card.innerHTML = `
      <strong>${name}</strong>
      <p class="muted">${row.mobile} · device …${row.ssaidTail || ""}</p>
      <div class="row">
        <button class="ok" type="button" data-act="admit">Admit</button>
        <button class="danger" type="button" data-act="reject">Reject</button>
      </div>
    `;
    card.querySelector('[data-act="admit"]').addEventListener("click", () => act("admit", row.mobile));
    card.querySelector('[data-act="reject"]').addEventListener("click", () => act("reject", row.mobile));
    els.list.appendChild(card);
  }
}

async function loadList() {
  showError(els.deskError, "");
  try {
    const data = await api("/api/admin/requests", null, "GET");
    renderRows(data.requests || []);
  } catch (err) {
    showError(els.deskError, err.message);
    if (/sign-in|expired|admin/i.test(err.message)) signOut();
  }
}

async function act(kind, mobile) {
  showError(els.deskError, "");
  try {
    await api(`/api/admin/${kind}`, { mobile });
    await loadList();
  } catch (err) {
    showError(els.deskError, err.message);
  }
}

function showDesk(mobile) {
  els.login.classList.add("hidden");
  els.desk.classList.remove("hidden");
  els.who.textContent = `Signed in as ${mobile}`;
  loadList();
}

function signOut() {
  sessionStorage.removeItem(TOKEN_KEY);
  els.desk.classList.add("hidden");
  els.login.classList.remove("hidden");
}

els.loginBtn.addEventListener("click", async () => {
  showError(els.loginError, "");
  els.loginBtn.disabled = true;
  try {
    const data = await api("/api/admin/login", {
      mobile: els.mobile.value,
      otp: els.otp.value,
    });
    sessionStorage.setItem(TOKEN_KEY, data.token);
    showDesk(data.mobile);
  } catch (err) {
    showError(els.loginError, err.message);
  } finally {
    els.loginBtn.disabled = false;
  }
});

els.refresh.addEventListener("click", loadList);
els.logout.addEventListener("click", signOut);

if (sessionStorage.getItem(TOKEN_KEY)) {
  showDesk("admin");
}
