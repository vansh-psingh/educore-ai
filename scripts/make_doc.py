#!/usr/bin/env python3
"""Generate EDUCORE AI project documentation PDF."""
from fpdf import FPDF

OUT = r"A:\Projects\EducoreAI\EDUCORE_AI_Documentation.pdf"


class Doc(FPDF):
    def header(self):
        if self.page_no() > 1:
            self.set_font("Helvetica", "I", 8)
            self.set_text_color(140, 140, 160)
            self.cell(0, 8, "EDUCORE AI - Project Documentation", align="R")
            self.ln(10)

    def footer(self):
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(140, 140, 160)
        self.cell(0, 10, f"Page {self.page_no()}/{{nb}}", align="C")

    def h1(self, text):
        self.set_font("Helvetica", "B", 16)
        self.set_text_color(90, 60, 200)
        self.multi_cell(0, 9, text)
        self.ln(2)

    def h2(self, text):
        self.set_font("Helvetica", "B", 12.5)
        self.set_text_color(50, 50, 70)
        self.multi_cell(0, 7, text)
        self.ln(1)

    def body(self, text):
        self.set_font("Helvetica", "", 10.5)
        self.set_text_color(40, 40, 55)
        self.multi_cell(0, 5.6, text)
        self.ln(2)

    def bullet(self, text):
        self.set_font("Helvetica", "", 10.5)
        self.set_text_color(40, 40, 55)
        x = self.get_x()
        self.cell(6, 5.6, "-")
        self.multi_cell(0, 5.6, "  " + text)
        self.set_x(x)


pdf = Doc()
pdf.alias_nb_pages()
pdf.set_auto_page_break(True, margin=18)
pdf.add_page()

# ---------- Title ----------
pdf.ln(30)
pdf.set_font("Helvetica", "B", 30)
pdf.set_text_color(80, 50, 200)
pdf.cell(0, 14, "EDUCORE AI", align="C", new_x="LMARGIN", new_y="NEXT")
pdf.set_font("Helvetica", "", 14)
pdf.set_text_color(90, 90, 110)
pdf.cell(0, 8, "AI-Powered Student Learning Platform", align="C", new_x="LMARGIN", new_y="NEXT")
pdf.ln(6)
pdf.set_font("Helvetica", "I", 11)
pdf.set_text_color(120, 120, 140)
pdf.cell(0, 7, "Project Documentation  |  Problem Statement, Solution & Future Plans", align="C", new_x="LMARGIN", new_y="NEXT")
pdf.ln(20)
pdf.set_font("Helvetica", "", 10)
pdf.set_text_color(120, 120, 140)
pdf.cell(0, 6, "Version 2.0  -  November 2026  (Production Upgrade)", align="C")
pdf.ln(14)
pdf.set_font("Helvetica", "I", 10)
pdf.set_text_color(140, 140, 160)
pdf.multi_cell(0, 6,
    "Live deployment: https://educore-ai-9dc3c.web.app\n"
    "Source repository: github.com/vansh-psingh/educore-ai",
    align="C")

# ---------- 1. Overview ----------
pdf.add_page()
pdf.h1("1. Project Overview")
pdf.body(
    "EDUCORE AI is a full-stack AI-powered educational platform designed for students. "
    "It combines a natural-language AI tutor with persistent conversation memory and "
    "voice input, automated study-notes generation with mathematical notation rendering, "
    "a hybrid quiz/exam engine with a dedicated rubric-based long-answer evaluation "
    "system, a persistent personal notes library, document intelligence (PDF/DOCX/TXT), "
    "a study planner, classrooms, multilingual support (22+ Indian languages), progress "
    "tracking, and a secure authentication system with role-based access "
    "(student / teacher / admin) backed by a formal admin dashboard."
)
pdf.body(
    "The platform is built on a Java 17 backend (JDK HttpServer + Jackson + JDBC) "
    "serving a single-page web dashboard, with Gemini (Google) as the primary generative "
    "AI provider and OpenAI retained as a fallback-compatible provider. All AI credentials "
    "remain strictly server-side. Application data is persisted in a managed PostgreSQL "
    "database (Neon) with a HikariCP connection pool, versioned SQL migrations and "
    "per-user data isolation."
)

