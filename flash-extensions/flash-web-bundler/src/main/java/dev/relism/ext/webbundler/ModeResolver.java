package dev.relism.ext.webbundler;

import dev.relism.Flash;

final class ModeResolver {
    RuntimeEnvironment resolve(WebBundlerConfig config) {
        return switch (config.runtimeMode()) {
            case PROD -> RuntimeEnvironment.PROD;
            case AUTODETECT -> Flash.DEV ? RuntimeEnvironment.DEV : RuntimeEnvironment.PROD;
            case ENV -> fromEnvironment(config.envRuntimeVariable());
        };
    }

    private RuntimeEnvironment fromEnvironment(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) return RuntimeEnvironment.PROD;
        return "dev".equalsIgnoreCase(value) ? RuntimeEnvironment.DEV : RuntimeEnvironment.PROD;
    }
}
