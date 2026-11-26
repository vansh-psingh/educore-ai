/* ============================================================
   EDUCORE AI - Advanced modules: Exams, Documents, Planner, Classrooms
   ============================================================ */

/* ============================ EXAMS ============================ */

function vExams(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Exams & Past Papers</h2><p>Generate timed practice exams, submit, and review results. Practice questions only - not official exam papers.</p></div>
    <div class="grid grid-2">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("quiz")} Exam generator</div>
        <div class="form-grid">
          <div class="field"><label>Subject</label><input type="text" id="eSubject" placeholder="Math"></div>
          <div class="field"><label>Topic</label><input type="text" id="eTopic" placeholder="Fractions"></div>
          <div class="field"><label>Level</label><select id="eLevel"><option>General</option><option>Middle school</option><option>High school</option><option>Undergraduate</option></select></div>
          <div class="field"><label>Difficulty</label><select id="eDiff"><option value="easy">Easy</option><option value="standard" selected>Standard</option><option value="hard">Hard</option></select></div>
          <div class="field"><label>Questions</label><input type="number" id="eCount" value="5" min="1" max="30"></div>
          <div class="field"><label>Duration (min)</label><input type="number" id="eDur" value="15" min="1" max="180"></div>
        </div>
        <div class="field"><label>Source material (Term: definition per line - optional; leave empty for AI-generated questions)</label>
          <textarea id="eSource" rows="3" placeholder="Fraction: a part of a whole"></textarea></div>
        <button class="btn btn-primary" id="eGo">${icon("quiz")} Generate exam</button>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("clock")} Recent attempts</div>
        <div id="examHistory"><div class="muted">Loading...</div></div>
      </div>
    </div>
    <div class="card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("quiz")} Long-answer evaluation</div>
      <p class="muted" style="margin-top:0">Get rubric-based feedback on descriptive answers. AI scores are suggestions - teachers finalize official marks.</p>
      <div class="form-grid">
        <div class="field"><label>Question</label><textarea id="essayQ" rows="2" placeholder="Paste or type the question..."></textarea></div>
        <div class="field"><label>Your answer</label><textarea id="essayA" rows="5" placeholder="Write your detailed answer..."></textarea></div>
        <div class="field"><label>Subject</label><input type="text" id="essaySubject" placeholder="General"></div>
        <div class="field"><label>Level</label><select id="essayLevel"><option>General</option><option>Middle school</option><option>High school</option><option>Undergraduate</option></select></div>
        <div class="field"><label>Max marks</label><input type="number" id="essayMarks" value="10" min="1" max="100"></div>
      </div>
      <button class="btn btn-primary" id="essayGo">${icon("quiz")} Evaluate answer</button>
      <div id="essayResult" style="margin-top:14px"></div>
      <div class="section-title" style="margin-top:18px">${icon("notes")} Evaluation history</div>
      <div id="essayHistory"><div class="muted">Loading...</div></div>
    </div>
    <div id="examArea" style="margin-top:18px"></div>
  </div>`;

  loadExamHistory();
  loadEssayHistory();

  document.getElementById("essayGo").addEventListener("click", async () => {
    const q = document.getElementById("essayQ").value.trim();
    const a = document.getElementById("essayA").value.trim();
    if (!q || !a) { toast("Enter both the question and your answer."); return; }
    const btn = document.getElementById("essayGo");
    btn.disabled = true;
    const out = document.getElementById("essayResult");
    out.innerHTML = '<div class="muted">Evaluating with AI...</div>';
    try {
      const r = await API.essayEvaluate({
        question: q, answer: a,
        subject: document.getElementById("essaySubject").value || "General",
        level: document.getElementById("essayLevel").value,
        max_marks: +document.getElementById("essayMarks").value || 10
      });
      const ev = r.evaluation || {};
      out.innerHTML = `
        <div class="quiz-result-banner ${ev.total_score >= (r.max_marks * 0.7) ? "good" : ev.total_score >= (r.max_marks * 0.4) ? "mid" : "bad"}">
          <div class="score">${ev.total_score ?? 0}/${r.max_marks}</div>
          <div><strong>AI-suggested marks</strong><br><span class="muted">${esc(r.disclaimer || "Requires teacher review.")}</span></div>
        </div>
        ${(ev.criteria || []).map(cr => `
          <div class="quiz-q"><div class="q-num">${esc(cr.name)} · ${cr.score}/${cr.max}</div>
          <div class="muted"><em>${esc(cr.comment || "")}</em></div></div>`).join("")}
        ${ev.feedback ? `<div class="muted" style="margin:8px 0"><strong>Feedback:</strong> ${esc(ev.feedback)}</div>` : ""}
        ${(ev.missing_concepts || []).length ? `<div class="muted" style="margin:4px 0"><strong>Missing concepts:</strong> ${ev.missing_concepts.map(esc).join(", ")}</div>` : ""}
        ${(ev.improvements || []).length ? `<div class="muted" style="margin:4px 0"><strong>To improve:</strong> ${ev.improvements.map(esc).join("; ")}</div>` : ""}`;
      mathify(out);
      loadEssayHistory();
    } catch (e) {
      out.innerHTML = `<div class="auth-error">${esc(e.message)}</div>`;
    } finally { btn.disabled = false; }
  });

  document.getElementById("eGo").addEventListener("click", async () => {
    const topic = document.getElementById("eTopic").value.trim();
    if (!topic) { toast("Enter a topic first."); return; }
    const btn = document.getElementById("eGo");
    btn.disabled = true;
    try {
      const exam = await API.examGenerate({
        subject: document.getElementById("eSubject").value || "General",
        topic,
        level: document.getElementById("eLevel").value,
        difficulty: document.getElementById("eDiff").value,
        count: +document.getElementById("eCount").value || 5,
        duration_min: +document.getElementById("eDur").value || 15,
        source: document.getElementById("eSource").value
      });
      state.exam = { ...exam, current: 0, answers: {}, marked: new Set(), submitted: false, secondsLeft: exam.duration_min * 60 };
      renderExam();
    } catch (e) {
      toast("Exam generation failed: " + e.message);
    } finally { btn.disabled = false; }
  });
}

function loadEssayHistory() {
  const el = document.getElementById("essayHistory");
  if (!el) return;
  API.essays(10).then(list => {
    if (!list.length) { el.innerHTML = '<div class="muted">No evaluations yet.</div>'; return; }
    el.innerHTML = list.map(e => `
      <div class="exam-hist-row">
        <div><strong>${esc((e.question || "").slice(0, 60))}${(e.question || "").length > 60 ? "…" : ""}</strong><br>
        <span class="muted">${esc(e.subject)} · ${esc((e.created || "").slice(0, 10))} · ${e.status === "teacher_reviewed" ? "teacher reviewed" : "AI suggestion"}</span></div>
        <div class="exam-score ${e.final_marks >= 0 ? (e.final_marks >= e.max_marks * 0.7 ? "good" : "mid") : "mid"}">${e.final_marks >= 0 ? e.final_marks : "—"}/${e.max_marks}</div>
      </div>`).join("");
  }).catch(() => { el.innerHTML = '<div class="muted">History unavailable.</div>'; });
}

function loadExamHistory() {
  const el = document.getElementById("examHistory");
  if (!el) return;
  API.examHistory().then(h => {
    if (!h.length) { el.innerHTML = '<div class="muted">No attempts yet - generate your first exam.</div>'; return; }
    el.innerHTML = h.slice().reverse().slice(0, 8).map(a => `
      <div class="exam-hist-row">
        <div><strong>${esc(a.title)}</strong><br><span class="muted">${esc(a.subject)} · ${esc((a.date || "").slice(0, 10))}</span></div>
        <div class="exam-score ${a.score >= 70 ? "good" : a.score >= 40 ? "mid" : "bad"}">${a.score}%</div>
      </div>`).join("");
  }).catch(() => { el.innerHTML = '<div class="muted">History unavailable.</div>'; });
}

function renderExam() {
  const area = document.getElementById("examArea");
  const ex = state.exam;
  if (!ex) return;
  const q = ex.questions[ex.current];
  const answered = Object.keys(ex.answers).length;

  area.innerHTML = `
  <div class="card exam-interface">
    <div class="exam-topbar">
      <div><strong>${esc(ex.title)}</strong>${modeBadge(ex.generation_mode)} <span class="muted">· ${ex.questions.length} questions · ${ex.total_marks} marks</span></div>
      <div class="exam-timer" id="examTimer">${fmtTime(ex.secondsLeft)}</div>
    </div>
    <div class="exam-body">
      <div class="exam-nav" id="examNav">
        ${ex.questions.map((qq, i) => `
          <button class="exam-nav-btn ${i === ex.current ? "current" : ""} ${ex.answers[qq.number] !== undefined ? "answered" : ""} ${ex.marked.has(qq.number) ? "marked" : ""}" data-i="${i}">${i + 1}${ex.marked.has(qq.number) ? " •" : ""}</button>`).join("")}
      </div>
      <div class="exam-question" id="examQuestion"></div>
    </div>
    <div class="exam-controls">
      <button class="btn btn-ghost" id="exPrev" ${ex.current === 0 ? "disabled" : ""}>← Previous</button>
      <button class="btn btn-ghost" id="exMark">${ex.marked.has(q.number) ? "Unmark review" : "Mark for review"}</button>
      <button class="btn btn-ghost" id="exSubmitBtn">Submit exam</button>
      <button class="btn btn-primary" id="exNext" ${ex.current === ex.questions.length - 1 ? "disabled" : ""}">Next →</button>
    </div>
    <div class="muted" style="margin-top:10px">${esc(ex.disclaimer || "")}</div>
  </div>`;

  renderExamQuestion();
  startExamTimer();

  area.querySelectorAll(".exam-nav-btn").forEach(b =>
    b.addEventListener("click", () => { ex.current = +b.dataset.i; renderExamQuestion(); syncNav(); }));
  document.getElementById("exPrev").onclick = () => { if (ex.current > 0) { ex.current--; renderExam(); } };
  document.getElementById("exNext").onclick = () => { if (ex.current < ex.questions.length - 1) { ex.current++; renderExam(); } };
  document.getElementById("exMark").onclick = () => {
    ex.marked.has(q.number) ? ex.marked.delete(q.number) : ex.marked.add(q.number);
    renderExam();
  };
  document.getElementById("exSubmitBtn").onclick = () => submitExam();
}

function renderExamQuestion() {
  const ex = state.exam;
  const q = ex.questions[ex.current];
  const box = document.getElementById("examQuestion");
  if (!box || !q) return;
  box.innerHTML = `
    <div class="q-num">QUESTION ${q.number} OF ${ex.questions.length} · ${q.type.replace("_", " ").toUpperCase()} · ${q.marks} MARK${q.marks > 1 ? "S" : ""}</div>
    <div class="q-text" style="font-size:16px;font-weight:600;margin:10px 0 16px">${esc(q.question)}</div>
    ${q.type === "mcq" ? q.options.map((o, i) => `
      <button class="quiz-opt ${ex.answers[q.number] === o ? "selected" : ""}" data-v="${esc(o)}">${String.fromCharCode(65 + i)}. ${esc(o)}</button>`).join("") : ""}
    ${q.type === "true_false" ? q.options.map(o => `
      <button class="quiz-opt ${ex.answers[q.number] === o ? "selected" : ""}" data-v="${esc(o)}">${esc(o)}</button>`).join("") : ""}
    ${q.type === "short_answer" ? `<input type="text" class="exam-short" value="${esc(ex.answers[q.number] || "")}" placeholder="Type your answer...">` : ""}
    ${q.type === "long_answer" ? `<textarea class="exam-long" rows="8" placeholder="Write a detailed answer (rubric-evaluated)...">${esc(ex.answers[q.number] || "")}</textarea>` : ""}`;

  box.querySelectorAll(".quiz-opt").forEach(b =>
    b.addEventListener("click", () => {
      ex.answers[q.number] = b.dataset.v;
      box.querySelectorAll(".quiz-opt").forEach(x => x.classList.remove("selected"));
      b.classList.add("selected");
      syncNav();
    }));
  const inp = box.querySelector(".exam-short");
  if (inp) inp.addEventListener("input", () => { ex.answers[q.number] = inp.value; syncNav(); });
  const ta = box.querySelector(".exam-long");
  if (ta) ta.addEventListener("input", () => { ex.answers[q.number] = ta.value; syncNav(); });
  mathify(box);
}

function syncNav() {
  const ex = state.exam;
  if (!ex) return;
  document.querySelectorAll(".exam-nav-btn").forEach((b, i) => {
    const qq = ex.questions[i];
    b.classList.toggle("current", i === ex.current);
    b.classList.toggle("answered", ex.answers[qq.number] !== undefined && ex.answers[qq.number] !== "");
    b.classList.toggle("marked", ex.marked.has(qq.number));
  });
}

function fmtTime(s) {
  const m = Math.floor(s / 60), sec = s % 60;
  return `${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`;
}

let examTimerId = null;
function startExamTimer() {
  clearInterval(examTimerId);
  examTimerId = setInterval(() => {
    const ex = state.exam;
    if (!ex || ex.submitted) { clearInterval(examTimerId); return; }
    ex.secondsLeft--;
    const el = document.getElementById("examTimer");
    if (el) {
      el.textContent = fmtTime(Math.max(0, ex.secondsLeft));
      el.classList.toggle("urgent", ex.secondsLeft <= 60);
    }
    if (ex.secondsLeft <= 0) submitExam(true);
  }, 1000);
}

async function submitExam(auto = false) {
  const ex = state.exam;
  if (!ex || ex.submitted) return;
  if (!auto && !confirm("Submit your exam? You cannot change answers afterwards.")) return;
  ex.submitted = true;
  clearInterval(examTimerId);
  try {
    const r = await API.examSubmit(ex.id, ex.answers);
    renderExamResult(r, auto);
    loadExamHistory();
  } catch (e) {
    toast("Submit failed: " + e.message);
    ex.submitted = false;
  }
}

function renderExamResult(r, auto) {
  const area = document.getElementById("examArea");
  const cls = r.score >= 70 ? "good" : r.score >= 40 ? "mid" : "bad";
  area.innerHTML = `
  <div class="card">
    <div class="quiz-result-banner ${cls}">
      <div class="score">${r.score}%</div>
      <div><strong>${auto ? "Time expired - " : ""}${r.marks} marks</strong><br>
      <span class="muted">${r.score >= 70 ? "Excellent work!" : r.score >= 40 ? "Good effort - review below." : "Keep practicing."}</span></div>
    </div>
    <div class="section-title">Answer review</div>
    ${r.review.map(rv => `
      <div class="quiz-q">
        <div class="q-num">QUESTION ${rv.number} · ${rv.marks} MARK${rv.marks > 1 ? "S" : ""} ${rv.correct ? '<span style="color:#4ade80">✓ correct</span>' : '<span style="color:#f87171">✗ incorrect</span>'}${rv.ai_suggestion ? ' <span class="badge" style="background:#7c3aed22;color:#a78bfa">AI SUGGESTION</span>' : ""}</div>
        <div class="muted" style="margin:6px 0">Your answer: <strong>${esc((rv.your_answer || "-").slice(0, 300))}${(rv.your_answer || "").length > 300 ? "…" : ""}</strong>${rv.ai_suggestion ? "" : ` → Correct: <strong style="color:#4ade80">${esc(rv.correct_answer)}</strong>`}</div>
        ${rv.ai_suggestion && rv.evaluation ? `
          <div class="muted" style="margin:6px 0">AI score: <strong style="color:#67e8f9">${rv.evaluation.total_score}/${rv.marks}</strong> (suggested)</div>
          ${(rv.evaluation.criteria || []).map(cr => `
            <div class="muted" style="margin:2px 0 2px 12px">· ${esc(cr.name)}: ${cr.score}/${cr.max} - <em>${esc(cr.comment || "")}</em></div>`).join("")}
          ${rv.evaluation.feedback ? `<div class="muted" style="margin:6px 0"><em>${esc(rv.evaluation.feedback)}</em></div>` : ""}
          ${(rv.evaluation.missing_concepts || []).length ? `<div class="muted" style="margin:4px 0"><strong>Missing concepts:</strong> ${rv.evaluation.missing_concepts.map(esc).join(", ")}</div>` : ""}
          <div class="muted" style="margin:4px 0;font-size:12px">${esc(rv.note || "AI-generated evaluation - requires teacher review for official marks.")}</div>` : ""}
        ${!rv.ai_suggestion ? `<div class="muted"><em>${esc(rv.explanation || "")}</em></div>` : ""}
      </div>`).join("")}
    <div style="margin-top:14px;display:flex;gap:10px">
      <button class="btn btn-ghost" id="examAgain">Generate another exam</button>
      <button class="btn btn-primary" id="examBack">Back to exams</button>
    </div>
  </div>`;
  mathify(area);
  document.getElementById("examAgain").onclick = () => { state.exam = null; document.getElementById("examArea").innerHTML = ""; document.getElementById("eGo").click(); };
  document.getElementById("examBack").onclick = () => { state.exam = null; document.getElementById("examArea").innerHTML = ""; };
}

/* ============================ DOCUMENTS ============================ */

function vDocuments(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Document Library</h2><p>Upload PDF, DOCX or TXT files and ask EDUCORE AI questions about them.</p></div>
    <div class="grid grid-2">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("upload")} Upload</div>
        <div class="dropzone" id="dropzone">
          <div>${icon("upload")}</div>
          <p>Drag & drop a file here, or</p>
          <button class="btn btn-ghost" id="pickFile">Choose file</button>
          <input type="file" id="fileInput" accept=".pdf,.docx,.txt" style="display:none">
          <div class="muted" id="uploadStatus" style="margin-top:8px"></div>
        </div>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("search")} Library</div>
        <input type="text" id="docSearch" placeholder="Search documents..." style="width:100%;margin-bottom:12px">
        <div id="docList"><div class="muted">Loading...</div></div>
      </div>
    </div>
    <div id="docDetail" style="margin-top:18px"></div>
  </div>`;

  const dz = document.getElementById("dropzone");
  const fi = document.getElementById("fileInput");
  document.getElementById("pickFile").onclick = () => fi.click();
  fi.onchange = () => fi.files[0] && uploadDoc(fi.files[0]);
  ["dragover", "dragleave", "drop"].forEach(ev =>
    dz.addEventListener(ev, e => { e.preventDefault(); dz.classList.toggle("drag", ev === "dragover"); }));
  dz.addEventListener("drop", e => { const f = e.dataTransfer.files[0]; if (f) uploadDoc(f); });

  document.getElementById("docSearch").addEventListener("input", loadDocs);
  loadDocs();
}

