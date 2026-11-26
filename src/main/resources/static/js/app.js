/* ============================================================
   EDUCORE AI - Dashboard SPA
   Vanilla JS, hash router, calls Java backend /api endpoints.
   ============================================================ */

const NAV = [
  { view: "overview",     icon: "overview",   label: "Overview" },
  { view: "tutor",        icon: "brain",      label: "AI Tutor" },
  { view: "workspace",    icon: "workspace",  label: "Study Workspace" },
  { view: "notes",        icon: "notes",      label: "Notes Generator" },
  { view: "summarizer",   icon: "filetext",   label: "Summarizer" },
  { view: "quiz",         icon: "quiz",       label: "Quiz Generator" },
  { view: "exams",        icon: "exams",      label: "Exams" },
  { view: "documents",    icon: "documents",  label: "Document Library" },
  { view: "progress",     icon: "progress",   label: "Learning Progress" },
  { view: "insights",     icon: "target",     label: "Insights" },
  { view: "planner",      icon: "planner",    label: "Study Planner" },
  { view: "classrooms",   icon: "classrooms", label: "Classrooms" },
  { view: "_sep" },
  { view: "notifications",icon: "bell",       label: "Notifications" },
  { view: "settings",     icon: "settings",   label: "Settings" },
];

const TITLES = {
  overview: "Overview", tutor: "AI Tutor", workspace: "Study Workspace",
  notes: "Notes Generator", summarizer: "Summarizer", quiz: "Quiz Generator",
  exams: "Exams", documents: "Document Library", progress: "Learning Progress",
  insights: "Learning Insights", planner: "Study Planner", classrooms: "Classrooms",
  notifications: "Notifications", settings: "Settings",
  login: "Sign in", register: "Create account",
  forgot: "Reset password", otp: "Verify code", reset: "Set new password",
};

const state = {
  view: "overview",
  user: null,
  chats: {},          // view -> messages[]
  remoteChats: {},    // chatKey -> backend session_id (persistent history)
  activeChat: "main",
  quiz: null,         // {student, key, answers, submitted, score}
};

const AUTH_PAGES = ["login", "register", "forgot", "otp", "reset"];

/* ---------------- boot ---------------- */

function init() {
  renderNav();
  bindChrome();
  window.addEventListener("hashchange", route);
  // auth gate: resolve session before routing
  API.authMe()
    .then(u => { state.user = u; if (u) applyServerTheme(); })
    .catch(() => { state.user = null; })
    .finally(() => { renderProfile(); route(); });

  // session expired mid-use: drop local state and return to sign-in
  window.addEventListener("educore:unauthorized", () => {
    state.user = null;
    state.chats = {};
    state.remoteChats = {};
    renderProfile();
    location.hash = "#/login";
  });
}

function renderProfile() {
  const u = state.user;
  const nameEl = document.querySelector(".profile-name");
  const roleEl = document.querySelector(".profile-role");
  if (nameEl) nameEl.textContent = u ? u.name : "Guest";
  if (roleEl) roleEl.textContent = u ? u.role : "signed out";
  const initial = ((u && u.name) || "S").trim().charAt(0).toUpperCase() || "S";
  document.querySelectorAll(".avatar").forEach(a => {
    a.textContent = initial;
    a.style.backgroundImage = state.avatarUrl ? `url(${state.avatarUrl})` : "";
  });
  refreshAvatar();
  updateAdminNav();
}

/** Fetches the user's avatar once and applies it to every .avatar element. */
async function refreshAvatar() {
  if (!state.user) {
    if (state.avatarUrl) { URL.revokeObjectURL(state.avatarUrl); state.avatarUrl = null; }
    document.querySelectorAll(".avatar").forEach(a => { a.style.backgroundImage = ""; });
    return;
  }
  try {
    const blob = await API.profileAvatarBlob();
    if (state.avatarUrl) URL.revokeObjectURL(state.avatarUrl);
    state.avatarUrl = blob ? URL.createObjectURL(blob) : null;
    const initial = ((state.user.name) || "S").trim().charAt(0).toUpperCase() || "S";
    document.querySelectorAll(".avatar").forEach(a => {
      a.textContent = state.avatarUrl ? "" : initial;
      a.style.backgroundImage = state.avatarUrl ? `url(${state.avatarUrl})` : "";
    });
    // keep the settings preview in sync if it's on screen
    const pv = document.getElementById("pfAvatarImg");
    if (pv) {
      pv.src = state.avatarUrl || "";
      pv.style.display = state.avatarUrl ? "block" : "none";
    }
    const iv = document.getElementById("pfAvatarInitial");
    if (iv) iv.style.display = state.avatarUrl ? "none" : "";
  } catch (e) { /* keep initials on failure */ }
}

/** Shows the Admin link only for admin-role users (real page, /admin). */
function updateAdminNav() {
  const nav = document.getElementById("nav");
  if (!nav) return;
  nav.querySelectorAll(".nav-item-admin").forEach(a => a.remove());
  if (state.user && state.user.role === "admin") {
    const a = document.createElement("a");
    a.className = "nav-item nav-item-admin";
    a.href = "/admin";
    a.title = "Admin dashboard";
    a.innerHTML = icon("settings") + "<span>Admin</span>";
    const sep = nav.querySelector(".nav-divider");
    nav.insertBefore(a, sep || null);
  }
}

function renderNav() {
  const nav = document.getElementById("nav");
  nav.innerHTML = NAV.map(n => n.view === "_sep"
    ? '<div class="nav-divider"></div>'
    : `<a class="nav-item" data-view="${n.view}" href="#/${n.view}" title="${n.label}">${icon(n.icon)}<span>${n.label}</span></a>`
  ).join("");
}

function bindChrome() {
  // sidebar nav clicks
  document.getElementById("nav").addEventListener("click", e => {
    const item = e.target.closest(".nav-item");
    if (item) closeMobileNav();
  });

  // collapse
  document.getElementById("collapseBtn").innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="m15 18-6-6 6-6"/></svg>';
  document.getElementById("collapseBtn").addEventListener("click", () => {
    document.getElementById("sidebar").classList.toggle("collapsed");
  });

  // mobile menu
  document.getElementById("menuBtn").innerHTML = icon("menu");
  document.getElementById("menuBtn").addEventListener("click", openMobileNav);
  document.getElementById("sidebarOverlay").addEventListener("click", closeMobileNav);

  // topbar icons
  document.getElementById("notifBtn").innerHTML = icon("bell") + '<span class="notif-dot"></span>';
  document.getElementById("helpBtn").innerHTML = icon("help");
  document.getElementById("themeBtn").innerHTML = icon("moon");
  document.getElementById("notifBtn").addEventListener("click", showNotifPanel);
  document.getElementById("helpBtn").addEventListener("click", () => toast("Help center coming soon - for now, explore the AI Tutor."));
  document.getElementById("themeBtn").addEventListener("click", toggleTheme);
  document.getElementById("logoutBtn").innerHTML = icon("logout");
  document.getElementById("logoutBtn").addEventListener("click", async () => {
    try { await API.authLogout(); } catch (e) {}
    state.user = null;
    state.chats = {};
    renderProfile();
    location.hash = "#/login";
  });
  document.getElementById("profileChip").addEventListener("click", () => location.hash = "#/settings");

  // global search: pages + saved notes, debounced dropdown
  const gs = document.getElementById("globalSearch");
  let gsTimer = null, gsSeq = 0;
  const hideGs = () => { const d = document.querySelector(".gs-drop"); if (d) d.remove(); };
  const gsGo = (target) => {
    hideGs(); gs.value = "";
    if (!target) return;
    if (target.kind === "note") {
      state.pendingNoteQuery = target.q;
      location.hash = "#/notes";
    } else {
      location.hash = "#/" + target.view;
    }
  };
  gs.addEventListener("input", () => {
    clearTimeout(gsTimer);
    const q = gs.value.trim();
    if (q.length < 2) { hideGs(); return; }
    gsTimer = setTimeout(() => runGlobalSearch(q), 250);
  });
  gs.addEventListener("keydown", e => {
    if (e.key === "Enter") {
      e.preventDefault();
      const first = document.querySelector(".gs-item");
      if (first && first._gsTarget) gsGo(first._gsTarget);
      else if (gs.value.trim().length >= 2) {
        state.pendingNoteQuery = gs.value.trim();
        hideGs(); gs.value = "";
        location.hash = "#/notes";
      }
    } else if (e.key === "Escape") { gs.value = ""; hideGs(); }
  });
  gs.addEventListener("blur", () => setTimeout(hideGs, 150));

  // click outside closes dropdowns
  document.addEventListener("click", e => {
    if (!e.target.closest(".dropdown")) {
      document.querySelectorAll(".dropdown").forEach(d => d.remove());
    }
  });
}

/* ---------------- global search ---------------- */

async function runGlobalSearch(q) {
  const box = document.querySelector(".search-box");
  if (!box) return;
  hideGsDrop();
  const lq = q.toLowerCase();
  const pages = NAV.filter(n => n.view !== "_sep" && n.label.toLowerCase().includes(lq))
    .slice(0, 4);
  let notes = [];
  try { notes = (await API.notes(q)).slice(0, 5); } catch (e) { /* notes optional */ }
  if (!pages.length && !notes.length) return;

  const dd = document.createElement("div");
  dd.className = "gs-drop dropdown";
  const row = (target, iconName, title, sub) => {
    const el = document.createElement("a");
    el.className = "dropdown-item gs-item";
    el.href = "javascript:void(0)";
    el.innerHTML = `<span class="gs-ic">${icon(iconName)}</span>
      <span class="gs-txt"><strong>${esc(title)}</strong>${sub ? `<br><span class="muted" style="font-size:11px">${esc(sub)}</span>` : ""}</span>`;
    el._gsTarget = target;
    el.addEventListener("mousedown", e => { e.preventDefault(); gsGo(target); });
    return el;
  };
  if (pages.length) {
    const h = document.createElement("div");
    h.className = "dropdown-item"; h.style.cssText = "cursor:default;color:var(--text-2);font-size:11px;font-weight:600;letter-spacing:.04em";
    h.textContent = "PAGES";
    dd.appendChild(h);
    pages.forEach(n => dd.appendChild(row({ kind: "page", view: n.view }, n.icon, n.label, "Go to page")));
  }
  if (notes.length) {
    const h = document.createElement("div");
    h.className = "dropdown-item"; h.style.cssText = "cursor:default;color:var(--text-2);font-size:11px;font-weight:600;letter-spacing:.04em";
    h.textContent = "NOTES";
    dd.appendChild(h);
    notes.forEach(n => dd.appendChild(row({ kind: "note", q }, "notes", n.title, (n.subject || "") + " · " + (n.updated || "").slice(0, 10))));
  }
  box.appendChild(dd);
}
function hideGsDrop() { const d = document.querySelector(".gs-drop"); if (d) d.remove(); }

function openMobileNav() {
  document.getElementById("sidebar").classList.add("open");
  document.getElementById("sidebarOverlay").classList.add("show");
}
function closeMobileNav() {
  document.getElementById("sidebar").classList.remove("open");
  document.getElementById("sidebarOverlay").classList.remove("show");
}

/** Applies a theme everywhere: DOM, topbar icon, localStorage, profile. */
function setTheme(t, persistServer = true) {
  if (t !== "dark" && t !== "light") return;
  document.documentElement.dataset.theme = t;
  const tb = document.getElementById("themeBtn");
  if (tb) tb.innerHTML = icon(t === "dark" ? "moon" : "sun");
  try { localStorage.setItem("educore-theme", t); } catch (e) {}
  document.querySelectorAll("#themeSeg .seg-btn").forEach(b =>
    b.classList.toggle("active", b.dataset.t === t));
  if (persistServer && state.user) {
    API.profileUpdate({ theme: t }).catch(() => {});
  }
}

function toggleTheme() {
  setTheme(document.documentElement.dataset.theme === "dark" ? "light" : "dark");
}

/** Pulls the saved theme from the student profile (server is source of truth). */
async function applyServerTheme() {
  try {
    const p = await getProfile();
    if ((p.theme === "dark" || p.theme === "light")
        && p.theme !== document.documentElement.dataset.theme) {
      setTheme(p.theme, false);
    }
  } catch (e) { /* keep local theme on failure */ }
}

function toast(msg) {
  const t = document.getElementById("toast");
  t.textContent = msg;
  t.classList.add("show");
  clearTimeout(t._timer);
  t._timer = setTimeout(() => t.classList.remove("show"), 3200);
}

function copyText(text, label) {
  navigator.clipboard.writeText(text).then(() => toast((label || "Content") + " copied.")).catch(() => toast("Copy failed."));
}