# ---------- 2. Problem statement ----------
pdf.add_page()
pdf.h1("2. Problem Statement")
pdf.body(
    "Students today face several practical problems that existing tools address only "
    "partially or in fragmented ways:"
)
for b in [
    "Information overload - long chapters and PDFs are hard to digest; summarization "
    "tools rarely preserve structure, accuracy or exam relevance.",
    "Weak self-assessment - students lack instant, level-appropriate practice questions "
    "with explanations, and most quiz tools require manually prepared material.",
    "Poor retention - generated notes disappear when the browser closes; there is no "
    "persistent, searchable personal notes library tied to the student's account.",
    "Inconsistent explanation quality - generic chatbots do not adapt to a student's "
    "academic level, subject or preferred language, and mathematical notation is often "
    "rendered as unreadable plain text.",
    "Fragmented workflow - notes, quizzes, exams, documents, planning and classroom "
    "collaboration usually live in separate apps with separate logins.",
    "Accessibility and privacy - quality help is expensive or unavailable in regional "
    "languages, and student data is frequently stored insecurely or shared across accounts.",
    "Lost learning context - chat histories and caches vanish on restart, so students "
    "must re-explain context and the platform 'forgets' every session.",
    "No trustworthy essay grading - descriptive answers are forced through short-answer "
    "scoring, giving no rubric feedback, partial marks or teacher review workflow.",
    "No administrative control - platform operators lack user management, role control, "
    "audit trails, usage statistics or health monitoring.",
    "Insecure password recovery - development reset flows expose tokens in API responses "
    "instead of delivering them by email.",
    "File-based storage limits - JSON files cannot safely support concurrent users, "
    "transactions, indexing or reliable backups at scale.",
]:
    pdf.bullet(b)
pdf.ln(2)
pdf.body(
    "EDUCORE AI was created to solve these problems with one secure, student-centric "
    "platform that is accurate, personalized, multilingual, and free to use."
)

# ---------- 3. Solution statement ----------
pdf.add_page()
pdf.h1("3. Solution Statement")
pdf.body(
    "EDUCORE AI delivers an integrated learning environment where every feature is "
    "connected to a real backend - no UI-only placeholders:"
)
for b in [
    "Universal AI Tutor - understands informal, misspelled, multilingual and long "
    "inputs; streams answers token-by-token; supports conversation memory, stop/retry, "
    "image understanding (vision) and document Q&A.",
    "Advanced math rendering - KaTeX renders LaTeX fractions, equations, roots, sums, "
    "integrals and matrices across notes, chat, quizzes and exams.",
    "Notes Generator + persistent library - AI generates structured notes in 22+ "
    "languages; every note is auto-saved to a per-user history with search, sort, "
    "rename, edit, download (.md) and delete.",
    "Hybrid Quiz & Exam engine - generate assessments from uploaded notes OR purely "
    "from AI knowledge matched to subject, class level and difficulty; timed exams "
    "with navigation, mark-for-review, automatic scoring, explanations and attempt "
    "history.",
    "Feedback-driven improvement - students rate answers; highly-rated examples are "
    "injected into future prompts so the tutor adapts to each user's preferred style.",
    "Document Library - upload PDF/DOCX/TXT, extract text, ask questions and summarize "
    "with strict per-user isolation.",
    "Study Planner & Classrooms - task management, AI timetable generation, class "
    "creation/join, announcements and materials.",
    "Secure authentication - PBKDF2 password hashing, JWT access/refresh tokens, "
    "HttpOnly/SameSite cookies, rate limiting, password reset and role-based access "
    "control enforced server-side.",
    "Multilingual & accessible - responses match the user's language; dark premium "
    "UI is responsive on desktop and mobile.",
    "Persistent relational storage - PostgreSQL (Neon) with 15 typed tables, HikariCP "
    "pooling, versioned migrations, transactions, indexes, foreign keys and automatic "
    "JSON-to-SQL migration with timestamped backups; JSON files remain only as cold backup.",
    "Secure email password reset - cryptographically random single-use tokens stored "
    "only as hashes, expiring rows, atomic consume-on-verify, SMTP delivery with a "
    "professional HTML template, rate limiting and no token exposure in API responses.",
    "Persistent conversation memory - every chat message is stored per authenticated "
    "user in PostgreSQL; history restores across sessions and restarts, sessions can "
    "be listed and deleted, and AI cache keys are user-namespaced to prevent leakage.",
    "Admin dashboard - overview statistics, user search/filter/pagination, role and "
    "status management, account activity, audit log, system/database health, platform "
    "notifications and usage monitoring - all enforced server-side by role.",
    "Voice input - microphone button with Web Speech API (English/Hindi), live "
    "transcript preview, editable text, permission/error states and text fallback.",
    "Long-answer evaluation engine - dedicated essay question type with configurable "
    "rubrics, criterion-wise AI scoring (always labelled a suggestion), partial marks, "
    "missing-concept feedback, stored submissions and teacher override/finalize flow.",
    "Role-aware authentication UI - dashboard chrome renders only after session "
    "verification; auth pages are isolated, direct-URL access is guarded, and expired "
    "sessions redirect to sign-in automatically.",
    "Official brand system - neural-cap SVG logo (mark, full lockup, favicon) used "
    "consistently across login, sidebar, admin and browser tabs.",
]:
    pdf.bullet(b)

