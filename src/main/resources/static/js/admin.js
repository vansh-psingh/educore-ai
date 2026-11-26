/* EDUCORE AI - Admin dashboard (admin role only; backend enforces too) */

const $ = (s) => document.querySelector(s);
const esc = (s) => String(s ?? "").replace(/[&<>"']/g,
  c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

function toast(msg) {
  const t = $("#toast");
  t.textContent = msg;
  t.style.display = "block";
  setTimeout(() => t.style.display = "none", 3000);
}

let me = null;
const state = { sec: "overview", page: 1, size: 15, q: "", role: "", status: "" };

async function boot() {
  try {
    me = await API.authMe();
  } catch (e) {
    location.href = "/#/login";
    return;
  }
  if (me.role !== "admin") {
    $("#adminMain").innerHTML = `<div class="card"><h2>Access denied</h2><p class="muted">This area requires the admin role.</p><a href="/">Back to app</a></div>`;
    $("#adminNav").style.display = "none";
    return;
  }
  $("#adminNav").addEventListener("click", e => {
    const b = e.target.closest("button");
    if (!b) return;
    document.querySelectorAll("#adminNav button").forEach(x => x.classList.remove("active"));
    b.classList.add("active");
    state.sec = b.dataset.sec;
    render();
  });
  render();
}

async function render() {
  const main = $("#adminMain");
  try {
    if (state.sec === "overview") return renderOverview(main);
    if (state.sec === "users") return renderUsers(main);
    if (state.sec === "audit") return renderAudit(main);
    if (state.sec === "health") return renderHealth(main);
    if (state.sec === "notify") return renderNotify(main);
  } catch (e) {
    main.innerHTML = `<div class="card"><p class="muted">Failed to load: ${esc(e.message)}</p></div>`;
  }
}

/* ---------- overview ---------- */
async function renderOverview(main) {
  const o = await API.adminOverview();
  const roles = o.users_by_role || {};
  const statuses = o.users_by_status || {};
  const cc = o.content_counts || {};
  const stat = (label, val) => `<div class="stat-card"><div class="num">${val ?? 0}</div><div class="lbl">${label}</div></div>`;
  main.innerHTML = `
    <h2 style="margin-top:0">Platform overview</h2>
    <div class="stat-grid">
      ${stat("Total users", (roles.student||0)+(roles.teacher||0)+(roles.admin||0))}
      ${stat("Students", roles.student)}
      ${stat("Teachers", roles.teacher)}
      ${stat("Admins", roles.admin)}
      ${stat("Active", statuses.active)}
      ${stat("Suspended", statuses.suspended)}
      ${stat("Deactivated", statuses.deactivated)}
      ${stat("Notes", cc.notes)}
      ${stat("Exams", cc.exams)}
      ${stat("Documents", cc.documents)}
      ${stat("Chat messages", cc.chat_messages)}
      ${stat("Essays", cc.essays)}
    </div>
    <div class="card">
      <div class="section-title" style="margin-top:0">System</div>
      <div class="kv">
        <b>Storage</b><span>${esc(o.storage)} ${o.db_ping ? "(connected)" : ""}</span>
        <b>AI provider</b><span>${esc(o.ai_provider)}</span>
        <b>AI cache</b><span>${o.ai_cache_entries} entries</span>
        <b>SMTP</b><span>${o.smtp_configured ? "configured" : "not configured"}</span>
        <b>Uptime</b><span>${Math.floor((o.uptime_sec||0)/60)}m ${(o.uptime_sec||0)%60}s</span>
      </div>
    </div>
    <div class="card" style="margin-top:14px">
      <div class="section-title" style="margin-top:0">Recent logins</div>
      ${(o.recent_logins || []).length ? `<table class="admin-table"><tr><th>User</th><th>Email</th><th>Role</th><th>Last login</th></tr>` +
        o.recent_logins.map(u => `<tr><td>${esc(u.name)}</td><td>${esc(u.email)}</td><td>${esc(u.role)}</td><td>${esc((u.last_login_at||"").slice(0,16).replace("T"," "))}</td></tr>`).join("") + `</table>`
        : '<p class="muted">No login activity recorded yet.</p>'}
    </div>`;
}

/* ---------- users ---------- */
async function renderUsers(main) {
  main.innerHTML = `
    <h2 style="margin-top:0">User management</h2>
    <div class="filters">
      <input type="text" id="uq" placeholder="Search name or email..." value="${esc(state.q)}">
      <select id="urole">
        <option value="">All roles</option>
        <option value="student" ${state.role==="student"?"selected":""}>Student</option>
        <option value="teacher" ${state.role==="teacher"?"selected":""}>Teacher</option>
        <option value="admin" ${state.role==="admin"?"selected":""}>Admin</option>
      </select>
      <select id="ustatus">
        <option value="">All statuses</option>
        <option value="active" ${state.status==="active"?"selected":""}>Active</option>
        <option value="suspended" ${state.status==="suspended"?"selected":""}>Suspended</option>
        <option value="deactivated" ${state.status==="deactivated"?"selected":""}>Deactivated</option>
      </select>
      <button class="btn btn-ghost" id="ufilter">Filter</button>
    </div>
    <div class="card" style="padding:0;overflow:auto"><table class="admin-table" id="utable"></table></div>
    <div class="pager">
      <button id="prevPage">← Prev</button><span class="muted" id="pageInfo"></span><button id="nextPage">Next →</button>
    </div>`;

  $("#ufilter").onclick = () => {
    state.q = $("#uq").value.trim();
    state.role = $("#urole").value;
    state.status = $("#ustatus").value;
    state.page = 1;
    loadUsers();
  };
  $("#uq").addEventListener("keydown", e => { if (e.key === "Enter") $("#ufilter").click(); });
  $("#prevPage").onclick = () => { if (state.page > 1) { state.page--; loadUsers(); } };
  $("#nextPage").onclick = () => { state.page++; loadUsers(); };
  await loadUsers();
}

async function loadUsers() {
  const t = $("#utable");
  if (!t) return;
  t.innerHTML = '<tr><td class="muted">Loading...</td></tr>';
  try {
    const r = await API.adminUsers({ q: state.q, role: state.role, status: state.status, page: state.page, size: state.size });
    const pages = Math.max(1, Math.ceil((r.total || 0) / state.size));
    $("#pageInfo").textContent = `Page ${state.page} of ${pages} · ${r.total} users`;
    if (!(r.users || []).length) {
      t.innerHTML = '<tr><td class="muted">No users match.</td></tr>';
      return;
    }
    t.innerHTML = `<tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th>Notes</th><th>Exams</th><th>Last login</th><th></th></tr>` +
      r.users.map(u => `
        <tr>
          <td>${esc(u.name)}</td><td>${esc(u.email)}</td>
          <td><span class="role-badge role-${esc(u.role)}">${esc(u.role)}</span></td>
          <td class="status-${esc(u.status)}">${esc(u.status)}</td>
          <td>${u.notes ?? ""}</td><td>${u.exams ?? ""}</td>
          <td class="muted">${esc((u.last_login_at || "—").slice(0, 16).replace("T", " "))}</td>
          <td><button class="btn btn-ghost" style="padding:4px 10px;font-size:12px" onclick="openUser('${esc(u.id)}')">Manage</button></td>
        </tr>`).join("");
  } catch (e) {
    t.innerHTML = `<tr><td class="muted">${esc(e.message)}</td></tr>`;
  }
}

window.openUser = async function (id) {
  try {
    const u = await API.adminUser(id);
    const act = u.activity || {};
    $("#modal").innerHTML = `
      <h3 style="margin-top:0">${esc(u.name)}</h3>
      <div class="kv">
        <b>Email</b><span>${esc(u.email)}</span>
        <b>Role</b><span>${esc(u.role)}</span>
        <b>Status</b><span class="status-${esc(u.status)}">${esc(u.status)}</span>
        <b>Joined</b><span>${esc((u.created || "").slice(0, 10))}</span>
        <b>Last login</b><span>${esc(u.last_login_at || "never")}</span>
      </div>
      <div class="section-title">Activity</div>
      <div class="kv">
        <b>Notes</b><span>${act.notes ?? "—"}</span>
        <b>Exams</b><span>${act.exams ?? "—"}</span>
        <b>Documents</b><span>${act.documents ?? "—"}</span>
        <b>Tasks</b><span>${act.planner_tasks ?? "—"}</span>
        <b>Chat msgs</b><span>${act.chat_messages ?? "—"}</span>
        <b>Essays</b><span>${act.essay_submissions ?? "—"}</span>
      </div>
      <div class="section-title">Manage</div>
      <div class="filters">
        <select id="mRole">
          <option value="">Role: ${esc(u.role)}</option>
          <option value="student">student</option>
          <option value="teacher">teacher</option>
          <option value="admin">admin</option>
        </select>
        <select id="mStatus">
          <option value="">Status: ${esc(u.status)}</option>
          <option value="active">active</option>
          <option value="suspended">suspended</option>
          <option value="deactivated">deactivated</option>
        </select>
        <button class="btn btn-primary" id="mSave">Apply</button>
      </div>
      <div class="muted" id="mMsg" style="margin-top:8px"></div>`;
    $("#modalBg").classList.add("open");
    $("#mSave").onclick = async () => {
      const patch = {};
      const r = $("#mRole").value, s = $("#mStatus").value;
      if (r) patch.role = r;
      if (s) patch.status = s;
      if (!r && !s) { $("#mMsg").textContent = "Pick a role or status first."; return; }
      try {
        await API.adminUserUpdate(id, patch);
        $("#mMsg").textContent = "Updated.";
        toast("User updated");
        setTimeout(() => { $("#modalBg").classList.remove("open"); loadUsers(); }, 800);
      } catch (e) { $("#mMsg").textContent = e.message; }
    };
  } catch (e) { toast(e.message); }
};

/* ---------- audit ---------- */
async function renderAudit(main) {
  main.innerHTML = `<h2 style="margin-top:0">Audit log</h2><div class="card" style="padding:0;overflow:auto"><table class="admin-table" id="atable"></table></div>`;
  try {
    const rows = await API.adminAudit(200);
    $("#atable").innerHTML = `<tr><th>Time</th><th>Actor</th><th>Action</th><th>Target</th></tr>` +
      (rows.length ? rows.map(r => `
        <tr><td class="muted">${esc((r.created_at||"").slice(0,19).replace("T"," "))}</td>
        <td>${esc(r.actor_email || r.actor_id || "system")}</td>
        <td>${esc(r.action)}</td><td class="muted">${esc(r.target || "")}</td></tr>`).join("")
      : '<tr><td class="muted">No audit events yet.</td></tr>');
  } catch (e) {
    $("#atable").innerHTML = `<tr><td class="muted">${esc(e.message)}</td></tr>`;
  }
}

/* ---------- health ---------- */
async function renderHealth(main) {
  main.innerHTML = `<h2 style="margin-top:0">System health</h2><div class="card" id="hcard"><div class="muted">Loading...</div></div>`;
  try {
    const h = await API.adminHealth();
    $("#hcard").innerHTML = `<div class="kv">
      <b>Storage mode</b><span>${esc(h.storage)}</span>
      <b>DB configured</b><span>${h.db_configured ? "yes" : "no"}</span>
      <b>DB ping</b><span>${h.db_ping ? "OK" : "FAILED"}</span>
      <b>DB error</b><span>${esc(h.db_init_error || "—")}</span>
      <b>Uptime</b><span>${Math.floor((h.uptime_sec||0)/60)}m ${(h.uptime_sec||0)%60}s</span>
      <b>AI provider</b><span>${esc(h.ai_provider)}</span>
      <b>AI cache entries</b><span>${h.ai_cache_entries}</span>
      <b>SMTP</b><span>${h.smtp_configured ? "configured" : "not configured"}</span>
      <b>Memory</b><span>${h.memory_mb} MB heap</span>
      <b>CPU cores</b><span>${h.processors}</span>
    </div>`;
  } catch (e) {
    $("#hcard").innerHTML = `<div class="muted">${esc(e.message)}</div>`;
  }
}

/* ---------- notify ---------- */
async function renderNotify(main) {
  main.innerHTML = `
    <h2 style="margin-top:0">Platform notifications</h2>
    <div class="grid grid-2">
      <div class="card">
        <div class="section-title" style="margin-top:0">Broadcast</div>
        <div class="field"><label>Title</label><input type="text" id="nTitle"></div>
        <div class="field"><label>Message</label><textarea id="nBody" rows="4"></textarea></div>
        <div class="field"><label>Audience</label>
          <select id="nAud"><option value="platform">All users (platform)</option><option value="user">Single user</option></select></div>
        <div class="field" id="nUserWrap" style="display:none"><label>User ID</label><input type="text" id="nUserId" placeholder="e.g. a1b2c3d4"></div>
        <button class="btn btn-primary" id="nSend">Send notification</button>
        <div class="muted" id="nMsg" style="margin-top:8px"></div>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">Sent</div>
        <div id="nList"><div class="muted">Loading...</div></div>
      </div>
    </div>`;
  $("#nAud").onchange = e => { $("#nUserWrap").style.display = e.target.value === "user" ? "" : "none"; };
  $("#nSend").onclick = async () => {
    const payload = {
      title: $("#nTitle").value.trim(),
      body: $("#nBody").value.trim(),
      audience: $("#nAud").value,
      user_id: $("#nUserId") ? $("#nUserId").value.trim() : ""
    };
    if (!payload.title) { $("#nMsg").textContent = "Title is required."; return; }
    try {
      await API.adminNotify(payload);
      $("#nMsg").textContent = "Sent.";
      $("#nTitle").value = ""; $("#nBody").value = "";
      loadNotifList();
    } catch (e) { $("#nMsg").textContent = e.message; }
  };
  loadNotifList();
}

async function loadNotifList() {
  const el = $("#nList");
  if (!el) return;
  try {
    const rows = await API.adminNotifications();
    el.innerHTML = rows.length ? rows.map(n => `
      <div style="padding:10px 0;border-bottom:1px solid var(--border)">
        <strong>${esc(n.title)}</strong> <span class="chip">${esc(n.audience)}</span>
        <div class="muted" style="margin-top:4px">${esc(n.body || "")}</div>
        <div class="muted" style="font-size:12px">${esc((n.created_at || "").slice(0, 16).replace("T", " "))}</div>
      </div>`).join("") : '<div class="muted">None sent yet.</div>';
  } catch (e) { el.innerHTML = `<div class="muted">${esc(e.message)}</div>`; }
}

$("#modalBg").addEventListener("click", e => { if (e.target.id === "modalBg") e.target.classList.remove("open"); });

document.addEventListener("DOMContentLoaded", boot);