let docCache = [];
async function uploadDoc(file) {
  const status = document.getElementById("uploadStatus");
  const ext = file.name.split(".").pop().toLowerCase();
  if (!["pdf", "docx", "txt"].includes(ext)) { toast("Unsupported type - use PDF, DOCX or TXT."); return; }
  if (file.size > 15 * 1024 * 1024) { toast("File too large (max 15 MB)."); return; }
  status.textContent = "Uploading & extracting text...";
  try {
    const buf = await file.arrayBuffer();
    const bytes = new Uint8Array(buf);
    let bin = "";
    const CHUNK = 0x2000;
    for (let i = 0; i < bytes.length; i += CHUNK) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK));
    }
    const b64 = btoa(bin);
    await API.docUpload(file.name, b64);
    status.textContent = "Uploaded!";
    toast("Document uploaded: " + file.name);
    loadDocs();
  } catch (e) {
    status.textContent = "Upload failed: " + e.message;
  }
}

async function loadDocs() {
  const list = document.getElementById("docList");
  if (!list) return;
  try {
    docCache = await API.docs();
    const q = (document.getElementById("docSearch")?.value || "").toLowerCase();
    const filtered = docCache.filter(d => !q || d.name.toLowerCase().includes(q));
    if (!filtered.length) {
      list.innerHTML = '<div class="muted">No documents yet - upload your first file.</div>';
      return;
    }
    list.innerHTML = filtered.map(d => `
      <div class="doc-row" data-id="${d.id}">
        <div class="doc-icon">${d.ext === "pdf" ? "PDF" : d.ext.toUpperCase()}</div>
        <div class="doc-meta">
          <strong>${esc(d.name)}</strong>
          <span class="muted">${fmtSize(d.size)} · ${esc((d.uploaded || "").slice(0, 10))} · ${d.text_length} chars</span>
        </div>
        <div class="doc-actions">
          <button class="chip" data-act="view">view</button>
          <button class="chip" data-act="ask">ask AI</button>
          <button class="chip" data-act="sum">summarize</button>
          <button class="chip danger" data-act="del">delete</button>
        </div>
      </div>`).join("");
    list.querySelectorAll(".doc-row").forEach(row => {
      const id = row.dataset.id;
      row.querySelectorAll("[data-act]").forEach(b =>
        b.addEventListener("click", () => docAction(id, b.dataset.act, row)));
    });
  } catch (e) {
    list.innerHTML = '<div class="muted">Could not load documents.</div>';
  }
}

