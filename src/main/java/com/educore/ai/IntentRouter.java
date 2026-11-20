package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Routes a student message to a module by scoring keyword matches
 * defined in data/intents.json. Falls back to the configured default.
 */
public final class IntentRouter {

    private final Map<String, String[]> keywordsByModule = new HashMap<>();
    private final String fallbackModule;

    public IntentRouter() {
        JsonNode root = JsonStore.load("data/intents.json");
        this.fallbackModule = root.path("fallback").asText("support");
        JsonNode modules = root.path("modules");
        Iterator<Map.Entry<String, JsonNode>> it = modules.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode kws = e.getValue().path("keywords");
            String[] arr = new String[kws.size()];
            for (int i = 0; i < kws.size(); i++) {
                arr[i] = kws.get(i).asText().toLowerCase(Locale.ROOT);
            }
            keywordsByModule.put(e.getKey(), arr);
        }
    }

    public String route(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        String best = null;
        int bestScore = 0;
        for (Map.Entry<String, String[]> e : keywordsByModule.entrySet()) {
            int score = 0;
            for (String kw : e.getValue()) {
                if (lower.contains(kw)) {
                    // longer (more specific) keywords outweigh short generic ones
                    score += kw.length();
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = e.getKey();
            }
        }
        return best != null ? best : fallbackModule;
    }
}