function esc(s) {
  return String(s ?? "")
    .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

// concurrent callers share one profile request; sequential calls refetch
let _profileInflight = null;
function getProfile() {
  if (!_profileInflight) {
    _profileInflight = API.profile().finally(() => { _profileInflight = null; });
  }
  return _profileInflight;
}

// KaTeX math rendering - works on raw text nodes so multi-line $$ blocks render
function mathify(el) {
  if (typeof renderMathInElement !== "function" || !el) return;
  renderMathInElement(el, {
    delimiters: [
      { left: "$$", right: "$$", display: true },
      { left: "\\(", right: "\\)", display: false },
      { left: "\\[", right: "\\]", display: true },
    ],
    throwOnError: false,
    strict: false
  });
}

// tiny markdown-ish formatter for AI replies
function fmt(text) {
  // stash math spans first so newlines inside $$...$$ don't become <br>
  const stash = [];
  const masked = String(text).replace(/\$\$[\s\S]*?\$\$|\\\([\s\S]*?\\\)|\\\[[\s\S]*?\\\]/g,
    m => { stash.push(m); return "" + (stash.length - 1) + ""; });
  let h = esc(masked);
  h = h.replace(/```(\w*)\n?([\s\S]*?)```/g, '<pre><code>$2</code></pre>');
  h = h.replace(/`([^`\n]+)`/g, "<code>$1</code>");
  h = h.replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>");
  h = h.replace(/^### (.+)$/gm, "<strong>$1</strong>");
  h = h.replace(/^## (.+)$/gm, "<strong>$1</strong>");
  h = h.replace(/^- (.+)$/gm, "<li>$1</li>");
  h = h.replace(/(<li>.*<\/li>\n?)+/g, m => "<ul>" + m + "</ul>");
  h = h.replace(/\n{2,}/g, "<br><br>").replace(/\n/g, "<br>");
  h = h.replace(/(\d+)/g, (_, i) => esc(stash[+i]));
  return h;
}

/* ---------------- router ---------------- */

const VIEWS = {
  overview: vOverview,
  tutor: vTutor,
  workspace: vWorkspace,
  notes: vNotes,
  summarizer: vSummarizer,
  quiz: vQuiz,
  exams: vComingSoon,
  documents: vComingSoon,
  progress: vProgress,
  planner: vComingSoon,
  classrooms: vComingSoon,
  notifications: vNotifications,
  settings: vSettings,
  login: vLogin,
  register: vRegister,
  forgot: vForgot,
  otp: vOtp,
  reset: vReset,
};

function route() {
  const hash = location.hash.replace("#/", "") || "overview";
  state.view = VIEWS[hash] ? hash : "overview";

  // auth gate
  if (!state.user && !AUTH_PAGES.includes(state.view)) {
    location.hash = "#/login";
    return;
  }
  if (state.user && AUTH_PAGES.includes(state.view)) {
    location.hash = "#/overview";
    return;
  }

  // layout gate: chrome only renders for authenticated dashboard views
  document.body.classList.toggle("auth-mode",
    !state.user || AUTH_PAGES.includes(state.view));

  document.querySelectorAll(".nav-item").forEach(a =>
    a.classList.toggle("active", a.dataset.view === state.view));
  document.getElementById("pageTitle").textContent = TITLES[state.view] || "Overview";

  const view = document.getElementById("view");
  view.innerHTML = "";
  try {
    VIEWS[state.view](view);
  } catch (e) {
    console.error("[view] " + state.view + " failed:", e);
    view.innerHTML = `
      <div class="view">
        <div class="card" style="max-width:520px;margin:60px auto;text-align:center">
          <div class="icon-wrap" style="margin:0 auto 12px">${icon("help")}</div>
          <h3>Something went wrong</h3>
          <p class="muted">The ${esc(state.view)} page hit an unexpected error.</p>
          <button class="btn btn-primary" id="viewRetry">Try again</button>
        </div>
      </div>`;
    const rb = document.getElementById("viewRetry");
    if (rb) rb.onclick = route;
  }
  closeMobileNav();
}

/* ============================================================
   VIEWS
   ============================================================ */

function vOverview(root) {
  root.innerHTML = `
  <div class="view">
    <div class="welcome">
      <span class="ai-badge">${icon("sparkles")} AI POWERED</span>
      <h2>Welcome back${state.user && state.user.name ? ", " + esc(state.user.name.split(" ")[0]) : ""}!</h2>
      <p>What would you like to learn today?</p>
      <div id="streakChip" class="streak-chip" style="display:none"></div>
    </div>

    <div id="profileNotice"></div>

    <div class="grid grid-4" id="quickCards"></div>

    <div class="card ask-panel">
      <div class="view-header" style="margin-bottom:14px">
        <h2 style="font-size:17px">Ask EDUCORE AI</h2>
        <p>Get instant answers, explanations and study help.</p>
      </div>
      <div class="ask-box">
        <textarea id="askInput" placeholder="Ask anything about your studies..."></textarea>
        <div class="ask-row">
          <select id="askSubject"><option value="">Any subject</option><option>Mathematics</option><option>Science</option><option>Physics</option><option>Chemistry</option><option>Biology</option><option>English</option><option>History</option><option>Computer Science</option></select>
          <select id="askLevel"><option>General learner</option><option>Middle school</option><option>High school</option><option>Undergraduate</option></select>
          <select id="askLang"><option>English</option><option>Hindi</option><option>Hinglish</option><option>Assamese</option><option>Bengali</option><option>Bodo</option><option>Dogri</option><option>Gujarati</option><option>Kannada</option><option>Kashmiri</option><option>Konkani</option><option>Maithili</option><option>Malayalam</option><option>Manipuri</option><option>Marathi</option><option>Nepali</option><option>Odia</option><option>Punjabi</option><option>Sanskrit</option><option>Santali</option><option>Sindhi</option><option>Tamil</option><option>Telugu</option><option>Urdu</option></select>
          <button class="btn btn-primary" id="askBtn">${icon("send")} Ask</button>
        </div>
        <div class="chips" id="askChips"></div>
      </div>
    </div>

    <div class="section-title">${icon("trending")} Learning overview</div>
    <div class="grid grid-4" id="statCards"></div>

    <div class="grid grid-2" style="margin-top:18px">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("clock")} Recent activity</div>
        <div id="recentActivity">
          <div class="skel skel-line" style="width:80%"></div>
          <div class="skel skel-line" style="width:65%"></div>
          <div class="skel skel-line" style="width:72%"></div>
        </div>
      </div>
      <div class="card" id="progressCard">
        <div class="section-title" style="margin-top:0">${icon("target")} Study progress</div>
        <div class="muted" id="progressSummary">Loading your learning report...</div>
        <div style="margin-top:14px"><a class="btn btn-ghost" href="#/progress">View full report</a></div>
      </div>
    </div>
  </div>`;

  // quick cards
  const quick = [
    { v: "tutor", icon: "brain", c: "linear-gradient(135deg,rgba(139,92,246,.25),rgba(59,130,246,.15))", color: "#a78bfa", t: "AI Tutor", d: "Ask questions and understand any topic.", b: "Start Learning" },
    { v: "notes", icon: "notes", c: "linear-gradient(135deg,rgba(34,211,238,.2),rgba(59,130,246,.12))", color: "#22d3ee", t: "Notes Generator", d: "Generate organized study notes instantly.", b: "Create Notes" },
    { v: "summarizer", icon: "filetext", c: "linear-gradient(135deg,rgba(59,130,246,.22),rgba(139,92,246,.12))", color: "#60a5fa", t: "Summarizer", d: "Convert long material into clear summaries.", b: "Summarize" },
    { v: "quiz", icon: "quiz", c: "linear-gradient(135deg,rgba(34,197,94,.18),rgba(34,211,238,.12))", color: "#4ade80", t: "Quiz Generator", d: "Test your understanding with practice quizzes.", b: "Generate Quiz" },
  ];
  document.getElementById("quickCards").innerHTML = quick.map(q => `
    <div class="card quick-card hoverable">
      <div class="icon-wrap" style="background:${q.c};color:${q.color}">${icon(q.icon)}</div>
      <h3>${q.t}</h3><p>${q.d}</p>
      <a class="btn btn-primary" href="#/${q.v}">${q.b}</a>
    </div>`).join("");

  // ask chips
  const chips = ["Explain photosynthesis simply", "Quiz me on fractions", "Summarize my notes", "Help me with algebra"];
  document.getElementById("askChips").innerHTML = chips.map(c => `<span class="chip">${esc(c)}</span>`).join("");
  document.getElementById("askChips").addEventListener("click", e => {
    if (e.target.classList.contains("chip")) {
      document.getElementById("askInput").value = e.target.textContent;
    }
  });

  const ask = async () => {
    const msg = document.getElementById("askInput").value.trim();
    if (!msg) return;
    // route to tutor with context
    state.pendingQuestion = msg;
    state.askContext = {
      subject: document.getElementById("askSubject").value || undefined,
      academic_level: document.getElementById("askLevel").value,
      language: document.getElementById("askLang").value,
    };
    location.hash = "#/tutor";
  };
  document.getElementById("askBtn").addEventListener("click", ask);
  document.getElementById("askInput").addEventListener("keydown", e => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); ask(); } });

  // stats - honest empty states (no fabricated numbers)
  const stats = [
    { icon: "brain", color: "#a78bfa", bg: "rgba(139,92,246,.15)", label: "Questions answered", val: null },
    { icon: "notes", color: "#22d3ee", bg: "rgba(34,211,238,.12)", label: "Notes generated", val: null },
    { icon: "quiz", color: "#4ade80", bg: "rgba(34,197,94,.12)", label: "Quizzes completed", val: null },
    { icon: "clock", color: "#fbbf24", bg: "rgba(251,191,36,.12)", label: "Learning time", val: null },
  ];
  document.getElementById("statCards").innerHTML = stats.map(s => `
    <div class="card stat-card">
      <div class="stat-icon" style="background:${s.bg};color:${s.color}">${icon(s.icon)}</div>
      <div><div class="stat-empty">No data yet</div><div class="stat-label">${s.label}</div></div>
    </div>`).join("");

  // progress summary from real report endpoint
  API.report().then(r => {
    document.getElementById("progressSummary").innerHTML =
      `Suggested next quiz difficulty: <strong style="color:var(--cyan)">${esc(r.suggested_difficulty)}</strong><br>
       <span class="muted">Based on your recorded quiz history in student_profile.json</span>`;
  }).catch(() => {
    document.getElementById("progressSummary").textContent = "Learning report unavailable.";
  });

  loadProfileNotice();
  loadOverviewExtras();
}

/** Streak chip + real recent-activity feed on the overview page. */
async function loadOverviewExtras() {
  const chip = document.getElementById("streakChip");
  const actBox = document.getElementById("recentActivity");
  const [act, exams, notes, tasks] = await Promise.all([
    API.insightsActivity().catch(() => null),
    API.examHistory().catch(() => []),
    API.notes("", "updated").catch(() => []),
    API.plannerTasks().catch(() => []),
  ]);

  if (chip && act) {
    const n = act.streak_days || 0;
    chip.style.display = "";
    chip.innerHTML = `${icon("award")}<span><strong>${n}</strong> day streak</span>
      <span class="streak-sub">${act.active_today ? "Active today - keep it up!" : "Study today to start a streak"}</span>`;
  }

  if (!actBox) return;
  const items = [];
  (Array.isArray(exams) ? exams : []).forEach(a => items.push({
    d: a.date, icon: "exams", view: "exams",
    txt: `Completed exam “${a.title || "Untitled"}” — scored ${a.score ?? "-"}%`
  }));
  (Array.isArray(notes) ? notes : []).forEach(n => items.push({
    d: n.updated || n.created, icon: "notes", view: "notes",
    txt: `Note “${n.title || "Untitled"}”`
  }));
  (Array.isArray(tasks) ? tasks : []).forEach(t => {
    if (t.completed_at) items.push({
      d: t.completed_at, icon: "planner", view: "planner",
      txt: `Completed task “${t.title || "Untitled"}”`
    });
  });
  items.sort((a, b) => String(b.d || "").localeCompare(String(a.d || "")));
  const top = items.slice(0, 6);
  actBox.innerHTML = top.length ? top.map(i => `
    <a class="act-row" href="#/${i.view}">
      <span class="act-ic">${icon(i.icon)}</span>
      <span class="act-txt">${esc(i.txt)}</span>
      <span class="act-date muted">${esc((i.d || "").slice(0, 10))}</span>
    </a>`).join("")
    : `<div class="empty-state" style="padding:30px 10px">${icon("inbox")}
       <h3>No recent activity</h3>
       <p>Your AI conversations, notes and quizzes will appear here once you start learning.</p></div>`;
}

/* ---------- student profile (mandatory fields) ---------- */

const PROFILE_LABELS = {
  gender: "Gender", age: "Age", qualification: "Qualification",
  course: "Course", academic_year: "Academic year"
};

/** Non-blocking completion notice on the overview page. */
async function loadProfileNotice() {
  const box = document.getElementById("profileNotice");
  if (!box) return;
  try {
    const p = await getProfile();
    if (p.complete) return;
    const names = (p.missing || []).map(f => PROFILE_LABELS[f] || f);
    box.innerHTML = `
      <div class="card" style="border-left:3px solid var(--purple);margin-bottom:18px">
        <div style="display:flex;align-items:center;gap:12px;flex-wrap:wrap">
          <div class="icon-wrap" style="background:rgba(139,92,246,.15);color:#a78bfa;width:38px;height:38px">${icon("users")}</div>
          <div style="flex:1;min-width:200px">
            <strong>Complete your student profile</strong>
            <div class="muted" style="font-size:12.5px">${p.completion_pct}% complete - missing: ${esc(names.join(", "))}</div>
          </div>
          <a class="btn btn-ghost" href="#/settings">Complete now</a>
        </div>
      </div>`;
  } catch (e) { /* profile endpoint unavailable - stay silent */ }
}

/** Settings -> profile form with per-field validation. */
async function loadProfileForm() {
  const box = document.getElementById("profileCard");
  if (!box) return;
  let p;
  try { p = await getProfile(); }
  catch (e) { box.innerHTML = `<div class="muted">Profile unavailable.</div>`; return; }

  const prof = p.profile || {};
  const opt = p.options || {};
  const sel = (id, label, options, val, ph) => `
    <div class="field"><label>${label} <span style="color:#f87171">*</span></label>
      <select id="pf_${id}">
        <option value="">${ph || "Select..."}</option>
        ${(options || []).map(o => `<option ${o === val ? "selected" : ""}>${esc(o)}</option>`).join("")}
      </select>
      <div class="pf-err" id="pferr_${id}"></div>
    </div>`;
  const txt = (id, label, val, ph, type, extra) => `
    <div class="field"><label>${label} <span style="color:#f87171">*</span></label>
      <input type="${type || "text"}" id="pf_${id}" value="${esc(val || "")}" placeholder="${esc(ph || "")}" ${extra || ""}>
      <div class="pf-err" id="pferr_${id}"></div>
    </div>`;

  // split stored E.164 mobile into country code + national number
  const MOB_CC = ["+91", "+971", "+44", "+1", "+65", "+61", "+81"];
  let mobCc = "+91", mobNum = "";
  const rawMob = p.mobile || "";
  if (rawMob.startsWith("+")) {
    const hit = MOB_CC.filter(c => rawMob.startsWith(c)).sort((a, b) => b.length - a.length)[0];
    if (hit) { mobCc = hit; mobNum = rawMob.slice(hit.length); }
    else mobNum = rawMob.slice(1);
  }
  const mobStatus = p.mobile_status || "none";

  box.innerHTML = `
    <div class="pf-avatar-row">
      <div class="pf-avatar" id="pfAvatarWrap">
        <img id="pfAvatarImg" alt="Profile photo" style="display:none">
        <span id="pfAvatarInitial">${esc(((state.user && state.user.name) || "S").trim().charAt(0).toUpperCase())}</span>
      </div>
      <div>
        <div style="font-weight:600;margin-bottom:4px">Profile photo</div>
        <div class="muted" style="font-size:12px;margin-bottom:10px">Optional - PNG, JPEG or WebP, max 2 MB.</div>
        <div style="display:flex;gap:8px;flex-wrap:wrap">
          <button class="btn btn-ghost" id="pfAvatarPick" type="button">${p.has_avatar ? "Change photo" : "Upload photo"}</button>
          ${p.has_avatar ? '<button class="btn btn-ghost" id="pfAvatarRemove" type="button">Remove</button>' : ""}
        </div>
        <input type="file" id="pfAvatarFile" accept="image/png,image/jpeg,image/webp" hidden>
      </div>
    </div>
    <div class="ins-comp" style="margin-bottom:14px">
      <div class="ins-comp-top"><span>Profile completion</span><strong>${p.completion_pct}%</strong></div>
      <div class="ins-bar"><div class="ins-bar-fill" style="width:${p.completion_pct}%"></div></div>
      ${(p.missing || []).length ? `<div class="muted" style="font-size:12px;margin-top:6px">Missing: ${(p.missing || []).map(f => PROFILE_LABELS[f] || f).join(", ")}</div>` : '<div class="muted" style="font-size:12px;margin-top:6px;color:#4ade80">All required fields complete.</div>'}
    </div>
    <div class="form-grid">
      ${sel("gender", "Gender", opt.gender, prof.gender)}
      ${sel("qualification", "Qualification", opt.qualification, prof.qualification)}
      ${sel("academic_year", "Academic year", opt.academic_year, prof.academic_year)}
      ${txt("age", "Age", prof.age, "e.g. 20", "number", 'min="13" max="100" step="1"')}
    </div>
    <div class="field"><label>Course <span style="color:#f87171">*</span></label>
      <input type="text" id="pf_course" list="pf_courses" value="${esc(prof.course || "")}" placeholder="e.g. B.Tech">
      <datalist id="pf_courses">${(opt.courses || []).map(c => `<option value="${esc(c)}">`).join("")}</datalist>
      <div class="pf-err" id="pferr_course"></div>
    </div>
    <div class="field"><label>Mobile number</label>
      <div class="mob-row">
        <select id="pf_cc" class="mob-cc">
          ${[["+91","India"],["+1","US/Canada"],["+44","UK"],["+971","UAE"],["+65","Singapore"],["+61","Australia"],["+81","Japan"]]
            .map(([c, n]) => `<option value="${c}" ${mobCc === c ? "selected" : ""}>${c} ${n}</option>`).join("")}
        </select>
        <input type="tel" id="pf_mobile" class="mob-num" placeholder="98765 43210" value="${esc(mobNum)}" inputmode="numeric">
      </div>
      <div class="pf-err" id="pferr_mobile"></div>
      <div id="mobBox"></div>
    </div>
    <div class="auth-error" id="pfErr" style="margin:4px 0"></div>
    <button class="btn btn-primary" id="pfSave">Save profile</button>`;

  document.getElementById("pfSave").addEventListener("click", async () => {
    const errBox = document.getElementById("pfErr");
    errBox.textContent = "";
    document.querySelectorAll(".pf-err").forEach(e => e.textContent = "");
    const payload = {};
    let clientErr = false;
    for (const f of ["gender", "age", "qualification", "course", "academic_year"]) {
      const el = document.getElementById("pf_" + f);
      const v = (el.value || "").trim();
      const fe = document.getElementById("pferr_" + f);
      if (!v) { fe.textContent = "This field is required."; clientErr = true; continue; }
      if (f === "age") {
        if (!/^\d+$/.test(v) || +v < 13 || +v > 100) {
          fe.textContent = "Enter a whole number between 13 and 100."; clientErr = true; continue;
        }
        payload.age = v;
      } else {
        payload[f] = v;
      }
    }
    if (clientErr) return;
    const btn = document.getElementById("pfSave");
    btn.disabled = true; btn.textContent = "Saving...";
    try {
      const r = await API.profileUpdate(payload);
      toast(r.complete ? "Profile complete!" : "Profile saved.");
      loadProfileForm();
      loadProfileNotice();
    } catch (e) {
      errBox.textContent = e.message;
      btn.disabled = false; btn.textContent = "Save profile";
    }
  });

  refreshAvatar();

  // show cached avatar immediately if we already have it
  if (state.avatarUrl) {
    const avImg = document.getElementById("pfAvatarImg");
    avImg.src = state.avatarUrl; avImg.style.display = "block";
    document.getElementById("pfAvatarInitial").style.display = "none";
  }

  // ---------- avatar upload / remove ----------
  const fileInput = document.getElementById("pfAvatarFile");
  document.getElementById("pfAvatarPick").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    const f = fileInput.files && fileInput.files[0];
    fileInput.value = "";
    if (!f) return;
    if (f.size > 2 * 1024 * 1024) { toast("Image is too large (max 2 MB)."); return; }
    if (!["image/png", "image/jpeg", "image/webp"].includes(f.type)) {
      toast("Use a PNG, JPEG or WebP image."); return;
    }
    try {
      toast("Uploading photo...");
      await API.profileAvatarUpload(f);
      toast("Photo updated.");
      await refreshAvatar();
      loadProfileForm();
    } catch (e) {
      toast(e.message || "Upload failed.");
    }
  });
  const rmBtn = document.getElementById("pfAvatarRemove");
  if (rmBtn) rmBtn.addEventListener("click", async () => {
    try {
      await API.profileAvatarRemove();
      toast("Photo removed.");
      await refreshAvatar();
      loadProfileForm();
    } catch (e) { toast(e.message || "Could not remove photo."); }
  });

  // ---------- mobile number + SMS OTP ----------
  const mobBox = document.getElementById("mobBox");
  const mobChip = st => st === "verified"
    ? '<span class="chip" style="color:#4ade80;border-color:rgba(74,222,128,.4)">Verified</span>'
    : st === "pending"
      ? '<span class="chip" style="color:#fbbf24;border-color:rgba(251,191,36,.4)">Verification pending</span>'
      : '<span class="chip">Not verified</span>';

  mobBox.innerHTML = `
    <div style="display:flex;align-items:center;gap:10px;flex-wrap:wrap;margin:8px 0">
      ${mobChip(mobStatus)}
      <button class="btn btn-ghost" id="mobSaveNum" type="button" style="padding:6px 14px;font-size:12.5px">Save number</button>
    </div>
    ${rawMob && mobStatus !== "verified" ? `<button class="btn btn-primary" id="mobSendOtp" type="button" style="padding:6px 14px;font-size:12.5px">${mobStatus === "pending" ? "Resend OTP" : "Send OTP"}</button>` : ""}
    ${mobStatus === "pending" ? `
      <div class="mob-otp-row" style="display:flex;gap:8px;margin-top:10px;max-width:340px">
        <input type="text" id="mobOtp" maxlength="6" inputmode="numeric" placeholder="6-digit code" style="flex:1">
        <button class="btn btn-primary" id="mobVerify" type="button">Verify</button>
      </div>` : ""}
    <div class="auth-error" id="mobErr" style="margin:4px 0"></div>`;

  const mobErr = () => document.getElementById("mobErr");

  document.getElementById("mobSaveNum").addEventListener("click", async () => {
    mobErr().textContent = "";
    document.getElementById("pferr_mobile").textContent = "";
    const cc = document.getElementById("pf_cc").value;
    const num = (document.getElementById("pf_mobile").value || "").replace(/[^\d]/g, "");
    if (!num) { document.getElementById("pferr_mobile").textContent = "Enter your mobile number."; return; }
    try {
      await API.mobileSave({ country_code: cc, mobile: num });
      toast("Mobile number saved.");
      loadProfileForm();
      loadProfileNotice();
    } catch (e) {
      document.getElementById("pferr_mobile").textContent = e.message;
    }
  });

  const sendBtn = document.getElementById("mobSendOtp");
  if (sendBtn) sendBtn.addEventListener("click", async () => {
    mobErr().textContent = "";
    sendBtn.disabled = true;
    try {
      const r = await API.mobileOtpSend();
      toast("Verification code sent.");
      loadProfileForm();
      startResendCooldown();
    } catch (e) {
      mobErr().textContent = e.message;
      sendBtn.disabled = false;
    }
  });

  const verifyBtn = document.getElementById("mobVerify");
  if (verifyBtn) verifyBtn.addEventListener("click", async () => {
    mobErr().textContent = "";
    const otp = (document.getElementById("mobOtp").value || "").trim();
    if (!/^\d{6}$/.test(otp)) { mobErr().textContent = "Enter the 6-digit code."; return; }
    verifyBtn.disabled = true;
    try {
      await API.mobileOtpVerify({ otp });
      toast("Mobile number verified!");
      loadProfileForm();
      loadProfileNotice();
    } catch (e) {
      mobErr().textContent = e.message;
      verifyBtn.disabled = false;
    }
  });

  // 60s resend cooldown on the button label
  function startResendCooldown() {
    let left = 60;
    const tick = () => {
      const b = document.getElementById("mobSendOtp");
      if (!b) return;
      if (left <= 0) { b.disabled = false; b.textContent = "Resend OTP"; return; }
      b.disabled = true;
      b.textContent = `Resend OTP (${left}s)`;
      left--;
      setTimeout(tick, 1000);
    };
    tick();
  }
}

/* ---------- AI Tutor (chat) ---------- */

function chatMessages() {
  if (!state.chats[state.activeChat]) state.chats[state.activeChat] = [];
  return state.chats[state.activeChat];
}

function vTutor(root) {
  root.innerHTML = `
  <div class="view" style="height:100%">
    <div class="chat-wrap">
      <div class="chat-sidebar">
        <button class="btn btn-primary" id="newChat" style="justify-content:center">${icon("plus")} New chat</button>
        <div class="chat-list" id="chatList"></div>
      </div>
      <div class="chat-main">
        <div class="chat-messages" id="chatMsgs"></div>
        <div class="chat-input-bar">
          <input type="file" id="chatFile" accept="image/*" style="display:none">
          <button class="icon-btn" id="chatAttach" title="Attach image"></button>
          <textarea id="chatInput" placeholder="Ask anything about your studies..."></textarea>
          <select id="chatLang" class="chat-lang" title="Voice recognition language">
            <option value="en-IN">EN</option>
            <option value="hi-IN">हिं</option>
          </select>
          <button class="icon-btn" id="chatMic" title="Voice input (speech is transcribed, not stored)"></button>
          <button class="btn btn-primary" id="chatSend">${icon("send")}</button>
          <button class="btn btn-ghost" id="chatStop" style="display:none">Stop</button>
        </div>
      </div>
    </div>
  </div>`;

  renderChatList();
  renderChatMsgs();
  loadRemoteChats();

  document.getElementById("newChat").addEventListener("click", () => {
    state.activeChat = "c" + Date.now();
    state.chats[state.activeChat] = [];
    renderChatList();
    renderChatMsgs();
  });

  const sessionId = () => "chat-" + state.activeChat;
  let abortCtrl = null;

  const setBusy = (busy) => {
    document.getElementById("chatSend").style.display = busy ? "none" : "";
    document.getElementById("chatStop").style.display = busy ? "" : "none";
  };

  const send = async (overrideText) => {
    const input = document.getElementById("chatInput");
    const text = (overrideText ?? input.value).trim();
    if (!text || abortCtrl) return;
    input.value = "";
    const msgs = chatMessages();
    msgs.push({ role: "user", text });
    renderChatMsgs();

    const botMsg = { role: "bot", text: "", module: "ai", streaming: true };
    msgs.push(botMsg);
    renderChatMsgs();
    const streamEl = () => document.querySelector("#chatMsgs .msg.bot:last-child .stream-text");

    abortCtrl = new AbortController();
    setBusy(true);
    try {
      const r = await API.aiChatStream(
        { message: text, session_id: sessionId() },
        delta => { if (streamEl()) streamEl().textContent += delta; },
        abortCtrl.signal
      );
      botMsg.text = r.answer || "";
      botMsg.module = "gemini";
      delete botMsg.streaming;
      renderChatMsgs();
    } catch (e) {
      if (e.name === "AbortError") {
        botMsg.text = botMsg.text || "(stopped)";
        delete botMsg.streaming;
        renderChatMsgs();
      } else {
        // fallback: non-streaming universal chat, then local engine
        try {
          const r = await API.aiChat({ message: text, session_id: sessionId() });
          botMsg.text = r.answer; botMsg.module = r.model || "ai";
        } catch (e2) {
          try {
            const r = await API.chatLocal(text);
            botMsg.text = r.reply; botMsg.module = r.module;
          } catch (e3) {
            botMsg.text = "Sorry, something went wrong: " + e3.message; botMsg.module = "error";
          }
        }
        delete botMsg.streaming;
        renderChatMsgs();
      }
    } finally {
      abortCtrl = null;
      setBusy(false);
    }
  };

  document.getElementById("chatSend").addEventListener("click", () => send());
  document.getElementById("chatStop").addEventListener("click", () => abortCtrl?.abort());
  document.getElementById("chatInput").addEventListener("keydown", e => {
    if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
  });

  // ---------- voice input (Web Speech API - no audio is stored) ----------
  const micBtn = document.getElementById("chatMic");
  const langSel = document.getElementById("chatLang");
  const chatInput = document.getElementById("chatInput");
  micBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z"/><path d="M19 10v2a7 7 0 0 1-14 0v-2"/><line x1="12" x2="12" y1="19" y2="22"/></svg>';

  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  let recog = null, listening = false;

  const setMicState = (state, label) => {
    micBtn.classList.toggle("recording", state === "recording");
    micBtn.classList.toggle("denied", state === "denied");
    micBtn.title = label || "Voice input (speech is transcribed, not stored)";
  };

  if (SR) {
    recog = new SR();
    recog.interimResults = true;
    recog.continuous = false;
    recog.lang = langSel.value;

    recog.onresult = (e) => {
      let finalText = "", interim = "";
      for (let i = e.resultIndex; i < e.results.length; i++) {
        const t = e.results[i][0].transcript;
        if (e.results[i].isFinal) finalText += t; else interim += t;
      }
      // show live transcription in the input so the user can edit before sending
      const base = chatInput.dataset.preVoice || "";
      chatInput.value = (base + " " + (finalText || interim)).trim();
      chatInput.dataset.preVoice = base;
    };
    recog.onend = () => {
      listening = false;
      setMicState("idle");
      if (chatInput.value.trim()) chatInput.focus();
    };
    recog.onerror = (e) => {
      listening = false;
      setMicState("idle");
      if (e.error === "not-allowed" || e.error === "service-not-allowed") {
        setMicState("denied", "Microphone blocked - check browser site permissions");
        toast("Microphone access denied. Allow mic permission for this site, or keep typing - text input works fine.");
      } else if (e.error === "no-speech") {
        toast("No speech detected. Try again or type your question.");
      } else if (e.error !== "aborted") {
        toast("Voice input error: " + e.error + ". You can still type your message.");
      }
    };
  } else {
    micBtn.style.opacity = "0.4";
    micBtn.title = "Voice input not supported in this browser - type instead";
  }

  micBtn.addEventListener("click", () => {
    if (!recog) {
      toast("Voice input isn't supported in this browser. Please type your message.");
      return;
    }
    if (listening) { recog.stop(); return; }
    try {
      recog.lang = langSel.value;
      chatInput.dataset.preVoice = chatInput.value;
      listening = true;
      setMicState("recording", "Listening... click mic to stop");
      recog.start();
    } catch (e) {
      listening = false;
      setMicState("idle");
      toast("Could not start microphone: " + e.message);
    }
  });
  langSel.addEventListener("change", () => { if (recog) recog.lang = langSel.value; });

  // image attach -> vision endpoint
  document.getElementById("chatAttach").innerHTML = icon("upload");
  const fileInput = document.getElementById("chatFile");
  document.getElementById("chatAttach").addEventListener("click", () => fileInput.click());
  fileInput.addEventListener("change", async () => {
    const f = fileInput.files[0];
    fileInput.value = "";
    if (!f) return;
    const question = document.getElementById("chatInput").value.trim();
    document.getElementById("chatInput").value = "";
    const msgs = chatMessages();
    msgs.push({ role: "user", text: (question || "What is in this image?") + " [image attached]" });
    const botMsg = { role: "bot", text: "", module: "vision", streaming: true };
    msgs.push(botMsg);
    renderChatMsgs();
    const streamEl = () => document.querySelector("#chatMsgs .msg.bot:last-child .stream-text");
    try {
      const dataUrl = await new Promise((res, rej) => {
        const fr = new FileReader();
        fr.onload = () => res(fr.result);
        fr.onerror = rej;
        fr.readAsDataURL(f);
      });
      const b64 = String(dataUrl).split(",")[1];
      const mime = f.type || "image/png";
      const r = await API.aiVision({ session_id: sessionId(), image: b64, mime, question });
      botMsg.text = r.answer; botMsg.module = "vision";
    } catch (e) {
      botMsg.text = "Image analysis failed: " + e.message; botMsg.module = "error";
    }
    delete botMsg.streaming;
    renderChatMsgs();
  });

  // pending question from overview
  if (state.pendingQuestion) {
    const q = state.pendingQuestion;
    state.pendingQuestion = null;
    document.getElementById("chatInput").value = q;
    send();
  } else if (chatMessages().length === 0) {
    chatMessages().push({
      role: "bot",
      module: "educore",
      text: "Hello! I'm EDUCORE AI. Ask me anything - I'll use the OpenAI tutor when configured, or my built-in study engine otherwise. Try: \"Explain photosynthesis\", \"quiz me on math\", \"study notes topic: water cycle\"."
    });
    renderChatMsgs();
  }
}

// backend session id for a chat key: "<userId>:chat-<key>"
function backendSession(chatKey) {
  return (state.user ? state.user.id : "anon") + ":" + "chat-" + chatKey;
}

async function loadRemoteChats() {
  try {
    const sessions = await API.chatSessions();
    const uidPrefix = (state.user ? state.user.id : "") + ":";
    sessions.forEach(s => {
      const sid = s.session_id || "";
      if (!sid.startsWith(uidPrefix)) return;
      const chatKey = sid.slice(uidPrefix.length).replace(/^chat-/, "");
      if (chatKey) state.remoteChats[chatKey] = sid;
    });
    renderChatList();
  } catch (e) { /* history unavailable - local chats still work */ }
}

async function restoreChat(chatKey) {
  const sid = state.remoteChats[chatKey];
  if (!sid || (state.chats[chatKey] && state.chats[chatKey].length)) return;
  try {
    const turns = await API.chatHistory(sid);
    if (!turns.length) return;
    state.chats[chatKey] = turns.map(t => ({
      role: t.role === "model" ? "bot" : "user",
      text: t.text,
      module: "history"
    }));
    if (chatKey === state.activeChat) renderChatMsgs();
  } catch (e) { /* ignore */ }
}

function renderChatList() {
  const list = document.getElementById("chatList");
  if (!list) return;
  const keys = [...new Set([...Object.keys(state.chats), ...Object.keys(state.remoteChats)])];
  list.innerHTML = keys.map(k => {
    const msgs = state.chats[k] || [];
    const first = msgs.length
      ? (msgs.find(m => m.role === "user")?.text || msgs[0].text || "Chat").slice(0, 34)
      : "Previous chat";
    return `<div class="chat-item ${k === state.activeChat ? "active" : ""}" data-chat="${k}">${esc(first)}</div>`;
  }).join("");
  list.querySelectorAll(".chat-item").forEach(el =>
    el.addEventListener("click", () => {
      state.activeChat = el.dataset.chat;
      restoreChat(state.activeChat);
      renderChatList(); renderChatMsgs();
    }));
}

function renderChatMsgs() {
  const box = document.getElementById("chatMsgs");
  if (!box) return;
  const msgs = chatMessages();
  box.innerHTML = msgs.map((m, i) => `
    <div class="msg ${m.role}">
      ${m.role === "bot" && m.module ? `<span class="module-tag">${esc(m.module)}</span>` : ""}
      <div>${m.streaming ? `<span class="stream-text">${esc(m.text)}</span>` : fmt(m.text)}</div>
      ${m.role === "bot" && !m.streaming ? `<button class="chip" style="margin-top:8px" data-copy="${i}">copy</button>` : ""}
      ${m.role === "bot" && !m.streaming && i === msgs.length - 1 ? `<button class="chip" style="margin-top:8px;margin-left:6px" data-retry="${i}">retry</button>` : ""}
      ${m.role === "bot" && !m.streaming ? `<button class="chip fb-up" style="margin-top:8px;margin-left:6px" data-fb="5" data-i="${i}" title="Good answer">&#128077;</button><button class="chip fb-down" style="margin-top:8px;margin-left:4px" data-fb="1" data-i="${i}" title="Bad answer">&#128078;</button>` : ""}
    </div>`).join("");
  box.querySelectorAll("[data-copy]").forEach(b =>
    b.addEventListener("click", () => copyText(msgs[+b.dataset.copy].text, "Answer")));
  box.querySelectorAll("[data-fb]").forEach(b =>
    b.addEventListener("click", async () => {
      const i = +b.dataset.i;
      const m = msgs[i];
      const prev = msgs[i - 1];
      const rating = +b.dataset.fb;
      let comment = "";
      if (rating <= 2) {
        comment = prompt("What should EDUCORE AI do better? (optional)") || "";
      }
      b.disabled = true;
      try {
        await API.feedback({
          rating,
          comment,
          question: prev && prev.role === "user" ? prev.text : "",
          answer: (m.text || "").slice(0, 1500)
        });
        toast(rating >= 4 ? "Thanks! I'll remember this style." : "Thanks - your feedback helps me improve.");
      } catch (e) {
        toast("Could not save feedback.");
        b.disabled = false;
      }
    }));
  box.querySelectorAll("[data-retry]").forEach(b =>
    b.addEventListener("click", () => {
      const i = +b.dataset.retry;
      const prev = msgs[i - 1];
      if (prev && prev.role === "user") {
        const text = prev.text;
        msgs.splice(i - 1, 2);
        renderChatMsgs();
        send(text);
      }
    }));
  box.scrollTop = box.scrollHeight;
  mathify(box);
}

/* ---------- Study Workspace ---------- */

function vWorkspace(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Study Workspace</h2><p>Your daily learning hub - quick tools and focus areas.</p></div>
    <div class="grid grid-3">
      ${[
        ["notes", "Generate study notes", "Structured notes for any topic", "notes"],
        ["summarizer", "Summarize material", "Condense long texts fast", "filetext"],
        ["quiz", "Practice quiz", "Test yourself with generated questions", "quiz"],
        ["tutor", "Ask the tutor", "Get explanations at your level", "brain"],
        ["progress", "Learning report", "See revision topics & schedule", "progress"],
        ["planner", "Study planner", "Organize your week (coming soon)", "planner"],
      ].map(([v, t, d, ic]) => `
        <a class="card quick-card hoverable" href="${v === "planner" ? "#/planner" : "#/" + v}" style="text-decoration:none;color:inherit">
          <div class="icon-wrap" style="background:var(--grad-soft);color:var(--purple)">${icon(ic)}</div>
          <h3>${t}</h3><p>${d}</p>
          <span class="btn btn-ghost">Open</span>
        </a>`).join("")}
    </div>
    <div class="card lvf-card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("video")} Lecture Video Finder</div>
      <p class="muted" style="margin-bottom:14px">Find the best YouTube lectures for any topic - curated for your level and language.</p>
      <div class="lvf-grid">
        <div class="field"><label>Subject</label><input type="text" id="lvfSubject" placeholder="e.g. Data Structures, Organic Chemistry, Physics"></div>
        <div class="field"><label>Topic / Chapter</label><input type="text" id="lvfTopic" placeholder="e.g. Binary Search Trees, Thermodynamics"></div>
        <div class="field"><label>Academic Level / Course</label>
          <select id="lvfLevel">
            <option value="School / High School">School / High School</option>
            <option value="Undergraduate / B.Tech" selected>Undergraduate / B.Tech</option>
            <option value="Competitive Exams">Competitive Exams</option>
            <option value="Beginner Crash Course">Beginner Crash Course</option>
          </select></div>
        <div class="field"><label>Language</label>
          <select id="lvfLang"><option>English</option><option>Hindi</option><option>Hinglish</option></select></div>
        <div class="field"><label>Lecture Type</label>
          <select id="lvfFormat">
            <option value="full chapter lecture">One-Shot / Full Chapter</option>
            <option value="detailed playlist">Detailed Playlist</option>
            <option value="quick revision">Quick Concept Revision</option>
          </select></div>
        <div class="field lvf-go-wrap">
          <button class="btn btn-primary lvf-go" id="lvfGo">${icon("play")} Find Best Lectures</button>
        </div>
      </div>
      <div id="lvfResults"></div>
    </div>
    <div class="card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("sparkles")} Daily focus</div>
      <p class="muted">Consistency beats intensity - even 30 focused minutes daily builds real progress. Use the Learning Progress page to see which topics need revision based on your quiz scores.</p>
    </div>
  </div>`;

  // prefill academic level from the student profile when possible
  getProfile().then(p => {
    const prof = p && p.profile ? p.profile : {};
    const q = (prof.qualification || "") + " " + (prof.academic_year || "");
    const lvl = document.getElementById("lvfLevel");
    if (!lvl) return;
    if (/school|secondary|higher secondary/i.test(q)) lvl.value = "School / High School";
    else if (/undergrad|postgrad|diploma|b\.tech|bachelor|master/i.test(q)) lvl.value = "Undergraduate / B.Tech";
  }).catch(() => {});

  document.getElementById("lvfGo").addEventListener("click", lvfSearch);
}