function fmtSize(b) {
  if (b > 1048576) return (b / 1048576).toFixed(1) + " MB";
  if (b > 1024) return (b / 1024).toFixed(1) + " KB";
  return b + " B";
}

async function docAction(id, act, row) {
  const detail = document.getElementById("docDetail");
  if (act === "del") {
    if (!confirm("Delete this document permanently?")) return;
    await API.docDelete(id);
    toast("Document deleted.");
    loadDocs();
    return;
  }
  if (act === "view") {
    try {
      const d = await API.doc(id);
      detail.innerHTML = `<div class="card"><div class="section-title" style="margin-top:0">${esc(d.name)}</div>
        <div class="output-panel" style="max-height:300px">${esc((d.text || "(no extractable text)").slice(0, 4000))}${(d.text || "").length > 4000 ? "\n\n... (truncated)" : ""}</div></div>`;
      detail.scrollIntoView({ behavior: "smooth" });
    } catch (e) { toast(e.message); }
    return;
  }
  if (act === "sum") {
    detail.innerHTML = `<div class="card"><div class="muted">Summarizing (requires OpenAI key)...</div></div>`;
    try {
      const r = await API.docSummarize(id);
      detail.innerHTML = `<div class="card"><div class="section-title" style="margin-top:0">Summary</div><div class="output-panel">${esc(r.answer)}</div></div>`;
    } catch (e) {
      detail.innerHTML = `<div class="card"><div class="muted">Error: ${esc(e.message)}</div></div>`;
    }
    detail.scrollIntoView({ behavior: "smooth" });
    return;
  }
  if (act === "ask") {
    detail.innerHTML = `<div class="card">
      <div class="section-title" style="margin-top:0">Ask about this document</div>
      <div style="display:flex;gap:8px">
        <input type="text" id="docQ" placeholder="e.g. What is photosynthesis?" style="flex:1">
        <button class="btn btn-primary" id="docQGo">Ask</button>
      </div>
      <div class="output-panel" id="docA" style="margin-top:12px;display:none"></div>
    </div>`;
    document.getElementById("docQGo").onclick = async () => {
      const q = document.getElementById("docQ").value.trim();
      if (!q) return;
      const out = document.getElementById("docA");
      out.style.display = "";
      out.textContent = "Thinking...";
      try {
        const r = await API.docAsk(id, q);
        out.textContent = r.answer;
      } catch (e) { out.textContent = "Error: " + e.message; }
    };
    detail.scrollIntoView({ behavior: "smooth" });
  }
}

