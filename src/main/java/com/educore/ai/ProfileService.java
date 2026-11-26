package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Per-user student profile with mandatory fields.
 *
 * Stored on the user record inside users.json (round-trips losslessly
 * through the payload jsonb column in PostgreSQL mode). Validation runs
 * on BOTH backend (authoritative) and frontend (UX). Incomplete profiles
 * never block dashboard access - they surface a completion notice.
 */
public final class ProfileService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final Set<String> GENDERS = Set.of(
            "Male", "Female", "Non-binary", "Prefer not to say");
    public static final Set<String> QUALIFICATIONS = Set.of(
            "School Student", "Higher Secondary", "Diploma",
            "Undergraduate", "Postgraduate", "Other");
    public static final Set<String> ACADEMIC_YEARS = Set.of(
            "1st Year", "2nd Year", "3rd Year", "4th Year",
            "5th Year", "Final Year", "Other / Not Applicable");
    public static final List<String> DEFAULT_COURSES = List.of(
            "B.Tech", "BCA", "BBA", "B.Sc", "BA", "MCA", "MBA", "Other");

    private static final String[] FIELDS = {
            "gender", "age", "qualification", "course", "academic_year"};

    /** Optional non-mandatory settings stored on the profile. */
    private static final Set<String> THEMES = Set.of("dark", "light");

    // ================= avatar =================

    private static final Path AVATAR_DIR = Path.of("data", "avatars");
    private static final long MAX_AVATAR_BYTES = 2L * 1024 * 1024; // 2 MB

    /** Small record for a stored avatar image. */
    public record Avatar(byte[] data, String contentType, String fileName) {
    }

    /** Validates + stores an uploaded avatar; returns the fresh profile state. */
    public static ObjectNode saveAvatar(AuthService.AuthUser user, byte[] data)
            throws Exception {
        if (data == null || data.length == 0)
            throw new IllegalArgumentException("No image data received.");
        if (data.length > MAX_AVATAR_BYTES)
            throw new IllegalArgumentException("Image is too large (max 2 MB).");
        String ext = detectImageType(data);
        if (ext == null)
            throw new IllegalArgumentException("Unsupported image type. Use PNG, JPEG or WebP.");

        JsonNode u = findUser(user.id());
        if (u == null) throw new IllegalArgumentException("Account not found.");
        ObjectNode un = (ObjectNode) u.deepCopy();

        // remove any previous avatar file(s) for this user, then write new
        deleteAvatarFiles(user.id());
        Files.createDirectories(AVATAR_DIR);
        String fileName = user.id() + "." + ext;
        Files.write(AVATAR_DIR.resolve(fileName), data);

        un.put("avatar", fileName);
        un.put("avatar_updated", Instant.now().toString());
        persistUser(un);
        AuditService.log(user.id(), user.email(), "avatar_updated", user.id());
        return get(user);
    }

    /** Loads the user's avatar image, or null when none is set. */
    public static Avatar loadAvatar(AuthService.AuthUser user) {
        try {
            JsonNode u = findUser(user.id());
            if (u == null) return null;
            String name = u.path("avatar").asText("");
            // only ever serve files named "<userid>.<ext>" inside AVATAR_DIR
            if (!name.matches(java.util.regex.Pattern.quote(user.id()) + "\\.(png|jpe?g|webp)"))
                return null;
            Path p = AVATAR_DIR.resolve(name).normalize();
            if (!p.startsWith(AVATAR_DIR.normalize()) || !Files.isRegularFile(p)) return null;
            byte[] data = Files.readAllBytes(p);
            String ct = name.endsWith(".png") ? "image/png"
                    : name.endsWith(".webp") ? "image/webp" : "image/jpeg";
            return new Avatar(data, ct, name);
        } catch (Exception e) {
            return null;
        }
    }

    /** Removes the user's avatar; keeps all other profile data intact. */
    public static ObjectNode removeAvatar(AuthService.AuthUser user) throws Exception {
        JsonNode u = findUser(user.id());
        if (u == null) throw new IllegalArgumentException("Account not found.");
        ObjectNode un = (ObjectNode) u.deepCopy();
        deleteAvatarFiles(user.id());
        un.remove("avatar");
        un.put("avatar_updated", Instant.now().toString());
        persistUser(un);
        AuditService.log(user.id(), user.email(), "avatar_removed", user.id());
        return get(user);
    }

    /** Detects PNG/JPEG/WebP by magic bytes; returns file extension or null. */
    private static String detectImageType(byte[] d) {
        if (d.length > 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G')
            return "png";
        if (d.length > 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF)
            return "jpg";
        if (d.length > 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F'
                && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P')
            return "webp";
        return null;
    }

    private static void deleteAvatarFiles(String userId) {
        try {
            if (!Files.isDirectory(AVATAR_DIR)) return;
            try (var stream = Files.list(AVATAR_DIR)) {
                for (Path p : stream.filter(f -> f.getFileName().toString().startsWith(userId + ".")).toList())
                    Files.deleteIfExists(p);
            }
        } catch (IOException ignored) {
        }
    }

    /** Writes a mutated user record back into users.json (keyed by email). */
    static void persistUser(ObjectNode un) {
        // atomic read-modify-write: prevents lost updates between concurrent
        // profile/mobile/theme writes (Store.read/write lock on Store.class)
        synchronized (Store.class) {
            ObjectNode root = Store.obj(Store.read("users.json"));
            root.set(un.path("email").asText().toLowerCase(), un);
            Store.write("users.json", root);
        }
    }

    private ProfileService() {
    }

    /** Course options - configurable via PROFILE_COURSES env (comma-separated). */
    public static List<String> courses() {
        String env = envValue("PROFILE_COURSES");
        if (env == null || env.isBlank()) return DEFAULT_COURSES;
        List<String> out = new ArrayList<>();
        for (String c : env.split(",")) {
            String t = c.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out.isEmpty() ? DEFAULT_COURSES : out;
    }

    /** Returns the user's profile fields + completion status. */
    public static ObjectNode get(AuthService.AuthUser user) {
        JsonNode u = findUser(user.id());
        ObjectNode out = MAPPER.createObjectNode();
        ObjectNode profile = MAPPER.createObjectNode();
        for (String f : FIELDS) {
            profile.put(f, u == null ? "" : u.path(f).asText(""));
        }
        out.set("profile", profile);
        out.set("missing", MAPPER.valueToTree(missingFields(profile)));
        out.put("completion_pct", completionPct(profile));
        out.put("complete", missingFields(profile).isEmpty());
        // options for the frontend controls
        ObjectNode opts = MAPPER.createObjectNode();
        opts.set("gender", MAPPER.valueToTree(new ArrayList<>(GENDERS)));
        opts.set("qualification", MAPPER.valueToTree(new ArrayList<>(QUALIFICATIONS)));
        opts.set("academic_year", MAPPER.valueToTree(new ArrayList<>(ACADEMIC_YEARS)));
        opts.set("courses", MAPPER.valueToTree(courses()));
        out.set("options", opts);
        out.put("has_avatar", u != null && u.hasNonNull("avatar"));
        // mobile verification (private - owner only, via this authenticated endpoint)
        out.put("mobile", u == null ? "" : u.path("mobile").asText(""));
        out.put("mobile_verified_at",
                u == null ? "" : u.path("mobile_verified_at").asText(""));
        out.put("mobile_status", MobileService.status(u));
        out.put("theme", u == null ? "dark" : u.path("theme").asText("dark"));
        return out;
    }

    /**
     * Validates and persists provided fields. Only supplied fields are
     * validated+written; omitted fields keep existing values, so partial
     * updates can never wipe valid data. Returns the fresh profile state.
     */
    public static ObjectNode update(AuthService.AuthUser user, JsonNode req) throws Exception {
        JsonNode u = findUser(user.id());
        if (u == null) throw new IllegalArgumentException("Account not found.");
        ObjectNode un = (ObjectNode) u.deepCopy();

        // validate every supplied field BEFORE writing anything;
        // all five fields are mandatory, so a supplied blank/whitespace
        // value is rejected rather than silently clearing valid data
        for (String f : FIELDS) {
            if (!req.has(f)) continue;
            JsonNode v = req.get(f);
            String s = v == null || v.isNull() ? "" : v.asText("").trim();
            if (s.isEmpty())
                throw new IllegalArgumentException(label(f) + " is required.");
            validate(f, s);
            un.put(f, s);
        }

        // optional theme preference (not part of mandatory completion)
        if (req.has("theme")) {
            String t = req.path("theme").asText("").trim().toLowerCase();
            if (!THEMES.contains(t))
                throw new IllegalArgumentException("Theme must be 'dark' or 'light'.");
            un.put("theme", t);
        }

        un.put("profile_updated", Instant.now().toString());
        persistUser(un);
        AuditService.log(user.id(), user.email(), "profile_updated", user.id());
        return get(user);
    }

    // ================= validation =================

    private static void validate(String field, String value) {
        if (value.isEmpty()) return; // empty = unset; completion logic handles it
        switch (field) {
            case "gender" -> {
                if (!GENDERS.contains(value))
                    throw new IllegalArgumentException("Select a valid gender option.");
            }
            case "age" -> {
                if (!value.matches("\\d+"))
                    throw new IllegalArgumentException("Age must be a whole number between 13 and 100.");
                int age = Integer.parseInt(value);
                if (age < 13 || age > 100)
                    throw new IllegalArgumentException("Age must be between 13 and 100.");
            }
            case "qualification" -> {
                if (!QUALIFICATIONS.contains(value))
                    throw new IllegalArgumentException("Select a valid qualification.");
            }
            case "course" -> {
                if (value.length() > 60)
                    throw new IllegalArgumentException("Course name is too long (max 60 characters).");
            }
            case "academic_year" -> {
                if (!ACADEMIC_YEARS.contains(value))
                    throw new IllegalArgumentException("Select a valid academic year.");
            }
            default -> throw new IllegalArgumentException("Unknown profile field: " + field);
        }
    }

    private static String label(String field) {
        return switch (field) {
            case "gender" -> "Gender";
            case "age" -> "Age";
            case "qualification" -> "Qualification";
            case "course" -> "Course";
            case "academic_year" -> "Academic year";
            default -> field;
        };
    }

    static List<String> missingFields(JsonNode profile) {
        List<String> missing = new ArrayList<>();
        for (String f : FIELDS) {
            if (!isFieldValid(f, profile.path(f).asText(""))) missing.add(f);
        }
        return missing;
    }

    private static boolean isFieldValid(String field, String value) {
        if (value == null || value.isBlank()) return false;
        try {
            validate(field, value.trim());
            return !value.trim().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    static int completionPct(JsonNode profile) {
        int ok = 0;
        for (String f : FIELDS) if (isFieldValid(f, profile.path(f).asText(""))) ok++;
        return (int) Math.round(100.0 * ok / FIELDS.length);
    }

    static JsonNode findUser(String userId) {
        JsonNode users = Store.read("users.json");
        var it = users.fields();
        while (it.hasNext()) {
            var e = it.next();
            if (e.getValue().path("id").asText("").equals(userId)) return e.getValue();
        }
        return null;
    }

    private static String envValue(String key) {
        return Env.get(key);
    }
}
