package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Locale;

/**
 * Module 3 - Multilingual communication.
 * Responds in the requested language using phrases from
 * data/languages.json. Honest about limited coverage.
 */
public final class LanguageModule implements EducoreModule {

    private final JsonNode languages;

    public LanguageModule() {
        this.languages = JsonStore.load("data/languages.json");
    }

    public String respond(String input) {
        String lower = input.toLowerCase(Locale.ROOT);

        JsonNode phrase = null;
        for (JsonNode p : languages.path("phrases")) {
            if (lower.contains(p.path("english").asText().toLowerCase(Locale.ROOT))) {
                phrase = p;
                break;
            }
        }

        String targetLang = detectLanguage(lower);
        if (targetLang == null) {
            return "Tell me which language you'd like, e.g. \"translate hello to Spanish\", "
                    + "or which phrase you need.";
        }

        if (phrase == null) {
            return "I can help with basic phrases. Try \"translate hello to " + targetLang + "\" "
                    + "or \"how do I say thank you in " + targetLang + "\".";
        }

        JsonNode translations = phrase.path("translations");
        JsonNode t = translations.path(targetLang.toLowerCase(Locale.ROOT));
        if (t.isMissingNode()) {
            return "I don't have \"" + phrase.path("english").asText() + "\" in " + targetLang
                    + " yet. Supported languages: " + supportedList() + ".";
        }
        return "\"" + phrase.path("english").asText() + "\" in " + targetLang + ": " + t.asText();
    }

    private String detectLanguage(String lower) {
        for (JsonNode lang : languages.path("languages")) {
            String name = lang.path("name").asText().toLowerCase(Locale.ROOT);
            if (lower.contains(name)) {
                return lang.path("code").asText();
            }
        }
        return null;
    }

    private String supportedList() {
        StringBuilder sb = new StringBuilder();
        for (JsonNode lang : languages.path("languages")) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(lang.path("name").asText());
        }
        return sb.toString();
    }
}
