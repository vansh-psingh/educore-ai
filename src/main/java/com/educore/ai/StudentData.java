package com.educore.ai;

import com.fasterxml.jackson.databind.JsonNode;
import edu.core.QuizResult;
import edu.core.StudentProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads the authorized student record from data/student_profile.json
 * and adapts it to the edu.core StudentProfile used by the
 * LearningEngine and AssessmentEngine.
 */
public final class StudentData {

    private StudentData() {
    }

    public static StudentProfile loadProfile() {
        JsonNode s = JsonStore.load("data/student_profile.json").path("student");

        Set<String> subjects = new LinkedHashSet<>();
        for (JsonNode v : s.path("subjects")) {
            subjects.add(v.asText());
        }

        List<String> goals = new ArrayList<>();
        for (JsonNode v : s.path("learning_goals")) {
            goals.add(v.asText());
        }

        Set<String> completed = new LinkedHashSet<>();
        for (JsonNode v : s.path("completed_topics")) {
            completed.add(v.asText());
        }

        List<QuizResult> history = new ArrayList<>();
        for (JsonNode q : s.path("quiz_history")) {
            history.add(new QuizResult(
                    q.path("topic").asText(""),
                    q.path("subject").asText(""),
                    q.path("score").asInt(0),
                    q.path("max_score").asInt(100),
                    q.path("date").asText("")));
        }

        return new StudentProfile(
                s.path("academic_level").asText("General"),
                subjects,
                goals,
                completed,
                history,
                s.path("daily_study_minutes").asInt(45),
                s.path("language").asText("English"));
    }
}
