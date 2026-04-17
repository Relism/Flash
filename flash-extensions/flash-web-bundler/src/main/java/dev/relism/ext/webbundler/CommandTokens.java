package dev.relism.ext.webbundler;

import java.util.Arrays;
import java.util.List;

final class CommandTokens {
    private CommandTokens() {}

    static List<String> split(String command) {
        return Arrays.stream(command.trim().split("\\s+"))
                .filter(s -> !s.isBlank())
                .toList();
    }
}