/* ---------- Lecture Video Finder ---------- */

const LVF_LEVEL_QUERY = {
  "School / High School": "high school",
  "Undergraduate / B.Tech": "undergraduate lecture",
  "Competitive Exams": "competitive exam preparation",
  "Beginner Crash Course": "crash course"
};

async function lvfSearch() {
  const subject = document.getElementById("lvfSubject").value.trim();
  const topic = document.getElementById("lvfTopic").value.trim();
  if (!subject && !topic) { toast("Enter a subject or topic first."); return; }

  const btn = document.getElementById("lvfGo");
  const out = document.getElementById("lvfResults");
  btn.disabled = true;
  btn.classList.add("loading");
  btn.innerHTML = `${icon("video")} Searching...`;
  out.innerHTML = `<div class="vid-grid">${'<div class="vid-card"><div class="skel skel-line" style="height:140px"></div><div class="skel skel-line" style="width:70%"></div></div>'.repeat(3)}</div>`;

  try {
    const r = await API.videoSearch({
      subject,
      topic,
      level: LVF_LEVEL_QUERY[document.getElementById("lvfLevel").value] || document.getElementById("lvfLevel").value,
      language: document.getElementById("lvfLang").value,
      format: document.getElementById("lvfFormat").value
    });
    renderVideoResults(r);
  } catch (e) {
    out.innerHTML = `<div class="card" style="margin-top:14px"><div class="auth-error">${esc(e.message)}</div></div>`;
  } finally {
    btn.disabled = false;
    btn.classList.remove("loading");
    btn.innerHTML = `${icon("play")} Find Best Lectures`;
  }
}

