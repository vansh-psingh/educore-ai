package edu.core;

import java.util.List;

/**
 * Student-safe view of a Question. Contains no answer key or explanation.
 * This is the only representation that may be sent to students before submission.
 */
public class StudentQuestion {
    private final String text;
    private final QuestionType type;
    private final List<String> options;
    private final int marks;
    private final String topic;

    public StudentQuestion(Question q) {
        this.text = q.text();
        this.type = q.type();
        this.options = q.options();
        this.marks = q.marks();
        this.topic = q.topic();
    }

    public String text() { return text; }
    public QuestionType type() { return type; }
    public List<String> options() { return options; }
    public int marks() { return marks; }
    public String topic() { return topic; }
}
