package dev.relism.ext.webbundler;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandSafetyPolicyTest {
    @Test
    void block_mode_rejectsUnknownBinary() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .commandSafetyMode(CommandSafetyMode.BLOCK)
                .safeCommandRegistry(List.of("npm"))
                .build();
        CommandSafetyPolicy policy = new CommandSafetyPolicy(cfg);
        assertThrows(IllegalStateException.class, () -> policy.check(List.of("sh", "-c", "echo nope")));
    }

    @Test
    void allow_mode_permitsUnknownBinary() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .commandSafetyMode(CommandSafetyMode.ALLOW)
                .safeCommandRegistry(List.of("npm"))
                .build();
        CommandSafetyPolicy policy = new CommandSafetyPolicy(cfg);
        assertDoesNotThrow(() -> policy.check(List.of("custom", "run")));
    }
}
