package edu.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A single assessment question.
 * This object holds the protected grading data (correct answer, explanation).
 * It must never be serialized directly to students - use StudentQuestion for that.
 */
public class Question {
    private final String text;
    private final QuestionType type;
    private final List<String> options;      // empty for non-MCQ
    private final String correctAnswer;      // protected: never sent to students pre-submission
    private final String explanation;        // protected: revealed after submission
    private final int marks;
    private final String topic;

    public Question(String text, QuestionType type, List<String> options,
                    String correctAnswer, String explanation, int marks, String topic) {
        this.text = text;
        this.type = type;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.correctAnswer = correctAnswer;
        this.explanation = explanation;
        this.marks = Math.max(1, marks);
        this.topic = topic;
    }

    public String text() { return text; }
    public QuestionType type() { return type; }
    public List<String> options() { return Collections.unmodifiableList(options); }
    public String correctAnswer() { return correctAnswer; }
    public String explanation() { return explanation; }
    public int marks() { return marks; }
    public String topic() { return topic; }
}
