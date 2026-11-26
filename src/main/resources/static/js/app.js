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
  planner: "Study Planner", classrooms: "Classrooms",
  notifications: "Notifications", settings: "Settings",
  login: "Sign in", register: "Create account",
  forgot: "Reset password", reset: "Set new password",
};

const state = {
  view: "overview",
  user: null,
  chats: {},          // view -> messages[]
  remoteChats: {},    // chatKey -> backend session_id (persistent history)
  activeChat: "main",
  quiz: null,         // {student, key, answers, submitted, score}
};

const AUTH_PAGES = ["login", "register", "forgot", "reset"];

/* ---------------- boot ---------------- */

function init() {
  renderNav();
  bindChrome();
  window.addEventListener("hashchange", route);
  // auth gate: resolve session before routing
  API.authMe()
    .then(u => { state.user = u; })
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
  document.querySelectorAll(".avatar").forEach(a => { a.textContent = initial; });
  updateAdminNav();
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
    a.innerHTML = icon("settings") + "<span>Admin</span>";
    const sep = nav.querySelector(".nav-divider");
    nav.insertBefore(a, sep || null);
  }
}

function renderNav() {
  const nav = document.getElementById("nav");
  nav.innerHTML = NAV.map(n => n.view === "_sep"
    ? '<div class="nav-divider"></div>'
    : `<a class="nav-item" data-view="${n.view}" href="#/${n.view}">${icon(n.icon)}<span>${n.label}</span></a>`
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

  // global search
  document.getElementById("globalSearch").addEventListener("keydown", e => {
    if (e.key === "Enter" && e.target.value.trim()) {
      toast("Global search is coming soon. Try the AI Tutor for answers!");
      e.target.value = "";
    }
  });

  // click outside closes dropdowns
  document.addEventListener("click", e => {
    if (!e.target.closest(".dropdown")) {
      document.querySelectorAll(".dropdown").forEach(d => d.remove());
    }
  });
}

function openMobileNav() {
  document.getElementById("sidebar").classList.add("open");
  document.getElementById("sidebarOverlay").classList.add("show");
}
function closeMobileNav() {
  document.getElementById("sidebar").classList.remove("open");
  document.getElementById("sidebarOverlay").classList.remove("show");
}

function toggleTheme() {
  const html = document.documentElement;
  const next = html.dataset.theme === "dark" ? "light" : "dark";
  html.dataset.theme = next;
  document.getElementById("themeBtn").innerHTML = icon(next === "dark" ? "moon" : "sun");
  localStorage.setItem("educore-theme", next);
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
  return String(s ?? "").replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
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
  VIEWS[state.view](view);
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
      <h2>Welcome back, Student!</h2>
      <p>What would you like to learn today?</p>
    </div>

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
        <div class="empty-state" style="padding:30px 10px">
          ${icon("inbox")}
          <h3>No recent activity</h3>
          <p>Your AI conversations, notes and quizzes will appear here once you start learning.</p>
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
    <div class="card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("sparkles")} Daily focus</div>
      <p class="muted">Consistency beats intensity - even 30 focused minutes daily builds real progress. Use the Learning Progress page to see which topics need revision based on your quiz scores.</p>
    </div>
  </div>`;
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
        source: document.getElementById("qSource").value,
        include_answers: true   // fetched once; key hidden client-side until submit
      });
      state.quiz = { student: r.student_view, key: r.answer_key, answers: {}, submitted: false, mode: r.generation_mode || "notes" };
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

  document.getElementById("qSubmit").addEventListener("click", () => {
    q.submitted = true;
    // score
    const keyMap = {};
    (q.key?.questions || []).forEach(k => keyMap[k.number] = k);
    let got = 0, total = 0;
    sv.questions.forEach(qq => {
      const k = keyMap[qq.number];
      if (!k) return;
      total += k.marks;
      const ans = (q.answers[qq.number] || "").trim().toLowerCase();
      const correct = (k.correct_answer || "").trim().toLowerCase();
      // MCQ/TF exact match; short answer: keyword overlap >= 50%
      let ok = ans === correct;
      if (!ok && qq.type === "short_answer" && ans) {
        const kw = correct.split(/[^a-z0-9]+/).filter(w => w.length > 3);
        const hits = kw.filter(w => ans.includes(w)).length;
        ok = kw.length > 0 && hits / kw.length >= 0.5;
      }
      if (ok) got += k.marks;
    });
    q.score = total ? Math.round(100 * got / total) : 0;
    renderQuizResult(sv, keyMap, got, total);
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
    <div class="grid grid-2">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("sparkles")} Appearance</div>
        <div class="field"><label>Theme</label>
          <select id="setTheme">
            <option value="dark" ${theme === "dark" ? "selected" : ""}>Dark (default)</option>
            <option value="light" ${theme === "light" ? "selected" : ""}>Light</option>
          </select>
        </div>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("users")} Profile</div>
        <p class="muted">Your student record is stored in <code>data/student_profile.json</code> on the backend (name, academic level, subjects, quiz history, study minutes). Edit that file to update it - account management is coming soon.</p>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("bell")} Notifications</div>
        <p class="muted">Study reminders and progress alerts - coming soon. The notifications panel will light up when the backend supports it.</p>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("settings")} Change password</div>
        <div class="field"><label>Current password</label><input type="password" id="cpCur"></div>
        <div class="field"><label>New password</label><input type="password" id="cpNew"></div>
        <button class="btn btn-ghost" id="cpGo">Update password</button>
        <div class="auth-ok" id="cpOk"></div>
        <div class="auth-error" id="cpErr"></div>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("settings")} Privacy</div>
        <p class="muted">Your data stays local: the backend only reads <code>student_profile.json</code> that you control. AI features send your prompts to OpenAI only when a key is configured - no keys are ever exposed to the browser.</p>
      </div>
    </div>
  </div>`;

  document.getElementById("setTheme").addEventListener("change", e => {
    document.documentElement.dataset.theme = e.target.value;
    document.getElementById("themeBtn").innerHTML = icon(e.target.value === "dark" ? "moon" : "sun");
    localStorage.setItem("educore-theme", e.target.value);
  });

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

function vLogin(root) {
  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Welcome back</h2>
    <div class="field"><label>Email</label><input type="email" id="liEmail" placeholder="you@example.com" autocomplete="email"></div>
    <div class="field"><label>Password</label>
      <div class="pw-wrap"><input type="password" id="liPass" placeholder="Your password" autocomplete="current-password">${pwToggle("liPass")}</div>
    </div>
    <div class="auth-error" id="liErr"></div>
    <button class="btn btn-primary" id="liGo" style="width:100%;justify-content:center">Sign in</button>
    <div class="auth-links">
      <a href="#/forgot">Forgot password?</a>
      <a href="#/register">Create account</a>
    </div>`);

  const go = async () => {
    const err = document.getElementById("liErr");
    err.textContent = "";
    const email = document.getElementById("liEmail").value.trim();
    const password = document.getElementById("liPass").value;
    if (!email || !password) { err.textContent = "Enter your email and password."; return; }
    document.getElementById("liGo").disabled = true;
    try {
      const r = await API.authLogin({ email, password });
      state.user = r.user;
      renderProfile();
      location.hash = "#/overview";
    } catch (e) {
      err.textContent = e.message;
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
    <p class="muted" style="margin-bottom:14px">Enter your account email and we'll send you a secure reset link. The link expires in 30 minutes.</p>
    <div class="field"><label>Email</label><input type="email" id="fgEmail" placeholder="you@example.com"></div>
    <div class="auth-error" id="fgErr"></div>
    <div class="auth-ok" id="fgOk"></div>
    <button class="btn btn-primary" id="fgGo" style="width:100%;justify-content:center">Send reset link</button>
    <div class="auth-links"><a href="#/login">Back to sign in</a></div>`);

  document.getElementById("fgGo").addEventListener("click", async () => {
    const err = document.getElementById("fgErr");
    const ok = document.getElementById("fgOk");
    err.textContent = ""; ok.textContent = "";
    const email = document.getElementById("fgEmail").value.trim();
    if (!email) { err.textContent = "Enter your email."; return; }
    try {
      const r = await API.authForgot({ email });
      ok.textContent = r.message || "If an account exists for this email, a reset link has been sent.";
    } catch (e) { err.textContent = e.message; }
  });
}

function vReset(root) {
  // support emailed links: #/reset?token=...&email=...
  const hashQ = location.hash.includes("?") ? location.hash.split("?")[1] : "";
  const qp = new URLSearchParams(hashQ);
  const prefillToken = qp.get("token") || "";
  const prefillEmail = qp.get("email") || "";
  root.innerHTML = authShell(`
    <h2 style="text-align:center;margin-bottom:18px">Set new password</h2>
    <div class="field"><label>Email</label><input type="email" id="rsEmail" placeholder="you@example.com" value="${esc(prefillEmail)}"></div>
    <div class="field"><label>Reset token</label><input type="text" id="rsToken" placeholder="Paste reset token" value="${esc(prefillToken)}"></div>
    <div class="field"><label>New password</label>
      <div class="pw-wrap"><input type="password" id="rsPass" placeholder="Min 8 characters">${pwToggle("rsPass")}</div>
    </div>
    <div class="auth-error" id="rsErr"></div>
    <div class="auth-ok" id="rsOk"></div>
    <button class="btn btn-primary" id="rsGo" style="width:100%;justify-content:center">Update password</button>
    <div class="auth-links"><a href="#/login">Back to sign in</a></div>`);

  document.getElementById("rsGo").addEventListener("click", async () => {
    const err = document.getElementById("rsErr");
    const ok = document.getElementById("rsOk");
    err.textContent = ""; ok.textContent = "";
    try {
      await API.authReset({
        email: document.getElementById("rsEmail").value.trim(),
        token: document.getElementById("rsToken").value.trim(),
        password: document.getElementById("rsPass").value
      });
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
