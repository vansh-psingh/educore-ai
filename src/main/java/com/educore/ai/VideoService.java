package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Lecture Video Finder backend.
 *
 * Builds a targeted YouTube query from the student's subject/topic/level/
 * language inputs. When YOUTUBE_API_KEY is configured it calls the YouTube
 * Data API v3 and returns real videos (title, channel, thumbnail, duration).
 * Without a key it returns smart search links + an embed-search query so the
 * feature still works - the frontend embeds YouTube's own search playlist
 * player, which needs no API key.
 */
public final class VideoService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private VideoService() {
    }

    private static String env(String key) {
        return Env.get(key);
    }

    /** Combines form inputs into one targeted search phrase. */
    public static String buildQuery(JsonNode req) {
        String subject = req.path("subject").asText("").trim();
        String topic = req.path("topic").asText("").trim();
        String level = req.path("level").asText("").trim();
        String lang = req.path("language").asText("English").trim();
        String format = req.path("format").asText("").trim();

        String core = (subject + " " + topic).trim();
        if (core.isEmpty()) core = subject.isEmpty() ? topic : subject;

        StringBuilder q = new StringBuilder();
        if (!core.isEmpty()) q.append(core).append(' ');
        if (!format.isEmpty()) q.append(format).append(' ');
        if (!level.isEmpty()) q.append(level).append(' ');
        q.append("lecture");
        if (!lang.isEmpty() && !"English".equalsIgnoreCase(lang)) {
            q.append(" in ").append(lang);
        }
        return q.toString().trim();
    }

    public static ObjectNode search(JsonNode req) throws Exception {
        String query = buildQuery(req);
        if (query.isEmpty())
            throw new IllegalArgumentException("Enter a subject or topic to search.");

        String apiKey = env("YOUTUBE_API_KEY");
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return apiSearch(query, apiKey.trim());
            } catch (Exception e) {
                // fall through to link mode rather than failing the request
                System.err.println("[videos] YouTube API failed, using link mode: " + e.getMessage());
            }
        }
        return linkMode(query, req);
    }

    // ================= YouTube Data API v3 =================

    private static ObjectNode apiSearch(String query, String apiKey) throws Exception {
        String enc = URLEncoder.encode(query, StandardCharsets.UTF_8);
        String searchUrl = "https://www.googleapis.com/youtube/v3/search"
                + "?part=snippet&type=video&videoEmbeddable=true&maxResults=4"
                + "&order=relevance&q=" + enc + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);

        HttpResponse<String> res = HTTP.send(
                HttpRequest.newBuilder(URI.create(searchUrl)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200)
            throw new IllegalStateException("YouTube API HTTP " + res.statusCode());

        JsonNode root = MAPPER.readTree(res.body());
        JsonNode items = root.path("items");
        if (!items.isArray() || items.isEmpty())
            throw new IllegalStateException("No videos found");

        // second call: durations via videos.list (search doesn't return them)
        StringBuilder ids = new StringBuilder();
        for (JsonNode it : items) {
            if (ids.length() > 0) ids.append(',');
            ids.append(it.path("id").path("videoId").asText(""));
        }
        java.util.Map<String, String> durations = new java.util.HashMap<>();
        try {
            String vurl = "https://www.googleapis.com/youtube/v3/videos"
                    + "?part=contentDetails&id=" + URLEncoder.encode(ids.toString(), StandardCharsets.UTF_8)
                    + "&key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8);
            HttpResponse<String> vres = HTTP.send(
                    HttpRequest.newBuilder(URI.create(vurl)).timeout(TIMEOUT).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (vres.statusCode() == 200) {
                for (JsonNode v : MAPPER.readTree(vres.body()).path("items")) {
                    durations.put(v.path("id").asText(""),
                            v.path("contentDetails").path("duration").asText(""));
                }
            }
        } catch (Exception ignored) { /* durations optional */ }

        ObjectNode out = MAPPER.createObjectNode();
        out.put("mode", "api");
        out.put("query", query);
        ArrayNode videos = out.putArray("videos");
        for (JsonNode it : items) {
            String vid = it.path("id").path("videoId").asText("");
            if (vid.isEmpty()) continue;
            JsonNode sn = it.path("snippet");
            ObjectNode v = videos.addObject();
            v.put("id", vid);
            v.put("title", sn.path("title").asText(""));
            v.put("channel", sn.path("channelTitle").asText(""));
            v.put("thumbnail", sn.path("thumbnails").path("medium")
                    .path("url").asText(sn.path("thumbnails").path("default").path("url").asText("")));
            v.put("duration", formatDuration(durations.getOrDefault(vid, "")));
            v.put("published", sn.path("publishedAt").asText("").substring(0, Math.min(10, sn.path("publishedAt").asText("").length())));
            v.put("url", "https://www.youtube.com/watch?v=" + vid);
            v.put("embed", "https://www.youtube-nocookie.com/embed/" + vid);
        }
        return out;
    }

    /** PT1H2M3S -> "1:02:03"; PT4M5S -> "4:05". */
    private static String formatDuration(String iso) {
        if (iso == null || iso.isBlank()) return "";
        try {
            java.time.Duration d = java.time.Duration.parse(iso);
            long s = d.getSeconds();
            long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
            return h > 0 ? String.format("%d:%02d:%02d", h, m, sec)
                         : String.format("%d:%02d", m, sec);
        } catch (Exception e) {
            return "";
        }
    }

    // ================= no-key fallback: smart search links =================

    /**
     * Without an API key we cannot fetch real video metadata. Instead we
     * return curated query variations; the frontend renders them as cards
     * whose "Watch Here" embeds YouTube's own search-results player
     * (listType=search) - real playable lectures, zero API dependency.
     */
    private static ObjectNode linkMode(String query, JsonNode req) {
        String lang = req.path("language").asText("English").trim();
        String subject = req.path("subject").asText("").trim();
        String topic = req.path("topic").asText("").trim();
        String level = req.path("level").asText("").trim();
        String format = req.path("format").asText("").trim();

        ArrayNode queries = MAPPER.createArrayNode();
        queries.add(query);
        queries.add(query + " lecture");
        queries.add(query + " explained in detail");
        if (!"English".equalsIgnoreCase(lang) && !lang.isBlank())
            queries.add((subject + " " + topic).trim() + " " + lang + " lecture");
        queries.add(query + " " + format);

        ObjectNode out = MAPPER.createObjectNode();
        out.put("mode", "links");
        out.put("query", query);
        out.put("embed_query", query);
        ArrayNode results = out.putArray("results");
        int i = 0;
        for (JsonNode q : queries) {
            String qs = q.asText().trim();
            if (qs.isEmpty()) continue;
            ObjectNode r = results.addObject();
            r.put("id", "s" + (++i));
            r.put("title", qs);
            r.put("channel", "YouTube search");
            r.put("duration", "");
            r.put("query", qs);
            r.put("url", "https://www.youtube.com/results?search_query="
                    + URLEncoder.encode(qs, StandardCharsets.UTF_8));
            if (results.size() >= 4) break;
        }
        // expose raw fields for the frontend's profile-based suggestions
        out.put("subject", subject);
        out.put("topic", topic);
        out.put("level", level);
        out.put("language", lang);
        out.put("format", format);
        return out;
    }
}
