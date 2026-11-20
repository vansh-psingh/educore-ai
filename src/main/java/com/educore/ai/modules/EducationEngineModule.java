package com.educore.ai.modules;

import com.educore.ai.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Education Engine - academic tutor module.
 *
 * Functions: concept explanation, study notes, summarization,
 * question generation, answer evaluation, curriculum alignment.
 *
 * Input parameters are read from "key: value" pairs:
 *   subject, topic, level, difficulty, goal, source, answer, syllabus, length
 * All content comes from JSON data files - nothing is fabricated.
 */
public final class EducationEngineModule implements EducoreModule {

    private static final Set<String> STOPWORDS = Set.of(
            "the", "a", "an", "is", "are", "of", "to", "in", "and", "or", "for", "on",
            "with", "what", "how", "why", "does", "do", "did", "i", "me", "my", "we",
            "you", "it", "its", "this", "that", "these", "those", "be", "been", "by",
            "at", "as", "from", "can", "could", "should", "would", "will", "shall",
            "may", "might", "not", "no", "so", "if", "then", "than", "but", "about",
            "many", "much", "number", "numbers", "part", "parts", "equal", "equals",
            "represents", "represent", "show", "shows", "using", "use", "used",
            "into", "have", "has", "had", "their", "they", "them", "also", "just");

    private static final Pattern PARAM = Pattern.compile("([a-zA-Z_]+)\\s*[:=]\\s*([^;\\n]+?)(?=\\s+[a-zA-Z_]+\\s*[:=]|$)");

    private final JsonNode knowledge;
    private final JsonNode engine;
    private final JsonNode questionBank;
    private final Random random = new Random();

    public EducationEngineModule() {
        this.knowledge = JsonStore.load("data/knowledge.json");
        this.engine = JsonStore.load("data/education_engine.json");
        this.questionBank = JsonStore.load("data/question_bank.json");
    }

    @Override
    public String respond(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        Map<String, String> params = parseParams(input);
        String fn = detectFunction(lower);
        JsonNode topic = resolveTopic(input, params);

        return switch (fn) {
            case "evaluate" -> evaluateAnswer(input, lower, topic, params);
            case "notes" -> studyNotes(topic, params);
            case "summarize" -> summarize(params, input);
            case "questions" -> generateQuestions(topic, params, lower);
            case "curriculum" -> curriculumAlignment(topic, params);
            default -> conceptExplanation(topic, params);
        };
    }

    // ---------- parameter parsing ----------

    private Map<String, String> parseParams(String input) {
        Map<String, String> map = new HashMap<>();
        Matcher m = PARAM.matcher(input);
        while (m.find()) {
            map.put(m.group(1).toLowerCase(Locale.ROOT), m.group(2).trim());
        }
        return map;
    }

    private String param(Map<String, String> p, String... keys) {
        for (String k : keys) {
            String v = p.get(k);
            if (v != null && !v.isBlank()) {
                return v.trim().replaceAll("[,;\\s]+$", "");
            }
        }
        return "";
    }

    // ---------- function detection ----------

    private String detectFunction(String lower) {
        if (lower.contains("evaluate") || lower.contains("grade") || lower.contains("check my answer")
                || lower.contains("mark my")) {
            return "evaluate";
        }
        if (lower.contains("notes") || lower.contains("study notes")) {
            return "notes";
        }
        if (lower.contains("summar")) {
            return "summarize";
        }
        if (lower.contains("question") || lower.contains("mcq") || lower.contains("multiple choice")
                || lower.contains("quiz") || lower.contains("fill in the blank")
                || lower.contains("true or false") || lower.contains("generate")) {
            return "questions";
        }
        if (lower.contains("curriculum") || lower.contains("syllabus") || lower.contains("align")) {
            return "curriculum";
        }
        return "explain";
    }

