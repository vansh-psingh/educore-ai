package com.educore.ai;

import com.educore.ai.modules.AssessmentModule;
import com.educore.ai.modules.EducoreModule;
import com.educore.ai.modules.EducationEngineModule;
import com.educore.ai.modules.EducationModule;
import com.educore.ai.modules.ExtractionEngineModule;
import com.educore.ai.modules.LanguageModule;
import com.educore.ai.modules.LearningModule;
import com.educore.ai.modules.StudyModule;
import com.educore.ai.modules.SupportModule;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared engine used by both the CLI and the web server.
 * Routes a message to a module and returns the reply.
 */
public final class ChatEngine {

    private final IntentRouter router = new IntentRouter();
    private final Map<String, EducoreModule> modules = new LinkedHashMap<>();

    public ChatEngine() {
        modules.put("tutor", new EducationEngineModule());
        modules.put("assessment", new AssessmentModule());
        modules.put("learning", new LearningModule());
        modules.put("education", new EducationModule());
        modules.put("study", new StudyModule());
        modules.put("support", new SupportModule());
        modules.put("extraction", new ExtractionEngineModule());
        modules.put("language", new LanguageModule());
    }

    /** Returns the module name that would handle the input. */
    public String route(String input) {
        return router.route(input);
    }

    /** Returns the module's reply for the input. */
    public String respond(String input) {
        String moduleName = route(input);
        EducoreModule module = modules.get(moduleName);
        return module != null ? module.respond(input) : "I'm not sure how to help with that yet.";
    }

    /** Module name + reply pair for API responses. */
    public Map<String, String> respondWithModule(String input) {
        String moduleName = route(input);
        EducoreModule module = modules.get(moduleName);
        String reply = module != null ? module.respond(input) : "I'm not sure how to help with that yet.";
        Map<String, String> out = new LinkedHashMap<>();
        out.put("module", moduleName);
        out.put("reply", reply);
        return out;
    }

    public Map<String, EducoreModule> modules() {
        return modules;
    }
}
