package dev.relism.ext.webbundler;

final class WebBundlerRuntime {
    private final RuntimeEnvironment environment;
    private final StaticAssetServingPolicy servingPolicy;
    private final SpaFallbackPolicy fallbackPolicy;
    private final CommandOrchestrator orchestrator;
    private final WatchList watchList;

    WebBundlerRuntime(
            RuntimeEnvironment environment,
            StaticAssetServingPolicy servingPolicy,
            SpaFallbackPolicy fallbackPolicy,
            CommandOrchestrator orchestrator,
            WatchList watchList
    ) {
        this.environment = environment;
        this.servingPolicy = servingPolicy;
        this.fallbackPolicy = fallbackPolicy;
        this.orchestrator = orchestrator;
        this.watchList = watchList;
    }

    RuntimeEnvironment environment() { return environment; }
    StaticAssetServingPolicy servingPolicy() { return servingPolicy; }
    SpaFallbackPolicy fallbackPolicy() { return fallbackPolicy; }
    CommandOrchestrator orchestrator() { return orchestrator; }
    WatchList watchList() { return watchList; }
}
