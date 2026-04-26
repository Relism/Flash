package dev.relism.flash.ext.webbundler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PackageManagerAdapterTest {
    @Test
    void pnpm_hasFrozenInstallCommand() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .packageManager(PackageManager.PNPM)
                .build();
        PackageManagerAdapter adapter = new PackageManagerAdapter(cfg);
        assertTrue(adapter.installCommand().get(0).startsWith("pnpm"));
        assertEquals("--frozen-lockfile", adapter.installCommand().get(2));
    }
}
