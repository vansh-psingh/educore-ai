package edu.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * EDUCORE AI assessment generator.
 * Builds questions deterministically from the supplied source material.
 * Generated questions are practice material only - they are NOT official
 * examination questions.
 */
public class AssessmentEngine {

    public static final String DISCLAIMER =
        "These are AI-generated practice questions, not official examination questions.";

    private final Random rng;

    public AssessmentEngine() {
        this.rng = new Random();
    }

    public AssessmentEngine(long seed) {
        this.rng = new Random(seed);
    }

    public Quiz generate(String subject, String topic, String level, String difficulty,
                         int questionCount, Set<QuestionType> types, String sourceMaterial) {
        List<Fact> facts = parseSourceMaterial(sourceMaterial, topic);
        List<Question> questions = new ArrayList<>();

        if (facts.isEmpty()) {
            questions.add(fallbackQuestion(topic));
        } else {
            int perType = Math.max(1, questionCount / Math.max(1, types.size()));
            // round-robin over types, drawing each fact at most once
            List<Fact> pool = new ArrayList<>(facts);
            List<QuestionType> order = new ArrayList<>(types);
            for (int i = 0; questions.size() < questionCount && !pool.isEmpty(); i++) {
                QuestionType type = order.get(i % order.size());
                if (i / order.size() >= perType) break;
                Question q = switch (type) {
                    case MCQ -> buildDefinitionMcq(facts, pool, difficulty);
                    case TRUE_FALSE -> buildTrueFalse(facts, pool);
                    case SHORT_ANSWER -> buildShortAnswer(pool);
                };
                if (q != null) questions.add(q);
            }
        }

        return new Quiz("Practice Quiz: " + topic, subject, difficulty,
                        questions.subList(0, Math.min(questionCount, questions.size())));
    }

    // ---------- Source material parsing ----------
    // Recognizes lines like "Photosynthesis: the process by which plants make food"
    // or "Force = mass x acceleration". Only what is actually present is used.

    private record Fact(String term, String definition) {}

    private List<Fact> parseSourceMaterial(String material, String topic) {
        List<Fact> facts = new ArrayList<>();
        if (material == null || material.isBlank()) return facts;
        for (String line : material.split("\\R")) {
            line = line.trim();
            int sep = line.indexOf(':');
            if (sep > 0 && sep < line.length() - 1) {
                String term = line.substring(0, sep).trim();
                String def = line.substring(sep + 1).trim();
                boolean isLabel = term.chars().anyMatch(Character::isLetter)
                    && term.length() <= 40 && !term.equals(term.toUpperCase());
                if (isLabel && def.length() >= 10) {
                    facts.add(new Fact(term, def));
                }
            }
        }
        return facts;
    }

    // ---------- Question builders ----------

    private Question buildDefinitionMcq(List<Fact> facts, List<Fact> pool, String difficulty) {
        if (pool.size() < 2) return null;
        Fact correct = pool.remove(rng.nextInt(pool.size()));
        List<String> distractors = new ArrayList<>();
        for (Fact f : facts) {
            if (!f.equals(correct) && !f.definition().equals(correct.definition())) {
                distractors.add(f.definition());
            }
        }
        if (distractors.isEmpty()) return null;

        int optionCount = switch (difficulty.toLowerCase()) {
            case "easy" -> 3;
            case "hard" -> 5;
            default -> 4;
        };
        while (distractors.size() > optionCount - 1) {
            distractors.remove(rng.nextInt(distractors.size()));
        }

        List<String> options = new ArrayList<>(distractors);
        options.add(correct.definition());
        // shuffle
        for (int i = options.size() - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            String tmp = options.get(i); options.set(i, options.get(j)); options.set(j, tmp);
        }

        return new Question(
            "Which of the following best defines \"" + correct.term() + "\"?",
            QuestionType.MCQ, options, correct.definition(),
            "\"" + correct.term() + "\" is defined in the source material as: " + correct.definition(),
            1, correct.term());
    }

    private Question buildTrueFalse(List<Fact> facts, List<Fact> pool) {
        if (pool.isEmpty()) return null;
        Fact f = pool.remove(rng.nextInt(pool.size()));
        boolean truth = rng.nextBoolean();
        String statement = truth
            ? f.term() + " - " + f.definition()
            : f.term() + " - " + pickWrongDefinition(facts, f);
        return new Question(
            "True or False: " + statement,
            QuestionType.TRUE_FALSE, List.of("True", "False"),
            truth ? "True" : "False",
            truth
                ? "Correct - this matches the source material."
                : "Incorrect - \"" + f.term() + "\" is actually defined as: " + f.definition(),
            1, f.term());
    }

    private Question buildShortAnswer(List<Fact> pool) {
        if (pool.isEmpty()) return null;
        Fact f = pool.remove(rng.nextInt(pool.size()));
        return new Question(
            "In your own words, define \"" + f.term() + "\".",
            QuestionType.SHORT_ANSWER, List.of(),
            f.definition(),
            "Reference definition: " + f.definition(),
            2, f.term());
    }

    private String pickWrongDefinition(List<Fact> facts, Fact exclude) {
        for (int tries = 0; tries < 10; tries++) {
            Fact other = facts.get(rng.nextInt(facts.size()));
            if (!other.equals(exclude)) return other.definition();
        }
        return "an unrelated concept";
    }

    /** Used when no usable source material was supplied. Honest, not fabricated. */
    private Question fallbackQuestion(String topic) {
        return new Question(
            "No usable source material was provided for \"" + topic
                + "\". Please supply study notes so questions can be generated from them.",
            QuestionType.SHORT_ANSWER, List.of(),
            "N/A - regenerate with source material.",
            "Supply source material in 'Term: definition' format to enable generation.",
            0, topic);
    }
}
