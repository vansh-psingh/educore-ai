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
pdf.cell(0, 6, "Version 1.0  -  November 2026", align="C")
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
    "It combines a natural-language AI tutor, automated study-notes generation with "
    "mathematical notation rendering, a hybrid quiz/exam engine, a persistent personal "
    "notes library, document intelligence (PDF/DOCX/TXT), a study planner, classrooms, "
    "multilingual support (22+ Indian languages), progress tracking, and a secure "
    "authentication system with role-based access (student / teacher / admin)."
)
pdf.body(
    "The platform is built on a Java 17 backend (JDK HttpServer + Jackson) serving a "
    "single-page web dashboard, with Gemini (Google) as the primary generative AI "
    "provider and OpenAI retained as a fallback-compatible provider. All AI credentials "
    "remain strictly server-side."
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
]:
    pdf.bullet(b)

# ---------- 4. Architecture ----------
pdf.add_page()
pdf.h1("4. Technical Architecture")
pdf.h2("4.1 Stack")
for b in [
    "Backend: Java 17, JDK built-in HttpServer, Jackson Databind, Maven build",
    "AI provider: Google Gemini (generateContent + SSE streaming + vision); "
    "OpenAI Responses API kept compatible",
    "Frontend: static SPA (HTML/CSS/JS), KaTeX for math, no framework runtime",
    "Persistence: JSON file store (data/store/*.json) - users, notes, exams, "
    "documents, planner, classrooms, feedback",
    "Auth: PBKDF2-SHA256 hashing, HS256 JWT access (15 min) + refresh (7 day) tokens",
]:
    pdf.bullet(b)
pdf.h2("4.2 Key backend components")
for b in [
    "WebServer.java - all REST endpoints, static file serving, CORS, auth guards",
    "AIProvider / GeminiService - provider routing, caching, streaming, vision",
    "HybridAssessment.java - Mode A (notes-based) vs Mode B (AI-knowledge) quiz/exam "
    "generation with strict-JSON parsing and graceful fallback",
    "NotesService / FeedbackService / ExamService / DocumentService / PlannerService / "
    "ClassroomService - per-user JSON persistence",
    "AuthService - registration, login, refresh, reset tokens, roles, rate limiting",
]:
    pdf.bullet(b)
pdf.h2("4.3 Deployment")
pdf.body(
    "Frontend hosted free on Firebase Hosting (educore-ai-9dc3c.web.app). "
    "Backend runs as a Dockerized Java service; currently exposed publicly through a "
    "Cloudflare quick tunnel to the development machine, with a watchdog script "
    "(scripts/start-educore.ps1) that auto-starts the backend and tunnel at logon, "
    "patches the frontend API base URL and redeploys hosting automatically."
)

# ---------- 5. Future plans ----------
pdf.add_page()
pdf.h1("5. Future Plans")
for b in [
    "Permanent cloud hosting - migrate the backend to a managed container platform "
    "(Cloud Run / Render / Koyeb) with a Cloud Storage or PostgreSQL database so the "
    "site is live 24/7 independent of any personal computer.",
    "Real database - replace JSON file stores with PostgreSQL (users, notes, exams, "
    "feedback) for concurrent users, transactions and scalability.",
    "Email delivery - integrate SMTP/SendGrid for real password-reset links and "
    "notifications (currently dev-mode tokens).",
    "Voice input/output - browser SpeechRecognition for hands-free questions and "
    "text-to-speech answers.",
    "Advanced analytics - per-topic mastery scores, spaced-repetition revision "
    "scheduling and personalized study roadmaps from quiz/exam history.",
    "Teacher tools - question-bank management, graded assignments, analytics "
    "dashboards and official curriculum mapping.",
    "Mobile apps - wrap the PWA or build native iOS/Android clients against the same API.",
    "Model fine-tuning - move beyond prompt-level feedback to actual preference "
    "tuning on highly-rated Q&A pairs once sufficient data exists.",
    "Offline support - cache notes and quizzes for low-connectivity students.",
    "Institutional deployment - SSO (Google/Classroom), roster sync and admin "
    "controls for schools and colleges.",
]:
    pdf.bullet(b)

# ---------- 6. Honest limitations ----------
pdf.add_page()
pdf.h1("6. Current Limitations (Honest Assessment)")
for b in [
    "Persistence uses JSON files, not a relational database - fine for a demo/small "
    "cohort, not for production scale.",
    "Password reset exposes a development token in the API response - no SMTP yet.",
    "Conversation memory and some caches are in-memory - cleared on restart.",
    "Public availability depends on a tunnel to a local machine - not 24/7 yet.",
    "No formal admin dashboard UI (role checks exist server-side).",
    "Voice input is not implemented.",
    "Long-answer questions map to the short-answer engine type (no dedicated essay "
    "rubric scoring yet).",
]:
    pdf.bullet(b)

pdf.output(OUT)
print("written:", OUT)
