package edu.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AssessmentEngineTest {

    private static final String SOURCE = """
            Alpha: the first defined concept with enough words
            Beta: the second defined concept with enough words
            Gamma: the third defined concept with enough words
            Delta: the fourth defined concept with enough words
            Epsilon: the fifth defined concept with enough words
            Zeta: the sixth defined concept with enough words
            Eta: the seventh defined concept with enough words
            Theta: the eighth defined concept with enough words
            """;

    @Test
    void generatesRequestedQuestionCount() {
        Quiz quiz = new AssessmentEngine(42).generate("Math", "Topic", "General",
                "standard", 5, Set.of(QuestionType.MCQ, QuestionType.TRUE_FALSE,
                        QuestionType.SHORT_ANSWER), SOURCE);
        // regression: the old per-type cap produced only 3 questions for count=5
        assertEquals(5, quiz.questions().size());
    }

    @Test
    void neverExceedsRequestedCount() {
        Quiz quiz = new AssessmentEngine(7).generate("Math", "Topic", "General",
                "easy", 4, Set.of(QuestionType.MCQ, QuestionType.TRUE_FALSE,
                        QuestionType.SHORT_ANSWER), SOURCE);
        assertTrue(quiz.questions().size() <= 4);
        assertFalse(quiz.questions().isEmpty());
    }

    @Test
    void honestFallbackWhenNoSource() {
        Quiz quiz = new AssessmentEngine(1).generate("Math", "Topic", "General",
                "standard", 5, Set.of(QuestionType.MCQ), "");
        assertEquals(1, quiz.questions().size());
        assertTrue(quiz.questions().get(0).text().contains("No usable source material"));
    }
}