    private JsonNode resolveTopic(String input, Map<String, String> params) {
        String wanted = param(params, "topic", "subject");
        String haystack = (wanted + " " + input).toLowerCase(Locale.ROOT);
        JsonNode best = null;
        int bestScore = 0;
        for (JsonNode t : knowledge.path("topics")) {
            int score = 0;
            for (JsonNode kw : t.path("keywords")) {
                if (haystack.contains(kw.asText().toLowerCase(Locale.ROOT))) {
                    score += 2;
                }
            }
            if (haystack.contains(t.path("title").asText().toLowerCase(Locale.ROOT))) {
                score += 3;
            }
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    // ---------- 1. concept explanation ----------

    private String conceptExplanation(JsonNode topic, Map<String, String> params) {
        if (topic == null) {
            return "I need a topic to explain. Try:\n"
                    + "  \"explain photosynthesis, level: beginner, goal: exam prep\"\n"
                    + "Supported topics are listed in the knowledge base (math, science, history, physics, biology, english).";
        }
        String subject = topic.path("subject").asText();
        String level = param(params, "level", "academic_level");
        if (level.isEmpty()) level = "general";
        String goal = param(params, "goal", "learning_goal");

        StringBuilder sb = new StringBuilder();
        sb.append("CONCEPT EXPLANATION\n");
        sb.append("Subject: ").append(subject).append(" | Topic: ").append(topic.path("title").asText())
          .append(" | Level: ").append(level).append('\n');
        if (!goal.isEmpty()) {
            sb.append("Goal: ").append(goal).append('\n');
        }
        sb.append('\n');

        // introduction + definition
        sb.append("INTRODUCTION\n");
        sb.append("This topic belongs to ").append(subject).append(". ");
        sb.append(firstSentence(topic.path("explanation").asText())).append('\n');
        sb.append('\n');

        // key terminology
        sb.append("KEY TERMINOLOGY\n");
        List<String> terms = termsFor(subject, topic);
        if (terms.isEmpty()) {
            sb.append("(no glossary entries for this subject yet)\n");
        } else {
            for (String t : terms) {
                sb.append("- ").append(t).append('\n');
            }
        }
        sb.append('\n');

        // step-by-step explanation
        sb.append("EXPLANATION (step by step)\n");
        List<String> sentences = splitSentences(topic.path("explanation").asText());
        for (int i = 0; i < sentences.size(); i++) {
            sb.append(i + 1).append(". ").append(sentences.get(i)).append('\n');
        }
        sb.append('\n');

        // example
        sb.append("EXAMPLE\n").append(topic.path("example").asText()).append("\n\n");

        // practical applications
        sb.append("PRACTICAL APPLICATIONS\n");
        for (String app : applicationsFor(subject)) {
            sb.append("- ").append(app).append('\n');
        }
        sb.append('\n');

        // revision summary
        sb.append("REVISION SUMMARY\n");
        sb.append("- Core idea: ").append(firstSentence(topic.path("explanation").asText())).append('\n');
        sb.append("- Remember the example: ").append(firstSentence(topic.path("example").asText())).append('\n');
        if (!terms.isEmpty()) {
            sb.append("- Key terms: ").append(String.join(", ", topTerms(terms, 3))).append('\n');
        }
        if (level.equalsIgnoreCase("beginner")) {
            sb.append("\nNote: beginner level - focus on the example and revision summary first.");
        }
        return sb.toString();
    }

    // ---------- 2. study notes ----------

    private String studyNotes(JsonNode topic, Map<String, String> params) {
        if (topic == null) {
            return "Tell me which topic to make notes for, e.g. \"study notes: topic fractions, subject math\".";
        }
        String subject = topic.path("subject").asText();
        StringBuilder sb = new StringBuilder();
        sb.append("STUDY NOTES\n");
        sb.append("Topic: ").append(topic.path("title").asText())
          .append(" (").append(subject).append(")\n\n");

        sb.append("INTRODUCTION\n");
        sb.append(firstSentence(topic.path("explanation").asText())).append("\n\n");

        List<String> defs = termsFor(subject, topic);
        sb.append("DEFINITIONS\n");
        for (String t : defs) {
            sb.append(t).append('\n');
        }
        sb.append('\n');

        sb.append("MAIN EXPLANATION\n");
        sb.append(topic.path("explanation").asText()).append("\n\n");

        List<String> formulas = formulasFor(subject);
        if (!formulas.isEmpty()) {
            sb.append("FORMULAS\n");
            for (String f : formulas) {
                sb.append("- ").append(f).append('\n');
            }
            sb.append('\n');
        }

        sb.append("IMPORTANT CONCEPTS\n");
        for (String t : topTerms(defs, 3)) {
            sb.append("- ").append(t).append('\n');
        }
        sb.append('\n');

        sb.append("EXAMPLE\n").append(topic.path("example").asText()).append("\n\n");

        sb.append("KEY TAKEAWAYS\n");
        sb.append("- ").append(firstSentence(topic.path("explanation").asText())).append('\n');
        sb.append("- Practice with: \"quiz me on ").append(topic.path("title").asText().toLowerCase(Locale.ROOT)).append("\"\n");
        sb.append("- Revise the example until you can explain it in your own words.\n");
        return sb.toString();
    }

    // ---------- 3. summarization ----------

    private String summarize(Map<String, String> params, String input) {
        String source = param(params, "source", "material", "text");
        if (source.isEmpty()) {
            // fall back: text after "summarize" keyword
            int idx = input.toLowerCase(Locale.ROOT).indexOf("summar");
            if (idx >= 0) {
                source = input.substring(idx).replaceFirst("(?i)summari[sz]e\\s*(of\\s+)?", "").trim();
            }
        }
        if (source.isBlank() || source.length() < 40) {
            return "Please provide the material to summarize, e.g.\n"
                    + "  \"summarize source: <paste your text here>, length: standard\"\n"
                    + "Lengths supported: short, standard, detailed.";
        }

        String length = param(params, "length", "detail").toLowerCase(Locale.ROOT);
        List<String> sentences = splitSentences(source);
        if (sentences.size() <= 2) {
            return "The text is already very short. Original text:\n\n" + source;
        }

        Map<String, Integer> freq = wordFrequency(sentences);
        List<SentenceScore> scored = new ArrayList<>();
        for (int i = 0; i < sentences.size(); i++) {
            scored.add(new SentenceScore(i, scoreSentence(sentences.get(i), freq)));
        }
        scored.sort((a, b) -> Double.compare(b.score, a.score));

        int keep = switch (length) {
            case "short" -> Math.min(2, sentences.size());
            case "detailed" -> Math.min(Math.max(4, sentences.size() / 2), 8);
            default -> Math.min(4, sentences.size());
        };

        Set<Integer> chosen = new HashSet<>();
        for (int i = 0; i < keep && i < scored.size(); i++) {
            chosen.add(scored.get(i).index);
        }
        // always keep the first sentence (usually the main point)
        chosen.add(0);

        StringBuilder sb = new StringBuilder();
        sb.append("SUMMARY (").append(length.isEmpty() ? "standard" : length).append(")\n\n");
        for (int i = 0; i < sentences.size(); i++) {
            if (chosen.contains(i)) {
                sb.append(sentences.get(i)).append(' ');
            }
        }
        sb.append("\n\n(Extractive summary: sentences selected by keyword relevance. "
                + "Original meaning preserved; no facts added.)");
        return sb.toString().trim();
    }

    // ---------- 4. question generation ----------
    // ---------- 5. answer evaluation ----------
    // ---------- 6. curriculum alignment ----------

    private String generateQuestions(JsonNode topic, Map<String, String> params, String lower) {
        String subject = topic != null ? topic.path("subject").asText()
                : param(params, "subject");
        if (subject.isEmpty()) {
            for (String s : new String[]{"math", "science", "biology", "history", "english", "physics"}) {
                if (lower.contains(s)) {
                    subject = s;
                    break;
                }
            }
        }
        if (subject.isEmpty()) subject = "general";

        boolean mcq = lower.contains("mcq") || lower.contains("multiple choice");
        boolean tf = lower.contains("true or false") || lower.contains("true/false");
        boolean fill = lower.contains("fill") || lower.contains("blank");
        boolean shortQ = lower.contains("short") || lower.contains("answer");
        boolean longQ = lower.contains("long") || lower.contains("essay") || lower.contains("detailed");
        boolean appQ = lower.contains("application") || lower.contains("apply") || lower.contains("scenario");
        boolean any = mcq || tf || fill || shortQ || longQ || appQ;

        List<JsonNode> bankQs = questionsFor(subject);
        StringBuilder sb = new StringBuilder();
        sb.append("GENERATED QUESTIONS - ").append(subject.toUpperCase(Locale.ROOT)).append('\n');
        if (topic != null) {
            sb.append("Topic focus: ").append(topic.path("title").asText()).append('\n');
        }
        sb.append('\n');
        int n = 0;

        // MCQs from the question bank
        if (mcq || (!any && !bankQs.isEmpty())) {
            List<String> distractors = answersFor(subject);
            for (JsonNode q : bankQs) {
                if (n >= 8) break;
                if (mcq || !any) {
                    sb.append("MCQ ").append(++n).append(": ").append(q.path("question").asText()).append('\n');
                    List<String> opts = new ArrayList<>(distractors);
                    opts.remove(q.path("answer").asText());
                    Collections.shuffle(opts);
                    List<String> choices = new ArrayList<>();
                    choices.add(q.path("answer").asText());
                    for (int i = 0; i < Math.min(3, opts.size()); i++) {
                        choices.add(opts.get(i));
                    }
                    Collections.shuffle(choices);
                    char letter = 'A';
                    for (String c : choices) {
                        sb.append("   ").append(letter++).append(") ").append(c).append('\n');
                    }
                    sb.append('\n');
                }
            }
        }

        // True/False from topic explanation
        if (topic != null && (tf || (!any && mcq))) {
            String statement = firstSentence(topic.path("explanation").asText());
            sb.append("TRUE/FALSE ").append(++n).append(": ").append(statement).append('\n');
            sb.append("   Answer: True (verify against the explanation above)\n\n");
        }

        // Fill in the blanks from topic keywords
        if (topic != null && (fill || (!any && tf))) {
            String expl = topic.path("explanation").asText();
            for (JsonNode kw : topic.path("keywords")) {
                String term = kw.asText();
                int idx = expl.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));
                if (idx >= 0) {
                    String before = expl.substring(0, idx);
                    String after = expl.substring(idx + term.length());
                    int sentStart = Math.max(before.lastIndexOf('.'), before.lastIndexOf('!')) + 1;
                    int sentEndRel = after.indexOf('.');
                    String sentence = expl.substring(sentStart,
                            sentEndRel >= 0 ? idx + term.length() + sentEndRel + 1 : expl.length());
                    String blanked = sentence.replaceFirst("(?i)" + Pattern.quote(term), "______");
                    sb.append("FILL IN THE BLANK ").append(++n).append(": ").append(blanked.trim()).append('\n');
                    sb.append("   Answer: ").append(term).append('\n');
                    sb.append("   (Tip: ").append(firstSentence(topic.path("explanation").asText())).append(")\n\n");
                    break;
                }
            }
        }

        // Short answer from bank
        if (shortQ || (!any && fill)) {
            int added = 0;
            for (JsonNode q : bankQs) {
                if (added >= 3) break;
                sb.append("SHORT ANSWER ").append(++n).append(": ").append(q.path("question").asText()).append('\n');
                sb.append("   Expected answer: ").append(q.path("answer").asText()).append("\n\n");
                added++;
            }
        }

        // Long answer prompt
        if (longQ || appQ || (!any && shortQ)) {
            if (topic != null) {
                sb.append("LONG ANSWER ").append(++n).append(": Explain ")
                  .append(topic.path("title").asText())
                  .append(" in detail: definition, how it works step by step, one real-world example, ")
                  .append("and why it matters. Aim for 150-250 words.\n\n");
                sb.append("APPLICATION ").append(++n).append(": Describe a realistic situation where ")
                  .append(firstSentence(topic.path("explanation").asText()).toLowerCase(Locale.ROOT))
                  .append(" Your answer should apply the concept, not just repeat the definition.\n");
            } else {
                sb.append("LONG ANSWER ").append(++n)
                  .append(": Choose a topic from your syllabus and explain it with definition, steps, example, and significance.\n");
            }
        }

        if (n == 0) {
            return "I need a subject or topic to generate questions. Try:\n"
                    + "  \"generate mcq questions for math\"\n"
                    + "  \"quiz questions topic: fractions, subject: math, types: mcq, true or false\"";
        }
        return sb.toString();
    }

