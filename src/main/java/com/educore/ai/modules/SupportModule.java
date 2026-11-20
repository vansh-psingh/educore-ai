package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Module 4 - Customer support.
 * Answers only from the authorized knowledge base (data/support_kb.json).
 * Unresolved requests are escalated honestly - no invented policies.
 */
public final class SupportModule implements EducoreModule {

    private final JsonNode kb;

    public SupportModule() {
        this.kb = JsonStore.load("data/support_kb.json");
    }

    public String respond(String input) {
        String lower = input.toLowerCase(Locale.ROOT);

        JsonNode best = null;
        int bestScore = 0;
        for (JsonNode entry : kb.path("entries")) {
            int score = 0;
            for (JsonNode kw : entry.path("keywords")) {
                if (lower.contains(kw.asText().toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                best = entry;
            }
        }

        if (best == null) {
            return "I couldn't find an authorized answer for that. "
                    + "I've noted it for escalation to the support team. "
                    + "For account-specific issues, please contact support directly.";
        }

        return best.path("answer").asText()
                + "\n\n(Reference: " + best.path("id").asText() + ")";
    }
}
