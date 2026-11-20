package edu.core;

public class QuizResult {
    private final String topic;
    private final String subject;
    private final int score;      // percentage 0-100
    private final int maxScore;
    private final String date;    // ISO date, informational only

    public QuizResult(String topic, String subject, int score, int maxScore, String date) {
        this.topic = topic;
        this.subject = subject;
        this.score = Math.max(0, Math.min(100, score));
        this.maxScore = Math.max(1, maxScore);
        this.date = date;
    }

    public String topic() { return topic; }
    public String subject() { return subject; }
    public int score() { return score; }
    public int maxScore() { return maxScore; }
    public String date() { return date; }
}
