package edu.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class StudentProfile {
    private final String academicLevel;
    private final Set<String> subjects;
    private final List<String> learningGoals;
    private final Set<String> completedTopics;
    private final List<QuizResult> quizHistory;
    private final int dailyStudyMinutes;
    private final String preferredLanguage;

    public StudentProfile(String academicLevel,
                          Set<String> subjects,
                          List<String> learningGoals,
                          Set<String> completedTopics,
                          List<QuizResult> quizHistory,
                          int dailyStudyMinutes,
                          String preferredLanguage) {
        this.academicLevel = academicLevel;
        this.subjects = subjects == null ? new LinkedHashSet<>() : new LinkedHashSet<>(subjects);
        this.learningGoals = learningGoals == null ? new ArrayList<>() : new ArrayList<>(learningGoals);
        this.completedTopics = completedTopics == null ? new LinkedHashSet<>() : new LinkedHashSet<>(completedTopics);
        this.quizHistory = quizHistory == null ? new ArrayList<>() : new ArrayList<>(quizHistory);
        this.dailyStudyMinutes = Math.max(0, dailyStudyMinutes);
        this.preferredLanguage = preferredLanguage == null ? "English" : preferredLanguage;
    }

    public String academicLevel() { return academicLevel; }
    public Set<String> subjects() { return subjects; }
    public List<String> learningGoals() { return learningGoals; }
    public Set<String> completedTopics() { return completedTopics; }
    public List<QuizResult> quizHistory() { return quizHistory; }
    public int dailyStudyMinutes() { return dailyStudyMinutes; }
    public String preferredLanguage() { return preferredLanguage; }
}
