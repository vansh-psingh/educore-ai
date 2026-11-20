package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structured Extraction Engine.
 *
 * Extracts only information supported by the source text, following the
 * schema in data/extraction_engine.json. Outputs valid JSON or XML.
 * Missing fields are null / empty elements - never guessed.
 *
 * Input hints:
 *   "extract source: <text>, format: xml"
 *   "extract name: Ali email: a@b.com age: 20 format: json"
 */
public final class ExtractionEngineModule implements EducoreModule {

    private final JsonNode schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExtractionEngineModule() {
        this.schema = JsonStore.load("data/extraction_engine.json");
    }

    @Override
    public String respond(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        boolean xml = lower.contains("format: xml") || lower.contains("format xml")
                || lower.contains("as xml") || lower.matches(".*\\bxml\\b.*output.*")
                || (lower.contains("xml") && !lower.contains("json"));

        String source = extractSource(input);
        if (source.isBlank()) {
            return "No source content found. Provide text to extract from, e.g.\n"
                    + "  \"extract source: name: Ali, email: ali@test.com, age: 20\"\n"
                    + "Add \"format: xml\" for XML output.";
        }

        Map<String, String> values = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (JsonNode field : schema.path("fields")) {
            String name = field.path("name").asText();
            String type = field.path("type").asText("string");
            boolean required = field.path("required").asBoolean(false);

            String value = findValue(source, field);
            if (value == null) {
                values.put(name, null);
                if (required) {
                    warnings.add("missing required field: " + name);
                }
            } else {
                String typeError = validateType(value, type, schema.path("patterns"));
                if (typeError != null) {
                    warnings.add("field '" + name + "': " + typeError);
                }
                values.put(name, value);
            }
        }

        return xml ? toXml(values, warnings) : toJson(values, warnings);
    }

    // ---------- source extraction ----------

    private String extractSource(String input) {
        // prefer explicit "source:" or "text:" parameter (rest of the string)
        Matcher m = Pattern.compile("(?i)(?:source|text|content)\\s*[:=]\\s*(.+)", Pattern.DOTALL).matcher(input);
        if (m.find()) {
            String s = m.group(1);
            // strip trailing format instruction
            s = s.replaceAll("(?i)\\s*,?\\s*format\\s*[:=]?\\s*(json|xml)\\s*$", "").trim();
            return s;
        }
        // otherwise treat the whole message minus command words as source
        return input
                .replaceAll("(?i)^(please\\s+)?(extract|parse|structured data|data extraction)\\s*", "")
                .replaceAll("(?i)\\s*(format|output|as)\\s*[:=]?\\s*(json|xml|structured json|structured xml)\\s*$", "")
                .trim();
    }

    // ---------- field value detection ----------

    private String findValue(String source, JsonNode field) {
        String type = field.path("type").asText("string");
        String name = field.path("name").asText();
        String label = field.has("label") ? field.path("label").asText() : name;

        // typed patterns first (email/phone/date/number)
        if (!type.equals("string")) {
            JsonNode patterns = schema.path("patterns");
            String regex = patterns.path(type).asText("");
            if (!regex.isEmpty()) {
                Matcher m = Pattern.compile(regex).matcher(source);
                if (m.find()) {
                    return m.group().trim();
                }
            }
            // label-based fallback for typed fields
            Matcher lm = Pattern.compile("(?i)" + Pattern.quote(label) + "\\s*(?:is|=|:)\\s*([^,;\\n]+)").matcher(source);
            if (lm.find()) {
                return lm.group(1).trim();
            }
            return null;
        }

        // string fields: label match, then "label is X", then capitalized phrase
        Matcher lm = Pattern.compile("(?i)" + Pattern.quote(label) + "\\s*[:=]\\s*([^,;\\n]+)").matcher(source);
        if (lm.find()) {
            return clean(lm.group(1));
        }
        Matcher ism = Pattern.compile("(?i)\\b" + Pattern.quote(label) + "\\s+is\\s+([^,;\\n.]+)").matcher(source);
        if (ism.find()) {
            return clean(ism.group(1));
        }
        return null;
    }

    private String clean(String s) {
        return s.trim().replaceAll("[.\\s]+$", "");
    }

    // ---------- type validation ----------

    private String validateType(String value, String type, JsonNode patterns) {
        return switch (type) {
            case "email" -> value.matches(patterns.path("email").asText()) ? null : "not a valid email format";
            case "phone" -> value.replaceAll("[\\s().-]", "").matches("\\+?\\d{7,15}") ? null : "not a valid phone format";
            case "date" -> value.matches(patterns.path("date").asText()) ? null : "not a recognized date format";
            case "number" -> value.matches("-?\\d+(\\.\\d+)?") ? null : "not a valid number";
            default -> null;
        };
    }

    // ---------- output formatters ----------

    private String toJson(Map<String, String> values, List<String> warnings) {
        ObjectNode root = mapper.createObjectNode();
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (e.getValue() == null) {
                root.putNull(e.getKey());
            } else {
                // keep numbers numeric
                if (e.getKey().equals("age")) {
                    try {
                        root.put(e.getKey(), Integer.parseInt(e.getValue()));
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
                root.put(e.getKey(), e.getValue());
            }
        }
        if (schema.path("include_warnings").asBoolean(false) && !warnings.isEmpty()) {
            ArrayNode w = root.putArray("warnings");
            warnings.forEach(w::add);
        }
        try {
            return "EXTRACTION RESULT (JSON)\n"
                    + mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                    + "\n\nQuality check: " + (warnings.isEmpty() ? "all required fields present, types valid." : warnings.size() + " warning(s) - see 'warnings' array.");
        } catch (Exception e) {
            return "JSON serialization failed: " + e.getMessage();
        }
    }

    private String toXml(Map<String, String> values, List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append('<').append(schema.path("root").asText("extraction"))
          .append(" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        for (Map.Entry<String, String> e : values.entrySet()) {
            sb.append("  <").append(e.getKey());
            if (e.getValue() == null) {
                sb.append(" xsi:nil=\"true\"/>\n");
            } else {
                sb.append('>').append(escapeXml(e.getValue())).append("</").append(e.getKey()).append(">\n");
            }
        }
        if (!warnings.isEmpty()) {
            sb.append("  <warnings>\n");
            for (String w : warnings) {
                sb.append("    <warning>").append(escapeXml(w)).append("</warning>\n");
            }
            sb.append("  </warnings>\n");
        }
        sb.append("</").append(schema.path("root").asText("extraction")).append(">\n");
        sb.append("\nQuality check: ").append(warnings.isEmpty() ? "all required fields present, types valid." : warnings.size() + " warning(s) listed.");
        return sb.toString();
    }

    private String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
