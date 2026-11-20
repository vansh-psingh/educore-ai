package com.educore.ai.modules;

import com.educore.ai.StudentData;
import edu.core.LearningEngine;

/**
 * Personalized learning module - generates a learning report from the
 * authorized student record (data/student_profile.json) using
 * edu.core.LearningEngine. Only recorded data is used; nothing is invented.
 */
public final class LearningModule implements EducoreModule {

    private final LearningEngine engine = new LearningEngine();

    @Override
    public String respond(String input) {
        var student = StudentData.loadProfile();
        return engine.generateReport(student)
                + "Suggested next quiz difficulty: " + engine.suggestDifficulty(student)
                + "\n\n(Edit data/student_profile.json to update your record: subjects, completed topics, quiz history, daily study minutes.)";
    }
}