    private String evaluateAnswer(String input, String lower, JsonNode topic, Map<String, String> params) {
        String answerText = param(params, "answer", "response", "myanswer");
        if (answerText.isEmpty()) {
            int idx = lower.indexOf("answer");
            if (idx >= 0) {
                answerText = input.substring(idx).replaceFirst("(?i)answer\\s*[:=]?\\s*", "").trim();
            }
        }
        if (answerText.isBlank()) {
            return "Please include your answer to evaluate, e.g.\n"
                    + "  \"evaluate answer: The water cycle is evaporation then condensation then rain\"\n"
                    + "I'll compare it against the answer key and give rubric-style feedback.";
        }

        // find the best answer key: matching bank question, else topic explanation
        String key = null;
        String questionText = "";
        JsonNode bestQ = null;
        int bestScore = 0;
        for (JsonNode q : questionBank.path("questions")) {
            int score = overlapScore(answerText + " " + input, q.path("question").asText() + " " + q.path("answer").asText());
            if (score > bestScore) {
                bestScore = score;
                bestQ = q;
            }
        }
        if (bestQ != null && bestScore > 0) {
            key = bestQ.path("answer").asText();
            questionText = bestQ.path("question").asText();
        } else if (topic != null) {
            // use the opening sentences as the key - the full explanation is too broad
            List<String> keySents = splitSentences(topic.path("explanation").asText());
            key = String.join(" ", keySents.subList(0, Math.min(2, keySents.size())));
            questionText = "Explain " + topic.path("title").asText();
        } else {
            return "I couldn't find a reliable answer key for that response. "
                    + "Try naming the topic, e.g. \"evaluate topic: fractions, answer: ...\"";
        }

        Set<String> keyTerms = contentWords(key);
        Set<String> answerTerms = contentWords(answerText);
        Set<String> matched = new HashSet<>(answerTerms);
        matched.retainAll(keyTerms);
        Set<String> missing = new HashSet<>(keyTerms);
        missing.removeAll(answerTerms);

        double coverage = keyTerms.isEmpty() ? 0 : (double) matched.size() / keyTerms.size();
        String verdict = coverage >= 0.7 ? "Strong" : coverage >= 0.4 ? "Partial" : "Needs work";

        StringBuilder sb = new StringBuilder();
        sb.append("ANSWER EVALUATION\n");
        sb.append("Question: ").append(questionText).append('\n');
        sb.append("Your answer: ").append(answerText).append("\n\n");
        sb.append("Reference key: ").append(key).append("\n\n");
        sb.append("Rubric check (keyword coverage of key concepts): ").append(Math.round(coverage * 100)).append("%\n");
        sb.append("Verdict: ").append(verdict).append('\n');
        if (!matched.isEmpty()) {
            sb.append("Correct concepts identified: ").append(String.join(", ", topTerms(new ArrayList<>(matched), 6))).append('\n');
        }
        if (!missing.isEmpty()) {
            sb.append("Missing or unclear concepts: ").append(String.join(", ", topTerms(new ArrayList<>(missing), 6))).append('\n');
        }
        sb.append("\nFeedback: ");
        if (coverage >= 0.7) {
            sb.append("Your answer covers the main concepts. Check precision of terminology and add an example to strengthen it.");
        } else if (coverage >= 0.4) {
            sb.append("You have part of the idea. Review the missing concepts above and re-explain them in your own words.");
        } else {
            sb.append("The answer misses most key concepts. Re-read the explanation and try again - focus on the definition first, then the steps.");
        }
        sb.append("\n\nNote: this is an automated rubric check, not a substitute for teacher grading, "
                + "especially for subjective or essay-type answers.");
        return sb.toString();
    }