# ---------- 4. Architecture ----------
pdf.add_page()
pdf.h1("4. Technical Architecture")
pdf.h2("4.1 Stack")
for b in [
    "Backend: Java 17, JDK built-in HttpServer, Jackson Databind, Maven build",
    "Database: PostgreSQL on Neon (serverless) via JDBC + HikariCP pool; "
    "versioned SQL migration runner; JSON file fallback when DATABASE_URL is unset",
    "AI provider: Google Gemini (generateContent + SSE streaming + vision); "
    "OpenAI Responses API kept compatible",
    "Frontend: static SPA (HTML/CSS/JS), KaTeX for math, Web Speech API for voice, "
    "no framework runtime",
    "Email: Jakarta Mail SMTP (SMTP_* env vars) for password-reset delivery",
    "Auth: PBKDF2-SHA256 hashing, HS256 JWT access (15 min) + refresh (7 day) tokens",
]:
    pdf.bullet(b)
pdf.h2("4.2 Key backend components")
for b in [
    "WebServer.java - all REST endpoints, static file serving with correct MIME types, "
    "CORS, auth guards",
    "db/Db.java + db/Schema.java + db/EntityStore.java - connection pool, 15-table "
    "schema, transactional diff-based persistence adapter",
    "JsonToPostgres.java - one-shot guarded JSON->PostgreSQL migration with backups",
    "AuthService.java - registration, login, refresh, hashed single-use reset tokens, "
    "SMTP email, roles, audit hooks, rate limiting",
    "AdminService.java + AuditService.java + NotificationService.java - admin stats, "
    "user management, audit trail, platform notifications",
    "EssayService.java - long-answer rubric evaluation and teacher finalize workflow",
    "ConversationStore.java - per-user persistent chat history in PostgreSQL",
    "TtlCache.java - user-scoped TTL cache (Redis-ready swap point)",
    "AIProvider / GeminiService - provider routing, caching, streaming, vision",
    "HybridAssessment.java - Mode A (notes-based) vs Mode B (AI-knowledge) quiz/exam "
    "generation with strict-JSON parsing and graceful fallback",
    "NotesService / FeedbackService / ExamService / DocumentService / PlannerService / "
    "ClassroomService - per-user persistence (PostgreSQL or JSON fallback)",
]:
    pdf.bullet(b)
