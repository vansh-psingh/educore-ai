#!/usr/bin/env python3
"""Generate EDUCORE AI project documentation PDF (problem statement,
solution statement, architecture, features, future plans)."""
from fpdf import FPDF

OUT = "docs/EDUCORE_AI_Documentation.pdf"


def tx(s):
    """Map common unicode punctuation to latin-1 for fpdf core fonts."""
    return (s.replace("—", "-").replace("–", "-")
             .replace("“", '"').replace("”", '"')
             .replace("’", "'").replace("‘", "'")
             .replace("…", "...").replace("→", "->")
             .replace("≤", "<=").replace("≥", ">=")
             .replace("×", "x"))

NAVY = (13, 20, 38)
PURPLE = (109, 60, 220)
BLUE = (37, 99, 235)
SLATE = (71, 85, 105)
LIGHT = (241, 245, 249)


class Doc(FPDF):
    def header(self):
        if self.page_no() == 1:
            return
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(*SLATE)
        self.cell(0, 8, "EDUCORE AI - Project Documentation", align="R", new_x="LMARGIN", new_y="NEXT")
        self.ln(2)

    def footer(self):
        self.set_y(-15)
        self.set_font("Helvetica", "I", 8)
        self.set_text_color(*SLATE)
        self.cell(0, 10, f"Page {self.page_no()}", align="C")

    def h1(self, text):
        self.set_font("Helvetica", "B", 16)
        self.set_text_color(*PURPLE)
        self.multi_cell(0, 8, tx(text))
        self.ln(1)

    def h2(self, text):
        self.ln(3)
        self.set_font("Helvetica", "B", 12.5)
        self.set_text_color(*BLUE)
        self.multi_cell(0, 7, tx(text))
        self.ln(1)

    def h3(self, text):
        self.ln(2)
        self.set_font("Helvetica", "B", 10.5)
        self.set_text_color(*NAVY)
        self.multi_cell(0, 6, tx(text))

    def p(self, text):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(40, 50, 70)
        self.multi_cell(0, 5.2, tx(text), new_x="LMARGIN", new_y="NEXT")
        self.ln(1)

    def bullet(self, text, bold_prefix=None):
        self.set_font("Helvetica", "", 10)
        self.set_text_color(40, 50, 70)
        if bold_prefix:
            self.set_font("Helvetica", "B", 10)
            self.write(5.2, tx(bold_prefix))
            self.set_font("Helvetica", "", 10)
        self.multi_cell(0, 5.2, tx("  " + text if not bold_prefix else text), new_x="LMARGIN", new_y="NEXT")

    def code(self, text):
        self.set_fill_color(*LIGHT)
        self.set_font("Courier", "", 8.5)
        self.set_text_color(30, 40, 60)
        self.multi_cell(0, 4.6, tx(text), fill=True, new_x="LMARGIN", new_y="NEXT")
        self.ln(1)