    private String curriculumAlignment(JsonNode topic, Map<String, String> params) {
        String subject = param(params, "subject");
        if (subject.isEmpty() && topic != null) {
            subject = topic.path("subject").asText();
        }
        String syllabus = param(params, "syllabus", "source");
        JsonNode curr = engine.path("curriculum");

        StringBuilder sb = new StringBuilder();
        sb.append("CURRICULUM ALIGNMENT CHECK\n");
        sb.append(curr.path("note").asText()).append("\n\n");

        if (subject.isEmpty()) {
            sb.append("Available reference subjects: ");
            List<String> subs = new ArrayList<>();
            curr.path("subjects").fieldNames().forEachRemaining(subs::add);
            sb.append(String.join(", ", subs)).append('\n');
            sb.append("Ask e.g. \"curriculum alignment subject: math\" or include \"syllabus: <your syllabus text>\".\n");
            return sb.toString();
        }

        JsonNode refTopics = curr.path("subjects").path(subject.toLowerCase(Locale.ROOT));
        if (refTopics.isMissingNode() || refTopics.isEmpty()) {
            sb.append("No reference curriculum topics for \"").append(subject)
              .append("\". This does not mean the subject is excluded - just not in our reference map.\n");
            return sb.toString();
        }

        sb.append("Reference topics for ").append(subject).append(":\n");
        for (JsonNode t : refTopics) {
            sb.append("- ").append(t.asText()).append('\n');
        }
        sb.append('\n');

        if (!syllabus.isBlank()) {
            sb.append("Checking your supplied syllabus against available material...\n");
            String syl = syllabus.toLowerCase(Locale.ROOT);
            List<String> aligned = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (JsonNode t : knowledge.path("topics")) {
                String title = t.path("title").asText();
                boolean match = syl.contains(title.toLowerCase(Locale.ROOT));
                for (JsonNode kw : t.path("keywords")) {
                    if (syl.contains(kw.asText().toLowerCase(Locale.ROOT))) {
                        match = true;
                        break;
                    }
                }
                if (match) {
                    aligned.add(title + " [" + t.path("subject").asText() + "]");
                } else {
                    missing.add(title + " [" + t.path("subject").asText() + "]");
                }
            }
            sb.append("Topics in your syllabus that I can explain: ").append(aligned.isEmpty() ? "none found" : String.join(", ", aligned)).append('\n');
            sb.append("Topics I have but didn't match in your syllabus: ").append(missing.isEmpty() ? "none" : String.join(", ", missing)).append('\n');
            sb.append("\nThis is a keyword match against my knowledge base - it is NOT an official curriculum statement.\n");
        } else {
            sb.append("To check against YOUR syllabus, include it like:\n");
            sb.append("  \"curriculum alignment subject: math, syllabus: <paste syllabus>\"\n");
        }
        return sb.toString();
    }