pdf.h2("4.3 Database schema (PostgreSQL)")
for b in [
    "app_users (roles, status, pass_hash, last_login), password_reset_tokens "
    "(hashed, single-use, expiring), audit_log",
    "notes, feedback_items, planner_tasks, exams, exam_attempts, classrooms, "
    "documents (+extracted_text), chat_messages, notifications, essay_submissions",
    "Every entity keeps a jsonb payload for lossless round-trip plus typed indexed "
    "columns (owner_id, status, created_at...) for queries and admin filters.",
]:
    pdf.bullet(b)
pdf.h2("4.4 Deployment")
pdf.body(
    "Frontend hosted free on Firebase Hosting (educore-ai-9dc3c.web.app) with "
    "5-minute static caching. Backend runs as a Dockerized Java service against the "
    "Neon PostgreSQL database; currently exposed publicly through a Cloudflare quick "
    "tunnel to the development machine, with a watchdog script "
    "(scripts/start-educore.ps1) that auto-starts the backend and tunnel at logon, "
    "patches the frontend API base URL and redeploys hosting automatically. "
    "JSON store files remain on disk as a cold backup after migration."
)

# ---------- 5. Future plans ----------
pdf.add_page()
pdf.h1("5. Future Plans")
for b in [
    "Permanent backend hosting - move the Java service off the development machine "
    "to a managed container platform (Cloud Run / Render / Koyeb) so the site is "
    "live 24/7 independent of any personal computer; the database is already hosted.",
    "SMTP activation - wire real SMTP credentials (Gmail App Password / SendGrid / "
    "SES) so password-reset emails deliver; code is complete and tested pending config.",
    "Redis cache layer - swap the built-in TTL cache for Redis for shared, "
    "multi-instance caching; the code is already structured as a swap point.",
    "Real-time collaboration - shared classrooms with live document editing, "
    "presence and instant announcements.",
    "Advanced analytics - per-topic mastery scores, spaced-repetition revision "
    "scheduling and personalized study roadmaps from quiz/exam/essay history.",
    "Teacher tools - question-bank management, bulk essay review queues, official "
    "curriculum mapping and class-level analytics on top of the admin dashboard.",
    "Mobile apps - wrap the PWA or build native iOS/Android clients against the same API.",
    "Model fine-tuning - move beyond prompt-level feedback to actual preference "
    "tuning on highly-rated Q&A pairs once sufficient data exists.",
    "Offline support - cache notes and quizzes for low-connectivity students.",
    "Institutional deployment - SSO (Google/Classroom), roster sync and expanded "
    "admin controls for schools and colleges.",
    "Voice expansion - server-side transcription fallback for browsers without "
    "SpeechRecognition, plus text-to-speech answers.",
]:
    pdf.bullet(b)

# ---------- 6. Honest limitations ----------
pdf.add_page()
pdf.h1("6. Current Limitations (Honest Assessment)")
for b in [
    "Public availability still depends on a Cloudflare tunnel to a local machine - "
    "the site is not hosted 24/7 on a server yet (database already is).",
    "SMTP password-reset email is implemented but untested until real SMTP "
    "credentials are configured; without them reset links appear only in server logs.",
    "Redis is not deployed - the built-in per-user TTL cache is used instead "
    "(adequate single-instance, documented swap point for multi-instance).",
    "Voice input uses the browser SpeechRecognition API - best on Chrome/Edge; "
    "Firefox/Safari have limited or no support, with text input as fallback.",
    "Long-answer AI scores are suggestions pending teacher review - synchronous "
    "evaluation adds latency to exam submission.",
    "JSON store files remain on disk as a cold backup after PostgreSQL migration; "
    "deletion is intentionally not automated.",
    "classroom_members table exists but membership still lives inside the classroom "
    "payload (no behavior change yet).",
]:
    pdf.bullet(b)

pdf.output(OUT)
print("written:", OUT)