function renderVideoResults(r) {
  const out = document.getElementById("lvfResults");
  if (!out) return;
  const cards = [];

  if (r.mode === "api" && Array.isArray(r.videos)) {
    r.videos.forEach(v => cards.push(`
      <div class="vid-card">
        <div class="vid-thumb">
          ${v.thumbnail ? `<img src="${esc(v.thumbnail)}" alt="" loading="lazy" referrerpolicy="no-referrer">` : ""}
          ${v.duration ? `<span class="vid-dur">${esc(v.duration)}</span>` : ""}
          <button class="vid-play" data-embed="${esc(v.embed)}" title="Watch here">${icon("play")}</button>
        </div>
        <div class="vid-meta">
          <div class="vid-title">${esc(v.title)}</div>
          <div class="vid-sub muted">${esc(v.channel)}${v.published ? " · " + esc(v.published) : ""}</div>
          <div class="vid-actions">
            <button class="btn btn-primary btn-sm" data-embed="${esc(v.embed)}">${icon("play")} Watch Here</button>
            <a class="btn btn-ghost btn-sm" href="${esc(v.url)}" target="_blank" rel="noopener">${icon("external")} YouTube</a>
          </div>
        </div>
      </div>`));
  } else {
    // no API key: smart YouTube search cards - Watch Here embeds the
    // search-results player so real lectures play without any key
    (r.results || []).forEach(v => {
      const embed = "https://www.youtube-nocookie.com/embed?listType=search&list=" + encodeURIComponent(v.query || r.query || "");
      cards.push(`
      <div class="vid-card">
        <div class="vid-thumb vid-thumb-alt">
          <div class="vid-thumb-icon">${icon("video")}</div>
          <button class="vid-play" data-embed="${esc(embed)}" title="Watch here">${icon("play")}</button>
        </div>
        <div class="vid-meta">
          <div class="vid-title">${esc(v.title)}</div>
          <div class="vid-sub muted">${esc(v.channel)} · smart search</div>
          <div class="vid-actions">
            <button class="btn btn-primary btn-sm" data-embed="${esc(embed)}">${icon("play")} Watch Here</button>
            <a class="btn btn-ghost btn-sm" href="${esc(v.url)}" target="_blank" rel="noopener">${icon("external")} YouTube</a>
          </div>
        </div>
      </div>`);
    });
  }

  if (!cards.length) {
    out.innerHTML = `<div class="card" style="margin-top:14px"><div class="muted">No lecture results found. Try broadening the topic or level.</div></div>`;
    return;
  }
  out.innerHTML = `<div class="vid-grid">${cards.join("")}</div>
    <div class="muted" style="margin-top:10px;font-size:12px">Query: “${esc(r.query || "")}”${r.mode === "links" ? " · configure YOUTUBE_API_KEY for direct video results" : ""}</div>`;
  out.querySelectorAll("[data-embed]").forEach(b =>
    b.addEventListener("click", () => openVideoModal(b.dataset.embed)));
}