    // ---------- helpers ----------

    private List<String> termsFor(String subject) {
        List<String> out = new ArrayList<>();
        for (JsonNode t : engine.path("terminology").path(subject.toLowerCase(Locale.ROOT))) {
            out.add(t.path("term").asText() + " - " + t.path("definition").asText());
        }
        return out;
    }

    /** Returns glossary terms relevant to the topic; falls back to all subject terms. */
    private List<String> termsFor(String subject, JsonNode topic) {
        List<String> all = termsFor(subject);
        if (topic == null || all.isEmpty()) {
            return all;
        }
        String ctx = (topic.path("title").asText() + " " + topic.path("explanation").asText())
                .toLowerCase(Locale.ROOT);
        List<String> relevant = all.stream().filter(t -> {
            String term = t.split(" - ")[0];
            for (String w : contentWords(term)) {
                if (ctx.contains(w)) {
                    return true;
                }
            }
            return false;
        }).toList();
        return relevant.isEmpty() ? all : relevant;
    }

    private List<String> applicationsFor(String subject) {
        List<String> out = new ArrayList<>();
        for (JsonNode a : engine.path("applications").path(subject.toLowerCase(Locale.ROOT))) {
            out.add(a.asText());
        }
        return out;
    }

    private List<String> formulasFor(String subject) {
        List<String> out = new ArrayList<>();
        for (JsonNode f : engine.path("formulas").path(subject.toLowerCase(Locale.ROOT))) {
            out.add(f.asText());
        }
        return out;
    }

