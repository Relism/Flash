package dev.relism.flash.ext.webbundler;

import java.util.EnumMap;
import java.util.Map;

final class FrontendTypeResolver {
    private final Map<FrontendType, FrontendStrategy> strategies = new EnumMap<>(FrontendType.class);

    FrontendTypeResolver() {
        register(new ViteFrontendStrategy());
    }

    void register(FrontendStrategy strategy) {
        strategies.put(strategy.type(), strategy);
    }

    FrontendStrategy resolve(FrontendType type) {
        FrontendStrategy strategy = strategies.get(type);
        if (strategy == null) {
            throw new IllegalArgumentException("Unsupported frontend type: " + type);
        }
        return strategy;
    }
}
