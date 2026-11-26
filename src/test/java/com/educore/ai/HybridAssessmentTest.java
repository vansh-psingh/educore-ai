package com.educore.ai;

import edu.core.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HybridAssessmentTest {

    @Test
    void fixJsonEscapesPreservesValidEscapes() {
        // \n and \" are valid JSON escapes; \p (as in LaTeX \pm) is not and
        // must be doubled or Jackson rejects the document
        String in = "{\"q\":\"line\\n break \\\"quote\\\" \\pm 3\"}";
        String out = HybridAssessment.fixJsonEscapes(in);
        assertTrue(out.contains("\\n"));
        assertTrue(out.contains("\\\""));
        assertTrue(out.contains("\\\\pm"));
    }

    @Test
    void parsesMcqQuestions() {
        String raw = """
                {"questions":[
                  {"question":"What is 2+2?","type":"mcq",
                   "options":["3","4","5","6"],"correct_answer":"4",
                   "explanation":"basic math","marks":1}
                ]}
                """;
        List<edu.core.Question> qs = HybridAssessment.parseQuestions(raw, 5);
        assertEquals(1, qs.size());
        assertEquals(QuestionType.MCQ, qs.get(0).type());
        assertEquals("4", qs.get(0).correctAnswer());
    }

    @Test
    void mapsLongAnswerType() {
        String raw = """
                {"questions":[
                  {"question":"Explain osmosis.","type":"long_answer",
                   "options":[],"correct_answer":"water moves across membrane",
                   "explanation":"diffusion of water","marks":8}
                ]}
                """;
        List<edu.core.Question> qs = HybridAssessment.parseQuestions(raw, 5);
        assertEquals(1, qs.size());
        assertEquals(QuestionType.LONG_ANSWER, qs.get(0).type());
        assertEquals(8, qs.get(0).marks());
    }

    @Test
    void rejectsInvalidJson() {
        assertTrue(HybridAssessment.parseQuestions("garbage", 5).isEmpty());
        assertTrue(HybridAssessment.parseQuestions("", 5).isEmpty());
        assertTrue(HybridAssessment.parseQuestions(null, 5).isEmpty());
    }

    @Test
    void skipsQuestionsWithoutAnswer() {
        String raw = "{\"questions\":[{\"question\":\"Q?\",\"type\":\"mcq\",\"options\":[\"a\",\"b\"]}]}";
        assertTrue(HybridAssessment.parseQuestions(raw, 5).isEmpty());
    }
}
