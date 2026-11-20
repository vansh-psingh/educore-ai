package com.educore.ai;

import java.util.Locale;
import java.util.Scanner;

/**
 * EDUCORE AI - command-line educational assistant.
 * All behavior is driven by JSON files under src/main/resources/data.
 *
 * Usage:
 *   java -jar educore-ai.jar          -> interactive CLI chat
 *   java -jar educore-ai.jar web      -> start the web server (http://localhost:8080)
 *
 * Commands (CLI): /help, /modules, /exit
 */
public final class EducoreApp {

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equalsIgnoreCase("web")) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            WebServer.main(new String[]{String.valueOf(port)});
            return;
        }

        ChatEngine engine = new ChatEngine();
        Scanner scanner = new Scanner(System.in);

        System.out.println("==============================================");
        System.out.println("  EDUCORE AI v1.0 - Educational Assistant");
        System.out.println("  Type /help for commands, /exit to quit.");
        System.out.println("  Run with 'web' arg to start the website.");
        System.out.println("==============================================");

        while (true) {
            System.out.print("\nStudent> ");
            if (!scanner.hasNextLine()) {
                break;
            }
            String input = scanner.nextLine().trim();
            if (input.isEmpty()) {
                continue;
            }
            String cmd = input.toLowerCase(Locale.ROOT);
            if (cmd.equals("/exit") || cmd.equals("/quit")) {
                System.out.println("Goodbye! Keep learning.");
                break;
            }
            if (cmd.equals("/help")) {
                printHelp();
                continue;
            }
            if (cmd.equals("/modules")) {
                System.out.println("Modules: " + String.join(", ", engine.modules().keySet()));
                continue;
            }
            System.out.println("\nEDUCORE> " + engine.respond(input));
        }
        scanner.close();
    }

    private static void printHelp() {
        System.out.println("""
                Available commands:
                  /help     - show this help
                  /modules  - list active modules
                  /exit     - quit the application

                Try asking:
                  "Explain photosynthesis"
                  "explain topic: fractions, level: beginner, goal: exam prep"
                  "study notes topic: water cycle"
                  "summarize source: <paste text>, length: short"
                  "generate mcq questions for math"
                  "evaluate answer: <your answer>"
                  "curriculum alignment subject: math, syllabus: <paste syllabus>"
                  "I forgot my password"
                  "extract source: name: Ali, email: a@b.com format: xml"
                  "Translate hello to Spanish"
                """);
    }
}
