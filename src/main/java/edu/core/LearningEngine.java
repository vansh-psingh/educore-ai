package edu.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * EDUCORE AI personalized learning advisor.
 * All recommendations are derived strictly from the student's recorded data.
 * Quiz scores are treated as progress indicators, not measures of ability.
 */
public class LearningEngine {

    private static final int REVISION_THRESHOLD = 70;   // avg quiz score below this -> revision
    private static final int MASTERY_THRESHOLD = 85;    // avg quiz score at/above this -> consider mastered

    public String generateReport(StudentProfile student) {
        StringBuilder sb = new StringBuilder();
        Map<String, Double> topicAverages = analyzeQuizPerformance(student.quizHistory());
        Set<String> revisionTopics = identifyRevisionTopics(student, topicAverages);

        sb.append("==================================================\n");
        sb.append("  EDUCORE AI - PERSONALIZED LEARNING REPORT\n");
        sb.append("==================================================\n\n");

        appendOverview(sb, student, topicAverages);
        appendCompletedTopics(sb, student);
        appendRevisionTopics(sb, student, topicAverages, revisionTopics);
        appendActivities(sb, student, revisionTopics, topicAverages);
        appendSchedule(sb, student, revisionTopics);
        appendNextGoal(sb, student, revisionTopics, topicAverages);
        return sb.toString();
    }

    // ---------- 1. Overview ----------

    private void appendOverview(StringBuilder sb, StudentProfile s, Map<String, Double> avgs) {
        sb.append("1. CURRENT LEARNING OVERVIEW\n");
        sb.append("----------------------------------\n");
        sb.append("Academic level      : ").append(s.academicLevel()).append('\n');
        sb.append("Subjects            : ").append(String.join(", ", s.subjects())).append('\n');
        sb.append("Daily study time    : ").append(s.dailyStudyMinutes()).append(" minutes\n");
        sb.append("Preferred language  : ").append(s.preferredLanguage()).append('\n');
        sb.append("Topics completed    : ").append(s.completedTopics().size()).append('\n');
        sb.append("Quizzes recorded    : ").append(s.quizHistory().size()).append('\n');
        if (!avgs.isEmpty()) {
            double overall = avgs.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            sb.append(String.format("Average quiz score   : %.1f%% across %d assessed topic(s)\n",
                    overall, avgs.size()));
        } else {
            sb.append("Average quiz score   : no quiz data recorded yet\n");
        }
        sb.append("Note: scores reflect current progress on recorded material,\n");
        sb.append("not a fixed measure of ability.\n\n");
    }

    // ---------- 2. Completed topics ----------

    private void appendCompletedTopics(StringBuilder sb, StudentProfile s) {
        sb.append("2. TOPICS COMPLETED\n");
        sb.append("----------------------------------\n");
        if (s.completedTopics().isEmpty()) {
            sb.append("No topics marked complete yet.\n");
        } else {
            s.completedTopics().stream().sorted().forEach(t -> sb.append("  [x] ").append(t).append('\n'));
        }
        sb.append('\n');
    }

    // ---------- 3. Revision topics ----------

    private void appendRevisionTopics(StringBuilder sb, StudentProfile s,
                                      Map<String, Double> avgs, Set<String> revision) {
        sb.append("3. TOPICS FOR REVISION\n");
        sb.append("----------------------------------\n");
        if (avgs.isEmpty()) {
            sb.append("No quiz data available - revision list will appear after the first quizzes.\n");
        } else if (revision.isEmpty()) {
            sb.append("No topics currently flagged for revision. Nice consistency!\n");
        } else {
            for (String topic : revision) {
                sb.append(String.format("  [!] %-30s avg score %.1f%% (below %d%% threshold)\n",
                        topic, avgs.get(topic), REVISION_THRESHOLD));
            }
        }
        sb.append('\n');
    }

    // ---------- 4. Recommended activities ----------

    private void appendActivities(StringBuilder sb, StudentProfile s,
                                  Set<String> revision, Map<String, Double> avgs) {
        sb.append("4. RECOMMENDED ACTIVITIES\n");
        sb.append("----------------------------------\n");
        List<String> acts = new ArrayList<>();

        if (s.quizHistory().isEmpty()) {
            acts.add("Take a short baseline quiz in each subject to establish a starting point.");
            acts.add("Re-read the core notes for your first incomplete topic.");
        } else {
            if (!revision.isEmpty()) {
                acts.add("Spaced review: revisit " + revision.size() + " flagged topic(s) using "
                        + "short summary notes and one worked example each.");
                acts.add("Practice with slightly easier questions first, then rebuild toward "
                        + "the original difficulty.");
            } else {
                acts.add("Keep the current pace - review completed topics weekly to retain them.");
            }
            double overall = avgs.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (overall >= MASTERY_THRESHOLD) {
                acts.add("Stretch activity: apply mastered concepts to a new, slightly harder problem set.");
            } else if (overall < REVISION_THRESHOLD) {
                acts.add("Focus on understanding over speed: rework missed questions and explain "
                        + "each answer in your own words.");
            } else {
                acts.add("Mixed practice: alternate revision topics with new material to keep "
                        + "motivation steady.");
            }
            acts.add("Teach-back: explain one completed topic to yourself or a peer.");
        }
        acts.forEach(a -> sb.append("  - ").append(a).append('\n'));
        sb.append('\n');
    }

