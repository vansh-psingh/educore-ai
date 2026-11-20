// API client - talks to the Java backend.
// OpenAI/Gemini keys stay server-side; browser only sees our /api endpoints.
//
// Deployment: when hosted on Firebase (*.web.app / *.firebaseapp.com) the API
// lives on the Render backend - API_BASE points there. Locally it stays ""
// (same origin). Override any time via localStorage.educore_api_base.
const API_BASE = (() => {
  try {
    const saved = localStorage.getItem("educore_api_base");
    if (saved !== null) return saved;
  } catch (e) { /* ignore */ }
  return /(^|\.)(web\.app|firebaseapp\.com)$/.test(location.hostname)
    ? "https://educore-ai-backend.onrender.com"   // TODO: update after Render deploy
    : "";
})();

const API = {
  async post(path, body, _retried) {
    const res = await fetch(API_BASE + path, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "include",
      body: JSON.stringify(body || {})
    });
    // auto-refresh once on expired access token, then retry
    if (res.status === 401 && !_retried && !path.startsWith("/api/auth/")) {
      try {
        await this.authRefresh();
        return this.post(path, body, true);
      } catch (e) { /* fall through to error */ }
    }
    const data = await res.json().catch(() => null);
    if (!res.ok) {
      const err = new Error(data?.error || data?.detail || `HTTP ${res.status}`);
      err.status = res.status;
      throw err;
    }
    return data;
  },

  // ---------- auth ----------
  authRegister(p) { return this.post("/api/auth/register", p); },
  authLogin(p) { return this.post("/api/auth/login", p); },
  authRefresh() { return this.post("/api/auth/refresh", {}); },
  authLogout() { return this.post("/api/auth/logout", {}); },
  authForgot(p) { return this.post("/api/auth/forgot", p); },
  authReset(p) { return this.post("/api/auth/reset", p); },
  authChangePassword(p) { return this.post("/api/auth/change-password", p); },
  async authMe() {
    const res = await fetch(API_BASE + "/api/auth/me", { credentials: "include" });
    if (!res.ok) {
      const e = new Error("unauthenticated");
      e.status = res.status;
      throw e;
    }
    return res.json();
  },

  // local JSON-driven engine (always works)
  chatLocal(message) {
    return this.post("/api/chat", { message });
  },

  // OpenAI/Gemini-backed endpoints (require API key in backend .env)
  aiChat(payload) { return this.post("/api/ai/chat", payload); },

  /** Streaming chat - onDelta(text) per fragment; resolves {answer} on done. */
  async aiChatStream(payload, onDelta, signal) {
    const res = await fetch(API_BASE + "/api/ai/chat_stream", {
      credentials: "include",
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(payload),
      signal
    });
    if (!res.ok || !res.body) {
      const data = await res.json().catch(() => ({}));
      throw new Error(data.error || `HTTP ${res.status}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "", answer = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      let idx;
      while ((idx = buffer.indexOf("\n\n")) >= 0) {
        const chunk = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        for (const line of chunk.split("\n")) {
          if (!line.startsWith("data:")) continue;
          try {
            const evt = JSON.parse(line.slice(5).trim());
            if (evt.delta) { answer += evt.delta; onDelta(evt.delta); }
            if (evt.done) return { answer: evt.answer || answer };
            if (evt.error) throw new Error(evt.error);
          } catch (e) { if (e instanceof SyntaxError) continue; throw e; }
        }
      }
    }
    return { answer };
  },

  aiVision(payload) { return this.post("/api/ai/vision", payload); },
  feedback(payload) { return this.post("/api/feedback", payload); },
  async feedbackStats() {
    const res = await fetch(API_BASE + "/api/feedback/stats", { credentials: "include" });
    return res.json();
  },
  aiNotes(payload) { return this.post("/api/ai/notes", payload); },
  aiSummary(payload) { return this.post("/api/ai/summary", payload); },
  aiQuiz(payload) { return this.post("/api/ai/quiz", payload); },

  // notes library (persistent, per-user)
  noteSave(payload) { return this.post("/api/notes", payload); },
  async notes(q, sort) {
    const params = new URLSearchParams();
    if (q) params.set("q", q);
    if (sort) params.set("sort", sort);
    const res = await fetch(API_BASE + "/api/notes" + (params.toString() ? "?" + params : ""), { credentials: "include" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  },
  async note(id) {
    const res = await fetch(API_BASE + "/api/notes/" + id, { credentials: "include" });
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  },
  noteUpdate(id, patch) { return this.post(`/api/notes/${id}/update`, patch); },
  noteDelete(id) { return this.post(`/api/notes/${id}/delete`, {}); },

  // edu.core engines (local, deterministic)
  quiz(payload) { return this.post("/api/quiz", payload); },
  report() { return this.post("/api/report", {}); },

  // exams
  examGenerate(payload) { return this.post("/api/exams/generate", payload); },
  examSubmit(id, answers) { return this.post(`/api/exams/${id}/submit`, { answers }); },
  async examHistory() {
    const res = await fetch(API_BASE + "/api/exams/history", { credentials: "include" });
    return res.json();
  },

  // documents
  async docs() {
    const res = await fetch(API_BASE + "/api/documents", { credentials: "include" });
    return res.json();
  },
  async doc(id) {
    const res = await fetch(API_BASE + "/api/documents/" + id, { credentials: "include" });
    return res.json();
  },
  docUpload(name, dataBase64) { return this.post("/api/documents/upload", { name, data: dataBase64 }); },
  docDelete(id) { return this.post(`/api/documents/${id}/delete`, {}); },
  docAsk(id, question) { return this.post(`/api/documents/${id}/ask`, { question }); },
  docSummarize(id) { return this.post(`/api/documents/${id}/summarize`, {}); },

  // planner
  async plannerTasks() {
    const res = await fetch(API_BASE + "/api/planner/tasks", { credentials: "include" });
    return res.json();
  },
  plannerCreate(payload) { return this.post("/api/planner/tasks", payload); },
  plannerUpdate(id, patch) { return this.post(`/api/planner/tasks/${id}/update`, patch); },
  plannerDelete(id) { return this.post(`/api/planner/tasks/${id}/delete`, {}); },
  plannerAI(payload) { return this.post("/api/planner/ai", payload); },

  // classrooms
  async classrooms() {
    const res = await fetch(API_BASE + "/api/classrooms", { credentials: "include" });
    return res.json();
  },
  classroomCreate(payload) { return this.post("/api/classrooms/create", payload); },
  classroomJoin(payload) { return this.post("/api/classrooms/join", payload); },
  classroomGet(id) { return this.post(`/api/classrooms/${id}/get`, {}); },
  classroomAnnounce(id, payload) { return this.post(`/api/classrooms/${id}/announce`, payload); },
  classroomMaterial(id, payload) { return this.post(`/api/classrooms/${id}/material`, payload); }
};
