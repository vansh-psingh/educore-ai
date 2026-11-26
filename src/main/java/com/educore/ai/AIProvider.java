package com.educore.ai;

/**
 * Routes AI generation to whichever provider is configured.
 * OpenAI keys start with "sk-"; anything else configured as GEMINI_API_KEY
 * goes to the Gemini provider. Exposes the provider name for responses.
 */
public final class AIProvider {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Bounded TTL cache; keys are user-scoped so no data leaks across users. */
    private static final TtlCache CACHE = new TtlCache(10 * 60 * 1000, 200);

    private AIProvider() {
    }

    public static String providerName() {
        return isOpenAI() ? "openai" : isGemini() ? "gemini" : "none";
    }

    public static String generate(String instructions, String input)
            throws OpenAIService.OpenAIException {
        return generateForUser("", instructions, input);
    }

    /**
     * Generates with an optional user id. The cache key is namespaced by user
     * so identical prompts from different users never share cached output.
     */
    public static String generateForUser(String userId, String instructions, String input)
            throws OpenAIService.OpenAIException {
        String cacheKey = "ai:" + (userId == null ? "anon" : userId) + "|"
                + providerName() + "|" + instructions.hashCode() + "|" + input.hashCode();
        String hit = CACHE.get(cacheKey);
        if (hit != null) {
            return hit;
        }
        String text;
        if (isOpenAI()) {
            text = new OpenAIService().generate(instructions, input);
        } else if (isGemini()) {
            text = new GeminiService().generate(instructions, input);
        } else {
            throw new OpenAIService.OpenAIException(503,
                    "AI service is not configured (set OPENAI_API_KEY or GEMINI_API_KEY in .env).");
        }
        CACHE.put(cacheKey, text);
        return text;
    }

    /** Cache stats for admin health endpoint. */
    public static int cacheSize() {
        return CACHE.size();
    }

    /* ================= Universal chat (history-aware) ================= */

    /** Builds Gemini-style contents from stored history + the new user message. */
    private static com.fasterxml.jackson.databind.node.ArrayNode contentsFor(String sessionId, String message) {
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        for (ConversationStore.Turn t : ConversationStore.history(sessionId)) {
            com.fasterxml.jackson.databind.node.ObjectNode turn = arr.addObject();
            turn.put("role", t.role().equals("model") ? "model" : "user");
            turn.putArray("parts").addObject().put("text", t.text());
        }
        com.fasterxml.jackson.databind.node.ObjectNode user = arr.addObject();
        user.put("role", "user");
        user.putArray("parts").addObject().put("text", message);
        return arr;
    }

    /** Appends the user's own highly-rated answer examples (self-training signal). */
    private static String withExamples(String sessionId, String instructions) {
        try {
            String uid = sessionId == null ? "" : sessionId.split(":")[0];
            return instructions + FeedbackService.examplesPrompt(uid, 2);
        } catch (Exception e) {
            return instructions;
        }
    }

    /** Non-streaming universal chat with per-session memory. */
    public static String chatUniversal(String sessionId, String message, String instructions)
            throws OpenAIService.OpenAIException {
        instructions = withExamples(sessionId, instructions);
        if (isGemini()) {
            String answer = new GeminiService().generateWithContents(instructions, contentsFor(sessionId, message));
            ConversationStore.add(sessionId, "user", message);
            ConversationStore.add(sessionId, "model", answer);
            return answer;
        }
        // OpenAI fallback: flatten history into the input text
        StringBuilder input = new StringBuilder();
        for (ConversationStore.Turn t : ConversationStore.history(sessionId)) {
            input.append(t.role().equals("model") ? "Assistant: " : "Student: ").append(t.text()).append("\n");
        }
        input.append("Student: ").append(message);
        String answer = generate(instructions, input.toString());
        ConversationStore.add(sessionId, "user", message);
        ConversationStore.add(sessionId, "model", answer);
        return answer;
    }

    /** Streaming universal chat; onDelta receives text fragments as generated. */
    public static void chatUniversalStream(String sessionId, String message, String instructions,
                                           java.util.function.Consumer<String> onDelta)
            throws OpenAIService.OpenAIException {
        instructions = withExamples(sessionId, instructions);
        if (isGemini()) {
            StringBuilder acc = new StringBuilder();
            java.util.function.Consumer<String> wrapped = t -> {
                acc.append(t);
                onDelta.accept(t);
            };
            new GeminiService().streamWithContents(instructions, contentsFor(sessionId, message), wrapped);
            ConversationStore.add(sessionId, "user", message);
            ConversationStore.add(sessionId, "model", acc.toString());
            return;
        }
        String answer = chatUniversal(sessionId, message, instructions);
        onDelta.accept(answer);
    }

    /** Vision: answer a question about a base64-encoded image (Gemini inline_data). */
    public static String vision(String sessionId, String question, String base64Image, String mimeType,
                                String instructions) throws OpenAIService.OpenAIException {
        if (!isGemini()) {
            throw new OpenAIService.OpenAIException(501, "Image understanding requires the Gemini provider.");
        }
        com.fasterxml.jackson.databind.node.ArrayNode contents = MAPPER.createArrayNode();
        for (ConversationStore.Turn t : ConversationStore.history(sessionId)) {
            com.fasterxml.jackson.databind.node.ObjectNode turn = contents.addObject();
            turn.put("role", t.role().equals("model") ? "model" : "user");
            turn.putArray("parts").addObject().put("text", t.text());
        }
        com.fasterxml.jackson.databind.node.ObjectNode user = contents.addObject();
        user.put("role", "user");
        com.fasterxml.jackson.databind.node.ArrayNode parts = user.putArray("parts");
        parts.addObject().put("text", question == null || question.isBlank()
                ? "Describe this image and answer any question about it."
                : question);
        com.fasterxml.jackson.databind.node.ObjectNode img = parts.addObject();
        img.putObject("inline_data").put("mime_type", mimeType).put("data", base64Image);

        String answer = new GeminiService().generateWithContents(instructions, contents);
        ConversationStore.add(sessionId, "user", "[image] " + (question == null ? "" : question));
        ConversationStore.add(sessionId, "model", answer);
        return answer;
    }

    private static boolean isOpenAI() {
        String k = OpenAIConfig.get().apiKey;
        return k != null && k.trim().startsWith("sk-") && OpenAIConfig.get().configured;
    }

    private static boolean isGemini() {
        String k = firstNonBlank(System.getenv("GEMINI_API_KEY"), envValue("GEMINI_API_KEY"));
        return k != null && !k.isBlank() && !k.contains("YOUR_");
    }

    private static String envValue(String key) {
        try {
            java.nio.file.Path p = java.nio.file.Path.of(".env");
            if (java.nio.file.Files.isRegularFile(p)) {
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    String t = line.trim();
                    if (t.startsWith(key + "=")) {
                        return t.substring(key.length() + 1).trim();
                    }
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return null;
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
