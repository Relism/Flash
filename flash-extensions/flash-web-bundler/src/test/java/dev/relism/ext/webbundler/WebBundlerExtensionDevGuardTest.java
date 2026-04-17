package dev.relism.ext.webbundler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class WebBundlerExtensionDevGuardTest {
    @Test
    void classpathSourceInDev_isRejected() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .assetsFromClasspath("web/dist")
                .build();

        assertThrows(IllegalStateException.class, () -> WebBundlerExtension.validateDevAssetsSource(cfg));
    }
}