    // ---------- 5. Study schedule ----------

    private void appendSchedule(StringBuilder sb, StudentProfile s, Set<String> revision) {
        sb.append("5. SUGGESTED STUDY SCHEDULE\n");
        sb.append("----------------------------------\n");
        int minutes = s.dailyStudyMinutes();
        if (minutes <= 0) {
            sb.append("No daily study time recorded. Set available minutes to generate a schedule.\n\n");
            return;
        }

        // 7-day plan; revision topics spread across the week
        List<String> rev = new ArrayList<>(revision);
        rev.sort(Comparator.naturalOrder());

        sb.append(String.format("Daily budget: %d minutes\n", minutes));
        String[] days = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
        for (int i = 0; i < days.length; i++) {
            StringBuilder line = new StringBuilder("  ").append(days[i]).append(": ");
            if (rev.isEmpty()) {
                line.append(i < 5 ? "30 min new topic + 15 min review" : "60 min consolidation / rest");
            } else {
                String topic = rev.get(i % rev.size());
                line.append(String.format("%d min revision: %s", Math.min(minutes, 20 + i * 5), topic));
            }
            sb.append(line).append('\n');
        }
        sb.append('\n');
    }

    // ---------- 6. Next goal ----------

    private void appendNextGoal(StringBuilder sb, StudentProfile s,
                                Set<String> revision, Map<String, Double> avgs) {
        sb.append("6. NEXT LEARNING GOAL\n");
        sb.append("----------------------------------\n");
        if (!revision.isEmpty()) {
            String next = revision.iterator().next();
            sb.append("  Raise your average on \"").append(next)
              .append("\" above ").append(REVISION_THRESHOLD)
              .append("% through focused revision and re-quizzing.\n");
        } else if (avgs.isEmpty()) {
            sb.append("  Complete the first topic and record a baseline quiz score.\n");
        } else {
            double overall = avgs.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            if (overall >= MASTERY_THRESHOLD) {
                sb.append("  Start the next uncompleted topic in your strongest subject area.\n");
            } else {
                sb.append("  Finish reviewing flagged topics, then move to the next incomplete topic.\n");
            }
        }
        sb.append('\n');
    }

    // ---------- Analysis helpers ----------

    /** Average quiz score per topic, sorted by score ascending. */
    private Map<String, Double> analyzeQuizPerformance(List<QuizResult> history) {
        Map<String, double[]> sums = new LinkedHashMap<>(); // topic -> [sum, count]
        for (QuizResult q : history) {
            double[] acc = sums.computeIfAbsent(q.topic(), k -> new double[2]);
            acc[0] += q.score();
            acc[1]++;
        }
        Map<String, Double> avgs = new LinkedHashMap<>();
        sums.forEach((t, acc) -> avgs.put(t, acc[0] / acc[1]));
        // sort ascending by average
        Map<String, Double> sorted = new LinkedHashMap<>();
        avgs.entrySet().stream()
            .sorted(Map.Entry.comparingByValue())
            .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** Topics whose average quiz score is below the revision threshold. */
    private Set<String> identifyRevisionTopics(StudentProfile s, Map<String, Double> avgs) {
        Set<String> revision = new TreeSet<>();
        avgs.forEach((topic, avg) -> {
            if (avg < REVISION_THRESHOLD) revision.add(topic);
        });
        return revision;
    }

    // ---------- Difficulty adaptation helper ----------

    /** Suggested next quiz difficulty based on recent average. */
    public String suggestDifficulty(StudentProfile s) {
        Map<String, Double> avgs = analyzeQuizPerformance(s.quizHistory());
        if (avgs.isEmpty()) return "baseline";
        double overall = avgs.values().stream().mapToDouble(Double::doubleValue).average().orElse(0);
        if (overall >= MASTERY_THRESHOLD) return "advanced";
        if (overall >= REVISION_THRESHOLD) return "standard";
        return "foundational";
    }
}
