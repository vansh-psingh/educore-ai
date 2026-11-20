package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Module 1 - Education & Study.
 * Answers concept questions from data/knowledge.json using only
 * authorized, curated content. Reports honestly when evidence is missing.
 */
public final class EducationModule implements EducoreModule {

    private final JsonNode knowledge;
    private final JsonNode profile;

    public EducationModule() {
        this.knowledge = JsonStore.load("data/knowledge.json");
        this.profile = JsonStore.load("data/student_profile.json");
    }

    public String respond(String input) {
        String name = profile.path("student").path("name").asText("");
        String level = profile.path("student").path("level").asText("general");
        String greeting = name.isEmpty() ? "" : "Hi " + name + ", ";
        String lower = input.toLowerCase(Locale.ROOT);

        JsonNode bestTopic = null;
        int bestScore = 0;
        for (JsonNode topic : knowledge.path("topics")) {
            int score = 0;
            for (JsonNode kw : topic.path("keywords")) {
                if (lower.contains(kw.asText().toLowerCase(Locale.ROOT))) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestTopic = topic;
            }
        }

        if (bestTopic == null) {
            return greeting + "I don't have verified material on that topic yet. "
                    + "Try a subject like math, science, history, or language - "
                    + "or ask me for a study plan.";
        }

        String subject = bestTopic.path("subject").asText("");
        String explanation = bestTopic.path("explanation").asText("");
        String example = bestTopic.path("example").asText("");
        String levelNote = level.equals("beginner")
                ? "\n(Level: beginner - I've kept the explanation simple.)"
                : "";

        return greeting + "Topic: " + bestTopic.path("title").asText() + " [" + subject + "]\n\n"
                + explanation + "\n\nExample: " + example + levelNote
                + "\n\nTip: say \"quiz me on " + bestTopic.path("title").asText().toLowerCase(Locale.ROOT)
                + "\" for practice questions.";
    }
}
