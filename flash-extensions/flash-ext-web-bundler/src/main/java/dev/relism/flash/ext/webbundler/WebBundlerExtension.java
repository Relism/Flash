package dev.relism.flash.ext.webbundler;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.extension.FlashExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public final class WebBundlerExtension implements FlashExtension {
    private static final Logger log = LoggerFactory.getLogger(WebBundlerExtension.class);

    private final WebBundlerConfig config;
    private final ModeResolver modeResolver = new ModeResolver();
    private final FrontendTypeResolver frontendTypeResolver = new FrontendTypeResolver();
    private ScheduledExecutorService watchLoop;
    private Thread shutdownHook;

    public WebBundlerExtension() {
        this(WebBundlerConfig.builder().build());
    }

    public WebBundlerExtension(WebBundlerConfig config) {
        this.config = config;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        RuntimeEnvironment environment = modeResolver.resolve(config);
        PackageManagerAdapter pmAdapter = new PackageManagerAdapter(config);
        FrontendStrategy strategy = frontendTypeResolver.resolve(config.frontendType());
        CommandSafetyPolicy safetyPolicy = new CommandSafetyPolicy(config);
        CommandOrchestrator orchestrator = new CommandOrchestrator(config, safetyPolicy);
        WatchList watchList = new WatchList(config.webRoot());
        watchList.initialize(config.watchList());

        StaticAssetServingPolicy servingPolicy = null;
        SpaFallbackPolicy fallbackPolicy = null;

        try {
            if (environment == RuntimeEnvironment.DEV && config.frontendType().requiresOrchestration()) {
                bootstrapDev(strategy, pmAdapter, orchestrator, watchList);
            } else {
                AssetCatalog catalog = config.assetsSource().load(new AssetLoadRequest(
                        config.basePath(),
                        config.indexFile(),
                        environment,
                        config.webRoot()
                ));
                servingPolicy = new StaticAssetServingPolicy(catalog);
                fallbackPolicy = new SpaFallbackPolicy(catalog);
                log.info("flash-web-bundler loaded {} assets for production serving", catalog.size());
            }

            WebBundlerRuntime runtime = new WebBundlerRuntime(
                    environment, servingPolicy, fallbackPolicy, orchestrator, watchList
            );
            registerShutdownHook(runtime);
            ctx.provide(WebBundlerRuntime.class, runtime);
        } catch (RuntimeException ex) {
            shutdownResources(orchestrator);
            throw ex;
        }
        ctx.onReady(() -> {
        WebBundlerRuntime runtime = ctx.require(WebBundlerRuntime.class);
        boolean orchestrated = runtime.environment() == RuntimeEnvironment.DEV && config.frontendType().requiresOrchestration();
        if (orchestrated || config.operationMode() == OperationMode.ORCHESTRATE_ONLY) {
            return;
        }

        String basePath = config.basePath();
        String wildcard = "/".equals(basePath) ? "/**" : basePath + "/**";

        app.get(wildcard, (req, res) -> {
            if (runtime.servingPolicy().serve(req, res)) return null;
            runtime.fallbackPolicy().apply(res);
            return null;
        });
        app.head(wildcard, (req, res) -> {
            if (runtime.servingPolicy().serve(req, res)) {
                res.body(new byte[0]);
                return null;
            }
            runtime.fallbackPolicy().apply(res);
            res.body(new byte[0]);
            return null;
        });
        });
    }

    private void bootstrapDev(
            FrontendStrategy strategy,
            PackageManagerAdapter pmAdapter,
            CommandOrchestrator orchestrator,
            WatchList watchList
    ) {
        validateDevAssetsSource(config);
        InstallCache installCache = new InstallCache(config.webRoot());
        Path lock = config.expectedLockfile();
        if (config.installPolicy() == InstallPolicy.AUTO_IF_LOCK_HASH_CHANGED && installCache.lockChanged(lock)) {
            orchestrator.runBlocking(pmAdapter.installCommand(), config.webRoot());
            installCache.writeLockHash(lock);
        }

        List<String> devCommand = strategy.devCommand(config, pmAdapter);
        orchestrator.startDevServer(devCommand, config.webRoot());
        orchestrator.healthcheck(strategy.healthcheckPath(), Duration.ofSeconds(25));
        startWatchLoop(devCommand, orchestrator, watchList);
        log.info("flash-web-bundler started dev server at http://{}:{}", config.devHost(), config.devPort());
    }

    static void validateDevAssetsSource(WebBundlerConfig config) {
        if (config.assetsSource() instanceof ClasspathAssetsSource) {
            throw new IllegalStateException("Classpath assets source is not supported in DEV mode. Use assetsFromFilesystem(...) for DEV.");
        }
    }

    private void startWatchLoop(List<String> devCommand, CommandOrchestrator orchestrator, WatchList watchList) {
        watchLoop = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("flash-web-bundler-watch"));
        watchLoop.scheduleWithFixedDelay(() -> {
            if (watchList.changed()) {
                log.warn("Watchlist changed. Restarting frontend dev server.");
                orchestrator.restartDevServer(devCommand, config.webRoot());
            }
        }, 1500L, 1500L, TimeUnit.MILLISECONDS);
    }

    private void registerShutdownHook(WebBundlerRuntime runtime) {
        shutdownHook = new Thread(() -> shutdownResources(runtime.orchestrator()), "flash-web-bundler-shutdown");
        shutdownHook.setDaemon(true);
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    private void shutdownResources(CommandOrchestrator orchestrator) {
        if (watchLoop != null) {
            watchLoop.shutdownNow();
            watchLoop = null;
        }
        orchestrator.close();
    }

    private static ThreadFactory daemonThreadFactory(String namePrefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, namePrefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