/* ============================ STUDY PLANNER ============================ */

const plannerState = { year: new Date().getFullYear(), month: new Date().getMonth(), tasks: [] };

function vPlanner(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Study Planner</h2><p>Plan your week, track tasks and let AI build your timetable.</p></div>
    <div class="grid grid-2">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("planner")} Create task</div>
        <div class="field"><label>Title</label><input type="text" id="tTitle" placeholder="Revise chapter 4"></div>
        <div class="form-grid">
          <div class="field"><label>Subject</label><input type="text" id="tSubject" placeholder="Math"></div>
          <div class="field"><label>Due date</label><input type="date" id="tDue"></div>
          <div class="field"><label>Priority</label><select id="tPri"><option value="low">Low</option><option value="medium" selected>Medium</option><option value="high">High</option></select></div>
          <div class="field"><label>Minutes</label><input type="number" id="tMin" value="30" min="5" max="600"></div>
        </div>
        <div class="field"><label>Description</label><input type="text" id="tDesc" placeholder="Optional notes"></div>
        <button class="btn btn-primary" id="tAdd">${icon("plus")} Add task</button>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("sparkles")} AI study planner</div>
        <div class="form-grid">
          <div class="field"><label>Goal</label><input type="text" id="aiGoal" placeholder="Score 90% in math exam"></div>
          <div class="field"><label>Days</label><input type="number" id="aiDays" value="7" min="1" max="30"></div>
          <div class="field"><label>Min/day</label><input type="number" id="aiMin" value="60" min="15" max="600"></div>
          <div class="field"><label>Subjects</label><input type="text" id="aiSubj" placeholder="Math, Physics"></div>
        </div>
        <button class="btn btn-ghost" id="aiGo">${icon("sparkles")} Generate timetable (OpenAI)</button>
        <div class="output-panel" id="aiOut" style="display:none;margin-top:12px"></div>
      </div>
    </div>
    <div class="card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("calendar")} Calendar</div>
      <div class="cal-nav">
        <button class="btn btn-ghost" id="calPrev">←</button>
        <strong id="calTitle"></strong>
        <button class="btn btn-ghost" id="calNext">→</button>
      </div>
      <div class="cal-grid" id="calGrid"></div>
    </div>
    <div class="card" style="margin-top:18px">
      <div class="section-title" style="margin-top:0">${icon("quiz")} All tasks</div>
      <div id="taskList"><div class="muted">Loading...</div></div>
    </div>
  </div>`;

  document.getElementById("tAdd").onclick = async () => {
    const title = document.getElementById("tTitle").value.trim();
    if (!title) { toast("Enter a task title."); return; }
    await API.plannerCreate({
      title,
      subject: document.getElementById("tSubject").value || "General",
      due_date: document.getElementById("tDue").value,
      priority: document.getElementById("tPri").value,
      description: document.getElementById("tDesc").value,
      minutes: +document.getElementById("tMin").value || 30
    });
    document.getElementById("tTitle").value = "";
    toast("Task added.");
    loadPlanner();
  };

  document.getElementById("aiGo").onclick = async () => {
    const out = document.getElementById("aiOut");
    out.style.display = "";
    out.textContent = "Generating timetable...";
    try {
      const r = await API.plannerAI({
        goal: document.getElementById("aiGoal").value || "Improve my grades",
        days: document.getElementById("aiDays").value,
        minutes_per_day: document.getElementById("aiMin").value,
        subjects: document.getElementById("aiSubj").value || "General"
      });
      out.textContent = r.schedule;
    } catch (e) { out.textContent = "Error: " + e.message + " (needs OPENAI_API_KEY)"; }
  };

  document.getElementById("calPrev").onclick = () => { plannerState.month--; if (plannerState.month < 0) { plannerState.month = 11; plannerState.year--; } renderCalendar(); };
  document.getElementById("calNext").onclick = () => { plannerState.month++; if (plannerState.month > 11) { plannerState.month = 0; plannerState.year++; } renderCalendar(); };

  loadPlanner();
}

async function loadPlanner() {
  try {
    plannerState.tasks = await API.plannerTasks();
    renderCalendar();
    renderTaskList();
  } catch (e) { toast("Could not load tasks."); }
}

function renderCalendar() {
  const grid = document.getElementById("calGrid");
  const title = document.getElementById("calTitle");
  if (!grid || !title) return;
  const { year, month } = plannerState;
  title.textContent = new Date(year, month).toLocaleString(undefined, { month: "long", year: "numeric" });

  const first = new Date(year, month, 1).getDay();
  const days = new Date(year, month + 1, 0).getDate();
  const today = new Date();
  const todayStr = `${today.getFullYear()}-${String(today.getMonth() + 1).padStart(2, "0")}-${String(today.getDate()).padStart(2, "0")}`;

  const tasksByDate = {};
  plannerState.tasks.forEach(t => {
    if (t.due_date) (tasksByDate[t.due_date] = tasksByDate[t.due_date] || []).push(t);
  });

  let html = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"].map(d => `<div class="cal-dow">${d}</div>`).join("");
  for (let i = 0; i < first; i++) html += '<div class="cal-day empty"></div>';
  for (let d = 1; d <= days; d++) {
    const ds = `${year}-${String(month + 1).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
    const dayTasks = tasksByDate[ds] || [];
    html += `<div class="cal-day ${ds === todayStr ? "today" : ""}">
      <span class="cal-num">${d}</span>
      ${dayTasks.slice(0, 3).map(t => `<div class="cal-task ${t.priority}">${esc(t.title)}</div>`).join("")}
      ${dayTasks.length > 3 ? `<div class="cal-task more">+${dayTasks.length - 3} more</div>` : ""}
    </div>`;
  }
  grid.innerHTML = html;
}

