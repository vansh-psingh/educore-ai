package com.educore.ai;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EssayServiceTest {

    @Test
    void parsesValidEvaluation() {
        String raw = """
                {"criteria":[
                  {"name":"Relevance","score":4,"max":5,"comment":"on topic"},
                  {"name":"Completeness","score":3,"max":5,"comment":"missing detail"}
                ],"total_score":7,"max_marks":10,
                 "feedback":"Good structure.","strengths":["clear"],
                 "improvements":["add examples"],"missing_concepts":["mitosis"]}
                """;
        ObjectNode ev = EssayService.parseEvaluation(raw, 10);
        assertEquals(7.0, ev.path("total_score").asDouble(), 0.01);
        assertEquals(2, ev.path("criteria").size());
        assertEquals("Good structure.", ev.path("feedback").asText());
        assertEquals(1, ev.path("missing_concepts").size());
    }

    @Test
    void clampsScoresToMaxMarks() {
        String raw = "{\"criteria\":[{\"name\":\"A\",\"score\":99,\"max\":5}],\"total_score\":99}";
        ObjectNode ev = EssayService.parseEvaluation(raw, 10);
        // criterion capped at its max (5), total capped at maxMarks (10)
        assertTrue(ev.path("total_score").asDouble() <= 10.0);
        assertEquals(5.0, ev.path("criteria").get(0).path("score").asDouble(), 0.01);
    }

    @Test
    void toleratesMarkdownFencesAndProse() {
        String raw = "Here is the evaluation:\n```json\n{\"total_score\":8,\"feedback\":\"ok\"}\n```\nDone.";
        ObjectNode ev = EssayService.parseEvaluation(raw, 10);
        assertEquals(8.0, ev.path("total_score").asDouble(), 0.01);
    }

    @Test
    void handlesGarbageGracefully() {
        ObjectNode ev = EssayService.parseEvaluation("not json at all", 10);
        assertEquals(0.0, ev.path("total_score").asDouble(), 0.01);
        assertTrue(ev.path("criteria").isArray());
        ObjectNode ev2 = EssayService.parseEvaluation(null, 10);
        assertEquals(0.0, ev2.path("total_score").asDouble(), 0.01);
    }

    @Test
    void handlesLatexBackslashes() {
        String raw = "{\"feedback\":\"Use \\\\frac{a}{b} and \\\\(x^2\\\\) correctly\",\"total_score\":6}";
        ObjectNode ev = EssayService.parseEvaluation(raw, 10);
        assertEquals(6.0, ev.path("total_score").asDouble(), 0.01);
        assertTrue(ev.path("feedback").asText().contains("frac"));
    }
}
