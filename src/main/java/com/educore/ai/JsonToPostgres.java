package com.educore.ai;

import com.educore.ai.db.Db;
import com.educore.ai.db.EntityStore;
import com.educore.ai.db.Schema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One-time (idempotent) migration of data/store/*.json into PostgreSQL.
 *
 * Runs automatically at server start when DATABASE_URL is configured:
 *   1. creates schema_migrations + all tables (Schema.migrate)
 *   2. copies data/store -> data/store-backup-<timestamp> (safety backup)
 *   3. upserts every JSON entity into its relational table
 *
 * Safe to run repeatedly: EntityStore.write diffs and only writes changes.
 * JSON files are left on disk as a cold backup; the application reads from
 * PostgreSQL once configured.
 */
public final class JsonToPostgres {

    private JsonToPostgres() {
    }

    public static void runIfNeeded() {
        if (!Db.configured()) return;
        try {
            int applied = Schema.migrate();
            if (applied > 0) {
                System.out.println("[db] applied " + applied + " schema migration(s)");
            }
            backupStoreDir();
            migrateAll();
            migrateDocumentTexts();
            System.out.println("[db] JSON -> PostgreSQL migration complete (mode=" + Db.mode() + ")");
        } catch (Exception e) {
            System.err.println("[db] migration failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static void backupStoreDir() throws IOException {
        Path src = Store.dir();
        if (!Files.isDirectory(src)) return;
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(java.time.ZoneOffset.UTC).format(Instant.now());
        Path dst = Path.of("data", "store-backup-" + stamp);
        if (Files.exists(dst)) return;
        try (var walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path rel = src.relativize(p);
                Path target = dst.resolve(rel);
                if (Files.isDirectory(p)) Files.createDirectories(target);
                else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        System.out.println("[db] backup written to " + dst);
    }

    private static void migrateAll() throws Exception {
        // users first so FK owner references resolve
        String[] order = {"users.json", "notes.json", "feedback.json", "planner_tasks.json",
                "exams.json", "exam_history.json", "classrooms.json", "documents.json"};

        Set<String> userIds = collectUserIds();
        Set<String> examIds = collectIds("exams.json");

        for (String file : order) {
            EntityStore.Spec spec = EntityStore.SPECS.get(file);
            if (spec == null) continue;
            JsonNode doc = Store.readJson(file); // raw file, not DB
            if (doc.isMissingNode() || doc.isEmpty()) continue;

            doc = sanitize(doc, spec, userIds, examIds);
            backfillIds(doc, spec);
            EntityStore.write(spec, doc);
            System.out.println("[db] migrated " + file);
        }
    }

    /** Loads valid user ids from the migrated app_users table (or JSON fallback). */
    private static Set<String> collectUserIds() {
        Set<String> ids = new HashSet<>();
        try {
            try (var c = Db.conn();
                 var ps = c.prepareStatement("SELECT id FROM app_users");
                 var rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
        } catch (Exception ignored) {
        }
        if (ids.isEmpty()) {
            JsonNode users = Store.readJson("users.json");
            users.fields().forEachRemaining(e -> ids.add(e.getValue().path("id").asText("")));
        }
        ids.remove("");
        return ids;
    }

    private static Set<String> collectIds(String file) {
        Set<String> ids = new HashSet<>();
        JsonNode doc = Store.readJson(file);
        doc.fields().forEachRemaining(e -> ids.add(e.getKey()));
        return ids;
    }

    /**
     * Nulls owner/exam references that point at records which do not exist,
     * so FK constraints cannot fail the migration.
     */
    private static JsonNode sanitize(JsonNode doc, EntityStore.Spec spec,
                                     Set<String> userIds, Set<String> examIds) {
        if (spec.shape() == EntityStore.Shape.MAP) {
            ObjectNode copy = doc.deepCopy();
            Iterator<Map.Entry<String, JsonNode>> it = copy.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                sanitizeNode(e.getValue(), userIds, examIds);
            }
            return copy;
        }
        ObjectNode copy = (ObjectNode) doc.deepCopy();
        ArrayNode arr = copy.withArray(spec.arrayKey());
        for (JsonNode n : arr) sanitizeNode(n, userIds, examIds);
        return copy;
    }

    private static void sanitizeNode(JsonNode n, Set<String> userIds, Set<String> examIds) {
        if (!(n instanceof ObjectNode o)) return;
        for (String f : new String[]{"owner", "user", "owner_id"}) {
            if (o.has(f)) {
                String v = o.path(f).asText("");
                if (!v.isEmpty() && !userIds.contains(v)) o.put(f, "");
            }
        }
        if (o.has("exam_id")) {
            String v = o.path("exam_id").asText("");
            if (!v.isEmpty() && !examIds.contains(v)) o.put("exam_id", "");
        }
    }

    /** Exam attempts historically had no id - assign stable uuids. */
    private static void backfillIds(JsonNode doc, EntityStore.Spec spec) {
        if (spec.shape() != EntityStore.Shape.ARRAY) return;
        JsonNode arr = doc.path(spec.arrayKey());
        if (!arr.isArray()) return;
        for (JsonNode n : arr) {
            if (n instanceof ObjectNode o && o.path("id").asText("").isEmpty()) {
                o.put("id", UUID.randomUUID().toString().substring(0, 8));
            }
        }
    }

    /** Copies per-document extracted text files into documents.extracted_text. */
    private static void migrateDocumentTexts() {
        Path tdir = Store.dir().resolve("texts");
        if (!Files.isDirectory(tdir)) return;
        try (var c = Db.conn()) {
            try (var walk = Files.list(tdir)) {
                for (Path p : walk.toList()) {
                    String name = p.getFileName().toString();
                    if (!name.endsWith(".txt")) continue;
                    String id = name.substring(0, name.length() - 4);
                    String text = Files.readString(p);
                    try (var ps = c.prepareStatement(
                            "UPDATE documents SET extracted_text = ? WHERE id = ?")) {
                        ps.setString(1, text);
                        ps.setString(2, id);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[db] document text migration skipped: " + e.getMessage());
        }
    }
}