function openVideoModal(embedUrl) {
  closeVideoModal();
  const ov = document.createElement("div");
  ov.className = "video-modal";
  ov.innerHTML = `<div class="video-modal-box">
    <button class="video-modal-close" title="Close">✕</button>
    <div class="video-modal-frame">
      <iframe src="${esc(embedUrl)}" title="Lecture video"
        allow="accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share"
        allowfullscreen></iframe>
    </div>
  </div>`;
  const onKey = e => { if (e.key === "Escape") closeVideoModal(); };
  ov._onKey = onKey;
  ov.addEventListener("click", e => { if (e.target === ov) closeVideoModal(); });
  document.body.appendChild(ov);
  document.addEventListener("keydown", onKey);
  ov.querySelector(".video-modal-close").onclick = closeVideoModal;
}
function closeVideoModal() {
  document.querySelectorAll(".video-modal").forEach(m => {
    if (m._onKey) document.removeEventListener("keydown", m._onKey);
    m.remove();
  });
}

/* ---------- Notes Generator ---------- */

const notesState = { tab: "generate", editing: null };

function vNotes(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Notes Generator</h2><p>AI-powered structured study notes with math rendering - saved automatically to your library.</p></div>
    <div class="tabs" id="notesTabs">
      <button class="tab ${notesState.tab === "generate" ? "active" : ""}" data-tab="generate">${icon("notes")} Generate Notes</button>
      <button class="tab ${notesState.tab === "history" ? "active" : ""}" data-tab="history">${icon("clock")} Notes History</button>
    </div>
    <div id="notesPane"></div>
  </div>`;

  document.querySelectorAll("#notesTabs .tab").forEach(b =>
    b.addEventListener("click", () => { notesState.tab = b.dataset.tab; vNotes(root); }));

  if (notesState.tab === "history") renderNotesHistory(document.getElementById("notesPane"));
  else renderNotesGenerate(document.getElementById("notesPane"));
}

function renderNotesGenerate(pane) {
  pane.innerHTML = `
  <div class="grid" style="grid-template-columns:1fr 1fr">
    <div class="card">
      <div class="field"><label>Topic</label><input type="text" id="nTopic" placeholder="e.g. Human digestive system"></div>
      <div class="form-grid">
        <div class="field"><label>Subject</label><input type="text" id="nSubject" placeholder="e.g. Biology"></div>
        <div class="field"><label>Level</label><select id="nLevel"><option>Middle school</option><option selected>High school</option><option>Undergraduate</option><option>General learner</option></select></div>
        <div class="field"><label>Language</label><select id="nLang"><option>English</option><option>Hindi</option><option>Hinglish</option><option>Assamese</option><option>Bengali</option><option>Bodo</option><option>Dogri</option><option>Gujarati</option><option>Kannada</option><option>Kashmiri</option><option>Konkani</option><option>Maithili</option><option>Malayalam</option><option>Manipuri</option><option>Marathi</option><option>Nepali</option><option>Odia</option><option>Punjabi</option><option>Sanskrit</option><option>Santali</option><option>Sindhi</option><option>Tamil</option><option>Telugu</option><option>Urdu</option></select></div>
        <div class="field"><label>Detail</label><select id="nDetail"><option value="short">Short</option><option value="medium" selected>Medium</option><option value="detailed">Detailed</option></select></div>
      </div>
      <button class="btn btn-primary" id="nGo">${icon("sparkles")} Generate notes</button>
      <div class="muted" id="nStatus" style="margin-top:10px"></div>
    </div>
    <div class="card" style="grid-column:1/-1">
      <div class="section-title" style="margin-top:0">Output <span id="nCopyWrap" style="display:none"><button class="chip" id="nCopy">copy</button> <button class="chip" id="nSaveAs">save as new</button></span></div>
      <div class="output-panel" id="nOut">Generated notes will appear here...</div>
    </div>
  </div>`;

  let lastGenerated = null;

  document.getElementById("nGo").addEventListener("click", async () => {
    const topic = document.getElementById("nTopic").value.trim();
    if (!topic) { toast("Enter a topic first."); return; }
    const btn = document.getElementById("nGo");
    btn.disabled = true;
    document.getElementById("nStatus").textContent = "Generating...";
    document.getElementById("nOut").textContent = "";
    try {
      const r = await API.aiNotes({
        topic,
        subject: document.getElementById("nSubject").value || undefined,
        academic_level: document.getElementById("nLevel").value,
        language: document.getElementById("nLang").value,
        detail_level: document.getElementById("nDetail").value
      });
      const out = document.getElementById("nOut");
      out.textContent = r.answer;   // raw text keeps $$ blocks intact for KaTeX
      mathify(out);
      document.getElementById("nCopyWrap").style.display = "";
      document.getElementById("nStatus").textContent = "Done - model: " + r.model;
      document.getElementById("nCopy").onclick = () => copyText(r.answer, "Notes");
      lastGenerated = {
        topic,
        subject: document.getElementById("nSubject").value || "",
        language: document.getElementById("nLang").value,
        level: document.getElementById("nLevel").value,
        content: r.answer
      };
      // auto-save to the persistent notes library
      try {
        const saved = await API.noteSave(lastGenerated);
        document.getElementById("nStatus").textContent += " · saved to Notes History (id " + saved.id + ")";
      } catch (e) {
        document.getElementById("nStatus").textContent += " · (auto-save failed: " + e.message + ")";
      }
    } catch (e) {
      document.getElementById("nOut").textContent = "Error: " + e.message;
      document.getElementById("nStatus").textContent = "Failed.";
    } finally { btn.disabled = false; }
  });

  document.getElementById("nSaveAs")?.addEventListener("click", async () => {
    if (!lastGenerated) return;
    try {
      await API.noteSave(lastGenerated);
      toast("Saved to Notes History.");
    } catch (e) { toast("Save failed: " + e.message); }
  });
}

/* ---------- Notes History ---------- */

let notesCache = [];

function renderNotesHistory(pane) {
  pane.innerHTML = `
  <div class="card">
    <div style="display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:14px">
      <input type="text" id="nhSearch" placeholder="Search title, subject or topic..." style="flex:1;min-width:200px">
      <select id="nhSort">
        <option value="updated">Recently updated</option>
        <option value="oldest">Oldest first</option>
        <option value="title">Title A-Z</option>
      </select>
      <button class="chip" id="nhRefresh">refresh</button>
    </div>
    <div id="nhList"><div class="muted">Loading your notes...</div></div>
  </div>
  <div id="nhDetail"></div>`;

  const reload = () => loadNotesList();
  document.getElementById("nhSearch").addEventListener("input", reload);
  document.getElementById("nhSort").addEventListener("change", reload);
  document.getElementById("nhRefresh").addEventListener("click", reload);
  if (state.pendingNoteQuery) {
    document.getElementById("nhSearch").value = state.pendingNoteQuery;
    state.pendingNoteQuery = null;
  }
  loadNotesList();
}

async function loadNotesList() {
  const list = document.getElementById("nhList");
  if (!list) return;
  try {
    const q = document.getElementById("nhSearch")?.value || "";
    const sort = document.getElementById("nhSort")?.value || "updated";
    notesCache = await API.notes(q, sort);
    if (!notesCache.length) {
      list.innerHTML = '<div class="empty-state" style="padding:30px 10px">' + icon("notes") +
        '<h3>No saved notes yet</h3><p>Generate notes and they\'ll appear here automatically.</p></div>';
      return;
    }
    list.innerHTML = notesCache.map(n => `
      <div class="doc-row" data-id="${n.id}">
        <div class="doc-meta" style="flex:1">
          <strong>${esc(n.title)}</strong>
          <span class="muted">${esc(n.subject || "General")} · ${esc(n.topic || "")} · ${n.chars} chars</span><br>
          <span class="muted" style="font-size:11px">created ${esc((n.created || "").slice(0, 10))} · updated ${esc((n.updated || "").slice(0, 10))}</span>
        </div>
        <div class="doc-actions">
          <button class="chip" data-act="view">view</button>
          <button class="chip" data-act="rename">rename</button>
          <button class="chip" data-act="edit">edit</button>
          <button class="chip" data-act="dl">download</button>
          <button class="chip danger" data-act="del">delete</button>
        </div>
      </div>`).join("");
    list.querySelectorAll(".doc-row").forEach(row => {
      const id = row.dataset.id;
      row.querySelectorAll("[data-act]").forEach(b =>
        b.addEventListener("click", () => noteAction(id, b.dataset.act)));
    });
  } catch (e) {
    list.innerHTML = '<div class="muted">Could not load notes: ' + esc(e.message) + '</div>';
  }
}

async function noteAction(id, act) {
  const detail = document.getElementById("nhDetail");
  if (!detail) return;
  try {
    if (act === "del") {
      if (!confirm("Delete this note permanently? This cannot be undone.")) return;
      await API.noteDelete(id);
      toast("Note deleted.");
      detail.innerHTML = "";
      loadNotesList();
      return;
    }
    if (act === "rename") {
      const n = await API.note(id);
      const t = prompt("Rename note:", n.title);
      if (t === null || !t.trim()) return;
      await API.noteUpdate(id, { title: t.trim() });
      toast("Renamed.");
      loadNotesList();
      return;
    }
    const n = await API.note(id);
    if (act === "view") {
      detail.innerHTML = `<div class="card">
        <div class="section-title" style="margin-top:0">${esc(n.title)} <span class="muted" style="font-weight:400">· ${esc(n.subject || "")} · updated ${esc((n.updated || "").slice(0, 10))}</span></div>
        <div class="output-panel" id="nhView"></div>
        <div style="margin-top:10px;display:flex;gap:8px">
          <button class="chip" id="nhEditBtn">edit</button>
          <button class="chip" id="nhDlBtn">download .md</button>
          <button class="chip danger" id="nhDelBtn">delete</button>
        </div></div>`;
      const v = document.getElementById("nhView");
      v.textContent = n.content;
      mathify(v);
      document.getElementById("nhEditBtn").onclick = () => noteAction(id, "edit");
      document.getElementById("nhDlBtn").onclick = () => downloadNote(n);
      document.getElementById("nhDelBtn").onclick = () => noteAction(id, "del");
    } else if (act === "edit") {
      detail.innerHTML = `<div class="card">
        <div class="section-title" style="margin-top:0">Edit: ${esc(n.title)}</div>
        <div class="field"><label>Title</label><input type="text" id="neTitle" value="${esc(n.title)}"></div>
        <div class="field"><label>Content (LaTeX math supported: $$...$$ and \\(...\\))</label>
          <textarea id="neContent" rows="18" style="width:100%">${esc(n.content)}</textarea></div>
        <div style="display:flex;gap:8px">
          <button class="btn btn-primary" id="neSave">Save changes</button>
          <button class="btn btn-ghost" id="neCancel">Cancel</button>
        </div></div>`;
      document.getElementById("neSave").onclick = async () => {
        const content = document.getElementById("neContent").value;
        if (!content.trim()) { toast("Content cannot be empty."); return; }
        try {
          await API.noteUpdate(id, {
            title: document.getElementById("neTitle").value.trim() || n.title,
            content
          });
          toast("Note saved.");
          detail.innerHTML = "";
          loadNotesList();
        } catch (e) { toast("Save failed: " + e.message); }
      };
      document.getElementById("neCancel").onclick = () => { detail.innerHTML = ""; };
    } else if (act === "dl") {
      downloadNote(n);
    }
    detail.scrollIntoView({ behavior: "smooth" });
  } catch (e) {
    toast("Error: " + e.message);
  }
}

function downloadNote(n) {
  const blob = new Blob(["# " + n.title + "\n\n" + n.content], { type: "text/markdown" });
  const a = document.createElement("a");
  a.href = URL.createObjectURL(blob);
  a.download = (n.title || "note").replace(/[^\w\- ]+/g, "").trim().replace(/\s+/g, "-") + ".md";
  a.click();
  URL.revokeObjectURL(a.href);
}

/* ---------- Summarizer ---------- */

function vSummarizer(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Summarizer</h2><p>Turn long study material into clear summaries (requires OpenAI key).</p></div>
    <div class="grid" style="grid-template-columns:1fr 1fr">
      <div class="card">
        <div class="field"><label>Material</label><textarea id="sText" rows="10" placeholder="Paste your notes, chapter text or article here..."></textarea></div>
        <div class="form-grid">
          <div class="field"><label>Length</label><select id="sLen"><option value="short">Short</option><option value="standard" selected>Standard</option><option value="detailed">Detailed</option></select></div>
        </div>
        <button class="btn btn-primary" id="sGo">${icon("filetext")} Summarize</button>
        <div class="muted" id="sStatus" style="margin-top:10px"></div>
      </div>
      <div class="card" style="grid-column:1/-1">
        <div class="section-title" style="margin-top:0">Summary <span id="sCopyWrap" style="display:none"><button class="chip" id="sCopy">copy</button></span></div>
        <div class="output-panel" id="sOut">Your summary will appear here...</div>
      </div>
    </div>
  </div>`;

  document.getElementById("sGo").addEventListener("click", async () => {
    const text = document.getElementById("sText").value.trim();
    if (!text) { toast("Paste some material first."); return; }
    const btn = document.getElementById("sGo");
    btn.disabled = true;
    document.getElementById("sStatus").textContent = "Summarizing...";
    try {
      const r = await API.aiSummary({ text, length: document.getElementById("sLen").value });
      const out = document.getElementById("sOut");
      out.textContent = r.answer;
      mathify(out);
      document.getElementById("sCopyWrap").style.display = "";
      document.getElementById("sStatus").textContent = "Done - model: " + r.model;
      document.getElementById("sCopy").onclick = () => copyText(r.answer, "Summary");
    } catch (e) {
      document.getElementById("sOut").textContent = "Error: " + e.message;
      document.getElementById("sStatus").textContent = "Failed.";
    } finally { btn.disabled = false; }
  });
}