function renderTaskList() {
  const el = document.getElementById("taskList");
  if (!el) return;
  if (!plannerState.tasks.length) {
    el.innerHTML = '<div class="muted">No tasks yet - create your first study task.</div>';
    return;
  }
  el.innerHTML = plannerState.tasks.map(t => `
    <div class="task-row ${t.status === "done" ? "done" : ""}">
      <input type="checkbox" ${t.status === "done" ? "checked" : ""} data-toggle="${t.id}">
      <div class="task-meta">
        <strong>${esc(t.title)}</strong>
        <span class="muted">${esc(t.subject)}${t.due_date ? " · due " + esc(t.due_date) : ""}${t.minutes ? " · " + t.minutes + "min" : ""}</span>
        ${t.description ? `<span class="muted">${esc(t.description)}</span>` : ""}
      </div>
      <span class="pri pri-${esc(t.priority)}">${esc(t.priority)}</span>
      <button class="chip danger" data-del="${t.id}">delete</button>
    </div>`).join("");

  el.querySelectorAll("[data-toggle]").forEach(cb =>
    cb.addEventListener("change", async () => {
      await API.plannerUpdate(cb.dataset.toggle, { status: cb.checked ? "done" : "pending" });
      loadPlanner();
    }));
  el.querySelectorAll("[data-del]").forEach(b =>
    b.addEventListener("click", async () => {
      if (!confirm("Delete this task?")) return;
      await API.plannerDelete(b.dataset.del);
      loadPlanner();
    }));
}

/* ============================ CLASSROOMS ============================ */

