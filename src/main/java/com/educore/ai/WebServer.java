package com.educore.ai;

import com.educore.ai.OpenAIService.OpenAIException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.core.AssessmentEngine;
import edu.core.Quiz;
import edu.core.LearningEngine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * EDUCORE AI web server.
 * Uses the JDK built-in HTTP server - no external web dependencies.
 *
 * GET  /                 -> chat web page (static/index.html)
 * POST /api/chat         -> local JSON-driven chat engine
 * POST /api/ai/chat      -> AI Tutor (OpenAI Responses API)
 * POST /api/ai/notes     -> Study Notes generator
 * POST /api/ai/summary   -> Summarizer
 * POST /api/ai/quiz      -> Quiz generator
 * POST /api/ai/engine    -> Multilingual / Support / Document engines
 *
 * The OpenAI API key is read from backend config (.env / environment)
 * and is never exposed to the browser.
 */
public final class WebServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0])
                : envPort() > 0 ? envPort() : 8080;
        ChatEngine engine = new ChatEngine();
        JsonNode prompts = JsonStore.load("data/openai_prompts.json");

        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        // handle each request on its own thread so AI calls don't block the site
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/") || path.equals("/index.html")) {
                serveStatic(exchange, "static/index.html", "text/html; charset=UTF-8");
            } else if (path.startsWith("/static/")) {
                // serve files from classpath static dir; block traversal
                String rel = path.substring("/static/".length());
                if (rel.contains("..") || rel.isBlank()) {
                    send(exchange, 404, "{\"error\":\"not found\"}", "application/json");
                    return;
                }
                String type = rel.endsWith(".css") ? "text/css; charset=UTF-8"
                        : rel.endsWith(".js") ? "text/javascript; charset=UTF-8"
                        : "application/octet-stream";
                exchange.getResponseHeaders().set("Cache-Control", "public, max-age=300");
                serveStatic(exchange, "static/" + rel, type);
            } else {
                send(exchange, 404, "{\"error\":\"not found\"}", "application/json");
            }
        });

        // ---------- Auth ----------
        server.createContext("/api/auth", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            String ip = exchange.getRemoteAddress().getAddress().getHostAddress();
            if (AuthService.rateLimited(ip)) {
                send(exchange, 429, "{\"error\":\"too many requests, try again later\"}", "application/json");
                return;
            }
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("GET".equals(method) && path.equals("/api/auth/me")) {
                    AuthService.AuthUser u = requireUser(exchange);
                    if (u == null) return;
                    sendJson(exchange, 200, AuthService.me(claimsOf(exchange)));
                    return;
                }
                if (!"POST".equals(method)) {
                    send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
                    return;
                }
                JsonNode req = readJson(exchange);
                switch (path) {
                    case "/api/auth/register" -> {
                        ObjectNode resp = AuthService.register(req);
                        setAuthCookies(exchange, resp.path("access_token").asText(), resp.path("refresh_token").asText());
                        sendJson(exchange, 200, resp);
                    }
                    case "/api/auth/login" -> {
                        ObjectNode resp = AuthService.login(req);
                        setAuthCookies(exchange, resp.path("access_token").asText(), resp.path("refresh_token").asText());
                        sendJson(exchange, 200, resp);
                    }
                    case "/api/auth/refresh" -> {
                        String rt = req.path("refresh_token").asText("");
                        if (rt.isEmpty()) rt = cookieValue(exchange, "educore_rt");
                        if (rt.isEmpty()) { send(exchange, 401, "{\"error\":\"no refresh token\"}", "application/json"); return; }
                        ObjectNode resp = AuthService.refresh(rt);
                        setAuthCookies(exchange, resp.path("access_token").asText(), resp.path("refresh_token").asText());
                        sendJson(exchange, 200, resp);
                    }
                    case "/api/auth/logout" -> {
                        clearAuthCookies(exchange);
                        send(exchange, 200, "{\"ok\":true}", "application/json");
                    }
                    case "/api/auth/forgot" -> sendJson(exchange, 200, AuthService.forgot(req));
                    case "/api/auth/reset" -> { AuthService.reset(req); send(exchange, 200, "{\"ok\":true}", "application/json"); }
                    case "/api/auth/change-password" -> {
                        AuthService.AuthUser u = requireUser(exchange);
                        if (u == null) return;
                        AuthService.changePassword(claimsOf(exchange), req);
                        send(exchange, 200, "{\"ok\":true}", "application/json");
                    }
                    default -> send(exchange, 404, "{\"error\":\"unknown auth endpoint\"}", "application/json");
                }
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"auth error\"}", "application/json");
            }
        });

        // ---------- Feedback ----------
        server.createContext("/api/feedback", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String path = exchange.getRequestURI().getPath();
                if ("GET".equals(exchange.getRequestMethod()) && path.equals("/api/feedback/stats")) {
                    sendJson(exchange, 200, FeedbackService.stats(authUser));
                    return;
                }
                if ("POST".equals(exchange.getRequestMethod()) && path.equals("/api/feedback")) {
                    JsonNode req = readJson(exchange);
                    sendJson(exchange, 200, FeedbackService.submit(authUser, req));
                    return;
                }
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"feedback error\"}", "application/json");
            }
        });

        // ---------- Notes library ----------
        server.createContext("/api/notes", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                String query = exchange.getRequestURI().getQuery();

                if ("GET".equals(method) && path.equals("/api/notes")) {
                    String q = queryParam(query, "q");
                    String sort = queryParam(query, "sort");
                    send(exchange, 200, MAPPER.writeValueAsString(
                            NotesService.list(authUser, q, sort)), "application/json");
                    return;
                }
                if ("POST".equals(method) && path.equals("/api/notes")) {
                    sendJson(exchange, 200, NotesService.create(authUser, readJson(exchange)));
                    return;
                }
                if (path.startsWith("/api/notes/")) {
                    String rest = path.substring("/api/notes/".length());
                    int slash = rest.indexOf('/');
                    String id = slash < 0 ? rest : rest.substring(0, slash);
                    String action = slash < 0 ? "" : rest.substring(slash + 1);
                    if ("GET".equals(method) && (action.isEmpty() || action.equals("get"))) {
                        sendJson(exchange, 200, NotesService.get(id, authUser));
                        return;
                    }
                    if (("POST".equals(method) || "PUT".equals(method))
                            && (action.equals("update") || action.isEmpty())) {
                        sendJson(exchange, 200, NotesService.update(id, authUser, readJson(exchange)));
                        return;
                    }
                    if (("POST".equals(method) && action.equals("delete"))
                            || ("DELETE".equals(method) && (action.isEmpty() || action.equals("delete")))) {
                        NotesService.delete(id, authUser);
                        send(exchange, 200, "{\"ok\":true}", "application/json");
                        return;
                    }
                }
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"notes error\"}", "application/json");
            }
        });

        server.createContext("/api/chat", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
                return;
            }
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode req = MAPPER.readTree(body);
                String message = req.path("message").asText("").trim();
                if (message.isEmpty()) {
                    send(exchange, 400, "{\"error\":\"message is required\"}", "application/json");
                    return;
                }
                Map<String, String> result = engine.respondWithModule(message);
                send(exchange, 200, MAPPER.writeValueAsString(result), "application/json; charset=UTF-8");
            } catch (Exception e) {
                send(exchange, 400, "{\"error\":\"invalid request\"}", "application/json");
            }
        });

        server.createContext("/api/quiz", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
                return;
            }
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                JsonNode req = MAPPER.readTree(body);
                String topic = req.path("topic").asText("").trim();
                if (topic.isEmpty()) {
                    send(exchange, 400, "{\"error\":\"topic is required\"}", "application/json");
                    return;
                }
                HybridAssessment.Result result = HybridAssessment.generate(
                        req.path("subject").asText("General"),
                        topic,
                        req.path("level").asText("General"),
                        req.path("difficulty").asText("standard"),
                        Math.max(1, Math.min(20, req.path("count").asInt(5))),
                        req.path("source").asText(""));
                Quiz quiz = result.quiz();

                ObjectNode resp = MAPPER.createObjectNode();
                resp.set("student_view", MAPPER.readTree(quiz.studentViewJson()));
                resp.put("generation_mode", result.mode());
                resp.put("disclaimer", AssessmentEngine.DISCLAIMER);
                if (req.path("include_answers").asBoolean(false)) {
                    resp.set("answer_key", MAPPER.readTree(quiz.answerKeyJson()));
                }
                send(exchange, 200, MAPPER.writeValueAsString(resp), "application/json; charset=UTF-8");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 400, "{\"error\":\"invalid request\"}", "application/json");
            }
        });

        server.createContext("/api/report", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
                return;
            }
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                var student = StudentData.loadProfile();
                LearningEngine le = new LearningEngine();
                ObjectNode resp = MAPPER.createObjectNode();
                resp.put("report", le.generateReport(student));
                resp.put("suggested_difficulty", le.suggestDifficulty(student));
                send(exchange, 200, MAPPER.writeValueAsString(resp), "application/json; charset=UTF-8");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"report generation failed\"}", "application/json");
            }
        });

        server.createContext("/api/ai", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            if (!"POST".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
                return;
            }
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            handleAI(exchange, prompts, authUser);
        });

        // ---------- Exams ----------
        server.createContext("/api/exams", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if (path.equals("/api/exams/history") && "GET".equals(method)) {
                    send(exchange, 200, MAPPER.writeValueAsString(ExamService.history(authUser)), "application/json");
                    return;
                }
                if (!"POST".equals(method)) { send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json"); return; }
                JsonNode req = readJson(exchange);
                if (path.equals("/api/exams/generate")) {
                    sendJson(exchange, 200, ExamService.generate(req, authUser));
                } else if (path.startsWith("/api/exams/") && path.endsWith("/submit")) {
                    String id = path.substring("/api/exams/".length(), path.length() - "/submit".length());
                    sendJson(exchange, 200, ExamService.submit(id, req.path("answers"), authUser));
                } else {
                    send(exchange, 404, "{\"error\":\"unknown exams endpoint\"}", "application/json");
                }
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"exam error\"}", "application/json");
            }
        });

        // ---------- Documents ----------
        server.createContext("/api/documents", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("POST".equals(method) && path.equals("/api/documents/upload")) {
                    JsonNode req = readJson(exchange);
                    sendJson(exchange, 200, DocumentService.upload(
                            req.path("name").asText(""), req.path("data").asText(""), authUser));
                    return;
                }
                if ("GET".equals(method) && path.equals("/api/documents")) {
                    send(exchange, 200, MAPPER.writeValueAsString(DocumentService.list(authUser)), "application/json");
                    return;
                }
                if ("GET".equals(method) && path.startsWith("/api/documents/")) {
                    String id = path.substring("/api/documents/".length());
                    JsonNode meta = DocumentService.meta(id);
                    if (meta.isMissingNode() || !DocumentService.canAccess(meta, authUser)) {
                        send(exchange, 404, "{\"error\":\"not found\"}", "application/json");
                        return;
                    }
                    ObjectNode out = (ObjectNode) meta.deepCopy();
                    out.put("text", DocumentService.text(id));
                    sendJson(exchange, 200, out);
                    return;
                }
                if (("DELETE".equals(method) || "POST".equals(method)) && path.startsWith("/api/documents/")) {
                    String rest = path.substring("/api/documents/".length());
                    int slash = rest.indexOf('/');
                    String id = slash < 0 ? rest : rest.substring(0, slash);
                    String action = slash < 0 ? "" : rest.substring(slash + 1);
                    if (action.isEmpty() || action.equals("delete")) {
                        DocumentService.delete(id, authUser);
                        send(exchange, 200, "{\"ok\":true}", "application/json");
                        return;
                    }
                    JsonNode req = readJson(exchange);
                    if (action.equals("ask")) {
                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("answer", DocumentService.ask(id, req.path("question").asText(""), authUser));
                        sendJson(exchange, 200, out);
                    } else if (action.equals("summarize")) {
                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("answer", DocumentService.summarizeDoc(id, authUser));
                        sendJson(exchange, 200, out);
                    } else {
                        send(exchange, 404, "{\"error\":\"unknown document action\"}", "application/json");
                    }
                    return;
                }
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
            } catch (OpenAIException e) {
                send(exchange, e.status, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"document error\"}", "application/json");
            }
        });

        // ---------- Planner ----------
        server.createContext("/api/planner", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("GET".equals(method) && path.equals("/api/planner/tasks")) {
                    send(exchange, 200, MAPPER.writeValueAsString(PlannerService.tasks(authUser)), "application/json");
                    return;
                }
                if ("POST".equals(method)) {
                    JsonNode req = readJson(exchange);
                    if (path.equals("/api/planner/tasks")) {
                        sendJson(exchange, 200, PlannerService.create(req, authUser));
                    } else if (path.equals("/api/planner/ai")) {
                        ObjectNode out = MAPPER.createObjectNode();
                        out.put("schedule", PlannerService.aiGenerate(req));
                        sendJson(exchange, 200, out);
                    } else if (path.startsWith("/api/planner/tasks/")) {
                        String rest = path.substring("/api/planner/tasks/".length());
                        int slash = rest.indexOf('/');
                        String id = slash < 0 ? rest : rest.substring(0, slash);
                        String action = slash < 0 ? "" : rest.substring(slash + 1);
                        if (action.equals("update")) {
                            sendJson(exchange, 200, PlannerService.update(id, req, authUser));
                        } else if (action.equals("delete") || action.isEmpty()) {
                            PlannerService.delete(id, authUser);
                            send(exchange, 200, "{\"ok\":true}", "application/json");
                        } else {
                            send(exchange, 404, "{\"error\":\"unknown planner action\"}", "application/json");
                        }
                    } else {
                        send(exchange, 404, "{\"error\":\"unknown planner endpoint\"}", "application/json");
                    }
                    return;
                }
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
            } catch (OpenAIException e) {
                send(exchange, e.status, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"planner error\"}", "application/json");
            }
        });

        // ---------- Classrooms ----------
        server.createContext("/api/classrooms", exchange -> {
            if (preflight(exchange)) return;
            cors(exchange);
            AuthService.AuthUser authUser = requireUser(exchange);
            if (authUser == null) return;
            try {
                String path = exchange.getRequestURI().getPath();
                String method = exchange.getRequestMethod();
                if ("GET".equals(method) && path.equals("/api/classrooms")) {
                    send(exchange, 200, MAPPER.writeValueAsString(ClassroomService.list()), "application/json");
                    return;
                }
                if ("POST".equals(method)) {
                    JsonNode req = readJson(exchange);
                    if (path.equals("/api/classrooms/create")) {
                        if (!"teacher".equals(authUser.role()) && !"admin".equals(authUser.role())) {
                            send(exchange, 403, "{\"error\":\"only teachers or admins can create classrooms\"}", "application/json");
                            return;
                        }
                        sendJson(exchange, 200, ClassroomService.create(req, authUser));
                    } else if (path.equals("/api/classrooms/join")) {
                        sendJson(exchange, 200, ClassroomService.join(req, authUser));
                    } else if (path.startsWith("/api/classrooms/")) {
                        String rest = path.substring("/api/classrooms/".length());
                        int slash = rest.indexOf('/');
                        String id = slash < 0 ? rest : rest.substring(0, slash);
                        String action = slash < 0 ? "" : rest.substring(slash + 1);
                        if (action.equals("announce")) {
                            sendJson(exchange, 200, ClassroomService.announce(id, req, authUser));
                        } else if (action.equals("material")) {
                            sendJson(exchange, 200, ClassroomService.addMaterial(id, req, authUser));
                        } else if (action.equals("get") || action.isEmpty()) {
                            sendJson(exchange, 200, ClassroomService.get(id));
                        } else {
                            send(exchange, 404, "{\"error\":\"unknown classroom action\"}", "application/json");
                        }
                    } else {
                        send(exchange, 404, "{\"error\":\"unknown classroom endpoint\"}", "application/json");
                    }
                    return;
                }
                send(exchange, 405, "{\"error\":\"method not allowed\"}", "application/json");
            } catch (IllegalArgumentException e) {
                send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
            } catch (Exception e) {
                send(exchange, 500, "{\"error\":\"classroom error\"}", "application/json");
            }
        });

        server.start();
        System.out.println("EDUCORE AI web server running at http://localhost:" + port);
        System.out.println("API: /api/chat /api/quiz /api/report /api/exams/* /api/documents/* /api/planner/* /api/classrooms/* /api/ai/*");
        System.out.println("Press Ctrl+C to stop.");
    }

    private static int envPort() {
        try {
            String p = System.getenv("PORT");
            return p == null ? -1 : Integer.parseInt(p.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------- AI endpoints ----------

    private static void handleAI(HttpExchange exchange, JsonNode prompts, AuthService.AuthUser authUser) throws IOException {
        String path = exchange.getRequestURI().getPath(); // /api/ai/<op>
        String op = path.substring("/api/ai/".length());
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode req = MAPPER.readTree(body);

            switch (op) {
                case "chat" -> {
                    String message = req.path("message").asText("").trim();
                    require(message, "message");
                    String session = authUser.id() + ":" + req.path("session_id").asText("default");
                    String instructions = prompts.path("universal").asText("");
                    String answer = AIProvider.chatUniversal(session, message, instructions);
                    sendAI(exchange, answer, "tutor");
                }
                case "chat_stream" -> {
                    String message = req.path("message").asText("").trim();
                    require(message, "message");
                    String session = authUser.id() + ":" + req.path("session_id").asText("default");
                    String instructions = prompts.path("universal").asText("");
                    exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=UTF-8");
                    exchange.getResponseHeaders().set("Cache-Control", "no-cache");
                    exchange.sendResponseHeaders(200, 0);
                    try (java.io.OutputStream out = exchange.getResponseBody()) {
                        StringBuilder acc = new StringBuilder();
                        try {
                            AIProvider.chatUniversalStream(session, message, instructions, delta -> {
                                acc.append(delta);
                                try {
                                    out.write(("data: " + MAPPER.writeValueAsString(
                                            java.util.Map.of("delta", delta)) + "\n\n")
                                            .getBytes(StandardCharsets.UTF_8));
                                    out.flush();
                                } catch (IOException ignored) {
                                }
                            });
                            out.write(("data: " + MAPPER.writeValueAsString(
                                    java.util.Map.of("done", true, "answer", acc.toString())) + "\n\n")
                                    .getBytes(StandardCharsets.UTF_8));
                        } catch (OpenAIException e) {
                            out.write(("data: " + MAPPER.writeValueAsString(
                                    java.util.Map.of("error", e.getMessage())) + "\n\n")
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                    }
                    exchange.close();
                    return;
                }
                case "vision" -> {
                    String image = req.path("image").asText("");
                    require(image, "image");
                    String session = authUser.id() + ":" + req.path("session_id").asText("default");
                    String question = req.path("question").asText("");
                    String mime = req.path("mime").asText("image/png");
                    String instructions = prompts.path("universal").asText("");
                    String answer = AIProvider.vision(session, question, image, mime, instructions);
                    sendAI(exchange, answer, "vision");
                }
                case "notes" -> {
                    String topic = req.path("topic").asText("").trim();
                    require(topic, "topic");
                    JsonNode t = prompts.path("notes");
                    Map<String, String> vars = baseVars(req);
                    vars.put("topic", topic);
                    vars.put("detail_level", req.path("detail_level").asText("medium"));
                    String answer = AIProvider.generate(fill(t.path("instructions").asText(), vars),
                            fill(t.path("input_template").asText(), vars));
                    sendAI(exchange, answer, "notes");
                }
                case "summary" -> {
                    String text = req.path("text").asText("").trim();
                    require(text, "text");
                    JsonNode t = prompts.path("summary");
                    Map<String, String> vars = Map.of(
                            "text", text,
                            "length", req.path("length").asText("standard"));
                    String answer = AIProvider.generate(fill(t.path("instructions").asText(), vars),
                            fill(t.path("input_template").asText(), vars));
                    sendAI(exchange, answer, "summary");
                }
                case "quiz" -> {
                    String topic = req.path("topic").asText("").trim();
                    require(topic, "topic");
                    JsonNode t = prompts.path("quiz");
                    Map<String, String> vars = baseVars(req);
                    vars.put("topic", topic);
                    vars.put("count", req.path("count").asText("5"));
                    String answer = AIProvider.generate(fill(t.path("instructions").asText(), vars),
                            fill(t.path("input_template").asText(), vars));
                    sendAI(exchange, answer, "quiz");
                }
                case "engine" -> {
                    String engineName = req.path("engine").asText("").trim();
                    JsonNode ep = prompts.path("engines").path(engineName);
                    if (ep.isMissingNode()) {
                        send(exchange, 400, "{\"error\":\"unknown engine: " + engineName + "\"}", "application/json");
                        return;
                    }
                    Map<String, String> vars = new HashMap<>();
                    JsonNode fields = req.path("fields");
                    fields.fieldNames().forEachRemaining(k -> vars.put(k, fields.path(k).asText("")));
                    String system = fill(ep.path("system").asText(), vars);
                    String user = fill(ep.path("user_template").asText(), vars);
                    String answer = AIProvider.generate(system + "\n\n" + prompts.path("master_instructions").asText(), user);
                    sendAI(exchange, answer, engineName);
                }
                default -> send(exchange, 404, "{\"error\":\"unknown AI endpoint\"}", "application/json");
            }
        } catch (OpenAIException e) {
            send(exchange, e.status, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
        } catch (IllegalArgumentException e) {
            send(exchange, 400, "{\"error\":\"" + escape(e.getMessage()) + "\"}", "application/json");
        } catch (Exception e) {
            send(exchange, 400, "{\"error\":\"invalid request\"}", "application/json");
        }
    }

    private static Map<String, String> baseVars(JsonNode req) {
        Map<String, String> vars = new HashMap<>();
        vars.put("subject", req.path("subject").asText("General"));
        vars.put("academic_level", req.path("academic_level").asText("Not specified"));
        vars.put("language", req.path("language").asText("English"));
        return vars;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }

    /** Replaces {key} placeholders; missing keys become empty strings. */
    private static String fill(String template, Map<String, String> vars) {
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue());
        }
        return out.replaceAll("\\{[a-zA-Z_]+\\}", "");
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return MAPPER.readTree(body.isBlank() ? "{}" : body);
    }

    private static void sendJson(HttpExchange exchange, int status, com.fasterxml.jackson.databind.JsonNode node) throws IOException {
        send(exchange, status, MAPPER.writeValueAsString(node), "application/json; charset=UTF-8");
    }

    // ---------- CORS (frontend on Firebase Hosting, backend on Render) ----------

    private static final String[] ALLOWED_ORIGINS = {
            "https://educore-ai-9dc3c.web.app",
            "https://educore-ai-9dc3c.firebaseapp.com",
            "http://localhost:8080", "http://127.0.0.1:8080"
    };

    /** Sets CORS headers when the request Origin is allowlisted. */
    private static boolean cors(HttpExchange exchange) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) return false;
        for (String allowed : ALLOWED_ORIGINS) {
            if (allowed.equals(origin)) {
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
                exchange.getResponseHeaders().set("Access-Control-Allow-Credentials", "true");
                exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
                exchange.getResponseHeaders().set("Vary", "Origin");
                return true;
            }
        }
        return false;
    }

    /** Handles CORS preflight; returns true if the exchange was answered. */
    private static boolean preflight(HttpExchange exchange) throws IOException {
        if (!"OPTIONS".equals(exchange.getRequestMethod())) return false;
        if (cors(exchange)) {
            exchange.sendResponseHeaders(204, -1);
        } else {
            exchange.sendResponseHeaders(403, -1);
        }
        exchange.close();
        return true;
    }

    // ---------- auth helpers ----------

    /** Returns the authenticated user, or sends 401 and returns null. */
    private static AuthService.AuthUser requireUser(HttpExchange exchange) throws IOException {
        String token = bearerToken(exchange);
        if (token == null) token = cookieValue(exchange, "educore_at");
        AuthService.AuthUser u = token == null ? null : AuthService.userFromToken(token);
        if (u == null) {
            send(exchange, 401, "{\"error\":\"authentication required\"}", "application/json");
        }
        return u;
    }

    private static Map<String, String> claimsOf(HttpExchange exchange) {
        String token = bearerToken(exchange);
        if (token == null) token = cookieValue(exchange, "educore_at");
        Map<String, String> c = token == null ? null : AuthService.verifyToken(token);
        return c == null ? Map.of() : c;
    }

    private static String bearerToken(HttpExchange exchange) {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring(7).trim();
        }
        return null;
    }

    private static String queryParam(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String cookieValue(HttpExchange exchange, String name) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie == null) return null;
        for (String c : cookie.split(";")) {
            c = c.trim();
            if (c.startsWith(name + "=")) {
                return c.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static boolean isHttps(HttpExchange exchange) {
        return "https".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
    }

    private static void setAuthCookies(HttpExchange exchange, String access, String refresh) {
        // Cross-site deploy (Firebase -> Render): need SameSite=None + Secure over HTTPS.
        // Localhost http keeps SameSite=Lax so dev still works.
        String attrs = isHttps(exchange)
                ? "; SameSite=None; Secure; HttpOnly"
                : "; SameSite=Lax; HttpOnly";
        exchange.getResponseHeaders().add("Set-Cookie", "educore_at=" + access + "; Path=/; Max-Age=900" + attrs);
        exchange.getResponseHeaders().add("Set-Cookie", "educore_rt=" + refresh + "; Path=/; Max-Age=604800" + attrs);
    }

    private static void clearAuthCookies(HttpExchange exchange) {
        String attrs = isHttps(exchange)
                ? "; SameSite=None; Secure; HttpOnly"
                : "; SameSite=Lax; HttpOnly";
        exchange.getResponseHeaders().add("Set-Cookie", "educore_at=; Path=/; Max-Age=0" + attrs);
        exchange.getResponseHeaders().add("Set-Cookie", "educore_rt=; Path=/; Max-Age=0" + attrs);
    }

    private static void sendAI(HttpExchange exchange, String answer, String module) throws IOException {
        ObjectNode resp = MAPPER.createObjectNode();
        resp.put("answer", answer);
        resp.put("module", module);
        resp.put("model", AIProvider.providerName());
        send(exchange, 200, MAPPER.writeValueAsString(resp), "application/json; charset=UTF-8");
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void serveStatic(HttpExchange exchange, String resource, String contentType) throws IOException {
        try (InputStream in = WebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                send(exchange, 404, "{\"error\":\"not found\"}", "application/json");
                return;
            }
            byte[] data = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, data.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(data);
            }
        }
    }

    private static void send(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, data.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }
}