/* ---------- Quiz Generator (hybrid engine) ---------- */

function modeBadge(mode) {
  if (mode === "notes") return '<span class="mode-badge mode-notes">from your notes</span>';
  if (mode === "ai_knowledge") return '<span class="mode-badge mode-ai">AI knowledge</span>';
  return '<span class="mode-badge mode-fallback">local practice</span>';
}

function vQuiz(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Quiz Generator</h2><p>Practice quizzes generated from your source material - answers stay hidden until you submit.</p></div>
    <div class="card" id="quizSetup">
      <div class="form-grid">
        <div class="field"><label>Topic</label><input type="text" id="qTopic" placeholder="e.g. Fractions"></div>
        <div class="field"><label>Subject</label><input type="text" id="qSubject" placeholder="e.g. Math"></div>
        <div class="field"><label>Level</label><select id="qLevel"><option>General</option><option>Middle school</option><option>High school</option><option>Undergraduate</option></select></div>
        <div class="field"><label>Difficulty</label><select id="qDiff"><option value="easy">Easy</option><option value="standard" selected>Standard</option><option value="hard">Hard</option></select></div>
        <div class="field"><label>Questions</label><input type="number" id="qCount" value="5" min="1" max="20"></div>
      </div>
      <div class="field"><label>Source material (Term: definition per line - optional; leave empty for AI-generated questions)</label>
        <textarea id="qSource" rows="4" placeholder="Fraction: a part of a whole&#10;Denominator: the bottom number of a fraction"></textarea>
      </div>
      <button class="btn btn-primary" id="qGo">${icon("quiz")} Generate quiz</button>
      <span class="coming-soon-tag" style="margin-left:10px">hybrid engine</span>
    </div>
    <div id="quizArea" style="margin-top:18px"></div>
  </div>`;

  document.getElementById("qGo").addEventListener("click", async () => {
    const topic = document.getElementById("qTopic").value.trim() || "General";
    const btn = document.getElementById("qGo");
    btn.disabled = true;
    try {
      const r = await API.quiz({
        topic,
        subject: document.getElementById("qSubject").value || "General",
        level: document.getElementById("qLevel").value,
        difficulty: document.getElementById("qDiff").value,
        count: +document.getElementById("qCount").value || 5,
        source: document.getElementById("qSource").value
      });
      state.quiz = { student: r.student_view, sessionId: r.session_id, answers: {}, submitted: false, mode: r.generation_mode || "notes" };
      state.quizDisclaimer = r.disclaimer;
      renderQuiz();
    } catch (e) {
      toast("Quiz failed: " + e.message);
    } finally { btn.disabled = false; }
  });
}

function renderQuiz() {
  const area = document.getElementById("quizArea");
  const q = state.quiz;
  if (!q) return;
  const sv = q.student;

  area.innerHTML = `
  <div class="card">
    <div class="view-header" style="margin-bottom:16px">
      <h2 style="font-size:18px">${esc(sv.title)}</h2>
      <p>${esc(sv.subject)} · ${esc(sv.difficulty)} · ${sv.total_marks} marks · ${sv.questions.length} questions${modeBadge(q.mode)}</p>
    </div>
    <div id="quizQuestions">
      ${sv.questions.map(qq => `
        <div class="quiz-q" data-n="${qq.number}">
          <div class="q-num">QUESTION ${qq.number} · ${qq.marks} MARK${qq.marks > 1 ? "S" : ""} · ${qq.type.replace("_", " ")}</div>
          <div class="q-text">${esc(qq.question)}</div>
          ${qq.type === "mcq" ? qq.options.map((o, i) =>
            `<button class="quiz-opt" data-q="${qq.number}" data-v="${esc(o)}">${String.fromCharCode(65 + i)}. ${esc(o)}</button>`).join("") : ""}
          ${qq.type === "true_false" ? qq.options.map(o =>
            `<button class="quiz-opt" data-q="${qq.number}" data-v="${esc(o)}">${esc(o)}</button>`).join("") : ""}
          ${qq.type === "short_answer" ? `<div class="quiz-short"><input type="text" data-q="${qq.number}" placeholder="Type your answer..."></div>` : ""}
        </div>`).join("")}
    </div>
    <div style="display:flex;gap:10px;margin-top:8px">
      <button class="btn btn-primary" id="qSubmit">Submit answers</button>
      <button class="btn btn-ghost" id="qRetake">New quiz</button>
    </div>
    <div class="muted" style="margin-top:10px">${esc(state.quizDisclaimer || "These are AI-generated practice questions, not official examination questions.")}</div>
  </div>`;

  // option selection
  area.querySelectorAll(".quiz-opt").forEach(btn => {
    btn.addEventListener("click", () => {
      const qn = btn.dataset.q;
      area.querySelectorAll(`.quiz-opt[data-q="${qn}"]`).forEach(b => b.classList.remove("selected"));
      btn.classList.add("selected");
      q.answers[qn] = btn.dataset.v;
    });
  });
  area.querySelectorAll(".quiz-short input").forEach(inp => {
    inp.addEventListener("input", () => { q.answers[inp.dataset.q] = inp.value; });
  });

  document.getElementById("qSubmit").addEventListener("click", async () => {
    if (q.submitted) return;
    q.submitted = true;
    const btn = document.getElementById("qSubmit");
    btn.disabled = true;
    btn.textContent = "Scoring...";
    try {
      // server-side scoring - answer key never leaves the backend
      const r = await API.quizSubmit(q.sessionId, q.answers);
      const keyMap = {};
      let got = 0, total = 0;
      (r.review || []).forEach(k => {
        keyMap[k.number] = k;
        got += k.earned || 0;
        total += k.marks || 0;
      });
      renderQuizResult(sv, keyMap, got, total);
    } catch (e) {
      q.submitted = false;
      btn.disabled = false;
      btn.textContent = "Submit answers";
      toast("Submit failed: " + e.message);
    }
  });

  document.getElementById("qRetake").addEventListener("click", () => {
    state.quiz = null;
    document.getElementById("quizArea").innerHTML = "";
  });
  mathify(area);
}

function renderQuizResult(sv, keyMap, got, total) {
  const area = document.getElementById("quizArea");
  const pct = total ? Math.round(100 * got / total) : 0;
  const cls = pct >= 70 ? "good" : pct >= 40 ? "mid" : "bad";

  // mark options
  document.querySelectorAll("#quizQuestions .quiz-q").forEach(div => {
    const n = +div.dataset.n;
    const k = keyMap[n];
    if (!k) return;
    div.querySelectorAll(".quiz-opt").forEach(b => {
      b.disabled = true;
      if (b.dataset.v === k.correct_answer) b.classList.add("correct");
      else if (b.dataset.v === state.quiz.answers[n] && b.dataset.v !== k.correct_answer) b.classList.add("wrong");
    });
    const inp = div.querySelector(".quiz-short input");
    if (inp) {
      inp.disabled = true;
      const wrap = document.createElement("div");
      wrap.className = "muted";
      wrap.style.marginTop = "8px";
      wrap.innerHTML = `Your answer: <strong>${esc(state.quiz.answers[n] || "-")}</strong><br>Correct: <strong style="color:#4ade80">${esc(k.correct_answer)}</strong><br><em>${esc(k.explanation || "")}</em>`;
      div.appendChild(wrap);
    }
    // explanation for mcq/tf
    if (k.explanation && div.querySelector(".quiz-opt")) {
      const ex = document.createElement("div");
      ex.className = "muted";
      ex.style.marginTop = "8px";
      ex.innerHTML = `<em>${esc(k.explanation)}</em>`;
      div.appendChild(ex);
    }
  });

  document.getElementById("qSubmit").outerHTML = "";
  const banner = document.createElement("div");
  banner.className = `quiz-result-banner ${cls}`;
  banner.innerHTML = `<div class="score">${pct}%</div>
    <div><strong>${got}/${total} marks</strong><br>
    <span class="muted">${pct >= 70 ? "Great work!" : pct >= 40 ? "Good effort - review the explanations." : "Keep practicing - review the material and try again."}</span></div>`;
  area.querySelector(".card").insertBefore(banner, document.getElementById("quizQuestions"));
  mathify(area);
}

/* ---------- Coming soon pages ---------- */

function vComingSoon(root) {
  const conf = {
    exams: ["exams", "Exams & past papers", "Exam practice mode with timed tests and graded results is on the roadmap."],
    documents: ["documents", "Document Library", "Upload PDF/DOCX/TXT files, preview them and ask questions. Document storage backend is planned next."],
    planner: ["planner", "Study Planner", "Calendar view, tasks, due dates and reminders - coming in the next update."],
    classrooms: ["classrooms", "Classrooms", "Join classes, share materials and collaborate with teachers and peers - coming soon."],
  }[state.view] || ["sparkles", "Coming soon", "This feature is on the roadmap."];

  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>${conf[1]}</h2><p>${conf[2]}</p></div>
    <div class="card empty-state">
      ${icon(conf[0])}
      <h3>Coming soon</h3>
      <p>This module isn't wired to a backend yet - we don't show fake functionality.<br>
      Meanwhile, the AI Tutor, Notes, Summarizer, Quiz and Progress features are fully working.</p>
      <div style="margin-top:18px"><a class="btn btn-primary" href="#/overview">Back to overview</a></div>
    </div>
  </div>`;
}