function vClassrooms(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Classrooms</h2><p>Join classes with a code, or create your own. Teacher/admin role management is planned.</p></div>
    <div class="grid grid-2">
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("plus")} Create classroom</div>
        <div class="field"><label>Name</label><input type="text" id="crName" placeholder="Math Club"></div>
        <div class="form-grid">
          <div class="field"><label>Your name (teacher)</label><input type="text" id="crTeacher" placeholder="Ms. Rao"></div>
          <div class="field"><label>Description</label><input type="text" id="crDesc" placeholder="Weekly practice"></div>
        </div>
        <button class="btn btn-primary" id="crCreate">${icon("plus")} Create</button>
      </div>
      <div class="card">
        <div class="section-title" style="margin-top:0">${icon("users")} Join with code</div>
        <div style="display:flex;gap:8px">
          <input type="text" id="crCode" placeholder="6-char code" style="flex:1;text-transform:uppercase">
          <input type="text" id="crStudent" placeholder="Your name" style="flex:1">
          <button class="btn btn-ghost" id="crJoin">Join</button>
        </div>
      </div>
    </div>
    <div class="section-title">${icon("classrooms")} Your classrooms</div>
    <div class="grid grid-3" id="crList"><div class="muted">Loading...</div></div>
    <div id="crDetail" style="margin-top:18px"></div>
  </div>`;

  document.getElementById("crCreate").onclick = async () => {
    const name = document.getElementById("crName").value.trim();
    if (!name) { toast("Enter a classroom name."); return; }
    const c = await API.classroomCreate({
      name,
      teacher: document.getElementById("crTeacher").value || "Teacher",
      description: document.getElementById("crDesc").value
    });
    toast("Classroom created! Join code: " + c.code);
    loadClassrooms();
  };
  document.getElementById("crJoin").onclick = async () => {
    const code = document.getElementById("crCode").value.trim();
    const student = document.getElementById("crStudent").value.trim() || "Student";
    if (!code) { toast("Enter a join code."); return; }
    try {
      await API.classroomJoin({ code, student });
      toast("Joined classroom!");
      loadClassrooms();
    } catch (e) { toast(e.message); }
  };
  loadClassrooms();
}

async function loadClassrooms() {
  const list = document.getElementById("crList");
  if (!list) return;
  try {
    const rooms = await API.classrooms();
    if (!rooms.length) {
      list.innerHTML = '<div class="muted">No classrooms yet - create one or join with a code.</div>';
      return;
    }
    list.innerHTML = rooms.map(c => `
      <div class="card quick-card hoverable" data-room="${c.id}">
        <div class="icon-wrap" style="background:var(--grad-soft);color:var(--purple)">${icon("classrooms")}</div>
        <h3>${esc(c.name)}</h3>
        <p>${esc(c.teacher)} · ${c.members.length} member(s)<br><span class="muted">code: ${esc(c.code)}</span></p>
        <button class="btn btn-ghost" data-open="${c.id}">Open</button>
      </div>`).join("");
    list.querySelectorAll("[data-open]").forEach(b =>
      b.addEventListener("click", () => openClassroom(b.dataset.open)));
  } catch (e) { list.innerHTML = '<div class="muted">Could not load classrooms.</div>'; }
}

async function openClassroom(id) {
  const detail = document.getElementById("crDetail");
  try {
    const c = await API.classroomGet(id);
    detail.innerHTML = `
    <div class="card">
      <div class="section-title" style="margin-top:0">${esc(c.name)} <span class="coming-soon-tag">code ${esc(c.code)}</span></div>
      <p class="muted">${esc(c.description || "No description")} · Teacher: ${esc(c.teacher)}</p>
      <div class="section-title">Members (${c.members.length})</div>
      <div class="chips">${c.members.map(m => `<span class="chip">${esc(m)}</span>`).join("") || '<span class="muted">none yet</span>'}</div>
      <div class="section-title">Announcements</div>
      <div id="crAnns">${c.announcements.length ? c.announcements.slice().reverse().map(a =>
        `<div class="quiz-q"><div class="muted"><strong>${esc(a.author)}</strong> · ${esc((a.at || "").slice(0, 10))}</div><div>${esc(a.text)}</div></div>`).join("") : '<div class="muted">No announcements.</div>'}</div>
      <div style="display:flex;gap:8px;margin-top:10px">
        <input type="text" id="crAnnText" placeholder="Post an announcement..." style="flex:1">
        <input type="text" id="crAnnAuthor" placeholder="Your name" style="width:140px">
        <button class="btn btn-primary" id="crAnnGo">Post</button>
      </div>
      <div class="section-title">Shared materials</div>
      <div>${c.materials.length ? c.materials.map(m =>
        `<div class="quiz-q"><strong>${esc(m.name)}</strong> <span class="muted">(${esc(m.type)})</span>${m.url ? ` <a href="${esc(m.url)}" target="_blank" style="color:var(--cyan)">open</a>` : ""}</div>`).join("") : '<div class="muted">No materials yet.</div>'}</div>
      <div style="display:flex;gap:8px;margin-top:10px">
        <input type="text" id="crMatName" placeholder="Material name" style="flex:1">
        <input type="text" id="crMatUrl" placeholder="Link (optional)" style="flex:1">
        <button class="btn btn-ghost" id="crMatGo">Add material</button>
      </div>
    </div>`;
    document.getElementById("crAnnGo").onclick = async () => {
      const text = document.getElementById("crAnnText").value.trim();
      if (!text) return;
      await API.classroomAnnounce(id, { text, author: document.getElementById("crAnnAuthor").value || "Teacher" });
      openClassroom(id); loadClassrooms();
    };
    document.getElementById("crMatGo").onclick = async () => {
      const name = document.getElementById("crMatName").value.trim();
      if (!name) return;
      await API.classroomMaterial(id, { name, url: document.getElementById("crMatUrl").value, type: "link" });
      openClassroom(id);
    };
    detail.scrollIntoView({ behavior: "smooth" });
  } catch (e) { toast(e.message); }
}

/* ============================ INSIGHTS ============================ */

function vInsights(root) {
  root.innerHTML = `
  <div class="view">
    <div class="view-header"><h2>Learning Insights</h2><p>Mistake intelligence, spaced revision, readiness and your learning profile - all computed from your real quiz and exam activity.</p></div>
    <div id="insRoot"><div class="muted">Loading your learning data...</div></div>
  </div>`;
  loadInsights();
}

async function loadInsights() {
  const el = document.getElementById("insRoot");
  if (!el) return;
  try {
    const [mistakes, readiness, dna, map, revision] = await Promise.all([
      API.insightsMistakes(), API.insightsReadiness(), API.insightsDNA(),
      API.insightsMap(), API.insightsRevision()
    ]);
    el.innerHTML = `
      ${renderReadiness(readiness)}
      ${renderRevision(revision)}
      ${renderMistakes(mistakes)}
      ${renderDNA(dna)}
      ${renderMap(map)}`;
    bindInsightActions();
  } catch (e) {
    el.innerHTML = `<div class="card"><div class="auth-error">Could not load insights: ${esc(e.message)}</div>
      <p class="muted">Insights appear after you answer quiz or exam questions - your responses are recorded automatically.</p></div>`;
  }
}

/* ---------- readiness ---------- */

function renderReadiness(r) {
  const score = r.score || 0;
  const C = 2 * Math.PI * 42;
  const dash = (C * score) / 100;
  const comp = r.components || {};
  const bar = (label, val, hint) => `
    <div class="ins-comp">
      <div class="ins-comp-top"><span>${label}</span><strong>${val}%</strong></div>
      <div class="ins-bar"><div class="ins-bar-fill" style="width:${Math.min(100, val)}%"></div></div>
      ${hint ? `<div class="muted" style="font-size:11px">${hint}</div>` : ""}
    </div>`;
  return `
  <div class="card">
    <div class="section-title" style="margin-top:0">${icon("target")} Exam readiness</div>
    <div class="ins-readiness">
      <div class="ins-ring">
        <svg viewBox="0 0 100 100" width="120" height="120">
          <circle cx="50" cy="50" r="42" fill="none" stroke="rgba(148,163,184,.15)" stroke-width="9"/>
          <circle cx="50" cy="50" r="42" fill="none" stroke="url(#insGrad)" stroke-width="9"
            stroke-linecap="round" stroke-dasharray="${C.toFixed(1)}" stroke-dashoffset="${(C - dash).toFixed(1)}"
            transform="rotate(-90 50 50)"/>
          <defs><linearGradient id="insGrad" x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stop-color="#8B5CF6"/><stop offset="1" stop-color="#22D3EE"/>
          </linearGradient></defs>
        </svg>
        <div class="ins-ring-num"><strong>${score}</strong><span>/100</span></div>
      </div>
      <div style="flex:1;min-width:220px">
        <div style="font-size:15px;font-weight:700">${esc(r.label || "-")}</div>
        <div class="muted" style="font-size:12px;margin:4px 0 10px">${esc(r.disclaimer || "")}</div>
        ${bar("Topic coverage", comp.topic_coverage_pct ?? r.coverage_pct ?? 0, `${r.attempts ?? 0} responses recorded`)}
        ${bar("Answer accuracy", comp.accuracy_pct ?? r.accuracy_pct ?? 0, `${comp.accuracy_pct ?? 0}% of marks earned`)}
        ${bar("Revision completion", comp.revision_completion_pct ?? r.revision_pct ?? 0, "spaced-repetition items done")}
        ${bar("Recent activity", comp.recent_activity_score ?? 0, `${r.responses_7d ?? 0} responses in last 7 days`)}
      </div>
    </div>
    <div class="grid grid-2" style="margin-top:14px">
      <div><div class="section-title" style="font-size:13px">Needs attention</div>
        ${(r.attention_topics || []).length ? r.attention_topics.map(t => `
          <div class="ins-topic-row"><span>${esc(t.topic)}</span><span class="ins-acc bad">${t.accuracy_pct}%</span></div>`).join("")
        : '<div class="muted">Not enough data yet.</div>'}</div>
      <div><div class="section-title" style="font-size:13px">Strong topics</div>
        ${(r.strong_topics || []).length ? r.strong_topics.map(t => `
          <div class="ins-topic-row"><span>${esc(t.topic)}</span><span class="ins-acc good">${t.accuracy_pct}%</span></div>`).join("")
        : '<div class="muted">Not enough data yet.</div>'}</div>
    </div>
  </div>`;
}

/* ---------- revision ---------- */

function renderRevision(rv) {
  const item = it => `
    <div class="ins-rev-item">
      <div style="flex:1;min-width:0">
        <strong>${esc(it.topic)}</strong>
        <span class="muted"> · ${esc(it.subject)} · due ${esc(it.due || "-")} · interval ${it.interval ?? 1}d · ${it.reps ?? 0} reps${it.lapses ? ` · ${it.lapses} lapse(s)` : ""}</span>
      </div>
      <div class="ins-rev-actions">
        ${it.status !== "mastered" ? `
          <button class="chip" data-rev-q="${it.id}" data-q="4" title="I recalled it well">good</button>
          <button class="chip danger" data-rev-q="${it.id}" data-q="1" title="I forgot it">poor</button>
          <button class="chip" data-rev-mark="${it.id}">done today</button>
          <button class="chip" data-rev-plan="${it.id}">+planner</button>` :
        `<span class="badge" style="background:#4ade8022;color:#4ade80">mastered</span>`}
      </div>
    </div>`;
  return `
  <div class="card">
    <div class="section-title" style="margin-top:0">${icon("refresh")} Smart revision
      <span class="muted" style="font-weight:400;font-size:12px"> · ${rv.pending ?? 0} pending${rv.overdue ? ` · <strong style="color:#f87171">${rv.overdue} overdue</strong>` : ""}</span>
    </div>
    <p class="muted" style="margin-top:0;font-size:12px">Spaced repetition over your weak topics - intervals grow when you recall well and reset when you struggle. Auto-seeded from your mistakes.</p>
    ${(rv.due || []).length ? `<div class="section-title" style="font-size:13px;color:#f87171">Due now</div>${rv.due.map(item).join("")}` : ""}
    ${(rv.upcoming || []).length ? `<div class="section-title" style="font-size:13px">Upcoming</div>${rv.upcoming.map(item).join("")}` : ""}
    ${(rv.completed || []).length ? `<div class="section-title" style="font-size:13px">Completed / mastered</div>${rv.completed.slice(0, 10).map(item).join("")}` : ""}
    ${!(rv.due || []).length && !(rv.upcoming || []).length && !(rv.completed || []).length ?
      '<div class="muted">No revision items yet - they are seeded automatically from your quiz/exam mistakes.</div>' : ""}
  </div>`;
}

/* ---------- mistakes ---------- */

function renderMistakes(m) {
  const errLabel = { conceptual: "Conceptual", calculation: "Calculation", procedural: "Procedural", incomplete: "Incomplete", essay: "Essay", correct: "Correct" };
  return `
  <div class="card">
    <div class="section-title" style="margin-top:0">${icon("quiz")} Mistake intelligence
      <span class="muted" style="font-weight:400;font-size:12px"> · ${m.total_responses ?? 0} answers analyzed · ${m.accuracy_pct ?? 0}% accuracy</span>
    </div>
    ${Object.keys(m.error_summary || {}).length ? `
    <div class="ins-err-chips">${Object.entries(m.error_summary).map(([k, v]) =>
      `<span class="chip">${errLabel[k] || k}: ${v}</span>`).join("")}</div>` : ""}
    ${(m.repeated_mistakes || []).length ? `
      <div class="section-title" style="font-size:13px">Repeatedly missed questions</div>
      ${m.repeated_mistakes.map(x => `
        <div class="ins-topic-row"><span title="${esc(x.question)}">${esc(x.question)}</span>
        <span class="ins-acc bad">×${x.times_wrong}</span></div>`).join("")}` : ""}
    ${(m.topics || []).length ? `
      <div class="section-title" style="font-size:13px;margin-top:12px">Topic breakdown</div>
      <table class="ins-table"><thead><tr><th>Topic</th><th>Subject</th><th>Attempts</th><th>Errors</th><th>Score</th><th>Status</th></tr></thead><tbody>
      ${m.topics.slice(0, 15).map(t => `
        <tr><td>${esc(t.topic)}</td><td>${esc(t.subject)}</td><td>${t.attempts}</td><td>${t.errors}</td>
        <td>${t.score_pct}%</td>
        <td><span class="ins-status st-${t.status}">${t.status}${t.improving ? " ↑" : ""}</span></td></tr>`).join("")}
      </tbody></table>` : '<div class="muted">No responses recorded yet - take a quiz to start building insight.</div>'}
    ${(m.recommendations || []).length ? `
      <div class="section-title" style="font-size:13px;margin-top:12px">Recommended practice</div>
      ${m.recommendations.map(rc => `
        <div class="ins-topic-row"><span>${esc(rc.reason)}</span>
        <button class="chip" data-practice="${esc(rc.topic)}">${esc(rc.action_label || "Practice")}</button></div>`).join("")}` : ""}
  </div>`;
}

/* ---------- learning DNA ---------- */

function renderDNA(d) {
  const trendArrow = t => t > 0 ? `<span style="color:#4ade80">▲${t}</span>` : t < 0 ? `<span style="color:#f87171">▼${Math.abs(t)}</span>` : `<span class="muted">→0</span>`;
  return `
  <div class="card">
    <div class="section-title" style="margin-top:0">${icon("sparkles")} Learning DNA</div>
    ${(d.subjects || []).length ? `
      <div class="section-title" style="font-size:13px">Subject performance</div>
      ${d.subjects.map(s => `
        <div class="ins-comp">
          <div class="ins-comp-top"><span>${esc(s.subject)}</span>
            <strong>${s.accuracy_pct}% <span class="muted" style="font-weight:400">· ${s.attempts} attempts · trend ${trendArrow(s.trend_pct)}</span></strong></div>
          <div class="ins-bar"><div class="ins-bar-fill" style="width:${s.accuracy_pct}%"></div></div>
        </div>`).join("")}` : '<div class="muted">No subject data yet.</div>'}
    <div class="grid grid-2" style="margin-top:14px">
      <div>
        <div class="section-title" style="font-size:13px">Consistency</div>
        <div class="ins-topic-row"><span>Active days (last 14)</span><strong>${d.consistency?.active_days_14d ?? 0}/14</strong></div>
        <div class="ins-topic-row"><span>Consistency</span><strong>${d.consistency?.consistency_pct ?? 0}%</strong></div>
        <div class="ins-topic-row"><span>Activity span</span><strong>${d.consistency?.activity_span_days ?? 0} days</strong></div>
        <div class="ins-topic-row"><span>Responses/day</span><strong>${d.consistency?.responses_per_day ?? 0}</strong></div>
      </div>
      <div>
        <div class="section-title" style="font-size:13px">Mistake mix</div>
        ${Object.keys(d.mistake_frequency || {}).length ? Object.entries(d.mistake_frequency).map(([k, v]) =>
          `<div class="ins-topic-row"><span>${esc(k)}</span><strong>${v}</strong></div>`).join("")
        : '<div class="muted">No mistakes recorded.</div>'}
      </div>
    </div>
    ${(d.topic_mastery || []).length ? `
      <div class="section-title" style="font-size:13px;margin-top:12px">Topic mastery</div>
      ${d.topic_mastery.map(t => `
        <div class="ins-comp">
          <div class="ins-comp-top"><span>${esc(t.topic)} <span class="muted" style="font-weight:400">(${t.attempts} tries)</span></span><strong>${t.mastery_pct}%</strong></div>
          <div class="ins-bar"><div class="ins-bar-fill" style="width:${t.mastery_pct}%"></div></div>
        </div>`).join("")}` : ""}
    ${(d.recommendations || []).length ? `
      <div class="section-title" style="font-size:13px;margin-top:12px">For you</div>
      ${d.recommendations.map(rc => `<div class="ins-topic-row"><span>${esc(rc.text)}</span></div>`).join("")}` : ""}
  </div>`;
}

/* ---------- concept map ---------- */

function renderMap(m) {
  const nodes = m.nodes || [], edges = m.edges || [];
  if (!nodes.length) {
    return `<div class="card"><div class="section-title" style="margin-top:0">${icon("target")} Concept map</div>
      <div class="muted">${esc(m.note || "No topics yet.")} Answer quiz/exam questions or add planner tasks to populate your map.</div></div>`;
  }
  // layout: subjects on left column, topics on right, grouped by subject
  const bySubject = {};
  nodes.forEach(n => { if (n.type === "topic") (bySubject[n.subject] = bySubject[n.subject] || []).push(n); });
  const subjects = nodes.filter(n => n.type === "subject");
  const W = 860, rowH = 46, subjX = 30, topX = 300;
  const totalRows = Math.max(...Object.values(bySubject).map(a => a.length), 1);
  const H = Math.max(220, subjects.length * rowH + 40);
  const subjY = {};
  const subjStep = subjects.length > 1 ? (H - 60) / (subjects.length - 1) : 0;
  subjects.forEach((s, i) => subjY[s.id] = 30 + i * subjStep);
  // distribute topic rows evenly in right area per subject block
  const topicPos = {};
  let cursor = 30;
  subjects.forEach(s => {
    const topics = bySubject[s.label] || [];
    const blockH = Math.max(rowH, (H - 60) * (topics.length / totalRows));
    topics.forEach((t, i) => {
      topicPos[t.id] = { x: topX + 40, y: cursor + (i + 0.5) * (blockH / Math.max(1, topics.length)) };
    });
    cursor += blockH;
  });
  const color = { completed: "#4ade80", weak: "#f87171", pending: "#94a3b8" };
  const edgePaths = edges.filter(e => e.kind === "contains").map(e => {
    const a = { x: subjX + 90, y: subjY[e.from] || 0 };
    const b = topicPos[e.to] || { x: 0, y: 0 };
    const mx = (a.x + b.x) / 2;
    return `<path d="M${a.x},${a.y} C${mx},${a.y} ${mx},${b.y} ${b.x - 8},${b.y}" fill="none" stroke="rgba(139,92,246,.35)" stroke-width="1.5"/>`;
  }).join("");
  const subjNodes = subjects.map(s => `
    <g><rect x="${subjX}" y="${(subjY[s.id] || 0) - 16}" width="180" height="32" rx="8"
      fill="rgba(139,92,246,.15)" stroke="rgba(139,92,246,.5)"/>
      <text x="${subjX + 90}" y="${(subjY[s.id] || 0) + 4}" text-anchor="middle" fill="#e2e8f0" font-size="12" font-weight="600">${esc(s.label)}</text></g>`).join("");
  const topicNodes = nodes.filter(n => n.type === "topic").map(t => {
    const p = topicPos[t.id] || { x: 0, y: 0 };
    const c = color[t.status] || "#94a3b8";
    const acc = t.accuracy_pct > 0 ? ` · ${t.accuracy_pct}%` : "";
    return `<g>
      <circle cx="${p.x}" cy="${p.y}" r="7" fill="${c}" opacity=".9"/>
      <text x="${p.x + 12}" y="${p.y + 4}" fill="#cbd5e1" font-size="11">${esc(t.label)}${acc}</text>
      <title>${esc(t.label)} - ${t.status}${acc} (${t.attempts} attempts)</title>
    </g>`;
  }).join("");
  const legend = `<div class="ins-legend">
    <span><i style="background:#4ade80"></i>completed (≥70%)</span>
    <span><i style="background:#f87171"></i>weak (&lt;40%)</span>
    <span><i style="background:#94a3b8"></i>pending</span></div>`;
  const rec = (m.recommended_next || []).length ? `
    <div class="section-title" style="font-size:13px;margin-top:10px">Recommended next</div>
    <div class="chips">${m.recommended_next.map(t =>
      `<span class="chip">${esc(t.topic)} <span class="muted">(${esc(t.subject)})</span></span>`).join("")}</div>` : "";
  return `<div class="card">
    <div class="section-title" style="margin-top:0">${icon("target")} Concept map</div>
    <p class="muted" style="margin-top:0;font-size:12px">${esc(m.note || "")}</p>
    ${legend}
    <div style="overflow-x:auto"><svg viewBox="0 0 ${W} ${H}" width="100%" style="min-width:640px">${edgePaths}${subjNodes}${topicNodes}</svg></div>
    ${rec}
  </div>`;
}

/* ---------- insight actions ---------- */

function bindInsightActions() {
  // revision quality review / mark / planner
  document.querySelectorAll("[data-rev-q]").forEach(b =>
    b.addEventListener("click", async () => {
      b.disabled = true;
      try { await API.insightsRevisionReview(b.dataset.revQ, +b.dataset.q); toast("Revision recorded - schedule updated."); loadInsights(); }
      catch (e) { toast(e.message); b.disabled = false; }
    }));
  document.querySelectorAll("[data-rev-mark]").forEach(b =>
    b.addEventListener("click", async () => {
      b.disabled = true;
      try { await API.insightsRevisionMark(b.dataset.revMark); toast("Marked as revised."); loadInsights(); }
      catch (e) { toast(e.message); b.disabled = false; }
    }));
  document.querySelectorAll("[data-rev-plan]").forEach(b =>
    b.addEventListener("click", async () => {
      b.disabled = true;
      try { await API.insightsRevisionPlanner(b.dataset.revPlan); toast("Added to your study planner."); }
      catch (e) { toast(e.message); }
      b.disabled = false;
    }));
  // practice shortcut -> quiz generator
  document.querySelectorAll("[data-practice]").forEach(b =>
    b.addEventListener("click", () => {
      location.hash = "#/quiz";
      setTimeout(() => { const t = document.getElementById("qTopic"); if (t) { t.value = b.dataset.practice; t.dispatchEvent(new Event("input")); } }, 300);
    }));
}

/* register module views */
Object.assign(VIEWS, {
  exams: vExams,
  documents: vDocuments,
  planner: vPlanner,
  classrooms: vClassrooms,
  insights: vInsights,
});