    private List<JsonNode> questionsFor(String subject) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode q : questionBank.path("questions")) {
            if (q.path("subject").asText().equalsIgnoreCase(subject)
                    || subject.equalsIgnoreCase("general")) {
                out.add(q);
            }
        }
        return out;
    }

    private List<String> answersFor(String subject) {
        List<String> out = new ArrayList<>();
        for (JsonNode q : questionsFor(subject)) {
            out.add(q.path("answer").asText());
        }
        return out;
    }

    private List<String> splitSentences(String text) {
        String[] parts = text.split("(?<=[.!?])\\s+");
        List<String> out = new ArrayList<>();
        for (String s : parts) {
            s = s.trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private String firstSentence(String text) {
        List<String> s = splitSentences(text);
        return s.isEmpty() ? text : s.get(0);
    }

    private Map<String, Integer> wordFrequency(List<String> sentences) {
        Map<String, Integer> freq = new HashMap<>();
        for (String s : sentences) {
            for (String w : contentWords(s)) {
                freq.merge(w, 1, Integer::sum);
            }
        }
        return freq;
    }

    private double scoreSentence(String sentence, Map<String, Integer> freq) {
        double score = 0;
        for (String w : contentWords(sentence)) {
            score += freq.getOrDefault(w, 0);
        }
        return score / Math.max(1, contentWords(sentence).size());
    }

    private Set<String> contentWords(String text) {
        Set<String> words = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() > 2 && !STOPWORDS.contains(w)) {
                words.add(w);
            }
        }
        return words;
    }

    private int overlapScore(String a, String b) {
        Set<String> wa = contentWords(a);
        Set<String> wb = contentWords(b);
        if (wa.isEmpty() || wb.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(wa);
        inter.retainAll(wb);
        return inter.size();
    }

    private List<String> topTerms(List<String> terms, int max) {
        return terms.stream().limit(max).toList();
    }

    private record SentenceScore(int index, double score) {
    }
}
