package dev.relism.ext.webbundler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

final class CommandSafetyPolicy {
    private static final Logger log = LoggerFactory.getLogger(CommandSafetyPolicy.class);

    private final WebBundlerConfig config;

    CommandSafetyPolicy(WebBundlerConfig config) {
        this.config = config;
    }

    void check(List<String> command) {
        if (command.isEmpty()) throw new IllegalArgumentException("Command cannot be empty");
        String binary = command.get(0);
        boolean safe = config.safeCommandRegistry().contains(binary);
        if (safe || config.commandSafetyMode() == CommandSafetyMode.ALLOW) return;
        if (config.commandSafetyMode() == CommandSafetyMode.BLOCK) {
            throw new IllegalStateException("Blocked unsafe command: " + String.join(" ", command));
        }
        log.warn("Executing command outside safe registry: {}", String.join(" ", command));
    }
}