/* ---------- Notifications ---------- */

function vNotifications(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Notifications</h2><p>Platform announcements and alerts for your account.</p></div>
    <div class="card" id="notifList"><div class="muted">Loading...</div></div>
  </div>`;
  API.notifications().then(list => {
    const el = document.getElementById("notifList");
    if (!el) return;
    if (!list.length) {
      el.innerHTML = `<div class="empty-state">${icon("bell")}<h3>All caught up</h3><p>No notifications yet.</p></div>`;
      return;
    }
    el.innerHTML = list.map(n => `
      <div style="padding:12px 0;border-bottom:1px solid var(--border)">
        <strong>${esc(n.title)}</strong>
        ${n.audience === "platform" ? ' <span class="chip">platform</span>' : ""}
        <div class="muted" style="margin-top:4px">${esc(n.body || "")}</div>
        <div class="muted" style="font-size:12px;margin-top:4px">${esc((n.created_at || "").slice(0, 16).replace("T", " "))}</div>
      </div>`).join("");
  }).catch(() => {
    const el = document.getElementById("notifList");
    if (el) el.innerHTML = '<div class="muted">Notifications unavailable.</div>';
  });
}

/* ---------- Learning Progress ---------- */

function vProgress(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Learning Progress</h2><p>Personalized report generated from your recorded student data.</p></div>
    <div class="card" id="reportCard">
      <div class="muted">Generating your report...</div>
    </div>
    <div class="card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("settings")} Data source</div>
      <p class="muted">This report is built from <code>data/student_profile.json</code> - edit that file to update subjects, completed topics, quiz history and daily study time. Charts and streaks will appear once real activity tracking exists.</p>
    </div>
  </div>`;

  API.report().then(r => {
    document.getElementById("reportCard").innerHTML = `
      <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:14px">
        <span class="coming-soon-tag">suggested difficulty: ${esc(r.suggested_difficulty)}</span>
        <button class="chip" id="copyReport">copy report</button>
      </div>
      <div class="output-panel" style="max-height:none">${esc(r.report)}</div>`;
    document.getElementById("copyReport").onclick = () => copyText(r.report, "Report");
  }).catch(e => {
    document.getElementById("reportCard").innerHTML = `<div class="muted">Report unavailable: ${esc(e.message)}</div>`;
  });
}

/* ---------- Settings ---------- */

function vSettings(root) {
  const theme = document.documentElement.dataset.theme;
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Settings</h2><p>Customize your EDUCORE AI experience.</p></div>
    <div class="settings-cols">
      <div class="settings-col">
      <div class="card appearance-card">
        <div class="app-row">
          <div class="app-info">
            ${icon("sparkles")}
            <div>
              <div class="section-title" style="margin:0">Appearance</div>
              <div class="muted" style="font-size:12px">Pick your theme - applies instantly.</div>
            </div>
          </div>
          <div class="seg" id="themeSeg">
            <button type="button" class="seg-btn ${theme === "dark" ? "active" : ""}" data-t="dark"><span class="sw sw-dark"></span>Dark</button>
            <button type="button" class="seg-btn ${theme === "light" ? "active" : ""}" data-t="light"><span class="sw sw-light"></span>Light</button>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("bell")} Notifications</div>
        <p class="muted" style="margin-bottom:14px">Choose what EDUCORE AI can notify you about.</p>
        <div id="notifPrefs">
          <div class="notif-row">
            <div class="notif-info">
              <div class="notif-title">Daily Study Reminders</div>
              <div class="muted notif-desc">Get daily nudges to stay on track with your study goals.</div>
              <input type="time" id="npTime" class="notif-time" value="19:00">
            </div>
            <label class="switch"><input type="checkbox" id="npReminders"><span class="slider"></span></label>
          </div>
          <div class="notif-row">
            <div class="notif-info">
              <div class="notif-title">Progress &amp; Streak Alerts</div>
              <div class="muted notif-desc">Receive updates on completed topics, milestones, and weekly study summaries.</div>
            </div>
            <label class="switch"><input type="checkbox" id="npProgress"><span class="slider"></span></label>
          </div>
          <div class="notif-row">
            <div class="notif-info">
              <div class="notif-title">Quiz &amp; AI Recommendations</div>
              <div class="muted notif-desc">Get notified when new practice quizzes or personalized study tips are ready.</div>
            </div>
            <label class="switch"><input type="checkbox" id="npQuiz"><span class="slider"></span></label>
          </div>
          <div class="notif-perm">
            <span class="badge" id="npPermBadge">checking…</span>
            <button class="btn btn-ghost btn-sm" id="npPermBtn">Enable Browser Alerts</button>
          </div>
          <div class="muted" id="npSaved" style="font-size:12px;margin-top:10px;min-height:14px"></div>
        </div>
      </div>
      <div class="card cp-card">
        <div class="section-title" style="margin-top:0">${icon("settings")} Change password</div>
        <div class="field"><label>Current password</label><input type="password" id="cpCur" placeholder="Enter current password" autocomplete="current-password"></div>
        <div class="field"><label>New password</label><input type="password" id="cpNew" placeholder="Enter new password" autocomplete="new-password"></div>
        <button class="btn btn-ghost" id="cpGo">Update password</button>
        <div class="auth-ok" id="cpOk"></div>
        <div class="auth-error" id="cpErr"></div>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("settings")} Privacy</div>
        <p class="muted">Your data stays local: the backend only reads <code>student_profile.json</code> that you control. AI features send your prompts to OpenAI only when a key is configured - no keys are ever exposed to the browser.</p>
      </div>
      </div>
      <div class="settings-col">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("users")} Student profile</div>
        <div id="profileCard"><div class="muted">Loading profile...</div></div>
      </div>
      </div>
    </div>
  </div>`;

  document.getElementById("themeSeg").addEventListener("click", e => {
    const btn = e.target.closest(".seg-btn");
    if (btn) setTheme(btn.dataset.t);
  });

  loadProfileForm();
  initNotifPrefs();

  document.getElementById("cpGo").addEventListener("click", async () => {
    const ok = document.getElementById("cpOk");
    const err = document.getElementById("cpErr");
    ok.textContent = ""; err.textContent = "";
    try {
      await API.authChangePassword({
        current_password: document.getElementById("cpCur").value,
        new_password: document.getElementById("cpNew").value
      });
      ok.textContent = "Password updated.";
      document.getElementById("cpCur").value = "";
      document.getElementById("cpNew").value = "";
    } catch (e) { err.textContent = e.message; }
  });
}

/* ---------- Settings: notification preferences ---------- */

function initNotifPrefs() {
  const LS_KEY = "educore_notif_prefs";
  const TOGGLES = { reminders: "npReminders", progress: "npProgress", quiz: "npQuiz" };
  const defaults = { reminders: false, reminders_time: "19:00", progress: false, quiz: false };

  let savedTimer = null;
  const timeEl = document.getElementById("npTime");
  const savedEl = document.getElementById("npSaved");

  const collect = () => ({
    reminders: document.getElementById(TOGGLES.reminders).checked,
    reminders_time: timeEl.value || "19:00",
    progress: document.getElementById(TOGGLES.progress).checked,
    quiz: document.getElementById(TOGGLES.quiz).checked
  });

  const flashSaved = () => {
    savedEl.textContent = "Preferences saved";
    savedEl.style.color = "#22c55e";
    clearTimeout(savedTimer);
    savedTimer = setTimeout(() => { savedEl.textContent = ""; }, 2000);
  };

  const persist = (p) => {
    try { localStorage.setItem(LS_KEY, JSON.stringify(p)); } catch {}
    API.profileUpdate({ notification_prefs: p }).catch(() => {});
    flashSaved();
  };

  const apply = (p) => {
    p = { ...defaults, ...(p || {}) };
    for (const k of Object.keys(TOGGLES)) {
      document.getElementById(TOGGLES[k]).checked = !!p[k];
    }
    if (p.reminders_time) timeEl.value = p.reminders_time;
    return p;
  };

  // instant paint from localStorage, then server profile wins if present
  let prefs = defaults;
  try { prefs = { ...defaults, ...JSON.parse(localStorage.getItem(LS_KEY) || "{}") }; } catch {}
  apply(prefs);
  getProfile().then(r => {
    const sp = r && r.notification_prefs;
    if (sp && typeof sp === "object") {
      prefs = apply(sp);
      try { localStorage.setItem(LS_KEY, JSON.stringify(prefs)); } catch {}
    }
  }).catch(() => {});

  const updatePermBadge = () => {
    const badge = document.getElementById("npPermBadge");
    if (!("Notification" in window)) {
      badge.textContent = "Not supported"; badge.style.color = "var(--text-2)"; return;
    }
    const p = Notification.permission;
    badge.textContent = p === "granted" ? "Enabled" : p === "denied" ? "Blocked in browser" : "Not enabled";
    badge.style.color = p === "granted" ? "#22c55e" : p === "denied" ? "#f59e0b" : "var(--text-2)";
  };
  updatePermBadge();

  const maybeRequestPerm = async () => {
    if (!("Notification" in window)) return;
    if (Notification.permission === "default") {
      try { await Notification.requestPermission(); } catch {}
      updatePermBadge();
    }
  };

  for (const k of Object.keys(TOGGLES)) {
    document.getElementById(TOGGLES[k]).addEventListener("change", e => {
      persist(collect());
      if (e.target.checked) maybeRequestPerm();
    });
  }
  timeEl.addEventListener("change", () => persist(collect()));

  document.getElementById("npPermBtn").addEventListener("click", async () => {
    if (!("Notification" in window)) { toast("Browser notifications are not supported here."); return; }
    try { await Notification.requestPermission(); } catch {}
    updatePermBadge();
    if (Notification.permission === "granted") toast("Browser alerts enabled.");
    else if (Notification.permission === "denied") toast("Notifications are blocked in your browser settings.");
  });
}

/* ---------- notif dropdown ---------- */

function showNotifPanel(e) {
  e.stopPropagation();
  document.querySelectorAll(".dropdown").forEach(d => d.remove());
  const dd = document.createElement("div");
  dd.className = "dropdown";
  dd.innerHTML = `
    <div class="dropdown-item" style="cursor:default;color:var(--text);font-weight:600">Notifications</div>
    <div class="dropdown-sep"></div>
    <div class="dropdown-item" style="cursor:default">Loading...</div>
    <div class="dropdown-sep"></div>
    <a class="dropdown-item" href="#/notifications">View all</a>`;
  document.body.appendChild(dd);
  const btn = document.getElementById("notifBtn").getBoundingClientRect();
  dd.style.top = (btn.bottom + 8) + "px";
  dd.style.right = (window.innerWidth - btn.right) + "px";
  API.notifications().then(list => {
    const items = dd.querySelectorAll(".dropdown-item");
    if (items.length > 2) items[2].outerHTML = list.length
      ? list.slice(0, 3).map(n =>
          `<div class="dropdown-item" style="cursor:default"><strong>${esc(n.title)}</strong><br><span class="muted" style="font-size:12px">${esc(n.body || "")}</span></div>`).join("")
      : '<div class="dropdown-item" style="cursor:default">No new notifications</div>';
  }).catch(() => {});
}

/* ============================================================
   AUTH PAGES
   ============================================================ */

function authShell(inner) {
  return `
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <img src="/static/brand/educore-logo.svg" alt="EDUCORE AI" style="width:250px;max-width:85%;height:auto;display:block;margin:0 auto 14px">
        <p class="muted" style="text-align:center;margin-top:4px">Learn Smarter. Understand Deeper.</p>
      </div>
      ${inner}
    </div>
  </div>`;
}

function pwToggle(inputId) {
  return `<button type="button" class="pw-toggle" data-for="${inputId}">show</button>`;
}

function bindPwToggles(root) {
  root.querySelectorAll(".pw-toggle").forEach(b =>
    b.addEventListener("click", () => {
      const inp = document.getElementById(b.dataset.for);
      const show = inp.type === "password";
      inp.type = show ? "text" : "password";
      b.textContent = show ? "hide" : "show";
    }));
}

/* ---------- Cloudflare Turnstile CAPTCHA ---------- */
let _turnstileLoading = null;
function loadTurnstile() {
  if (window.turnstile) return Promise.resolve();
  if (_turnstileLoading) return _turnstileLoading;
  _turnstileLoading = new Promise((res, rej) => {
    const s = document.createElement("script");
    s.src = "https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit";
    s.async = true; s.defer = true;
    s.onload = res; s.onerror = () => rej(new Error("captcha script failed"));
    document.head.appendChild(s);
  });
  return _turnstileLoading;
}

function vLogin(root) {
  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Welcome back</h2>
    <div class="field"><label>Email</label><input type="email" id="liEmail" placeholder="you@example.com" autocomplete="email"></div>
    <div class="field"><label>Password</label>
      <div class="pw-wrap"><input type="password" id="liPass" placeholder="Your password" autocomplete="current-password">${pwToggle("liPass")}</div>
    </div>
    <div id="liCaptcha" style="margin:4px 0 10px;min-height:0"></div>
    <div class="auth-error" id="liErr"></div>
    <button class="btn btn-primary" id="liGo" style="width:100%;justify-content:center">Sign in</button>
    <div class="auth-links">
      <a href="#/forgot">Forgot password?</a>
      <a href="#/register">Create account</a>
    </div>`);

  let captchaToken = "";
  let widgetId = null;
  // render the widget only when the backend reports CAPTCHA is configured
  API.captchaConfig().then(cfg => {
    if (!cfg.enabled || !cfg.site_key) return;
    loadTurnstile().then(() => {
      const box = document.getElementById("liCaptcha");
      if (!box || !window.turnstile) return;
      widgetId = turnstile.render(box, {
        sitekey: cfg.site_key,
        callback: t => { captchaToken = t; },
        "expired-callback": () => { captchaToken = ""; },
        "error-callback": () => { captchaToken = ""; }
      });
    }).catch(() => {});
  }).catch(() => {});

  const resetCaptcha = () => {
    captchaToken = "";
    try { if (widgetId !== null && window.turnstile) turnstile.reset(widgetId); } catch (e) {}
  };

  const go = async () => {
    const err = document.getElementById("liErr");
    err.textContent = "";
    const email = document.getElementById("liEmail").value.trim();
    const password = document.getElementById("liPass").value;
    if (!email || !password) { err.textContent = "Enter your email and password."; return; }
    document.getElementById("liGo").disabled = true;
    try {
      const r = await API.authLogin({ email, password, captcha_token: captchaToken });
      state.user = r.user;
      renderProfile();
      applyServerTheme();
      location.hash = "#/overview";
    } catch (e) {
      err.textContent = e.message;
      resetCaptcha(); // fresh challenge after every failed attempt
      document.getElementById("liGo").disabled = false;
    }
  };
  document.getElementById("liGo").addEventListener("click", go);
  document.getElementById("liPass").addEventListener("keydown", e => { if (e.key === "Enter") go(); });
  bindPwToggles(root);
}