def main():
    pdf = Doc()
    pdf.set_auto_page_break(True, 20)
    pdf.add_page()

    # ---------- cover ----------
    pdf.ln(30)
    pdf.set_font("Helvetica", "B", 30)
    pdf.set_text_color(*PURPLE)
    pdf.cell(0, 14, "EDUCORE AI", align="C", new_x="LMARGIN", new_y="NEXT")
    pdf.set_font("Helvetica", "", 13)
    pdf.set_text_color(*SLATE)
    pdf.cell(0, 8, "Learn Smarter. Understand Deeper.", align="C", new_x="LMARGIN", new_y="NEXT")
    pdf.ln(6)
    pdf.set_draw_color(*PURPLE)
    pdf.set_line_width(0.6)
    pdf.line(60, pdf.get_y(), 150, pdf.get_y())
    pdf.ln(8)
    pdf.set_font("Helvetica", "", 11)
    pdf.set_text_color(60, 70, 90)
    pdf.multi_cell(0, 6,
        "Project Documentation\nProblem Statement - Solution Statement\n"
        "System Architecture - Feature Set - Roadmap",
        align="C")
    pdf.ln(20)
    pdf.set_font("Helvetica", "I", 10)
    pdf.set_text_color(*SLATE)
    pdf.cell(0, 6, "Java 17 backend  |  Vanilla JS single-page app  |  Gemini / OpenAI AI  |  JSON + PostgreSQL storage", align="C", new_x="LMARGIN", new_y="NEXT")
    pdf.cell(0, 6, "Hosted on Firebase Hosting  |  Cloudflare tunnel to local backend", align="C", new_x="LMARGIN", new_y="NEXT")

    # ---------- 1. overview ----------
    pdf.add_page()
    pdf.h1("1. Project Overview")
    pdf.p(
        "EDUCORE AI is a full-stack AI-powered learning platform for students. It combines a "
        "conversational AI tutor, automated study-notes generation, text summarization, quiz and "
        "exam engines, a persistent document library, a study planner, classrooms, learning "
        "analytics (mistakes, readiness, learning DNA, concept maps, spaced revision) and a "
        "lecture video finder - all inside one responsive web application."
    )
    pdf.h2("Technology Stack")
    pdf.bullet("Backend: Java 17, JDK built-in HttpServer, Maven build, ~60 service classes.", "Backend - ")
    pdf.bullet("Frontend: vanilla JavaScript SPA (hash router), custom CSS design system, KaTeX math rendering.", "Frontend - ")
    pdf.bullet("AI: Google Gemini (default) with OpenAI-compatible fallback, streaming responses, per-user rate limiting.", "AI - ")
    pdf.bullet("Storage: atomic JSON file store locally; Neon PostgreSQL in production (same service layer).", "Storage - ")
    pdf.bullet("Auth: PBKDF2 password hashing, JWT access/refresh tokens, server-verified CAPTCHA, HMAC-hashed OTPs (email + SMS).", "Security - ")
    pdf.bullet("Hosting: Firebase Hosting (static SPA) + Cloudflare tunnel to the Java backend.", "Deployment - ")

    # ---------- 2. problem statement ----------
    pdf.add_page()
    pdf.h1("2. Problem Statement")
    pdf.p(
        "Students preparing for exams and coursework face several recurring problems that existing "
        "tools only partially solve:"
    )
    pdf.bullet("Information overload - long chapters, PDFs and lecture notes are hard to digest; students lack quick, level-appropriate summaries.", "Fragmented study material. ")
    pdf.bullet("Passive reading gives no feedback. Students cannot tell whether they truly understood a topic until the exam.", "No honest self-assessment. ")
    pdf.bullet("Generic quizzes and tutors ignore the student's actual level, language, past mistakes and weak topics.", "One-size-fits-all AI. ")
    pdf.bullet("Good lectures exist on YouTube but finding the right one for a specific chapter, level and language is tedious.", "Lecture discovery is manual. ")
    pdf.bullet("Progress is invisible - students repeat the same mistakes because nothing tracks them over time.", "No learning memory. ")
    pdf.bullet("Many study apps are expensive, ad-heavy, or lock core features behind subscriptions.", "Cost barrier. ")
    pdf.p(
        "The goal was a single, zero-cost platform that a student can use end-to-end: ask, read, "
        "practice, track mistakes, plan revision and watch the right lecture - without leaving the app."
    )

    # ---------- 3. solution ----------
    pdf.h1("3. Solution Statement")
    pdf.p(
        "EDUCORE AI addresses each problem with a dedicated module, all sharing one authenticated, "
        "per-user data model:"
    )
    pdf.h2("3.1 AI Tutor & Content Tools")
    pdf.bullet("Conversational tutor with subject/level/language context, streaming answers and KaTeX math.", "AI Tutor - ")
    pdf.bullet("Structured notes generation saved to a searchable personal library (auto-saved, editable, downloadable).", "Notes Generator - ")
    pdf.bullet("Long-material summarizer with short/standard/detailed lengths.", "Summarizer - ")
    pdf.bullet("Quiz and exam engines with server-side scoring - clients cannot fabricate results.", "Assessment - ")
    pdf.h2("3.2 Learning Intelligence")
    pdf.bullet("Every answered question is recorded; mistake analysis, exam-readiness score, learning DNA and a concept map are computed from real activity only - no fabricated metrics.", "Insights - ")
    pdf.bullet("Weak topics automatically seed spaced-repetition revision items with review scheduling.", "Revision - ")
    pdf.bullet("Study streak, active-day tracking and a real recent-activity feed on the dashboard.", "Activity - ")
    pdf.h2("3.3 Lecture Video Finder")
    pdf.bullet("Subject/topic/level/language-aware YouTube search. With an API key it returns real videos; without one it embeds YouTube's own search player - the feature works with zero keys.", "Video Finder - ")
    pdf.bullet("In-app theatre player with autoplay, channel info, Open-in-YouTube and a study-notes scratchpad that saves directly to the notes library.", "Player - ")
    pdf.h2("3.4 Account & Personalization")
    pdf.bullet("Mandatory student profile (gender, age, qualification, course, academic year), avatar upload, theme persisted server-side, mobile-number verification via OTP, notification preferences synced to the backend.", "Profile - ")
    pdf.bullet("JWT sessions with refresh, CAPTCHA on auth endpoints, HMAC-hashed single-use OTPs, per-user AI rate limiting, ownership checks on every resource.", "Security - ")

    # ---------- 4. architecture ----------
    pdf.add_page()
    pdf.h1("4. System Architecture")
    pdf.h2("Request flow")
    pdf.code(
        "Browser (SPA, hash router)\n"
        "   |  HTTPS / fetch + Bearer JWT\n"
        "   v\n"
        "Cloudflare Tunnel  ->  Java HttpServer (:8080)\n"
        "   |-- AuthService ......... PBKDF2, JWT, CAPTCHA, OTP\n"
        "   |-- Profile/Mobile ...... profile fields, SMS OTP\n"
        "   |-- AIProvider ........... Gemini / OpenAI (shared HttpClient)\n"
        "   |-- QuizSessions/Exams ... server-side scoring, idempotent submit\n"
        "   |-- Responses ............ batched analytics writes\n"
        "   |-- Insights/Revision .... readiness, DNA, concept map, revision\n"
        "   |-- Notes/Documents ...... per-user libraries\n"
        "   |-- Planner/Classrooms ... tasks, completion stamps, classes\n"
        "   |-- VideoService ......... YouTube Data API / smart-search fallback\n"
        "   v\n"
        "Store: JSON files (atomic tmp+move writes)  OR  Neon PostgreSQL\n"
        "       (EntityStore, schema v4, per-user rows)"
    )
    pdf.h2("Key design decisions")
    pdf.bullet("Single JVM process serves API + static assets; horizontal scale not needed at current load.", "Simple deployment. ")
    pdf.bullet("All expensive AI calls are authenticated, quota-limited (30/min/user) and fail honestly when no key is configured - never silently switch to paid services.", "Cost safety. ")
    pdf.bullet("Submissions are idempotent and scoring happens server-side; analytics writes are batched to avoid O(n^2) file rewrites.", "Correctness. ")
    pdf.bullet("TtlCache with LRU-style eviction, cached .env/prompts, in-memory static assets with cache headers.", "Performance. ")

    # ---------- 5. features ----------
    pdf.h1("5. Feature Set")
    pdf.h2("Learning")
    for t in [
        "AI Tutor - contextual chat, streaming, vision input, conversation history",
        "Notes Generator - AI notes with LaTeX math, library with search/sort/edit/delete",
        "Summarizer - paste material, get short/standard/detailed summaries",
        "Quiz Generator - topic/subject/level/difficulty, server-scored, per-question review",
        "Exams - timed assessments with full history and review",
        "Essay evaluation - long-answer scoring with rubric feedback",
        "Study Planner - tasks with priorities, due dates, AI timetable generation",
        "Classrooms - create/join classes, announcements, materials",
        "Document Library - upload (<=15MB), ask questions about docs, summarize",
        "Lecture Video Finder - smart YouTube search + in-app player + notes scratchpad",
    ]:
        pdf.bullet(t)
    pdf.h2("Analytics & personalization")
    for t in [
        "Mistake analysis, exam readiness score, learning DNA, concept map",
        "Spaced revision queue seeded from weak topics",
        "Study streak + active-day activity feed",
        "Student profile with completion tracking, avatar, theme sync",
        "Mobile OTP verification (SMS gateway adapter, log mode for dev)",
        "Notification preferences (reminders, progress, quiz alerts) + browser alerts",
    ]:
        pdf.bullet(t)
    pdf.h2("Platform")
    for t in [
        "Email OTP registration/login, password reset, change password",
        "Admin panel - users, audit log, announcements",
        "Global search across pages and notes",
        "Dark/light themes persisted per user",
    ]:
        pdf.bullet(t)

    # ---------- 6. quality ----------
    pdf.add_page()
    pdf.h1("6. Quality & Engineering Practices")
    pdf.bullet("18 automated JUnit tests (assessment engine, essay service, hybrid assessment, TTL cache) - all passing.", "Tests - ")
    pdf.bullet("Full-repository audit completed: 8 bugs fixed (upload stack overflow, cross-user data leak, duplicate submissions, O(n^2) writes), rate limiting added to all AI-cost endpoints, atomic storage writes, race conditions closed.", "Audit - ")
    pdf.bullet("PBKDF2 120k iterations, JWT 15min/7d, server-verified CAPTCHA, hashed single-use OTPs with attempt/expiry/cooldown limits, magic-byte avatar validation, ownership checks everywhere.", "Security - ")
    pdf.bullet("Perceived-performance work: skeleton loaders, static asset caching, deduplicated profile fetches, error boundaries, reduced-motion support.", "UX - ")
    pdf.bullet("Zero recurring cost: free-tier Gemini quota, JSON/Postgres storage, Firebase free hosting, no paid APIs required for any core feature.", "Cost - ")

    # ---------- 7. future plans ----------
    pdf.h1("7. Future Plans")
    pdf.h2("Near term")
    pdf.bullet("Wire the stored notification preferences to an actual scheduler that fires browser/push reminders at the chosen time.", "Reminder delivery. ")
    pdf.bullet("Add YOUTUBE_API_KEY for direct video results (real thumbnails, durations, channels) - adapter already built, just needs the key.", "Video API. ")
    pdf.bullet("Activate a real SMS provider (MSG91) for mobile OTP once credits/DLT registration are available; adapter is ready.", "SMS OTP. ")
    pdf.bullet("Offline/PWA support: service worker, cached notes, queued quiz submissions for low-connectivity students.", "Offline mode. ")
    pdf.h2("Medium term")
    pdf.bullet("Backend-driven global search (notes, documents, planner) with indexing instead of client-side queries.", "Search. ")
    pdf.bullet("Parent/teacher dashboards: shared classrooms analytics, assignment distribution, progress reports.", "Roles. ")
    pdf.bullet("Curriculum packs: board-specific (CBSE/ICSE/state) topic trees mapped to notes, quizzes and lectures.", "Content. ")
    pdf.bullet("Spaced-repetition flashcards generated from the existing mistake/revision pipeline.", "Flashcards. ")
    pdf.h2("Long term")
    pdf.bullet("Adaptive study paths: the engine reorders topics and difficulty based on readiness and mistake history.", "Adaptive learning. ")
    pdf.bullet("Multi-language UI (the AI already answers in 8+ languages; extend to interface strings).", "i18n. ")
    pdf.bullet("Institutional deployment: class rostering, SSO, and per-school analytics for schools and coaching centers.", "Scale. ")

    # ---------- 8. repo ----------
    pdf.h1("8. Repository & Deployment")
    pdf.p("Repository: github.com/vansh-psingh/educore-ai (private). Live app: https://educore-ai-9dc3c.web.app")
    pdf.p(
        "Run locally: mvn package, then  java -jar target/educore-ai-1.0.0.jar web 8080. "
        "Frontend deploys via firebase deploy --only hosting after syncing "
        "src/main/resources/static into deploy/. Configuration lives in .env "
        "(see .env.example); secrets are never committed."
    )

    pdf.output(OUT)
    print("wrote", OUT)


if __name__ == "__main__":
    main()
