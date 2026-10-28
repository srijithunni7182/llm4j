package io.github.llm4j.loom.cli;

import io.github.llm4j.loom.runtime.HumanInterface;
import io.github.llm4j.loom.runtime.NoHumanAvailable;
import java.util.Scanner;

/** Standard console-based implementation of HumanInterface for CLI usage. */
public class ConsoleHumanInterface implements HumanInterface {
    private final Scanner scanner = new Scanner(System.in);

    @Override
    public String promptHuman(String message) {
        System.out.println("\n--- LOOM HUMAN PROMPT ---");
        System.out.println(message);
        System.out.print("> ");
        if (ConsoleInput.routing()) return ConsoleInput.answer(message);
        // a closed console is not an empty answer: nobody can say yes or no
        if (!scanner.hasNextLine()) throw new NoHumanAvailable(message);
        return scanner.nextLine();
    }
}