function vRegister(root) {
  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Create your account</h2>
    <div class="field"><label>Full name</label><input type="text" id="rgName" placeholder="Your name" autocomplete="name"></div>
    <div class="field"><label>Email</label><input type="email" id="rgEmail" placeholder="you@example.com" autocomplete="email"></div>
    <div class="field"><label>Password</label>
      <div class="pw-wrap"><input type="password" id="rgPass" placeholder="Min 8 characters" autocomplete="new-password">${pwToggle("rgPass")}</div>
    </div>
    <div class="field"><label>Confirm password</label>
      <div class="pw-wrap"><input type="password" id="rgPass2" placeholder="Repeat password" autocomplete="new-password">${pwToggle("rgPass2")}</div>
    </div>
    <div class="auth-error" id="rgErr"></div>
    <button class="btn btn-primary" id="rgGo" style="width:100%;justify-content:center">Sign up</button>
    <div class="auth-links">
      <span class="muted">Already have an account?</span>
      <a href="#/login">Sign in</a>
    </div>`);

  const go = async () => {
    const err = document.getElementById("rgErr");
    err.textContent = "";
    const name = document.getElementById("rgName").value.trim();
    const email = document.getElementById("rgEmail").value.trim();
    const password = document.getElementById("rgPass").value;
    const confirm = document.getElementById("rgPass2").value;
    if (!name || !email || !password) { err.textContent = "Fill in all fields."; return; }
    if (password !== confirm) { err.textContent = "Passwords do not match."; return; }
    document.getElementById("rgGo").disabled = true;
    try {
      const r = await API.authRegister({ name, email, password, confirm });
      state.user = r.user;
      renderProfile();
      location.hash = "#/overview";
    } catch (e) {
      err.textContent = e.message;
      document.getElementById("rgGo").disabled = false;
    }
  };
  document.getElementById("rgGo").addEventListener("click", go);
  bindPwToggles(root);
}

function vForgot(root) {
  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Reset password</h2>
    <p class="muted" style="margin-bottom:14px">Enter your account email and we'll send you a 6-digit verification code. The code expires in 5 minutes.</p>
    <div class="field"><label>Email</label><input type="email" id="fgEmail" placeholder="you@example.com" autocomplete="email"></div>
    <div class="auth-error" id="fgErr"></div>
    <div class="auth-ok" id="fgOk"></div>
    <button class="btn btn-primary" id="fgGo" style="width:100%;justify-content:center">Send verification code</button>
    <div class="auth-links"><a href="#/login">Back to sign in</a></div>`);

  document.getElementById("fgGo").addEventListener("click", async () => {
    const err = document.getElementById("fgErr");
    const ok = document.getElementById("fgOk");
    const btn = document.getElementById("fgGo");
    err.textContent = ""; ok.textContent = "";
    const email = document.getElementById("fgEmail").value.trim();
    if (!email) { err.textContent = "Enter your email."; return; }
    btn.disabled = true;
    btn.textContent = "Sending...";
    try {
      const r = await API.authOtpRequest({ email });
      // generic response - never reveals whether the account exists
      try { localStorage.setItem("educore_otp_email", email); } catch (e) {}
      ok.textContent = r.message || "If an account exists for this email, a verification code will be sent.";
      setTimeout(() => location.hash = "#/otp", 900);
    } catch (e) {
      err.textContent = e.message;
      btn.disabled = false;
      btn.textContent = "Send verification code";
    }
  });
}

function vOtp(root) {
  let email = "";
  try { email = localStorage.getItem("educore_otp_email") || ""; } catch (e) {}
  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Enter verification code</h2>
    <p class="muted" style="margin-bottom:14px">We sent a 6-digit code to <strong id="otpEmail"></strong>. It expires in 5 minutes.</p>
    <div class="field"><label>Verification code</label>
      <input type="text" id="otpCode" inputmode="numeric" pattern="[0-9]*" maxlength="6"
        placeholder="••••••" autocomplete="one-time-code" style="text-align:center;font-size:24px;letter-spacing:8px;font-weight:700">
    </div>
    <div class="auth-error" id="otpErr"></div>
    <div class="auth-ok" id="otpOk"></div>
    <button class="btn btn-primary" id="otpGo" style="width:100%;justify-content:center">Verify code</button>
    <div class="auth-links">
      <a href="#/forgot">Change email</a>
      <a href="#" id="otpResend">Resend code</a>
      <span class="muted" id="otpTimer"></span>
    </div>`);
  document.getElementById("otpEmail").textContent = email || "your email";

  let cooldown = 0;
  const timerEl = document.getElementById("otpTimer");
  const resendEl = document.getElementById("otpResend");
  const tick = setInterval(() => {
    if (cooldown > 0) {
      cooldown--;
      timerEl.textContent = `Resend in ${cooldown}s`;
      resendEl.style.pointerEvents = "none";
      resendEl.style.opacity = ".5";
    } else {
      timerEl.textContent = "";
      resendEl.style.pointerEvents = "";
      resendEl.style.opacity = "";
      clearInterval(tick);
    }
  }, 1000);

  const requestCode = async () => {
    if (!email) { location.hash = "#/forgot"; return; }
    try {
      await API.authOtpRequest({ email });
      cooldown = 60;
      timerEl.textContent = "Resend in 60s";
      resendEl.style.pointerEvents = "none";
      resendEl.style.opacity = ".5";
      document.getElementById("otpOk").textContent = "If an account exists for this email, a verification code will be sent.";
    } catch (e) {
      document.getElementById("otpErr").textContent = e.message;
    }
  };
  resendEl.addEventListener("click", e => { e.preventDefault(); requestCode(); });

  const go = async () => {
    const err = document.getElementById("otpErr");
    const ok = document.getElementById("otpOk");
    err.textContent = ""; ok.textContent = "";
    const code = document.getElementById("otpCode").value.trim();
    if (!/^\d{6}$/.test(code)) { err.textContent = "Enter the 6-digit code."; return; }
    if (!email) { err.textContent = "Session expired - start again."; location.hash = "#/forgot"; return; }
    document.getElementById("otpGo").disabled = true;
    try {
      const r = await API.authOtpVerify({ email, otp: code });
      try {
        localStorage.setItem("educore_reset_ticket", r.reset_ticket || "");
        localStorage.setItem("educore_reset_email", email);
      } catch (e) {}
      ok.textContent = "Code verified - set your new password.";
      setTimeout(() => location.hash = "#/reset", 700);
    } catch (e2) {
      err.textContent = e2.message;
      document.getElementById("otpGo").disabled = false;
    }
  };
  document.getElementById("otpGo").addEventListener("click", go);
  document.getElementById("otpCode").addEventListener("keydown", e => { if (e.key === "Enter") go(); });
  document.getElementById("otpCode").addEventListener("input", e => {
    e.target.value = e.target.value.replace(/\D/g, "").slice(0, 6);
  });
}

function vReset(root) {
  // OTP flow: #/reset?ticket=...&email=... (ticket stored client-side too)
  const hashQ = location.hash.includes("?") ? location.hash.split("?")[1] : "";
  const qp = new URLSearchParams(hashQ);
  const prefillToken = qp.get("token") || "";   // legacy link-based reset
  const prefillEmail = qp.get("email") || "";
  let ticket = qp.get("ticket") || "";
  let email = prefillEmail;
  try {
    if (!ticket) ticket = localStorage.getItem("educore_reset_ticket") || "";
    if (!email) email = localStorage.getItem("educore_reset_email") || "";
  } catch (e) {}
  const isOtp = !!ticket;

  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Set new password</h2>
    ${isOtp ? "" : `<div class="field"><label>Email</label><input type="email" id="rsEmail" placeholder="you@example.com" value="${esc(email)}"></div>
    <div class="field"><label>Reset token</label><input type="text" id="rsToken" placeholder="Paste reset token" value="${esc(prefillToken)}"></div>`}
    <div class="field"><label>New password</label>
      <div class="pw-wrap"><input type="password" id="rsPass" placeholder="Min 8 characters" autocomplete="new-password">${pwToggle("rsPass")}</div>
    </div>
    <div class="field"><label>Confirm new password</label>
      <div class="pw-wrap"><input type="password" id="rsPass2" placeholder="Repeat password" autocomplete="new-password">${pwToggle("rsPass2")}</div>
    </div>
    <div class="auth-error" id="rsErr"></div>
    <div class="auth-ok" id="rsOk"></div>
    <button class="btn btn-primary" id="rsGo" style="width:100%;justify-content:center">Update password</button>
    <div class="auth-links"><a href="#/login">Back to sign in</a></div>`);

  document.getElementById("rsGo").addEventListener("click", async () => {
    const err = document.getElementById("rsErr");
    const ok = document.getElementById("rsOk");
    err.textContent = ""; ok.textContent = "";
    const password = document.getElementById("rsPass").value;
    const confirm = document.getElementById("rsPass2").value;
    if (password !== confirm) { err.textContent = "Passwords do not match."; return; }
    try {
      if (isOtp) {
        await API.authOtpReset({ email, ticket, password, confirm });
        try { localStorage.removeItem("educore_reset_ticket"); localStorage.removeItem("educore_reset_email"); localStorage.removeItem("educore_otp_email"); } catch (e) {}
      } else {
        await API.authReset({
          email: document.getElementById("rsEmail").value.trim(),
          token: document.getElementById("rsToken").value.trim(),
          password
        });
      }
      ok.textContent = "Password updated. You can sign in now.";
      setTimeout(() => location.hash = "#/login", 1500);
    } catch (e) { err.textContent = e.message; }
  });
  bindPwToggles(root);
}

/* ---------------- boot ---------------- */

document.addEventListener("DOMContentLoaded", () => {
  const saved = localStorage.getItem("educore-theme");
  if (saved) {
    document.documentElement.dataset.theme = saved;
  }
  init();
});
