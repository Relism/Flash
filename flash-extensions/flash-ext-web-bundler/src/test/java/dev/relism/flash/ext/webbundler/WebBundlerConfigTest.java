package dev.relism.flash.ext.webbundler;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebBundlerConfigTest {
    @Test
    void defaults_areSane() {
        WebBundlerConfig cfg = WebBundlerConfig.builder().build();
        assertEquals(RuntimeMode.AUTODETECT, cfg.runtimeMode());
        assertEquals(OperationMode.ORCHESTRATE_ONLY, cfg.operationMode());
        assertEquals(InstallPolicy.AUTO_IF_LOCK_HASH_CHANGED, cfg.installPolicy());
        assertEquals(LoggingMode.MERGED, cfg.loggingMode());
        assertEquals(CommandSafetyMode.WARN, cfg.commandSafetyMode());
    }

    @Test
    void packageManager_setsLockfileInWatchList() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .packageManager(PackageManager.PNPM)
                .build();
        assertTrue(cfg.watchList().contains("pnpm-lock.yaml"));
    }

    @Test
    void basePath_isNormalized() {
        WebBundlerConfig cfg = WebBundlerConfig.builder().basePath("app/").build();
        assertEquals("/app", cfg.basePath());
    }

    @Test
    void invalidPort_throws() {
        assertThrows(IllegalArgumentException.class, () ->
                WebBundlerConfig.builder().devPort(0).build());
    }

    @Test
    void assetsFromFilesystem_setsFilesystemSource() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .webRoot(Path.of("frontend"))
                .assetsFromFilesystem(Path.of("build"))
                .watchList(List.of("package.json"))
                .build();
        assertTrue(cfg.assetsSource() instanceof FilesystemAssetsSource);
    }

    @Test
    void assetsFromClasspath_setsClasspathSource() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .assetsFromClasspath("web/dist")
                .build();
        assertTrue(cfg.assetsSource() instanceof ClasspathAssetsSource);
    }

    @Test
    void staticFrontend_defaultsToManagedAndFilesystemSource() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .frontendType(FrontendType.STATIC)
                .build();
        assertEquals(OperationMode.MANAGED, cfg.operationMode());
        assertTrue(cfg.assetsSource() instanceof FilesystemAssetsSource);
    }

    @Test
    void staticFrontend_skipsOrchestrationValidation() {
        assertDoesNotThrow(() -> WebBundlerConfig.builder()
                .frontendType(FrontendType.STATIC)
                .devPort(0)
                .watchList(List.of())
                .build());
    }
}
