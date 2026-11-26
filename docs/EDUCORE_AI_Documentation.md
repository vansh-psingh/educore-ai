# EDUCORE AI — Project Documentation

**Learn Smarter. Understand Deeper.**

Java 17 backend · Vanilla JS single-page app · Gemini / OpenAI AI · JSON + PostgreSQL storage
Hosted on Firebase Hosting · Cloudflare tunnel to local backend

---

## 1. Project Overview

EDUCORE AI is a full-stack AI-powered learning platform for students. It combines a conversational AI tutor, automated study-notes generation, text summarization, quiz and exam engines, a persistent document library, a study planner, classrooms, learning analytics (mistakes, readiness, learning DNA, concept maps, spaced revision) and a lecture video finder — all inside one responsive web application.

### Technology Stack

- **Backend:** Java 17, JDK built-in HttpServer, Maven build, ~60 service classes.
- **Frontend:** vanilla JavaScript SPA (hash router), custom CSS design system, KaTeX math rendering.
- **AI:** Google Gemini (default) with OpenAI-compatible fallback, streaming responses, per-user rate limiting.
- **Storage:** atomic JSON file store locally; Neon PostgreSQL in production (same service layer).
- **Security:** PBKDF2 password hashing, JWT access/refresh tokens, server-verified CAPTCHA, HMAC-hashed OTPs (email + SMS).
- **Deployment:** Firebase Hosting (static SPA) + Cloudflare tunnel to the Java backend.

---

## 2. Problem Statement

Students preparing for exams and coursework face several recurring problems that existing tools only partially solve:

- **Fragmented study material** — long chapters, PDFs and lecture notes are hard to digest; students lack quick, level-appropriate summaries.
- **No honest self-assessment** — passive reading gives no feedback. Students cannot tell whether they truly understood a topic until the exam.
- **One-size-fits-all AI** — generic quizzes and tutors ignore the student's actual level, language, past mistakes and weak topics.
- **Lecture discovery is manual** — good lectures exist on YouTube but finding the right one for a specific chapter, level and language is tedious.
- **No learning memory** — progress is invisible; students repeat the same mistakes because nothing tracks them over time.
- **Cost barrier** — many study apps are expensive, ad-heavy, or lock core features behind subscriptions.

The goal was a single, zero-cost platform that a student can use end-to-end: ask, read, practice, track mistakes, plan revision and watch the right lecture — without leaving the app.

---

## 3. Solution Statement

EDUCORE AI addresses each problem with a dedicated module, all sharing one authenticated, per-user data model.

### 3.1 AI Tutor & Content Tools

- **AI Tutor** — conversational tutor with subject/level/language context, streaming answers and KaTeX math.
- **Notes Generator** — structured notes generation saved to a searchable personal library (auto-saved, editable, downloadable).
- **Summarizer** — long-material summarizer with short/standard/detailed lengths.
- **Assessment** — quiz and exam engines with server-side scoring; clients cannot fabricate results.

### 3.2 Learning Intelligence

- **Insights** — every answered question is recorded; mistake analysis, exam-readiness score, learning DNA and a concept map are computed from real activity only — no fabricated metrics.
- **Revision** — weak topics automatically seed spaced-repetition revision items with review scheduling.
- **Activity** — study streak, active-day tracking and a real recent-activity feed on the dashboard.

### 3.3 Lecture Video Finder

- **Video Finder** — subject/topic/level/language-aware YouTube search. With an API key it returns real videos; without one it embeds YouTube's own search player — the feature works with zero keys.
- **Player** — in-app theatre player with autoplay, channel info, Open-in-YouTube and a study-notes scratchpad that saves directly to the notes library.

### 3.4 Account & Personalization

- **Profile** — mandatory student profile (gender, age, qualification, course, academic year), avatar upload, theme persisted server-side, mobile-number verification via OTP, notification preferences synced to the backend.
- **Security** — JWT sessions with refresh, CAPTCHA on auth endpoints, HMAC-hashed single-use OTPs, per-user AI rate limiting, ownership checks on every resource.

---

## 4. System Architecture

### Request flow

```
Browser (SPA, hash router)
   |  HTTPS / fetch + Bearer JWT
   v
Cloudflare Tunnel  ->  Java HttpServer (:8080)
   |-- AuthService ......... PBKDF2, JWT, CAPTCHA, OTP
   |-- Profile/Mobile ...... profile fields, SMS OTP
   |-- AIProvider ........... Gemini / OpenAI (shared HttpClient)
   |-- QuizSessions/Exams ... server-side scoring, idempotent submit
   |-- Responses ............ batched analytics writes
   |-- Insights/Revision .... readiness, DNA, concept map, revision
   |-- Notes/Documents ...... per-user libraries
   |-- Planner/Classrooms ... tasks, completion stamps, classes
   |-- VideoService ......... YouTube Data API / smart-search fallback
   v
Store: JSON files (atomic tmp+move writes)  OR  Neon PostgreSQL
       (EntityStore, schema v4, per-user rows)
```

