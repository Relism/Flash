package dev.relism.flash.ext.webbundler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ModeResolverTest {
    private final ModeResolver resolver = new ModeResolver();

    @Test
    void prod_mode_isAlwaysProd() {
        WebBundlerConfig cfg = WebBundlerConfig.builder()
                .runtimeMode(RuntimeMode.PROD)
                .build();
        assertEquals(RuntimeEnvironment.PROD, resolver.resolve(cfg));
    }
}
