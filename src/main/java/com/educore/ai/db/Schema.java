package com.educore.ai.db;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

/**
 * Schema migrations. Each migration runs once, recorded in schema_migrations.
 * SQL is plain PostgreSQL DDL.
 *
 * Design note: every entity table keeps the complete original JSON node in
 * `payload jsonb` (lossless round-trip through the Store adapter) plus typed,
 * indexed columns for the fields used by queries, admin stats and joins.
 */
public final class Schema {

    private Schema() {
    }

    private record Migration(int version, String name, String sql) {}

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "initial-schema", """
                    CREATE TABLE IF NOT EXISTS schema_migrations (
                        version     int PRIMARY KEY,
                        name        text NOT NULL,
                        applied_at  timestamptz NOT NULL DEFAULT now()
                    );

                    -- ============ users (auth + roles) ============
                    CREATE TABLE IF NOT EXISTS app_users (
                        id            text PRIMARY KEY,
                        email         text NOT NULL UNIQUE,
                        name          text NOT NULL DEFAULT '',
                        role          text NOT NULL DEFAULT 'student'
                                      CHECK (role IN ('student','teacher','admin')),
                        status        text NOT NULL DEFAULT 'active'
                                      CHECK (status IN ('active','suspended','deactivated')),
                        pass_hash     text NOT NULL DEFAULT '',
                        payload       jsonb,
                        seq           bigserial,
                        created_at    timestamptz NOT NULL DEFAULT now(),
                        updated_at    timestamptz NOT NULL DEFAULT now(),
                        last_login_at timestamptz
                    );
                    CREATE INDEX IF NOT EXISTS idx_users_role   ON app_users(role);
                    CREATE INDEX IF NOT EXISTS idx_users_status ON app_users(status);
                    CREATE INDEX IF NOT EXISTS idx_users_email  ON app_users(lower(email));

                    -- ============ password reset tokens ============
                    CREATE TABLE IF NOT EXISTS password_reset_tokens (
                        id          bigserial PRIMARY KEY,
                        user_id     text NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                        token_hash  text NOT NULL UNIQUE,
                        expires_at  timestamptz NOT NULL,
                        used_at     timestamptz,
                        created_ip  text,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_prt_user ON password_reset_tokens(user_id);

                    -- ============ audit log ============
                    CREATE TABLE IF NOT EXISTS audit_log (
                        id          bigserial PRIMARY KEY,
                        actor_id    text,
                        actor_email text,
                        action      text NOT NULL,
                        target      text,
                        detail      jsonb,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_audit_actor   ON audit_log(actor_id);
                    CREATE INDEX IF NOT EXISTS idx_audit_created ON audit_log(created_at DESC);

                    -- ============ notes ============
                    CREATE TABLE IF NOT EXISTS notes (
                        id          text PRIMARY KEY,
                        owner_id    text REFERENCES app_users(id) ON DELETE SET NULL,
                        title       text NOT NULL DEFAULT '',
                        subject     text NOT NULL DEFAULT '',
                        topic       text NOT NULL DEFAULT '',
                        language    text NOT NULL DEFAULT 'English',
                        level       text NOT NULL DEFAULT '',
                        content     text NOT NULL DEFAULT '',
                        payload     jsonb,
                        seq         bigserial,
                        created_at  timestamptz NOT NULL DEFAULT now(),
                        updated_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_notes_owner   ON notes(owner_id);
                    CREATE INDEX IF NOT EXISTS idx_notes_updated ON notes(updated_at DESC);

                    -- ============ feedback ============
                    CREATE TABLE IF NOT EXISTS feedback_items (
                        id          text PRIMARY KEY,
                        user_id     text REFERENCES app_users(id) ON DELETE SET NULL,
                        rating      int  NOT NULL DEFAULT 0,
                        question    text NOT NULL DEFAULT '',
                        answer      text NOT NULL DEFAULT '',
                        comment     text NOT NULL DEFAULT '',
                        payload     jsonb,
                        seq         bigserial,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_feedback_user ON feedback_items(user_id);

                    -- ============ planner tasks ============
                    CREATE TABLE IF NOT EXISTS planner_tasks (
                        id          text PRIMARY KEY,
                        owner_id    text REFERENCES app_users(id) ON DELETE SET NULL,
                        title       text NOT NULL DEFAULT '',
                        subject     text NOT NULL DEFAULT '',
                        status      text NOT NULL DEFAULT 'pending',
                        priority    text NOT NULL DEFAULT 'medium',
                        due_date    text NOT NULL DEFAULT '',
                        payload     jsonb,
                        seq         bigserial,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_tasks_owner ON planner_tasks(owner_id);
                    CREATE INDEX IF NOT EXISTS idx_tasks_status ON planner_tasks(status);

                    -- ============ exams ============
                    CREATE TABLE IF NOT EXISTS exams (
                        id           text PRIMARY KEY,
                        owner_id     text REFERENCES app_users(id) ON DELETE SET NULL,
                        title        text NOT NULL DEFAULT '',
                        subject      text NOT NULL DEFAULT '',
                        topic        text NOT NULL DEFAULT '',
                        level        text NOT NULL DEFAULT '',
                        difficulty   text NOT NULL DEFAULT '',
                        duration_min int  NOT NULL DEFAULT 15,
                        total_marks  int  NOT NULL DEFAULT 0,
                        payload      jsonb NOT NULL,
                        seq          bigserial,
                        created_at   timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_exams_owner ON exams(owner_id);

                    -- ============ exam attempts ============
                    CREATE TABLE IF NOT EXISTS exam_attempts (
                        id          text PRIMARY KEY,
                        seq         bigserial,
                        exam_id     text REFERENCES exams(id) ON DELETE CASCADE,
                        user_id     text REFERENCES app_users(id) ON DELETE SET NULL,
                        score_pct   int NOT NULL DEFAULT 0,
                        marks       text NOT NULL DEFAULT '',
                        payload     jsonb NOT NULL,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_attempts_user ON exam_attempts(user_id);
                    CREATE INDEX IF NOT EXISTS idx_attempts_exam ON exam_attempts(exam_id);
                    CREATE INDEX IF NOT EXISTS idx_attempts_seq  ON exam_attempts(seq);

                    -- ============ classrooms ============
                    CREATE TABLE IF NOT EXISTS classrooms (
                        id          text PRIMARY KEY,
                        code        text NOT NULL UNIQUE,
                        owner_id    text REFERENCES app_users(id) ON DELETE SET NULL,
                        name        text NOT NULL DEFAULT '',
                        teacher     text NOT NULL DEFAULT '',
                        payload     jsonb NOT NULL,
                        seq         bigserial,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_class_code ON classrooms(code);

                    CREATE TABLE IF NOT EXISTS classroom_members (
                        classroom_id text NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
                        user_id      text NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                        display_name text NOT NULL DEFAULT '',
                        joined_at    timestamptz NOT NULL DEFAULT now(),
                        PRIMARY KEY (classroom_id, user_id)
                    );

                    -- ============ documents ============
                    CREATE TABLE IF NOT EXISTS documents (
                        id              text PRIMARY KEY,
                        owner_id        text REFERENCES app_users(id) ON DELETE SET NULL,
                        name            text NOT NULL DEFAULT '',
                        ext             text NOT NULL DEFAULT '',
                        mime            text NOT NULL DEFAULT '',
                        size_bytes      bigint NOT NULL DEFAULT 0,
                        text_length     int NOT NULL DEFAULT 0,
                        extracted_text  text,
                        payload         jsonb NOT NULL,
                        seq             bigserial,
                        created_at      timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_docs_owner ON documents(owner_id);

                    -- ============ chat messages (persistent memory) ============
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id          bigserial PRIMARY KEY,
                        session_id  text NOT NULL,
                        user_id     text REFERENCES app_users(id) ON DELETE CASCADE,
                        role        text NOT NULL CHECK (role IN ('user','model')),
                        text        text NOT NULL,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_chat_session ON chat_messages(session_id, id);
                    CREATE INDEX IF NOT EXISTS idx_chat_user    ON chat_messages(user_id);

                    -- ============ notifications ============
                    CREATE TABLE IF NOT EXISTS notifications (
                        id          bigserial PRIMARY KEY,
                        user_id     text REFERENCES app_users(id) ON DELETE CASCADE,
                        audience    text NOT NULL DEFAULT 'platform' CHECK (audience IN ('platform','user')),
                        title       text NOT NULL,
                        body        text NOT NULL DEFAULT '',
                        created_by  text,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_notif_user ON notifications(user_id, created_at DESC);
                    CREATE INDEX IF NOT EXISTS idx_notif_aud  ON notifications(audience, created_at DESC);

                    -- ============ long-answer submissions ============
                    CREATE TABLE IF NOT EXISTS essay_submissions (
                        id             text PRIMARY KEY,
                        user_id        text NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
                        exam_id        text,
                        question       text NOT NULL,
                        answer         text NOT NULL,
                        subject        text NOT NULL DEFAULT '',
                        level          text NOT NULL DEFAULT '',
                        max_marks      numeric(6,2) NOT NULL DEFAULT 10,
                        rubric         jsonb,
                        evaluation     jsonb,
                        status         text NOT NULL DEFAULT 'pending'
                                       CHECK (status IN ('pending','ai_evaluated','teacher_reviewed')),
                        final_marks    numeric(6,2),
                        final_feedback text,
                        reviewed_by    text,
                        seq            bigserial,
                        created_at     timestamptz NOT NULL DEFAULT now(),
                        updated_at     timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_essay_user   ON essay_submissions(user_id);
                    CREATE INDEX IF NOT EXISTS idx_essay_status ON essay_submissions(status);
                    """),
            new Migration(2, "learning-intelligence", """
                    -- ============ quiz sessions (server-side key for honest scoring) ============
                    CREATE TABLE IF NOT EXISTS quiz_sessions (
                        id          text PRIMARY KEY,
                        owner_id    text REFERENCES app_users(id) ON DELETE CASCADE,
                        title       text NOT NULL DEFAULT '',
                        subject     text NOT NULL DEFAULT '',
                        topic       text NOT NULL DEFAULT '',
                        payload     jsonb NOT NULL,
                        seq         bigserial,
                        created_at  timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_quizsess_user ON quiz_sessions(owner_id);

                    -- ============ per-question attempt responses (mistake intelligence) ============
                    CREATE TABLE IF NOT EXISTS attempt_responses (
                        id           text PRIMARY KEY,
                        owner_id     text REFERENCES app_users(id) ON DELETE CASCADE,
                        source       text NOT NULL DEFAULT 'quiz'
                                     CHECK (source IN ('quiz','exam','essay')),
                        ref_id       text NOT NULL DEFAULT '',
                        topic        text NOT NULL DEFAULT '',
                        subject      text NOT NULL DEFAULT '',
                        q_type       text NOT NULL DEFAULT '',
                        question     text NOT NULL DEFAULT '',
                        given        text NOT NULL DEFAULT '',
                        correct      text NOT NULL DEFAULT '',
                        marks        int NOT NULL DEFAULT 1,
                        earned       int NOT NULL DEFAULT 0,
                        correct_flag int NOT NULL DEFAULT 0,
                        error_type   text NOT NULL DEFAULT '',
                        payload      jsonb,
                        seq          bigserial,
                        created_at   timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE INDEX IF NOT EXISTS idx_resp_user   ON attempt_responses(owner_id);
                    CREATE INDEX IF NOT EXISTS idx_resp_topic  ON attempt_responses(owner_id, topic);
                    CREATE INDEX IF NOT EXISTS idx_resp_source ON attempt_responses(owner_id, source);

                    -- ============ spaced-repetition revision items ============
                    CREATE TABLE IF NOT EXISTS revision_items (
                        id              text PRIMARY KEY,
                        owner_id        text REFERENCES app_users(id) ON DELETE CASCADE,
                        subject         text NOT NULL DEFAULT '',
                        topic           text NOT NULL DEFAULT '',
                        status          text NOT NULL DEFAULT 'pending'
                                        CHECK (status IN ('pending','reviewing','mastered')),
                        ease            text NOT NULL DEFAULT '2.5',
                        interval_days   int  NOT NULL DEFAULT 1,
                        reps            int  NOT NULL DEFAULT 0,
                        lapses          int  NOT NULL DEFAULT 0,
                        due_date        text NOT NULL DEFAULT '',
                        last_reviewed   timestamptz,
                        payload         jsonb NOT NULL,
                        seq             bigserial,
                        created_at      timestamptz NOT NULL DEFAULT now(),
                        updated_at      timestamptz NOT NULL DEFAULT now()
                    );
                    CREATE UNIQUE INDEX IF NOT EXISTS idx_rev_user_topic
                        ON revision_items(owner_id, subject, topic);
                    CREATE INDEX IF NOT EXISTS idx_rev_due ON revision_items(owner_id, due_date);
                    """)
    );

    /** Applies all pending migrations. Returns number applied. */
    public static int migrate() throws Exception {
        int applied = 0;
        try (Connection c = Db.conn()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS schema_migrations ("
                        + "version int PRIMARY KEY, name text NOT NULL, "
                        + "applied_at timestamptz NOT NULL DEFAULT now())");
            }
            for (Migration m : MIGRATIONS) {
                boolean done;
                try (var ps = c.prepareStatement(
                        "SELECT 1 FROM schema_migrations WHERE version = ?")) {
                    ps.setInt(1, m.version);
                    try (var rs = ps.executeQuery()) {
                        done = rs.next();
                    }
                }
                if (done) continue;
                for (String stmt : m.sql().split(";")) {
                    String s = stmt.trim();
                    if (!s.isEmpty()) {
                        try (Statement st = c.createStatement()) {
                            st.execute(s);
                        }
                    }
                }
                try (var ps = c.prepareStatement(
                        "INSERT INTO schema_migrations(version, name) VALUES (?, ?)")) {
                    ps.setInt(1, m.version);
                    ps.setString(2, m.name);
                    ps.executeUpdate();
                }
                applied++;
            }
            c.commit();
        }
        return applied;
    }
}