### Key design decisions

- **Simple deployment** — single JVM process serves API + static assets; horizontal scale not needed at current load.
- **Cost safety** — all expensive AI calls are authenticated, quota-limited (30/min/user) and fail honestly when no key is configured; never silently switch to paid services.
- **Correctness** — submissions are idempotent and scoring happens server-side; analytics writes are batched to avoid O(n²) file rewrites.
- **Performance** — TtlCache with LRU-style eviction, cached .env/prompts, in-memory static assets with cache headers.

---

## 5. Feature Set

### Learning

- AI Tutor — contextual chat, streaming, vision input, conversation history
- Notes Generator — AI notes with LaTeX math, library with search/sort/edit/delete
- Summarizer — paste material, get short/standard/detailed summaries
- Quiz Generator — topic/subject/level/difficulty, server-scored, per-question review
- Exams — timed assessments with full history and review
- Essay evaluation — long-answer scoring with rubric feedback
- Study Planner — tasks with priorities, due dates, AI timetable generation
- Classrooms — create/join classes, announcements, materials
- Document Library — upload (≤15 MB), ask questions about docs, summarize
- Lecture Video Finder — smart YouTube search + in-app player + notes scratchpad

### Analytics & personalization

- Mistake analysis, exam readiness score, learning DNA, concept map
- Spaced revision queue seeded from weak topics
- Study streak + active-day activity feed
- Student profile with completion tracking, avatar, theme sync
- Mobile OTP verification (SMS gateway adapter, log mode for dev)
- Notification preferences (reminders, progress, quiz alerts) + browser alerts

### Platform

- Email OTP registration/login, password reset, change password
- Admin panel — users, audit log, announcements
- Global search across pages and notes
- Dark/light themes persisted per user

---

## 6. Quality & Engineering Practices

- **Tests** — 18 automated JUnit tests (assessment engine, essay service, hybrid assessment, TTL cache) — all passing.
- **Audit** — full-repository audit completed: 8 bugs fixed (upload stack overflow, cross-user data leak, duplicate submissions, O(n²) writes), rate limiting added to all AI-cost endpoints, atomic storage writes, race conditions closed.
- **Security** — PBKDF2 120k iterations, JWT 15 min/7 d, server-verified CAPTCHA, hashed single-use OTPs with attempt/expiry/cooldown limits, magic-byte avatar validation, ownership checks everywhere.
- **UX** — perceived-performance work: skeleton loaders, static asset caching, deduplicated profile fetches, error boundaries, reduced-motion support.
- **Cost** — zero recurring cost: free-tier Gemini quota, JSON/Postgres storage, Firebase free hosting, no paid APIs required for any core feature.

---

## 7. Future Plans

### Near term

- Wire the stored notification preferences to an actual scheduler that fires browser/push reminders at the chosen time.
- Add `YOUTUBE_API_KEY` for direct video results (real thumbnails, durations, channels) — adapter already built, just needs the key.
- Activate a real SMS provider (MSG91) for mobile OTP once credits/DLT registration are available; adapter is ready.
- Offline/PWA support: service worker, cached notes, queued quiz submissions for low-connectivity students.

### Medium term

- Backend-driven global search (notes, documents, planner) with indexing instead of client-side queries.
- Parent/teacher dashboards: shared classrooms analytics, assignment distribution, progress reports.
- Curriculum packs: board-specific (CBSE/ICSE/state) topic trees mapped to notes, quizzes and lectures.
- Spaced-repetition flashcards generated from the existing mistake/revision pipeline.

### Long term

- Adaptive study paths: the engine reorders topics and difficulty based on readiness and mistake history.
- Multi-language UI (the AI already answers in 8+ languages; extend to interface strings).
- Institutional deployment: class rostering, SSO, and per-school analytics for schools and coaching centers.

---

## 8. Repository & Deployment

- **Repository:** github.com/vansh-psingh/educore-ai (private)
- **Live app:** https://educore-ai-9dc3c.web.app

**Run locally:**

```
mvn package
java -jar target/educore-ai-1.0.0.jar web 8080
```

**Frontend deploy:** sync `src/main/resources/static` into `deploy/`, then `firebase deploy --only hosting`.

**Configuration:** `.env` (see `.env.example`); secrets are never committed.
